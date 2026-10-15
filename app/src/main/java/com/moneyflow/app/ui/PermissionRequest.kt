package com.moneyflow.app.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.NotificationManagerCompat

/**
 * 权限申请的**唯一入口**。
 *
 * ⚠️ 为什么要有这个文件（真实踩过的坑）：
 * 以前「申请权限」这件事散在两个地方 ——
 * `MainActivity.onCreate` 里裸调一次 `permissionLauncher.launch(...)`，
 * 设置页的三个按钮各自 `startActivity(系统设置页)`。
 * 结果是：
 *   1. 冷启动那一次申请如果被用户划掉，**再也没有第二次机会**，
 *      而设置页按钮只会把用户丢进系统设置里自己找 —— 用户以为 App 坏了；
 *   2. 哪些权限能弹、哪些必须跳系统页，这个知识在两个文件里各写了一半。
 *
 * 所以这里把「申请」收敛成一条：**能弹窗就弹窗，弹不了才跳系统页**，
 * 并且把「这个权限到底能不能弹」这个判断**只写一份**（见 [canPrompt]）。
 * 和项目里 `Fingerprint` / `MoneyText` 的处理方式一致 —— 逻辑不复制。
 *
 * ── Android 平台的能力边界（决定了哪些能自动弹）────────────────
 * | 权限 | 能不能 App 自己弹窗 |
 * |---|---|
 * | 短信 (READ_SMS / RECEIVE_SMS) | ✅ 系统运行时弹窗 |
 * | 通知 (POST_NOTIFICATIONS, 13+) | ✅ 系统运行时弹窗 |
 * | 忽略电池优化 | ✅ 系统的「是否允许」对话框（[ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS]） |
 * | 通知使用权 (BIND_NOTIFICATION_LISTENER_SERVICE) | ❌ **平台不允许**，只能跳系统列表由用户手动开 |
 *
 * 最后一行是硬限制，不是本项目偷懒：Android 从来没有提供
 * 「申请通知使用权」的 Intent 或运行时权限。
 * 任何声称能一键开启的说法都是骗人的，所以设置页对它是**如实告知 + 跳转**。
 * 参考：https://developer.android.com/reference/android/provider/Settings#ACTION_NOTIFICATION_LISTENER_SETTINGS
 */
object PermissionRequest {

    /**
     * 一项权限的种类。设置页用它告诉调用方「用户点了哪一项」，
     * 而不是各自持有 `Context` 自己 startActivity —— 那样跳转逻辑又会散开。
     */
    enum class Kind { NOTIFICATION_ACCESS, SMS, BATTERY, NOTIFICATION }

