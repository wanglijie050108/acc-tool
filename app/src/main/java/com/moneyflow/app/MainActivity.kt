package com.moneyflow.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.moneyflow.app.capture.KeepAliveService
import com.moneyflow.app.capture.NotificationCaptureService
import com.moneyflow.app.ui.ParseBenchScreen
import com.moneyflow.app.ui.PermissionRequest
import com.moneyflow.app.ui.SettingsScreen
import com.moneyflow.app.ui.TransactionListScreen
import com.moneyflow.app.ui.TransactionViewModel
import com.moneyflow.app.ui.MoneyFlowTheme
import com.moneyflow.app.ui.readCsvText

/** 应用内页面。页面少，直接用一个状态变量切换，不引入导航库 */
private enum class Screen { LIST, SETTINGS, PARSE_BENCH, CSV_IMPORT, SAMPLE_EXPORT, RULE_MANAGER }

class MainActivity : ComponentActivity() {

    private val viewModel: TransactionViewModel by viewModels()

    /**
     * 运行时权限申请器（短信 + 通知）。
     *
     * 由 [PermissionRequest] 统一创建，保证「申请哪些权限」只有一份定义。
     * 注册必须在 Activity 创建时完成（`registerForActivityResult` 的要求），
     * 所以放在字段初始化而不是 onCreate 里。
     */
    private val permissionLauncher = PermissionRequest.runtimeLauncher(this)

