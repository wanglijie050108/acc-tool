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
import androidx.core.app.NotificationManagerCompat
import com.moneyflow.app.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 前台常驻服务 —— 保活 + 监听看门狗。
 *
 * 为什么需要前台服务：
 * Android 10+ 对后台 App 有严格限制，长时间在后台的进程会被冻结，
 * 冻结状态下 NotificationListenerService 回调可能收不到。
 * 挂一个前台服务（状态栏常驻通知）能让进程保持在较高优先级，大幅提升存活率。
 *
 * 为什么还需要看门狗（真机实测发现）：
 * 「通知使用权」是**静态授权**，「监听服务被系统绑定」是**动态状态**，
 * 两者会脱节。REDMI K80 / Android 15 上实测到：
 *   - 授权列表里明明有本应用
 *   - 但系统 dumpsys 的 live listener 列表里没有，服务根本没被绑上
 *   - 此时打开 App（onResume 里请求重连）**也救不回来**
 *   - 唯一有效的恢复方式，是去系统设置里把开关关掉再打开
 * 结果就是：用户付了钱，App 一条通知都没收到，而且毫无提示。
 *
 * 看门狗做的事：定期检查「已授权但未连接」这个组合，
 * 一旦出现就主动请求系统重绑。这是**尽力而为** ——
 * 部分 ROM（尤其国产 ROM）会忽略这个请求，所以设置页仍然要如实
 * 告诉用户「去把开关关掉再打开」，不能只依赖看门狗。
 *
 * 用户已确认不介意状态栏常驻通知。
 */
class KeepAliveService : Service() {

    private val watchdogScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var watchdogJob: Job? = null

    /** 连续多少次发现监听没连上。用于日志区分「偶发」和「一直连不上」 */
    @Volatile
    private var missedChecks: Int = 0

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
        startWatchdog()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 服务可能被系统重建但 onCreate 之外没走到，这里再确保一次
        startWatchdog()
        // 被杀后自动重启
        return START_STICKY
    }

    // ─────────────── 监听看门狗 ───────────────

    private fun startWatchdog() {
        if (watchdogJob?.isActive == true) return
        watchdogJob = watchdogScope.launch {
            while (isActive) {
                delay(WATCHDOG_INTERVAL_MS)
                try {
                    checkListener()
                } catch (t: Throwable) {
                    // 看门狗自己绝不能把服务搞崩
                    Log.e(TAG, "看门狗检查失败", t)
                }
            }
        }
    }

    /**
     * 检查一次监听状态。
     *
     * 只在「用户已授权、但服务没连上」时才动作 ——
     * 用户压根没授权的情况下反复请求重绑是没意义的，只会刷日志。
     */
    private fun checkListener() {
        val ctx = applicationContext

        val granted = try {
            NotificationManagerCompat.getEnabledListenerPackages(ctx)
                .contains(ctx.packageName)
        } catch (t: Throwable) {
            false
        }
        if (!granted) {
            missedChecks = 0
            return
        }

        if (NotificationCaptureService.isListening()) {
            if (missedChecks > 0) {
                Log.i(TAG, "通知监听已恢复（之前掉线 $missedChecks 次）")
                missedChecks = 0
            }
            return
        }

        missedChecks++
        Log.w(TAG, "已授权但监听未连接，第 $missedChecks 次请求重连")
        NotificationCaptureService.requestRebind(ctx)
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

        /**
         * 看门狗检查间隔。
         *
         * 60 秒：掉线后最多 1 分钟就能尝试恢复，而每秒一次也没必要 ——
         * 检查本身几乎不耗电，但重连请求太频繁会刷日志、也可能被 ROM 限流。
         */
        private const val WATCHDOG_INTERVAL_MS = 60_000L

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
