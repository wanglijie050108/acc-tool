package com.moneyflow.app.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.moneyflow.app.data.RawSampleEntity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 样本导出页 —— 让用户一键把真实通知交给开发者调规则。
 *
 * ── 为什么要有这个页面 ──
 * 解析规则的准确度完全取决于真实通知长什么样，但让用户手动
 * 「下拉通知栏 → 长按 → 复制 → 粘贴到聊天窗口」是不现实的，
 * 大部分人不会做，也不记得做。
 *
 * 所以 App 自己留档，用户只要点「分享样本」，选微信发给自己，
 * 再转发给开发者即可。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SampleExportScreen(
    samples: List<RawSampleEntity>,
    totalCount: Int,
    onBack: () -> Unit,
    onClear: () -> Unit,
    onShare: (String) -> Unit,
) {
    val matched = samples.count { it.matched }
    val unmatched = samples.size - matched

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("导出样本") },
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
                text = "这些样本是干什么用的",
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = "App 在后台把收到的支付通知都记了下来。" +
                    "如果发现有【未识别】的样本，把它发给我，我就能加规则让它被正确记账。",
                fontSize = 13.sp,
                lineHeight = 19.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(16.dp))

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(
                        text = "已记录 $totalCount 条，本次导出 ${samples.size} 条",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "✅ 已识别 $matched 条　　⚠️ 未识别 $unmatched 条",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            Button(
                onClick = { onShare(formatSamples(samples)) },
                modifier = Modifier.fillMaxWidth(),
                enabled = samples.isNotEmpty(),
            ) {
                Text("分享样本（推荐发给自己再转发）")
            }

            Spacer(Modifier.height(8.dp))

            OutlinedButton(
                onClick = onClear,
                modifier = Modifier.fillMaxWidth(),
                enabled = totalCount > 0,
            ) {
                Text("清空全部样本")
            }

            Spacer(Modifier.height(24.dp))

            Text(
                text = "最近记录的内容预览",
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.height(8.dp))

            if (samples.isEmpty()) {
                Text(
                    text = "还没有记录到样本。\n\n" +
                        "去用微信/支付宝付一笔钱，或者等一条银行短信，" +
                        "回到这里就能看到了。",
                    fontSize = 13.sp,
                    lineHeight = 19.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                samples.take(30).forEach { sample ->
                    SampleCard(sample)
                    Spacer(Modifier.height(8.dp))
                }
            }

            Spacer(Modifier.height(60.dp))
        }
    }
}

@Composable
private fun SampleCard(sample: RawSampleEntity) {
    val timeFmt = SimpleDateFormat("MM-dd HH:mm", Locale.CHINA)

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = if (sample.matched) "✅ 已识别" else "⚠️ 未识别",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = if (sample.matched) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.error,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = timeFmt.format(Date(sample.receivedAt)),
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                text = "来源：${sample.packageName ?: "短信"}" +
                    (sample.ruleId?.let { "　规则：$it" } ?: ""),
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = sample.text,
                fontSize = 12.sp,
                lineHeight = 17.sp,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}

/**
 * 把样本格式化成便于阅读、也便于我直接写正则的文本。
 *
 * 格式设计：每条一行 `[规则id 或 UNMATCHED] 包名 | 原文`
 * 这样我拿到后可以直接挑出 UNMATCHED 的来加规则。
 */
fun formatSamples(samples: List<RawSampleEntity>): String {
    val timeFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA)
    return buildString {
        appendLine("流水记账 — 通知样本导出")
        appendLine("共 ${samples.size} 条")
        appendLine("=".repeat(50))
        samples.sortedBy { it.receivedAt }.forEach { s ->
            val tag = if (s.matched) (s.ruleId ?: "MATCHED") else "UNMATCHED"
            appendLine()
            appendLine("[$tag]")
            appendLine("包名: ${s.packageName ?: "(短信)"}")
            if (!s.title.isNullOrBlank()) appendLine("发件人: ${s.title}")
            appendLine("时间: ${timeFmt.format(Date(s.receivedAt))}")
            appendLine("原文: ${s.text}")
        }
    }
}

/**
 * 用系统分享面板把样本发出去。
 * 用户选微信 → 发给自己 → 再转发给开发者。
 */
fun shareText(context: Context, text: String, subject: String = "流水记账—通知样本") {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, subject)
        putExtra(Intent.EXTRA_TEXT, text)
    }
    val chooser = Intent.createChooser(intent, "分享样本").apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    runCatching { context.startActivity(chooser) }
}

/** 未使用，保留以备将来做「直接存文件」的导出方式 */
@Suppress("unused")
private fun shareFile(context: Context, uri: Uri) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_STREAM, uri)
    }
    runCatching { context.startActivity(Intent.createChooser(intent, "分享")) }
}
