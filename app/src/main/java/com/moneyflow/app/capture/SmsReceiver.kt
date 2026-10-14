package com.moneyflow.app.capture

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.util.Log
import com.moneyflow.app.data.SampleRecorder
import com.moneyflow.app.data.TransactionRepository
import com.moneyflow.app.parse.ParseEngine
import com.moneyflow.app.parse.ParseResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 银行短信接收器。
 *
 * 短信是识别最准的数据源：格式由银行固定，不像通知那样五花八门。
 * 需要 RECEIVE_SMS 权限，属于敏感权限，部分国产 ROM 会在安装时弹警告。
 *
 * ⚠️ 注意：onReceive 有 10 秒执行上限，所以这里只做解析，入库放到协程里异步做。
 */
class SmsReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent == null) return
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return

        try {
            val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
            if (messages.isEmpty()) return

            // 一条长短信会被拆成多段，这里按发件人合并
            val sender = messages.first().originatingAddress.orEmpty()
            val body = messages.joinToString(separator = "") {
                it.displayMessageBody.orEmpty()
            }

            if (body.isBlank()) return

            val occurredAt = messages.first().timestampMillis
                .takeIf { it > 0 }
                ?: System.currentTimeMillis()

            // 短信没有包名概念，传 null 让引擎走不限制包名的规则
            when (val result = ParseEngine.parse(body, null, occurredAt)) {
                is ParseResult.Success -> {
                    // 留档短信原文，供导出调规则
                    SampleRecorder.record(
                        context = context.applicationContext,
                        packageName = null,
                        title = sender,
                        text = body,
                        receivedAt = occurredAt,
                        matched = true,
                        ruleId = result.ruleId,
                    )

                    // 必须先拿到 pendingResult 再进入异步，且保证任何分支都会 finish()，
                    // 否则系统会认为广播没处理完，占用 10 秒超时并可能 ANR。
                    val pending = goAsync()
                    val entity = try {
                        ParseEngine.toEntity(result, body, occurredAt)
                    } catch (t: Throwable) {
                        Log.e(TAG, "构造记录失败", t)
                        pending.finish()
                        return
                    }

                    scope.launch {
                        try {
                            val repository = TransactionRepository.get(context.applicationContext)
                            val inserted = repository.insertDeduplicated(entity)
                            Log.i(
                                TAG,
                                if (inserted) "短信记账：${sender} ${result.amount}"
                                else "短信重复丢弃：${sender} ${result.amount}"
                            )
                        } catch (t: Throwable) {
                            Log.e(TAG, "短信入库失败", t)
                        } finally {
                            pending.finish()
                        }
                    }
                }

                is ParseResult.NoAmount -> {
                    // 银行短信里「看起来像账务、但没识别出来」的必须留档，
                    // 这些正是需要补规则的样本。
                    if (looksMoneyRelated(body)) {
                        SampleRecorder.record(
                            context = context.applicationContext,
                            packageName = null,
                            title = sender,
                            text = body,
                            receivedAt = occurredAt,
                            matched = false,
                            ruleId = null,
                        )
                    }
                }
                is ParseResult.Failure -> Log.w(TAG, "短信解析失败")
            }
        } catch (t: Throwable) {
            Log.e(TAG, "处理短信失败", t)
        }
    }

    companion object {
        private const val TAG = "SmsReceiver"

        /**
         * 判断短信「看起来和钱有关」。
         * 只留档有价值的样本，避免把验证码、营销短信全存下来。
         */
        private fun looksMoneyRelated(text: String): Boolean {
            if (text.length > 500) return false
            val markers = listOf(
                "元", "¥", "￥", "支出", "收入", "消费", "付款", "支付",
                "扣款", "转账", "收款", "到账", "退款", "还款", "提现",
                "余额", "账单", "交易",
            )
            return markers.any { text.contains(it) }
        }

        /**
         * 接收器没有 onDestroy，无法跟随生命周期取消协程，
         * 所以用一个进程级单例作用域。协程体都很短（一次数据库写入）。
         */
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
