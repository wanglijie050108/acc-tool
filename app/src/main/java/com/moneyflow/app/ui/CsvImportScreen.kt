package com.moneyflow.app.ui

import android.content.Context
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * CSV 账单导入说明页。
 *
 * 实际的文件选择在 MainActivity 里用系统文件选择器完成
 * （ActivityResultContracts.OpenDocument），这里只负责说明和入口。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CsvImportScreen(
    onBack: () -> Unit,
    onPickFile: () -> Unit,
    statusText: String?,
    lastResultText: String?,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("导入账单") },
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
                text = "为什么要导入账单",
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = "通知抓取是实时的，但手机被系统冻结时可能漏记。" +
                    "导入账单可以补上漏掉的记录，并且自动和已记的流水去重 ——" +
                    "同一笔消费不会记两次。",
                fontSize = 13.sp,
                lineHeight = 19.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(20.dp))

            Text(
                text = "怎么导出账单",
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.height(8.dp))

            StepCard(
                title = "支付宝",
                lines = listOf(
                    "打开支付宝 → 我的 → 账单",
                    "右上角「···」→ 开具交易流水证明",
                    "选择「用于个人对账」→ 选时间范围",
                    "提交后账单会发到你邮箱，附件是 CSV",
                    "把 CSV 传到手机（微信发给自己最方便）",
                ),
            )

            Spacer(Modifier.height(10.dp))

            StepCard(
                title = "微信",
                lines = listOf(
                    "微信 → 我 → 服务 → 钱包 → 账单",
                    "右上角「常见问题」→ 下载账单",
                    "选择「用于个人对账」→ 选时间范围",
                    "账单会发到邮箱（微信可能要等几小时）",
                    "注意：微信发的是加密压缩包，需要密码解压",
                ),
            )

            Spacer(Modifier.height(20.dp))

            Button(
                onClick = onPickFile,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("选择 CSV 文件")
            }

            statusText?.let {
                Spacer(Modifier.height(12.dp))
                Text(
                    text = it,
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            lastResultText?.let {
                Spacer(Modifier.height(12.dp))
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                    ),
                ) {
                    Text(
                        text = it,
                        fontSize = 13.sp,
                        lineHeight = 19.sp,
                        modifier = Modifier.padding(14.dp),
                    )
                }
            }

            Spacer(Modifier.height(24.dp))

            Text(
                text = "说明：只支持支付宝/微信导出的原始 CSV。" +
                    "如果你用 Excel 打开过再另存，列名可能被改动导致无法识别 ——" +
                    "请直接用邮箱里下载的原始文件。",
                fontSize = 12.sp,
                lineHeight = 18.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(60.dp))
        }
    }
}

@Composable
private fun StepCard(title: String, lines: List<String>) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            lines.forEachIndexed { i, line ->
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = "${i + 1}.",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = line,
                        fontSize = 12.sp,
                        lineHeight = 18.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/**
 * 读取用户选中的 CSV 内容。
 *
 * 编码处理是关键：支付宝导出的 CSV 是 **GBK** 编码，
 * 直接按 UTF-8 读会得到乱码，导致表头匹配失败、整个文件解析不出来。
 * 这里先试 UTF-8，失败再退回 GBK。
 */
fun readCsvText(context: Context, uri: Uri): kotlin.Result<String> {
    return try {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        when {
            bytes == null -> kotlin.Result.failure(IllegalStateException("无法打开文件"))
            bytes.isEmpty() -> kotlin.Result.failure(IllegalStateException("文件是空的"))
            else -> kotlin.Result.success(decodeBytes(bytes))
        }
    } catch (t: Throwable) {
        kotlin.Result.failure(t)
    }
}

/** 按 UTF-8 → GBK 的顺序尝试解码 */
internal fun decodeBytes(bytes: ByteArray): String {
    // 有 BOM 直接按 UTF-8
    if (bytes.size >= 3 &&
        bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()
    ) {
        return String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
    }

    // 先试严格 UTF-8：如果字节序列合法，基本可以确定是 UTF-8
    val utf8 = runCatching {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
            .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
            .decode(java.nio.ByteBuffer.wrap(bytes))
            .toString()
    }.getOrNull()

    if (utf8 != null) return utf8

    // 退回 GBK（支付宝账单常用编码）
    return runCatching { String(bytes, charset("GBK")) }
        .getOrElse { String(bytes, Charsets.ISO_8859_1) }
}