    /**
     * 运行时权限集合。
     *
     * 放在这里而不是 `MoneyFlowApp` 里，是为了让「权限」这件事只有一个出处 ——
     * 原来它在 Application 类里，`MainActivity` 隔着文件去引用，改一处容易漏另一处。
     */
    val RUNTIME_PERMISSIONS: Array<String> = buildList {
        add(android.Manifest.permission.RECEIVE_SMS)
        add(android.Manifest.permission.READ_SMS)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(android.Manifest.permission.POST_NOTIFICATIONS)
        }
    }.toTypedArray()

    /** 创建运行时权限申请器。调用方负责用 `registerForActivityResult` 注册。 */
    fun runtimeLauncher(
        activity: ComponentActivity,
        onResult: (Map<String, Boolean>) -> Unit = {},
    ): ActivityResultLauncher<Array<String>> =
        activity.registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { result ->
            // 结果只用于日志/回调，界面状态由设置页实时查询（见 isSmsGranted）
            onResult(result)
        }

    /**
     * 申请运行时权限（短信 + 通知）。
     *
     * ⚠️ 只在有权限**还没给**的时候才调，否则 Android 会直接静默返回，
     * 而且反复弹窗会让用户反感、甚至触发系统「不再询问」。
     * 是否已给由 [isSmsGranted] / [isNotificationGranted] 判断。
     */
    fun requestRuntimePermissions(launcher: ActivityResultLauncher<Array<String>>) {
        launcher.launch(RUNTIME_PERMISSIONS)
    }

    /**
     * 请求忽略电池优化。
     *
     * 这个和「跳设置页」是**两种不同的 Intent**，容易搞混：
     *   - [Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS] ← 本方法，
     *     系统直接弹「是否允许忽略电池优化」对话框，用户点一下就完事
     *   - [Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS] ← 设置页里那个，
     *     只是打开列表，用户还得自己找本应用
     *
     * 要注意的坑：部分 ROM（魅族、部分定制 HyperOS）没有实现前者，
     * `startActivity` 会抛 `ActivityNotFoundException`，此时必须**降级**到列表页，
     * 不能崩、也不能静默失败（用户会以为按钮坏了）。
     */
    fun requestIgnoreBatteryOptimizations(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        if (isIgnoringBatteryOptimizations(context)) return // 已经给了就别再弹

        val direct = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.fromParts("package", context.packageName, null)
        }
        if (startSafely(context, direct)) return

        // 降级：打开电池优化列表页，让用户自己找
        if (startSafely(context, Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))) return

        // 再降级：本应用的详情页（小米等 ROM 一定有此页）
        openAppSettings(context)
    }

    /**
     * 通知使用权：**平台不允许 App 申请**，只能跳系统列表。
     * 保留这个方法名是为了让调用点读起来意图明确，而不是散落的 `startActivity`。
     */
    fun openNotificationListenerSettings(context: Context) {
        if (startSafely(context, Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))) return
        openAppSettings(context)
    }

    /** 本应用的系统设置详情页。兜底的兜底，任何 ROM 都有。 */
    fun openAppSettings(context: Context) {
        startSafely(
            context,
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", context.packageName, null),
            ),
        )
    }

    // ─────────────── 状态查询（设置页与启动逻辑共用）───────────────

    /** 短信权限是否已给 */
    fun isSmsGranted(context: Context): Boolean =
        context.checkSelfPermission(android.Manifest.permission.READ_SMS) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED

    /** 通知权限是否已给（13 以下默认有） */
    fun isNotificationGranted(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    /** 是否已忽略电池优化 */
    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
        val manager = context.getSystemService(PowerManager::class.java) ?: return false
        return manager.isIgnoringBatteryOptimizations(context.packageName)
    }

    /** 「通知使用权」这个开关是否已打开（静态授权，不代表服务真的连上了） */
    fun isNotificationAccessGranted(context: Context): Boolean =
        NotificationManagerCompat.getEnabledListenerPackages(context)
            .contains(context.packageName)

    /**
     * 是否还有**必须靠弹窗**才能拿到的权限。
     *
     * 用途：启动时只在「确实缺」的时候弹，避免每次冷启动都骚扰用户。
     * ⚠️ 故意**不包含**通知使用权和电池优化 —— 那两个弹不出来，
     * 只能在设置页引导，不该在启动时把用户拽走。
     */
    fun needsRuntimePrompt(context: Context): Boolean =
        needsRuntimePrompt(
            smsGranted = isSmsGranted(context),
            notificationGranted = isNotificationGranted(context),
        )

    /**
     * [needsRuntimePrompt] 的纯逻辑部分 —— 不碰 Context，方便 JVM 单测。
     *
     * 单独抽出来是因为「该不该弹窗」是这段代码里**唯一会静默做错事**的判断：
     * 判错了要么骚扰用户、要么让用户以为 App 坏了。
     * 抽成纯函数后可以用普通 JUnit 覆盖，不需要模拟器。
     * 回归测试：[PermissionRequestTest]。
     */
    fun needsRuntimePrompt(smsGranted: Boolean, notificationGranted: Boolean): Boolean =
        !smsGranted || !notificationGranted

    /**
     * 某一项权限点下去之后，**能不能由 App 弹出系统对话框**。
     *
     * 这是本文件存在的主要理由：把「哪些能弹」写成**代码**而不是注释，
     * 于是它可以被测试守住（[PermissionRequestTest.everyKindHasADecidedPromptBehaviour]）。
     *
     * `false` 不代表不处理 —— 调用方会退化成跳系统设置页，
     * 但那是「引导」不是「申请」，对用户的说法也不一样。
     */
    fun canPromptInApp(kind: Kind): Boolean = when (kind) {
        // 运行时权限，系统弹窗
        Kind.SMS, Kind.NOTIFICATION -> true
        // ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS，系统弹「是否允许」对话框
        Kind.BATTERY -> true
        // ⚠️ 平台硬限制：Android 不提供申请通知使用权的 Intent / 运行时权限，
        //    只能跳到系统列表由用户手动打开。这里是 false **不是** bug，别改成 true。
        Kind.NOTIFICATION_ACCESS -> false
    }

    /** 启动时该弹哪些（只含能弹的），设置页/启动逻辑共用 */
    val PROMPTABLE_ON_START: List<Kind> = listOf(Kind.SMS, Kind.NOTIFICATION)

    // ─────────────── 内部工具 ───────────────

    /**
     * startActivity 的容错包装。
     * 返回 true 表示成功发出去了。ROM 缺某个设置页时返回 false 让调用方降级 ——
     * 绝不让一个「跳设置」的失败把 App 搞崩（这些 Intent 在老 ROM / 定制 ROM 上确实会缺）。
     */
    private fun startSafely(context: Context, intent: Intent): Boolean = try {
        context.startActivity(intent)
        true
    } catch (_: Throwable) {
        false
    }
}
