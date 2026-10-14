package com.moneyflow.app.ui

import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
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
import androidx.core.app.NotificationManagerCompat
import com.moneyflow.app.capture.NotificationCaptureService

/**
 * 设置页。核心职责：引导用户完成三项必须手动开启的系统权限。
 * App 无法自动申请这些权限，只能跳转设置页。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onOpenParseBench: () -> Unit,
    onOpenCsvImport: () -> Unit,
    onOpenSampleExport: () -> Unit,
    onOpenRuleManager: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current

    val notificationAccessGranted = isNotificationAccessGranted(context)
    val smsGranted = isSmsGranted(context)
    val batteryOptimized = isIgnoringBatteryOptimizations(context)

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
                    append("这是记账的主要数据来源，不开则无法自动记账。")
                    if (notificationAccessGranted && !listenerConnected) {
                        append("\n\n⚠️ 已授权但监听未连接。")
                        append("通常是后台进程被系统冻结，退出重进本页面即可恢复。")
                        append("若长期如此，请检查下面的「后台运行」设置。")
                    }
                },
                granted = notificationAccessGranted,
                buttonText = "去开启",
                onClick = { openNotificationAccessSettings(context) },
            )

            Spacer(Modifier.height(10.dp))

            PermissionCard(
                title = "2. 短信权限（推荐）",
                description = "读取银行扣款短信。银行短信格式最规范，识别最准，" +
                    "能与通知互相补漏。",
                granted = smsGranted,
                buttonText = "去开启",
                onClick = { openAppSettings(context) },
            )

            Spacer(Modifier.height(10.dp))

            PermissionCard(
                title = "3. 后台运行（强烈建议）",
                description = "关闭电池优化，减少系统杀掉后台服务的概率。" +
                    "国产 ROM 还需在「最近任务」里给本应用加锁。",
                granted = batteryOptimized,
                buttonText = "去关闭",
                onClick = { openBatterySettings(context) },
            )

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
                    "小米：设置→应用→流水记账→省电策略→无限制，并在最近任务里加锁\n" +
                    "华为：设置→应用→应用启动管理→手动管理，全部打开\n" +
                    "OPPO/vivo：设置→电池→后台高耗电→允许\n" +
                    "另外：不要在最近任务里划掉本应用，划掉会导致服务被终止。",
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
                    text = if (granted) "已开启" else "未开启",
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

/** 检查通知使用权是否已授予 */
fun isNotificationAccessGranted(context: Context): Boolean {
    val enabled = NotificationManagerCompat.getEnabledListenerPackages(context)
    return enabled.contains(context.packageName)
}

/** 检查短信读取权限 */
fun isSmsGranted(context: Context): Boolean {
    return context.checkSelfPermission(android.Manifest.permission.READ_SMS) ==
        android.content.pm.PackageManager.PERMISSION_GRANTED
}

/** 检查是否已忽略电池优化 */
fun isIgnoringBatteryOptimizations(context: Context): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
    val manager = context.getSystemService(android.os.PowerManager::class.java) ?: return false
    return manager.isIgnoringBatteryOptimizations(context.packageName)
}

/** 跳转到「通知使用权」设置页 */
fun openNotificationAccessSettings(context: Context) {
    try {
        context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
    } catch (_: Throwable) {
        openAppSettings(context)
    }
}

/** 跳转到本应用的系统设置页 */
fun openAppSettings(context: Context) {
    try {
        context.startActivity(
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                android.net.Uri.fromParts("package", context.packageName, null),
            )
        )
    } catch (_: Throwable) {
    }
}

/** 跳转到电池优化设置 */
fun openBatterySettings(context: Context) {
    try {
        context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
    } catch (_: Throwable) {
        openAppSettings(context)
    }
}
