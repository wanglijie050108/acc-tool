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
 * 并且把「这个权限到底能不能弹」这个判断**只写一份**（见 [canPromptInApp]）。
 * 和项目里 `Fingerprint` / `MoneyText` 的处理方式一致 —— 逻辑不复制。
 *
 * ── Android 平台的能力边界（决定了哪些能自动弹）────────────────
 * | 权限 | App 自己弹窗 | 启动门（每次打开）的处理 |
 * |---|---|---|
 * | 短信 (READ_SMS / RECEIVE_SMS) | ✅ 系统运行时弹窗 | 缺了就弹 |
 * | 通知 (POST_NOTIFICATIONS, 13+) | ✅ 系统运行时弹窗 | 缺了就弹 |
 * | 忽略电池优化 | ✅ 系统「是否允许」对话框 | 缺了就弹 |
 * | 通知使用权 (BIND_NOTIFICATION_LISTENER_SERVICE) | ❌ 平台不允许 | 缺了就**直接跳**系统开关列表 |
 *
 * 最后一行是硬限制，不是本项目偷懒：Android 从来没有提供
 * 「申请通知使用权」的 Intent 或运行时权限。
 * 所以设置页对它是**如实告知 + 跳转**，不假装能弹。
 * 参考：https://developer.android.com/reference/android/provider/Settings#ACTION_NOTIFICATION_LISTENER_SETTINGS
 *
 * ── 启动门（用户 2026-10-15 明确要求）────────────────────────
 * 「每次打开软件自动检测通知权限和后台运行权限，如果没打开就要求打开，
 *   而不是让用户自己去手动确认。」
 *
 * 落地为 [ensureOnAppOpen]：每次 Activity 回到前台都检测一遍，
 * 缺哪项就替用户把系统界面打开（能弹框的弹框，不能弹框的跳开关列表），
 * 用户只需要点「允许」。
 *
 * ⚠️ 防骚扰设计（都是踩过的坑，不是想当然）：
 *   - 进程级 `askedThisProcess` 记忆：同一项权限在同一进程里只主动要求一次，
 *     不会因为用户在系统界面里逛了一圈回来就反复弹；
 *   - 状态驱动：权限一旦真的给全了，启动门永远静默；
 *   - 运行时权限被永久拒绝（系统「不再询问」）后弹框不再出现，
 *     此时用户仍可在设置页手动补 —— 启动门不替设置页兜底。
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
     *
     * 调用即计入 `askedThisProcess`：启动门用它做「本进程已要求过」的记忆，
     * 设置页按钮走的是用户主动路径，不受该记忆影响（直接调本方法即可）。
     */
    fun requestRuntimePermissions(launcher: ActivityResultLauncher<Array<String>>) {
        markAsked(Kind.SMS, Kind.NOTIFICATION)
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
        markAsked(Kind.BATTERY)
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
        markAsked(Kind.NOTIFICATION_ACCESS)
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
     * 是否还有**必须靠弹窗**才能拿到的运行时权限（短信 + 通知）。
     *
     * 用途：冷启动时只在「确实缺」的时候弹，避免每次冷启动都骚扰用户。
     * 电池优化和通知使用权不在这个方法里 —— 它们由启动门
     * [ensureOnAppOpen] 在 onResume 统一处理（弹系统对话框 / 跳系统开关列表），
     * 这个方法保持「只回答运行时权限弹不弹」，职责单一。
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

    // ─────────────── 启动门：每次打开 App 都检测 ───────────────

    /**
     * 启动门依次「要求」的顺序。
     *
     * 排序理由：
     *   1. 运行时权限（通知/短信）和电池优化都能在 App 内弹系统对话框，
     *      不切换界面，先弹这两类，成本最低；
     *   2. 通知使用权弹不出框，只能跳系统开关列表（会把用户带出 App），
     *      所以放最后 —— 用户回来时 onResume 会再跑一次门，前面几项也补上了。
     *
     * ⚠️ 每项只出现一次，且 [nextStartupAsk] 会跳过 `askedThisProcess` 里已有的，
     * 保证一次「打开 App」里同一项最多被主动要求一次。
     */
    val STARTUP_ASK_ORDER: List<Kind> =
        listOf(Kind.NOTIFICATION, Kind.SMS, Kind.BATTERY, Kind.NOTIFICATION_ACCESS)

    /**
     * 启动门的**纯判断**部分：下一个该主动要求的权限是哪个。
     *
     * 不碰 Context、不碰 Activity，方便 JVM 单测（见 [PermissionRequestTest]）。
     * 「该不该要求」和「真的去要求」必须分开 —— 前者是这段逻辑里唯一会
     * 静默做错事的判断（判错了要么骚扰用户、要么让用户以为 App 坏了）。
     *
     * @param asked 本进程已经主动要求过的项（进程级记忆，防同一 session 反复弹）
     * @return 下一个要要求的权限；null = 不缺，或要缺的本进程都已要求过
     */
    fun nextStartupAsk(
        notificationAccessGranted: Boolean,
        smsGranted: Boolean,
        notificationGranted: Boolean,
        batteryOptimized: Boolean,
        asked: Set<Kind>,
    ): Kind? = STARTUP_ASK_ORDER.firstOrNull { kind ->
        val missing = when (kind) {
            Kind.NOTIFICATION -> !notificationGranted
            Kind.SMS -> !smsGranted
            Kind.BATTERY -> !batteryOptimized
            Kind.NOTIFICATION_ACCESS -> !notificationAccessGranted
        }
        missing && kind !in asked
    }

    /**
     * 启动门执行入口：**每次 Activity 回到前台都调一次**（onResume）。
     *
     * 行为（用户 2026-10-15 明确要求）：
     *   检测通知权限 / 后台运行权限 / 通知使用权 / 短信，缺哪项就直接替用户
     *   把对应的系统界面打开 —— 能弹系统对话框的就弹（用户点一下「允许」即可），
     *   弹不了的（通知使用权）直接跳系统开关列表。
     *   用户要做的只是「允许」，不需要自己去设置里翻。
     *
     * ⚠️ 同一进程里每项最多主动要求一次（[askedThisProcess]）：
     * 用户在系统界面逛一圈回来会再次触发 onResume，没有这层记忆会无限弹/跳。
     * 权限真给全之后，本方法永远静默。
     *
     * @param runtimeLauncher MainActivity 注册的运行时权限申请器
     */
    fun ensureOnAppOpen(
        activity: ComponentActivity,
        runtimeLauncher: ActivityResultLauncher<Array<String>>,
    ) {
        val next = nextStartupAsk(
            notificationAccessGranted = isNotificationAccessGranted(activity),
            smsGranted = isSmsGranted(activity),
            notificationGranted = isNotificationGranted(activity),
            batteryOptimized = isIgnoringBatteryOptimizations(activity),
            asked = askedThisProcess,
        ) ?: return

        when (next) {
            // 一个系统对话框同时覆盖短信和通知，哪个缺弹哪个
            Kind.NOTIFICATION, Kind.SMS -> requestRuntimePermissions(runtimeLauncher)
            Kind.BATTERY -> requestIgnoreBatteryOptimizations(activity)
            Kind.NOTIFICATION_ACCESS -> openNotificationListenerSettings(activity)
        }
    }

    // ─────────────── 内部工具 ───────────────

    /**
     * 本进程已经「主动要求过」的权限。
     *
     * ⚠️ 只约束启动门的自动要求，不约束设置页按钮（用户主动点的永远放行）。
     * 进程级生命周期：App 被杀死重开 = 新一轮「每次打开软件」，
     * 此时重新检测、重新要求 —— 正是用户要的行为。
     * 只在主线程访问（onCreate/onResume），不同步。
     */
    private val askedThisProcess: MutableSet<Kind> = mutableSetOf()

    /** 记录「本进程已主动要求过」。幂等。 */
    private fun markAsked(vararg kinds: Kind) {
        askedThisProcess += kinds
    }

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
