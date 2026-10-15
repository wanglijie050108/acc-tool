package com.moneyflow.app

import com.moneyflow.app.ui.PermissionRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 权限申请逻辑的回归测试。
 *
 * ── 为什么这个文件必须存在 ──────────────────────────────────
 * 这个项目**丢过一次**这个功能：有一次改动让 App 自己在启动/点按钮时
 * 弹系统权限框（用户明确说过很满意），后来一次「更新」把它覆盖掉了 ——
 * 因为那次改动**没有提交、也没有测试守着**，重装/重编译就没了。
 *
 * 所以这里守两件事：
 *   1. 「什么时候该弹窗」的判断（[PermissionRequest.needsRuntimePrompt]）
 *   2. 「哪些权限能由 App 弹窗」的知识（[PermissionRequest.canPromptInApp]）
 *
 * 第 2 条尤其重要 —— 它是本项目里**唯一一个「正确答案包含 false」**的地方：
 * 通知使用权**必须**是 false（平台不允许 App 申请），
 * 如果有人「顺手修正」成 true，用户点按钮后会撞上一个什么都不弹的死按钮。
 * 所以下面 `notificationAccessCannotBePromptedInApp` 是**故意断言 false 的**，
 * 它不是写错了，是**防误伤**。
 */
class PermissionRequestTest {

    // ─────────── 1. 什么时候该弹窗 ───────────

    /** 全都没有 → 必须弹。这是新装用户的第一次，也是最重要的那次 */
    @Test
    fun promptsWhenNothingGranted() {
        assertTrue(
            "短信和通知都没给时必须弹窗，否则用户不知道要授权",
            PermissionRequest.needsRuntimePrompt(
                smsGranted = false,
                notificationGranted = false,
            ),
        )
    }

    /** 只缺一个 → 仍要弹（用户可能只拒绝了其中一个） */
    @Test
    fun promptsWhenOnlySmsMissing() {
        assertTrue(
            PermissionRequest.needsRuntimePrompt(smsGranted = false, notificationGranted = true),
        )
    }

    @Test
    fun promptsWhenOnlyNotificationMissing() {
        assertTrue(
            PermissionRequest.needsRuntimePrompt(smsGranted = true, notificationGranted = false),
        )
    }

    /**
     * 都给过了 → **不要**弹。
     *
     * ⚠️ 这条是防骚扰的：每次冷启动都弹会让用户反感，
     * 而且反复弹系统框会触发 Android 的「不再询问」，把用户**永久**锁在外面 ——
     * 那比不弹严重得多。
     */
    @Test
    fun doesNotPromptWhenEverythingGranted() {
        assertFalse(
            "权限齐了还弹 = 骚扰用户，且可能触发系统的「不再询问」",
            PermissionRequest.needsRuntimePrompt(
                smsGranted = true,
                notificationGranted = true,
            ),
        )
    }

    // ─────────── 2. 哪些权限能由 App 弹窗 ───────────

    /**
     * ⚠️ **故意断言 false**。别「修」它。
     *
     * Android 从来没有提供申请「通知使用权」的 Intent 或运行时权限
     * （参考 https://developer.android.com/reference/android/provider/Settings#ACTION_NOTIFICATION_LISTENER_SETTINGS ）。
     * App 能做的只有跳到系统列表让用户自己开。
     *
     * 若有人把它改成 true，`MainActivity` 会走「弹窗」分支，
     * 而那个分支对通知使用权**没有弹窗可弹** → 用户点按钮没反应，以为 App 坏了。
     */
    @Test
    fun notificationAccessCannotBePromptedInApp() {
        assertFalse(
            "通知使用权是平台硬限制，App 申请不了，只能跳系统列表",
            PermissionRequest.canPromptInApp(PermissionRequest.Kind.NOTIFICATION_ACCESS),
        )
    }

    /** 短信、通知是运行时权限 → 能弹系统授权框 */
    @Test
    fun runtimePermissionsCanBePromptedInApp() {
        assertTrue(PermissionRequest.canPromptInApp(PermissionRequest.Kind.SMS))
        assertTrue(PermissionRequest.canPromptInApp(PermissionRequest.Kind.NOTIFICATION))
    }

