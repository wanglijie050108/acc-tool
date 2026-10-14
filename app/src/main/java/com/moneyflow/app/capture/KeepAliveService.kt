package com.moneyflow.app.capture

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.moneyflow.app.MainActivity

/**
 * 前台常驻服务 —— 保活用。
 *
 * 为什么需要这个：
 * Android 10+ 对后台 App 有严格限制，长时间在后台的进程会被冻结，
 * 冻结状态下 NotificationListenerService 回调可能收不到。
 * 挂一个前台服务（状态栏常驻通知）能让进程保持在较高优先级，大幅提升存活率。
 *
 * 用户已确认不介意状态栏常驻通知。
 */
class KeepAliveService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        // Android 12+ 在后台启动前台服务会抛 ForegroundServiceStartNotAllowedException，
        // Android 14 对 dataSync 类型还有额外限制。
        // 这里必须兜住，否则服务直接崩溃，用户看到「应用已停止运行」。
        try {
            startForeground(NOTIFICATION_ID, buildNotification())
        } catch (t: Throwable) {
            Log.e(TAG, "启动前台服务失败，降级为普通服务", t)
            // 降级：不挂常驻通知，但服务仍然注册着，
            // 通知监听回调依然可用（只是存活率下降）。
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 被杀后自动重启
        return START_STICKY
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return

        val channel = NotificationChannel(
            CHANNEL_ID,
            "后台记账服务",
            NotificationManager.IMPORTANCE_MIN,
        ).apply {
            description = "保持记账助手在后台运行，用于自动记录支付流水"
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("流水记账运行中")
            .setContentText("正在监听支付通知，自动记录中")
            .setSmallIcon(android.R.drawable.ic_menu_agenda)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setSilent(true)
            .build()
    }

    companion object {
        private const val TAG = "KeepAliveService"
        private const val CHANNEL_ID = "moneyflow_keepalive"
        private const val NOTIFICATION_ID = 1001

        fun start(context: Context) {
            val intent = Intent(context, KeepAliveService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (t: Throwable) {
                // 部分 ROM 在后台启动前台服务会抛异常，静默处理即可
            }
        }

        fun stop(context: Context) {
            try {
                context.stopService(Intent(context, KeepAliveService::class.java))
            } catch (_: Throwable) {
            }
        }
    }
}
