package com.moneyflow.app.capture

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.moneyflow.app.data.TransactionRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 处理转账提醒上的「不是转账」按钮。
 *
 * 为什么要单独一个接收器，而不是让用户点进 App 再忽略：
 * 误报是会发生的（[com.moneyflow.app.parse.TransferSignal] 已经尽量收紧，
 * 但微信改文案后仍可能误判）。如果打发一条误报需要「点通知 → 等 App 启动
 * → 找到那条 → 点忽略」，用户会很快对这类提醒失去耐心。
 * 通知栏直接一颗按钮解决，成本最低。
 */
class PendingTransferActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent == null) return
        if (intent.action != ACTION_DISMISS) return

        val id = intent.getLongExtra(EXTRA_PENDING_ID, -1L)
        if (id <= 0L) return

        val appContext = context.applicationContext
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

    companion object {
        private const val TAG = "PendingTransferAction"

        const val ACTION_DISMISS = "com.moneyflow.app.action.DISMISS_PENDING_TRANSFER"
        const val EXTRA_PENDING_ID = "pending_id"

        /** 接收器没有生命周期，用进程级作用域（协程体很短，一次数据库删除） */
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
