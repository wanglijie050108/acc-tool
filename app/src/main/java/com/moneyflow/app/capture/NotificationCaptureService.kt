package com.moneyflow.app.capture

import android.app.Notification
import android.content.ComponentName
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.moneyflow.app.data.PendingTransferEntity
import com.moneyflow.app.data.SampleRecorder
import com.moneyflow.app.data.TransactionRepository
import com.moneyflow.app.parse.MoneyText
import com.moneyflow.app.parse.ParseEngine
import com.moneyflow.app.parse.ParseResult
import com.moneyflow.app.parse.TransferSignal
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 通知监听服务 —— App 最核心的数据来源。
 *
 * ⚠️ 这套机制完全依赖用户在系统设置里手动授予「通知使用权」。
 * App 自己无法申请，也无法检测是否被拒绝后自动重试，只能引导用户去设置页。
 *
 * 工作流程：
 *   系统推送通知 → onNotificationPosted → 提取文本 → 解析引擎 → 去重 → 入库
 */
class NotificationCaptureService : NotificationListenerService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * 服务连接状态。用于在设置页显示「监听是否真的在工作」。
     *
     * 为什么需要这个：系统会在某些情况下把这个服务解绑
     * （被 force-stop、长时间空闲、部分 ROM 的省电策略），
     * 此时「通知使用权」在设置里显示为已开启，但实际收不到任何通知 ——
     * 用户完全无法察觉，只会觉得「记账不灵了」。
     * 这个标志让问题可见。
     */
    @Volatile
    var isConnected: Boolean = false
        private set

    override fun onListenerConnected() {
        super.onListenerConnected()
        isConnected = true
        instance = this
        Log.i(TAG, "通知监听已连接")
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        isConnected = false
        Log.w(TAG, "通知监听已断开")
        // 系统断开后通常会自动重连，但部分 ROM 不会。主动请求一次重连。
        try {
            requestRebind(
                ComponentName(applicationContext, NotificationCaptureService::class.java)
            )
        } catch (t: Throwable) {
            Log.e(TAG, "请求重连失败", t)
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return
        try {
            val packageName = sbn.packageName ?: return

            // 忽略自己发出的常驻通知，否则会无限循环
            if (packageName == applicationContext.packageName) return

            val extras = sbn.notification?.extras ?: return

            val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
            val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
            val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString().orEmpty()
            val subText = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString().orEmpty()

            // 合并所有可用文本字段。微信/支付宝有时把金额放在 bigText 里
            val combined = listOf(title, text, bigText, subText)
                .filter { it.isNotBlank() }
                .distinct()
                .joinToString(" ")

            if (combined.isBlank()) return

            val occurredAt = if (sbn.postTime > 0) sbn.postTime else System.currentTimeMillis()

            handleText(
                rawText = combined,
                packageName = packageName,
                occurredAt = occurredAt,
            )
        } catch (t: Throwable) {
            Log.e(TAG, "处理通知失败", t)
        }
    }

    private fun handleText(rawText: String, packageName: String?, occurredAt: Long) {
        when (val result = ParseEngine.parse(rawText, packageName, occurredAt)) {
            is ParseResult.Success -> {
                val repository = TransactionRepository.get(applicationContext)
                // 留档：识别成功的样本（用于验证规则、也便于回溯）
                SampleRecorder.record(
                    context = applicationContext,
                    packageName = packageName,
                    title = null,
                    text = rawText,
                    receivedAt = occurredAt,
                    matched = true,
                    ruleId = result.ruleId,
                )
                scope.launch {
                    try {
                        val entity = ParseEngine.toEntity(result, rawText, occurredAt)
                        val inserted = repository.insertDeduplicated(entity)
                        Log.i(
                            TAG,
                            if (inserted) "已记账：${result.sourceName} ${result.amount} 规则=${result.ruleId}"
                            else "重复丢弃：${result.sourceName} ${result.amount}"
                        )
                    } catch (t: Throwable) {
                        Log.e(TAG, "入库失败", t)
                    }
                }
            }

            is ParseResult.NoAmount -> {
                // 先判断是不是「转账通知」。
                //
                // 顺序很重要：转账通知也满足 MoneyText.looksMoneyRelated
                // （含「转账」二字），如果先走留档分支，它会被记成
                // 一条 [UNMATCHED] 样本 —— 而这类通知**永远不可能**被规则匹配，
                // 因为通知里根本没有金额。让用户把它们当「待补规则的样本」
                // 导出发给我，只会污染样本、误导排查方向。
                //
                // 所以两者互斥：是转账就走转账通道，否则才考虑留档。
                val verdict = TransferSignal.detect(rawText, packageName)
                if (verdict is TransferSignal.Verdict.Transfer) {
                    maybeCaptureTransfer(rawText, packageName, occurredAt, verdict)
                } else if (MoneyText.looksMoneyRelated(rawText)) {
                    // 「看起来像钱、却没识别出来」的通知必须留档 ——
                    // 这些正是需要补规则的样本，用户平时根本不会主动报告。
                    // 统一走 MoneyText：验证码类内容不会被留档
                    SampleRecorder.record(
                        context = applicationContext,
                        packageName = packageName,
                        title = null,
                        text = rawText,
                        receivedAt = occurredAt,
                        matched = false,
                        ruleId = null,
                    )
                }
            }

            is ParseResult.Failure -> {
                Log.w(TAG, "解析失败：${result.reason}")
            }
        }
    }

    /**
     * 把一条「没有金额的转账通知」挂起成待确认。
     *
     * 背景：微信/支付宝给好友转账后，通知栏那条消息**不含金额**
     * （真机确认，原文形如「[转账]已被接收」），金额只在 App 内部的账单页里。
     * 所以这类通知注定过不了 [ParseEngine]，但也不能丢 ——
     * 丢了用户就永远不知道自己漏了一笔。
     *
     * ⚠️ 必须极其克制：用户每天收到几十条无金额通知，
     * 判定放宽一点就会变成骚扰。[TransferSignal] 负责严格判定，
     * 这里只负责落库和发提醒。
     */
    private fun maybeCaptureTransfer(
        rawText: String,
        packageName: String?,
        occurredAt: Long,
        verdict: TransferSignal.Verdict.Transfer,
    ) {
        val repository = TransactionRepository.get(applicationContext)
        scope.launch {
            try {
                val sourceName = when (packageName) {
                    "com.tencent.mm" -> "微信转账"
                    "com.eg.android.AlipayGphone" -> "支付宝转账"
                    else -> "转账"
                }

                val pending = PendingTransferEntity(
                    packageName = packageName,
                    sourceName = sourceName,
                    rawText = rawText,
                    detectedAt = occurredAt,
                    direction = verdict.direction,
                    dedupKey = PendingTransferEntity.dedupKeyOf(packageName, rawText),
                )

                val added = repository.addPendingTransfer(pending)
                if (!added) {
                    Log.i(TAG, "转账提醒重复，跳过：$rawText")
                    return@launch
                }

                // 重新查一次拿到自增 id，通知要用它做跳转参数
                val saved = repository.findPendingTransferByDedupKey(
                    pending.dedupKey,
                    occurredAt,
                ) ?: return@launch

                TransferPrompt.show(applicationContext, saved)
                Log.i(TAG, "已挂起待确认转账（id=${saved.id}）：$rawText")
            } catch (t: Throwable) {
                // 挂起失败不能影响其他通知的处理
                Log.e(TAG, "挂起待确认转账失败", t)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // ⚠️ 刻意不取消 scope。
        // 通知监听服务会被系统频繁重建，若在此取消协程，
        // 正在写入的那笔流水会被静默丢弃（用户永远看不到，也查不出原因）。
        // 代价是极少量协程可能多存活一会儿，但它只有一次数据库写入，随即结束。
    }

    companion object {
        private const val TAG = "NotificationCapture"

        /** 当前活跃的服务实例。为 null 表示系统尚未或不再绑定本服务 */
        @Volatile
        private var instance: NotificationCaptureService? = null

        /**
         * 监听是否真正在工作。
         *
         * ⚠️ 注意区分两件事：
         *   - 「通知使用权已开启」= 用户在设置里勾选了（静态配置）
         *   - 「监听已连接」     = 系统真的把服务绑上了（动态状态）
         * 前者为真、后者为假时，App 收不到任何通知，但用户看不出来。
         * 设置页用这个区分显示，避免「以为在记账其实没记」。
         */
        fun isListening(): Boolean = instance?.isConnected == true

        /** 主动请求系统重新绑定本服务。某些 ROM 解绑后不会自动重连，需要手动踢一下 */
        fun requestRebind(context: android.content.Context) {
            try {
                requestRebind(
                    ComponentName(context.applicationContext, NotificationCaptureService::class.java)
                )
            } catch (t: Throwable) {
                Log.e(TAG, "请求重连失败", t)
            }
        }
    }
}