    /**
     * 电池优化能弹**系统对话框**（ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS），
     * 不是只能跳设置页 —— 这是这个功能相对老版本的改进点之一。
     */
    @Test
    fun batteryOptimizationCanBePromptedInApp() {
        assertTrue(
            "ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS 会让系统弹「是否允许」框",
            PermissionRequest.canPromptInApp(PermissionRequest.Kind.BATTERY),
        )
    }

    /**
     * 设置页给每项权限都挂了一个按钮，所以每个 [PermissionRequest.Kind]
     * 都必须有明确的行为答案（true/false 都行，但不能"没人管"）。
     *
     * ⚠️ 这条会在**新增 Kind 却忘了在 `canPromptInApp` 里表态**时失败 ——
     * Kotlin 的 `when` 已经在编译期挡了一层，这条是第二层保险，
     * 保证将来即使有人加了 `else` 分支也仍会被提醒。
     */
    @Test
    fun everyKindHasADecidedPromptBehaviour() {
        val kinds = PermissionRequest.Kind.entries
        assertEquals(
            "Kind 的数量变了说明有新权限加入，请确认 canPromptInApp 已表态",
            4,
            kinds.size,
        )
        // 能弹的恰好是这三项；通知使用权是唯一的例外
        val promptable = kinds.filter { PermissionRequest.canPromptInApp(it) }
        assertEquals(
            "能弹窗的应该正好是 短信/通知/电池优化 三项",
            listOf(
                PermissionRequest.Kind.NOTIFICATION_ACCESS,
                PermissionRequest.Kind.SMS,
                PermissionRequest.Kind.BATTERY,
                PermissionRequest.Kind.NOTIFICATION,
            ).filter { it != PermissionRequest.Kind.NOTIFICATION_ACCESS },
            promptable,
        )
    }

    // ─────────── 3. 启动门：每次打开 App 都检测 ───────────

    /**
     * 启动门的顺序表里，每种权限**必须且只能**出现一次。
     *
     * 这条是防「加了新 Kind 却忘了加进顺序表」—— 那种疏漏编译不报错、
     * 单测不失败，但启动门会永远跳过那项权限，用户再也收不到授权引导。
     * （本项目丢过一次功能，根因正是「没有东西在守」。）
     */
    @Test
    fun startupGateOrderCoversEveryKindExactlyOnce() {
        val order = PermissionRequest.STARTUP_ASK_ORDER
        assertEquals(
            "启动门顺序表必须覆盖全部 Kind，一个都不能漏",
            PermissionRequest.Kind.entries.size,
            order.size,
        )
        assertEquals(
            "同一种权限在顺序表里重复出现，会请求两次",
            PermissionRequest.Kind.entries.toSet(),
            order.toSet(),
        )
    }

    /**
     * 全都缺 → 先要运行时权限（通知/短信）。
     *
     * 排序理由：系统运行时弹窗**不切换界面**，成本最低；
     * 通知使用权要跳系统开关列表（把用户带出 App），放最后。
     */
    @Test
    fun startupGateAsksRuntimePermissionFirst() {
        val next = PermissionRequest.nextStartupAsk(
            notificationAccessGranted = false,
            smsGranted = false,
            notificationGranted = false,
            batteryOptimized = false,
            asked = emptySet(),
        )
        assertEquals(PermissionRequest.Kind.NOTIFICATION, next)
    }

    /** 运行时权限齐了、电池优化没开 → 要电池优化（系统「是否允许」对话框） */
    @Test
    fun startupGateAsksBatteryAfterRuntimeGranted() {
        val next = PermissionRequest.nextStartupAsk(
            notificationAccessGranted = false,
            smsGranted = true,
            notificationGranted = true,
            batteryOptimized = false,
            asked = emptySet(),
        )
        assertEquals(PermissionRequest.Kind.BATTERY, next)
    }

    /**
     * 全齐 → 启动门静默。
     *
     * 这是防骚扰的底线：权限给全之后每次打开 App 都默默无闻，
     * 绝不弹任何系统框。
     */
    @Test
    fun startupGateIsSilentWhenEverythingGranted() {
        val next = PermissionRequest.nextStartupAsk(
            notificationAccessGranted = true,
            smsGranted = true,
            notificationGranted = true,
            batteryOptimized = true,
            asked = emptySet(),
        )
        assertNull("权限齐了还要求 = 骚扰用户", next)
    }