    /** CSV 文件选择器。用 OpenDocument 让用户从「最近文件」或文件管理器里挑 */
    private val csvPicker = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        val result = readCsvText(this, uri)
        if (result.isSuccess) {
            viewModel.importCsv(result.getOrThrow())
        } else {
            viewModel.reportImportError(
                result.exceptionOrNull()?.message ?: "读取文件失败"
            )
        }
    }

    /** 规则 JSON 导入选择器 */
    private val rulePicker = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        val result = readCsvText(this, uri)
        if (result.isSuccess) {
            viewModel.importCustomRules(result.getOrThrow())
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 申请短信与通知权限 —— 由 App 自己弹系统的运行时权限对话框。
        //
        // ⚠️ 这里有意加了三道判断，都是踩过的坑：
        //   1. savedInstanceState == null：只在**冷启动**弹。
        //      旋转屏幕 / 从后台回来时 savedInstanceState 非空，
        //      不加这条会每次重建都弹一次，像骚扰。
        //   2. needsRuntimePrompt：**已经给过的权限不再弹**。
        //      否则 Android 会静默返回，而且反复弹会触发系统的「不再询问」，
        //      反而把用户永久锁在外面。
        //   3. 只申请能弹的（短信 / 通知）。通知使用权和电池优化弹不出来，
        //      它们在设置页里引导，不该在启动时把用户拽到系统界面。
        if (savedInstanceState == null && PermissionRequest.needsRuntimePrompt(this)) {
            PermissionRequest.requestRuntimePermissions(permissionLauncher)
        }

        // 确保保活服务在跑
        KeepAliveService.start(this)

        // 处理「点通知进来补金额」的入口
        handlePendingTransferIntent(intent)

        setContent {
            MoneyFlowTheme {
                var screen by remember { mutableStateOf(Screen.LIST) }

                when (screen) {
                    Screen.LIST -> TransactionListScreen(
                        viewModel = viewModel,
                        onOpenSettings = { screen = Screen.SETTINGS },
                    )

                    Screen.SETTINGS -> SettingsScreen(
                        onBack = { screen = Screen.LIST },
                        // 设置页的「去开启」按钮走这里：能弹系统弹窗就弹，
                        // 弹不了才跳系统设置页（判断逻辑在 PermissionRequest 里）。
                        onRequestPermission = { kind ->
                            when (kind) {
                                PermissionRequest.Kind.SMS,
                                PermissionRequest.Kind.NOTIFICATION ->
                                    PermissionRequest.requestRuntimePermissions(permissionLauncher)

                                PermissionRequest.Kind.BATTERY ->
                                    PermissionRequest.requestIgnoreBatteryOptimizations(this)

                                PermissionRequest.Kind.NOTIFICATION_ACCESS ->
                                    PermissionRequest.openNotificationListenerSettings(this)
                            }
                        },
                        onOpenParseBench = { screen = Screen.PARSE_BENCH },
                        onOpenCsvImport = {
                            viewModel.clearImportMessages()
                            screen = Screen.CSV_IMPORT
                        },
                        onOpenSampleExport = {
                            viewModel.refreshSamples()
                            screen = Screen.SAMPLE_EXPORT
                        },
                        onOpenRuleManager = {
                            viewModel.refreshCustomRuleCount()
                            screen = Screen.RULE_MANAGER
                        },
                    )

                    Screen.PARSE_BENCH -> ParseBenchScreen(
                        onBack = { screen = Screen.SETTINGS },
                    )

                    Screen.CSV_IMPORT -> {
                        val status by viewModel.importStatus.collectAsStateWithLifecycle()
                        val result by viewModel.importResult.collectAsStateWithLifecycle()
                        com.moneyflow.app.ui.CsvImportScreen(
                            onBack = { screen = Screen.SETTINGS },
                            onPickFile = { csvPicker.launch(arrayOf("*/*")) },
                            statusText = status,
                            lastResultText = result,
                        )
                    }

                    Screen.SAMPLE_EXPORT -> {
                        val samples by viewModel.samples.collectAsStateWithLifecycle()
                        val total by viewModel.sampleTotal.collectAsStateWithLifecycle()
                        com.moneyflow.app.ui.SampleExportScreen(
                            samples = samples,
                            totalCount = total,
                            onBack = { screen = Screen.SETTINGS },
                            onClear = { viewModel.clearSamples() },
                            onShare = { text ->
                                com.moneyflow.app.ui.shareText(this@MainActivity, text)
                            },
                        )
                    }

                    Screen.RULE_MANAGER -> {
                        val count by viewModel.customRuleCount.collectAsStateWithLifecycle()
                        com.moneyflow.app.ui.RuleManagerScreen(
                            customRuleCount = count,
                            onBack = { screen = Screen.SETTINGS },
                            onSaveRule = { name, pattern, group, direction, smsOnly ->
                                viewModel.saveCustomRule(name, pattern, group, direction, smsOnly)
                            },
                            onExport = {
                                viewModel.exportCustomRules { json ->
                                    com.moneyflow.app.ui.shareText(
                                        this@MainActivity,
                                        json,
                                        subject = "流水记账—自定义规则",
                                    )
                                }
                            },
                            onImport = { rulePicker.launch(arrayOf("*/*")) },
                        )
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // 从设置页返回时，保活服务可能已被系统回收，这里补一次
        KeepAliveService.start(this)
        // 通知监听可能被系统解绑或进程被冻结过。
        // 每次回到前台都请求一次重连，让服务自动恢复。
        // 这是「自愈」：用户只要打开一次 App，监听就重新生效。
        NotificationCaptureService.requestRebind(this)
        // 启动门（用户 2026-10-15 要求）：**每次打开软件**都自动检测
        // 通知权限 / 后台运行权限（还有短信、通知使用权），
        // 缺哪项就直接弹系统对话框或跳系统开关列表，不让用户自己去设置里翻。
        //
        // 放在 onResume 而不是只在 onCreate：划掉权限弹窗后用户什么都没损失，
        // 但下次打开 App 会再检测到、再要求 —— 「每次打开都要求」正是本来的目的。
        // 同一进程里每项只要求一次（PermissionRequest 内部的会话记忆），
        // 所以不会因为在系统界面回来又弹一次。
        PermissionRequest.ensureOnAppOpen(this, permissionLauncher)
    }

    /**
     * 处理通知点击带来的「补金额」请求。
     *
     * 两条路径都会走到这里：
     *   - onCreate：App 没在跑，冷启动
     *   - onNewIntent：App 已在跑（比如用户在设置页），热启动
     * 所以两边都要调，且必须能重复调用而无副作用。
     */
    private fun handlePendingTransferIntent(intent: Intent?) {
        if (intent?.action != ACTION_CONFIRM_PENDING) return
        val pendingId = intent.getLongExtra(EXTRA_PENDING_ID, -1L)
        if (pendingId <= 0L) return

        // ⚠️ viewModel 必须在这里才访问。
        // 它是 by viewModels() 懒加载的，onCreate 里访问是安全的
        // （ComponentActivity 已把 ViewModelStore 准备好了）。
        viewModel.openPendingConfirmById(pendingId)

        // 消费掉这个 action，避免旋转屏幕等配置变更后重新触发一次弹窗
        intent.action = null
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // 让后续 getIntent() 返回新的，保持状态一致
        setIntent(intent)
        handlePendingTransferIntent(intent)
    }

    companion object {
        /** 转账提醒通知点击时带的 action */
        const val ACTION_CONFIRM_PENDING = "com.moneyflow.app.action.CONFIRM_PENDING_TRANSFER"

        /** 待确认记录的 id */
        const val EXTRA_PENDING_ID = "pending_id"
    }
}
