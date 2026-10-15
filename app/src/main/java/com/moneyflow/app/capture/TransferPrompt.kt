package com.moneyflow.app.capture

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.moneyflow.app.MainActivity
import com.moneyflow.app.data.PendingTransferEntity

/**
 * 待确认转账的提醒通知。
 *
 * ── 为什么用通知而不是悬浮窗 ──
 * 悬浮窗（SYSTEM_ALERT_WINDOW）在国产 ROM 上会被「支付保护 / 支付保险箱」
 * 拦截，需要用户额外关掉系统开关才行（这是实测结论，多个记账 App 的
 * 帮助文档里都提到了）。而通知只需要 [POST_NOTIFICATIONS]，用户点一下就
 * 进 App —— 对「转账后补个金额」这个动作来说，通知足够，且干扰更小。
 *
 * ── 语气要克制 ──
 * 这类提醒是在**打断用户**，所以：
 *   - 文案说清楚「检测到一笔转账」而不是「你有一笔未记账」，不制造焦虑
 *   - 提供「不是转账」的忽略入口，让用户能一键打发
 *   - 同一个 id 复用，避免同一笔转账堆出多条通知
 */
object TransferPrompt {

    private const val TAG = "TransferPrompt"
    private const val CHANNEL_ID = "moneyflow_transfer_prompt"

    /**
     * 通知 id 的基数。
     *
     * ⚠️ 用「基数 + 待确认记录 id」而不是固定 id：
     *   - 固定 id 会让新提醒覆盖旧的，用户可能漏看前一笔
     *   - 用记录 id 直接做 id 又可能和保活通知（1001）撞号
     * 取模到 1000 以内，保证不越界也不会和 KeepAliveService 冲突。
     */
    private const val NOTIFICATION_ID_BASE = 2000

    /**
     * 弹出一条「检测到转账，请补金额」的通知。
     *
     * @return true 表示通知已发出（用户授权了通知权限），false 表示被系统拦下
     */
    fun show(context: Context, pending: PendingTransferEntity): Boolean {
        val appContext = context.applicationContext
        ensureChannel(appContext)

        // Android 13+ 需要 POST_NOTIFICATIONS 运行时权限。
        // 没授权时不能抛异常（会让通知监听回调崩掉），静默返回 false，
        // 待确认记录仍然留在库里，用户打开 App 能看到。
        if (!NotificationManagerCompat.from(appContext).areNotificationsEnabled()) {
            Log.w(TAG, "通知权限未开启，待确认记录仅留在应用内")
            return false
        }

        val tapIntent = Intent(appContext, MainActivity::class.java).apply {
            // 让 MainActivity 知道要点开哪条待确认
            action = MainActivity.ACTION_CONFIRM_PENDING
            putExtra(MainActivity.EXTRA_PENDING_ID, pending.id)
            // ⚠️ 必须带 NEW_TASK：从 Application context 启动 Activity 需要它，
            // 否则部分 ROM 上点击通知无反应。
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }

        // requestCode 用记录 id，保证每笔转账的 PendingIntent 互不覆盖
        val contentIntent = PendingIntent.getActivity(
            appContext,
            (pending.id % 100000).toInt(),
            tapIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        // 「不是转账」：直接消掉这条待确认，不进 App
        val dismissIntent = Intent(appContext, PendingTransferActionReceiver::class.java).apply {
            action = PendingTransferActionReceiver.ACTION_DISMISS
            putExtra(PendingTransferActionReceiver.EXTRA_PENDING_ID, pending.id)
        }
        val dismissPendingIntent = PendingIntent.getBroadcast(
            appContext,
            (pending.id % 100000).toInt(),
            dismissIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val notification = NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setContentTitle("检测到一笔${shortSource(pending.sourceName)}")
            .setContentText("通知里没有金额，点一下补上金额就能记账")
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    "原文：${pending.rawText}\n\n" +
                        "微信/支付宝的转账通知里不含金额，所以需要你补一下。\n" +
                        "点这条通知 → 输入金额 → 完成记账。"
                )
            )
            .setSmallIcon(android.R.drawable.ic_menu_edit)
            .setContentIntent(contentIntent)
            .addAction(0, "不是转账", dismissPendingIntent)
            .setAutoCancel(true)
            // 用 DEFAULT 而不是 HIGH：这不是紧急事件，
            // 震动/横幅打扰用户不值当。声音也关掉。
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setSound(null)
            .setVibrate(null)
            .build()

        return try {
            NotificationManagerCompat.from(appContext)
                .notify(notificationId(pending.id), notification)
            true
        } catch (t: Throwable) {
            // Android 13+ 未授权时 notify 会抛 SecurityException
            Log.w(TAG, "发送转账提醒失败", t)
            false
        }
    }

    /** 用户处理完（确认或忽略）后，撤掉对应的通知 */
    fun cancel(context: Context, pendingId: Long) {
        try {
            NotificationManagerCompat.from(context.applicationContext)
                .cancel(notificationId(pendingId))
        } catch (_: Throwable) {
            // 取消失败无所谓，通知会自动消失或过期
        }
    }

    fun notificationId(pendingId: Long): Int =
        NOTIFICATION_ID_BASE + (pendingId % 1000).toInt()

    /** 「微信转账」→「微信转账」，「支付宝转账」→「支付宝转账」。保持不变，便于文案拼接 */
    private fun shortSource(sourceName: String): String = sourceName.ifBlank { "转账" }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return

        // ⚠️ 单独建通道，不要复用保活通道（那是 IMPORTANCE_MIN 静默的）。
        // 通道的重要性创建后用户才能改，所以一开始就要给对。
        val channel = NotificationChannel(
            CHANNEL_ID,
            "转账待确认提醒",
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = "检测到微信/支付宝转账但通知里没有金额时，提醒你补上金额"
            setShowBadge(true)
            enableVibration(false)
            setSound(null, null)
        }
        manager.createNotificationChannel(channel)
    }
}