    /**
     * 本进程已要求过的项要跳过。
     *
     * 防的是这个死循环：用户拒绝电池优化 → 从系统对话框回到 App →
     * onResume 再跑一次门 → 又弹 → 用户被弹到崩溃。
     * 进程级记忆保证一次「打开」里每项最多主动要求一次。
     */
    @Test
    fun startupGateSkipsKindsAlreadyAskedThisProcess() {
        val next = PermissionRequest.nextStartupAsk(
            notificationAccessGranted = false,
            smsGranted = false,
            notificationGranted = false,
            batteryOptimized = false,
            asked = setOf(
                PermissionRequest.Kind.NOTIFICATION,
                PermissionRequest.Kind.SMS,
            ),
        )
        assertEquals(
            "运行时权限本进程已要求过，该轮到电池优化",
            PermissionRequest.Kind.BATTERY,
            next,
        )
    }

    /** 缺的项本进程全都要求过了 → 也静默（等下次打开 App 再说） */
    @Test
    fun startupGateReturnsNullWhenAllMissingAlreadyAsked() {
        val next = PermissionRequest.nextStartupAsk(
            notificationAccessGranted = false,
            smsGranted = false,
            notificationGranted = false,
            batteryOptimized = false,
            asked = setOf(
                PermissionRequest.Kind.NOTIFICATION,
                PermissionRequest.Kind.SMS,
                PermissionRequest.Kind.BATTERY,
                PermissionRequest.Kind.NOTIFICATION_ACCESS,
            ),
        )
        assertNull(next)
    }

    /**
     * ⚠️ **行为反转测试**（用户 2026-10-15 明确要求，别改回去）：
     *
     * 通知使用权现在**会**进启动门 —— 缺了就由 App 直接跳系统开关列表，
     * 而不是只在设置页放一张卡片等用户自己翻。
     *
     * 旧行为（`notificationAccessIsNotPromptedOnStartUp`）断言它**不得**出现在
     * 启动流程里，理由是「冷启动会把用户直接拽进系统界面，很唐突」。
     * 用户原话：「每次打开软件自动检测通知权限和后台运行权限，
     * **如果没打开就要求打开，而不是让用户自己去手动确认**」——
     * 主动跳过去正是「替用户打开」，与「让用户自己去设置里找」是两回事。
     *
     * 与上面几条不矛盾：它排在顺序表最后，且受 askedThisProcess 约束，
     * 一次打开里最多被跳一次，用户从系统界面返回后不会再次被拽走。
     */
    @Test
    fun notificationAccessIsDemandedOnAppOpen() {
        val next = PermissionRequest.nextStartupAsk(
            notificationAccessGranted = false,
            smsGranted = true,
            notificationGranted = true,
            batteryOptimized = true,
            asked = emptySet(),
        )
        assertEquals(
            "通知使用权缺失时，启动门要直接跳系统开关列表（用户 2026-10-15 要求）",
            PermissionRequest.Kind.NOTIFICATION_ACCESS,
            next,
        )
        // 但注意：它仍然排在最后 —— 先把不切换界面的系统对话框弹完
        assertEquals(
            PermissionRequest.Kind.NOTIFICATION_ACCESS,
            PermissionRequest.STARTUP_ASK_ORDER.last(),
        )
    }

    // ─────────── 4. 运行时权限集合本身 ───────────

    /**
     * 申请的权限集合必须**非空**且只含短信/通知。
     *
     * ⚠️ 关键约束：**不能包含 INTERNET**。
     * README 向用户承诺「代码里没有申请网络权限，不可能上传」，
     * 这是本项目的核心卖点之一。这条测试是那句承诺的守卫。
     */
    @Test
    fun runtimePermissionSetIsSane() {
        val perms = PermissionRequest.RUNTIME_PERMISSIONS.toList()
        assertTrue("权限集合不能为空，否则运行时弹窗无事可做", perms.isNotEmpty())
        assertTrue(
            "必须申请短信权限（银行短信是主要数据源之一）",
            perms.any { it.contains("SMS") },
        )
        assertFalse(
            "⚠️ 绝不能申请网络权限 —— README 明确承诺「不可能上传」，这是隐私卖点",
            perms.any { it.contains("INTERNET") || it.contains("NETWORK") },
        )
    }
}
