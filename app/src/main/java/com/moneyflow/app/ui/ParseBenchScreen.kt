package com.moneyflow.app.ui

import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.moneyflow.app.parse.ParseEngine
import com.moneyflow.app.parse.ParseResult

/**
 * 解析测试台。
 *
 * 这是调优规则的核心工具：把真实通知/短信文本粘进来，立刻看到能否识别、识别成什么。
 * 用途：把识别失败的样本发给我，我据此修改 BuiltinRules 里的正则。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ParseBenchScreen(onBack: () -> Unit) {
    var input by remember { mutableStateOf("") }
    var packageName by remember { mutableStateOf("com.tencent.mm") }
    var result by remember { mutableStateOf<ParseResult?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("解析测试") },
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
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                label = { Text("粘贴通知或短信原文") },
                minLines = 4,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(12.dp))

            OutlinedTextField(
                value = packageName,
                onValueChange = { packageName = it },
                label = { Text("来源包名（短信留空）") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(8.dp))
            Text(
                text = "微信：com.tencent.mm\n支付宝：com.eg.android.AlipayGphone\n短信：留空",
                fontSize = 11.sp,
                lineHeight = 16.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(12.dp))

            Button(
                onClick = {
                    result = ParseEngine.parse(
                        text = input,
                        packageName = packageName.ifBlank { null },
                        occurredAt = System.currentTimeMillis(),
                    )
                },
                enabled = input.isNotBlank(),
            ) { Text("测试识别") }

            Spacer(Modifier.height(16.dp))

            when (val r = result) {
                null -> Unit

                is ParseResult.Success -> ResultCard(
                    title = "✅ 识别成功",
                    lines = listOf(
                        "金额：${Fmt.amount(r.amount)} 元" to true,
                        "方向：${r.direction.name}" to false,
                        "来源：${r.sourceName}" to false,
                        "商户：${r.merchant ?: "（未提取）"}" to false,
                        "命中规则：${r.ruleId}" to false,
                        "指纹：${r.fingerprint.take(12)}..." to false,
                    ),
                )

                is ParseResult.NoAmount -> ResultCard(
                    title = "⚠️ 未识别出金额",
                    lines = listOf(
                        "这条文本没有匹配到任何规则。" to false,
                        "如果它确实是一笔支付，请把原文发给我，我来加规则。" to false,
                    ),
                )

                is ParseResult.Failure -> ResultCard(
                    title = "❌ 解析出错",
                    lines = listOf(r.reason to false),
                )
            }
        }
    }
}

@Composable
private fun ResultCard(title: String, lines: List<Pair<String, Boolean>>) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(title, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(10.dp))
            lines.forEach { (line, bold) ->
                Text(
                    text = line,
                    fontSize = 13.sp,
                    fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal,
                    lineHeight = 20.sp,
                )
            }
        }
    }
}
