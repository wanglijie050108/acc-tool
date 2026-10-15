package com.moneyflow.app.ui

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.moneyflow.app.capture.NotificationCaptureService

/**
 * 设置页。核心职责：引导用户完成三项必须开启的系统权限。
 *
 * ⚠️ 按钮行为**不是**一律跳系统设置页：
 * 短信 / 通知 / 电池优化这三项由 App 自己弹系统对话框（用户点一下就好），
 * 只有「通知使用权」是平台不允许 App 申请的，才必须跳系统列表。
 * 这个分流由 [PermissionRequest] 统一判断，本文件不自己写 startActivity。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    /** 用户点了某一项权限的按钮。分流逻辑在 MainActivity → PermissionRequest */
    onRequestPermission: (PermissionRequest.Kind) -> Unit,
    onOpenParseBench: () -> Unit,
    onOpenCsvImport: () -> Unit,
    onOpenSampleExport: () -> Unit,
    onOpenRuleManager: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current

    val notificationAccessGranted = PermissionRequest.isNotificationAccessGranted(context)
    val smsGranted = PermissionRequest.isSmsGranted(context)
    val batteryOptimized = PermissionRequest.isIgnoringBatteryOptimizations(context)

    // 监听是否真的连着。授权为真但未连接 = 收不到通知，用户却看不出来。
    // 用 remember + key 让它每次进入界面重新计算。
    var listenerConnected by remember { mutableStateOf(NotificationCaptureService.isListening()) }
    LaunchedEffect(Unit) { listenerConnected = NotificationCaptureService.isListening() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("设置") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            Text(
                text = "权限配置",
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))

            PermissionCard(
                title = "1. 通知使用权（必须）",
                description = buildString {
                    append("自动读取微信、支付宝、银行的支付通知。")
                    append("这是记账的主要数据来源，不开则无法自动记账。\n\n")
                    append("⚠️ 这一项 Android 不允许 App 自己弹窗申请，")
                    append("点下面的按钮会打开系统的开关列表，")
                    append("请找到「流水记账」把开关打开。")
                    if (notificationAccessGranted && !listenerConnected) {
                        append("\n\n⚠️ 已授权，但系统没有把监听服务连上 —— ")
                        append("此时收不到任何通知，付款也不会被记录。\n\n")
                        append("恢复办法（真机实测唯一有效的一条）：\n")
                        append("点下面的按钮 → 找到「流水记账」→ 把开关关掉，再重新打开。\n\n")
                        append("小米/红米请额外确认：设置 → 应用设置 → 应用管理 → ")
                        append("流水记账 → 自启动，必须是打开状态。")
                    }
                },
                granted = notificationAccessGranted && listenerConnected,
                statusText = if (notificationAccessGranted && !listenerConnected) {
                    "已授权·未连接"
                } else {
                    null
                },
                buttonText = if (notificationAccessGranted && !listenerConnected) {
                    "去把开关关掉再打开"
                } else {
                    "去开启"
                },
                onClick = { onRequestPermission(PermissionRequest.Kind.NOTIFICATION_ACCESS) },
            )

            Spacer(Modifier.height(10.dp))

            PermissionCard(
                title = "2. 短信权限（推荐）",
                description = "读取银行扣款短信。银行短信格式最规范，识别最准，" +
                    "能与通知互相补漏。\n\n点下面的按钮会**直接弹出系统授权框**，" +
                    "点「允许」即可，不用自己去设置里找。",
                granted = smsGranted,
                buttonText = if (smsGranted) "已开启" else "点这里授权",
                onClick = { onRequestPermission(PermissionRequest.Kind.SMS) },
            )

            Spacer(Modifier.height(10.dp))

            PermissionCard(
                title = "3. 后台运行（强烈建议）",
                description = "关闭电池优化，减少系统杀掉后台服务的概率。" +
                    "国产 ROM 还需在「最近任务」里给本应用加锁。\n\n" +
                    "点下面的按钮会弹出系统的「是否允许忽略电池优化」对话框。",
                granted = batteryOptimized,
                buttonText = if (batteryOptimized) "已关闭" else "点这里关闭",
                onClick = { onRequestPermission(PermissionRequest.Kind.BATTERY) },
            )

            Spacer(Modifier.height(24.dp))

            Text(
                text = "关于朋友间转账",
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "转账通知里没有金额，这是正常的",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "微信/支付宝给好友转账后，通知栏那条消息" +
                            "原文只有「[转账]已被接收」，不含金额 —— " +
                            "金额只在 App 内部的账单页里，通知拿不到。\n\n" +
                            "所以本应用会给这类转账发一条提醒，让你补个金额。" +
                            "补录弹窗会自动聚焦金额框，输完点「记账」即可，约 3 秒。\n\n" +
                            "待确认的记录存在本地数据库，App 被系统杀掉也不会丢。",
                        fontSize = 12.sp,
                        lineHeight = 17.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Spacer(Modifier.height(24.dp))

            Text(
                text = "工具",
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))

            Card(
                modifier = Modifier.fillMaxWidth(),
                onClick = onOpenCsvImport,
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("导入账单（可选）", fontSize = 15.sp, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "导入支付宝/微信导出的 CSV 账单，补上通知漏记的流水。" +
                            "会自动和已记的流水去重，同一笔消费不会记两次。",
                        fontSize = 12.sp,
                        lineHeight = 17.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Spacer(Modifier.height(10.dp))

            Card(
                modifier = Modifier.fillMaxWidth(),
                onClick = onOpenRuleManager,
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("自定义规则（自己解决识别问题）", fontSize = 15.sp, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "遇到内置规则认不出的通知，可以自己加一条。写好后立刻生效，" +
                            "不用等 App 更新，也不用重新安装。",
                        fontSize = 12.sp,
                        lineHeight = 17.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Spacer(Modifier.height(10.dp))

            Card(
                modifier = Modifier.fillMaxWidth(),
                onClick = onOpenSampleExport,
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("导出样本（识别不准时用）", fontSize = 15.sp, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "App 会自动记下收到的支付通知。" +
                            "如果发现某类支付没被记上，来这里导出样本发给我，" +
                            "我加规则让它能被识别。",
                        fontSize = 12.sp,
                        lineHeight = 17.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Spacer(Modifier.height(10.dp))

            Card(
                modifier = Modifier.fillMaxWidth(),
                onClick = onOpenParseBench,
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("解析测试", fontSize = 15.sp, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "把真实的支付通知/短信文本粘进去，看看能不能正确识别。" +
                            "识别不准时，把样本发给我来校准规则。",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Spacer(Modifier.height(24.dp))

            Text(
                text = "国产 ROM 保活提示\n" +
                    "小米/红米：设置 → 应用设置 → 应用管理 → 流水记账 → " +
                    "把「自启动」打开，并把「省电策略」设为无限制；" +
                    "再在最近任务里给本应用加锁\n" +
                    "华为：设置 → 应用 → 应用启动管理 → 手动管理，全部打开\n" +
                    "OPPO/vivo：设置 → 电池 → 后台高耗电 → 允许\n" +
                    "另外：不要在最近任务里划掉本应用，划掉会导致服务被终止。\n\n" +
                    "⚠️ 如果你用「应用分身 / 双开」的微信、支付宝付款，本应用收不到它们的通知。\n" +
                    "安卓的通知监听是按用户空间隔离的，分身属于另一个空间。\n" +
                    "这种情况请用「导入账单」补记，或改用主空间的支付应用。",
                fontSize = 12.sp,
                lineHeight = 18.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(80.dp))
        }
    }
}

@Composable
private fun PermissionCard(
    title: String,
    description: String,
    granted: Boolean,
    buttonText: String,
    onClick: () -> Unit,
    /** 覆盖右上角状态文字。用于「已授权但监听未连接」这种需要说清楚的中间态 */
    statusText: String? = null,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = title,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = statusText ?: if (granted) "已开启" else "未开启",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = if (granted) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.error,
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                text = description,
                fontSize = 12.sp,
                lineHeight = 17.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            Button(onClick = onClick) { Text(buttonText) }
        }
    }
}

// ─────────────── 权限检测与跳转 ───────────────
//
// ⚠️ 本文件**不再自己实现**权限查询和跳转。
// 它们全部在 PermissionRequest 里，只有一份实现。
//
// 这么改的原因是这个项目已经吃过两次「同一逻辑写两份」的亏：
//   - 指纹算法（已抽成 Fingerprint）
//   - 「和钱有关」判定（已抽成 MoneyText，之前通知/短信各一份 → 验证码漏留档）
// 权限原本也散在 MainActivity 和本文件两处，且「哪些能弹窗」的知识只写在注释里。
// 想要新增一项权限引导时，**加到 PermissionRequest.Kind**，不要在这里 new Intent。
