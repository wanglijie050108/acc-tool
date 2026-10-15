package com.moneyflow.app.capture

import android.app.RemoteInput
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.moneyflow.app.data.Direction
import com.moneyflow.app.data.TransactionRepository
import com.moneyflow.app.parse.MoneyText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 处理转账提醒通知上的两个动作按钮：
 *
 * 1. 「不是转账」—— 直接消掉这条待确认，不进 App。
 *    为什么要单独一个接收器：误报是会发生的（[com.moneyflow.app.parse.TransferSignal]
 *    已经尽量收紧，但微信改文案后仍可能误判）。如果打发一条误报需要
 *    「点通知 → 等 App 启动 → 找到那条 → 点忽略」，用户会很快对这类提醒失去耐心。
 *
 * 2. 「填入金额」—— 快捷填写（用户 2026-10-15 要求）：
 *    用户在通知里直接输入金额（可选方向）→ 点发送 → 本接收器解析 →
 *    直接入库 → 用一条回执通知替换原提醒。**全程不打开 App**。
 *
 * ⚠️ 两个动作都是「一次数据库写 + 一次通知更新」，协程体很短，
 * 用 goAsync() 持有广播生命周期（ANR 上限约 10 秒，远够）。
 */
class PendingTransferActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent == null) return
        val appContext = context.applicationContext

        when (intent.action) {
            ACTION_DISMISS -> handleDismiss(appContext, intent)
            ACTION_QUICK_FILL -> handleQuickFill(appContext, intent)
        }
    }

    /** 「不是转账」：删掉待确认记录，撤掉通知 */
    private fun handleDismiss(appContext: Context, intent: Intent) {
        val id = intent.getLongExtra(EXTRA_PENDING_ID, -1L)
        if (id <= 0L) return

        // 先把通知撤掉，让用户立刻看到反馈；数据库删除是次要的，晚一点没关系
        TransferPrompt.cancel(appContext, id)

        val pending = goAsync()
        scope.launch {
            try {
                TransactionRepository.get(appContext).dismissPendingTransfer(id)
            } catch (t: Throwable) {
                Log.e(TAG, "忽略待确认转账失败", t)
            } finally {
                pending.finish()
            }
        }
    }

    /**
     * 「填入金额」：从通知的 RemoteInput 里取金额（和可选的方向），直接记账。
     *
     * 分支处理（都要给用户可见的反馈，别静默）：
     *   - 记录已不在（处理过/被清理）：撤通知即可
     *   - 金额没读懂：**重发**带提示的提醒通知，让用户在原通知里重填
     *   - 成功/重复：发回执通知替换原提醒
     */
    private fun handleQuickFill(appContext: Context, intent: Intent) {
        val id = intent.getLongExtra(EXTRA_PENDING_ID, -1L)
        if (id <= 0L) return

        val remoteInput = RemoteInput.getResultsFromIntent(intent)
        val amountText = remoteInput?.getCharSequence(TransferPrompt.KEY_QUICK_AMOUNT)?.toString()
        val directionText = remoteInput?.getCharSequence(TransferPrompt.KEY_QUICK_DIRECTION)?.toString()

        val pendingAsync = goAsync()
        scope.launch {
            try {
                val repository = TransactionRepository.get(appContext)
                val record = repository.findPendingTransfer(id)
                if (record == null) {
                    // 已经处理过了（可能在 App 里补的、或刚被 dismissed）—— 撤掉陈旧通知
                    TransferPrompt.cancel(appContext, id)
                    return@launch
                }

                val amount = MoneyText.parseAmount(amountText)
                if (amount == null) {
                    // 没读懂金额：原通知 id 重发，带提示，输入框还在，直接重填
                    Log.i(TAG, "快捷填写金额无法解析：$amountText")
                    TransferPrompt.show(
                        appContext,
                        record,
                        hint = "没读懂「${amountText.orEmpty()}」，请重新输入金额",
                    )
                    return@launch
                }

                // 方向：用户选了就用选的；没选就按记录的猜测方向
                val direction = when (directionText?.trim()) {
                    "支出" -> Direction.EXPENSE
                    "收入" -> Direction.INCOME
                    else -> record.direction
                }

                val inserted = repository.confirmPendingTransfer(
                    pendingId = id,
                    amount = amount,
                    direction = direction,
                    merchant = null,
                    note = null,
                )
                // 回执通知（同 id，原地替换原提醒）
                TransferPrompt.showResult(
                    appContext,
                    record,
                    amount,
                    direction,
                    deduplicated = !inserted,
                )
                Log.i(TAG, "快捷填写完成（id=$id）：$amount $direction inserted=$inserted")
            } catch (t: Throwable) {
                Log.e(TAG, "快捷填写失败", t)
            } finally {
                pendingAsync.finish()
            }
        }
    }

    companion object {
        private const val TAG = "PendingTransferAction"

        const val ACTION_DISMISS = "com.moneyflow.app.action.DISMISS_PENDING_TRANSFER"
        const val ACTION_QUICK_FILL = "com.moneyflow.app.action.QUICK_FILL_PENDING_TRANSFER"
        const val EXTRA_PENDING_ID = "pending_id"

        /** 接收器没有生命周期，用进程级作用域（协程体很短，一次数据库写入） */
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
