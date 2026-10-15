package com.moneyflow.app

import android.content.Intent
import android.os.Build
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
import com.moneyflow.app.ui.SettingsScreen
import com.moneyflow.app.ui.TransactionListScreen
import com.moneyflow.app.ui.TransactionViewModel
import com.moneyflow.app.ui.MoneyFlowTheme
import com.moneyflow.app.ui.readCsvText

/** 应用内页面。页面少，直接用一个状态变量切换，不引入导航库 */
private enum class Screen { LIST, SETTINGS, PARSE_BENCH, CSV_IMPORT, SAMPLE_EXPORT, RULE_MANAGER }

class MainActivity : ComponentActivity() {

    private val viewModel: TransactionViewModel by viewModels()

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* 用户选择结果不影响主流程，设置页会实时显示状态 */ }

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

        // 申请短信与通知权限。通知使用权无法在这里申请，必须用户去设置页手动开。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            permissionLauncher.launch(MoneyFlowApp.RUNTIME_PERMISSIONS)
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
