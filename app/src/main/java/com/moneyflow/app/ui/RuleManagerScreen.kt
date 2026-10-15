package com.moneyflow.app.ui

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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.moneyflow.app.data.Direction
import com.moneyflow.app.parse.ParseEngine
import com.moneyflow.app.parse.ParseResult

/**
 * 规则管理页。
 *
 * ── 为什么让用户自己写规则 ──
 * 通知格式千变万化（各家银行措辞、微信支付宝各版本），
 * 靠开发者发版追是追不完的。把规则做成用户可编辑的数据后，
 * 遇到新格式用户自己加一条即刻生效，不用等更新。
 *
 * 这个页面提供最简单的用法：把通知原文粘进去 → 填正则 → 立即测试 →
 * 保存。有实时预览，写错了马上能看出来。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RuleManagerScreen(
    customRuleCount: Int,
    onBack: () -> Unit,
    onSaveRule: (
        name: String,
        pattern: String,
        amountGroup: Int,
        direction: Direction,
        smsOnly: Boolean,
    ) -> Unit,
    onExport: () -> Unit,
    onImport: () -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var pattern by remember { mutableStateOf("") }
    var amountGroup by remember { mutableStateOf("1") }
    var direction by remember { mutableStateOf(Direction.EXPENSE) }
    var smsOnly by remember { mutableStateOf(false) }
    var testText by remember { mutableStateOf("") }
    var testResult by remember { mutableStateOf<String?>(null) }
    var savedHint by remember { mutableStateOf<String?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("自定义规则") },
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
                text = "当前有 $customRuleCount 条自定义规则",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(12.dp))

            ExplanationCard()

            Spacer(Modifier.height(16.dp))

            Text("① 给规则起个名字", fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(6.dp))
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                placeholder = { Text("例如：招商银行短信") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(14.dp))

            Text("② 写正则（金额要用括号括起来）", fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(6.dp))
            OutlinedTextField(
                value = pattern,
                onValueChange = { pattern = it },
                placeholder = { Text("""支出人民币([\d,]+\.?\d*)元""") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                textStyle = androidx.compose.ui.text.TextStyle(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp,
                ),
            )

            Spacer(Modifier.height(10.dp))

            Row {
                Text("金额在第几个括号：", fontSize = 13.sp)
                Spacer(Modifier.width(8.dp))
                OutlinedTextField(
                    value = amountGroup,
                    onValueChange = { amountGroup = it.filter { c -> c.isDigit() } },
                    singleLine = true,
                    modifier = Modifier.width(80.dp),
                )
            }

            Spacer(Modifier.height(14.dp))

            Text("③ 这笔钱是支出还是收入", fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(6.dp))
            Row {
                Direction.entries.forEach { d ->
                    FilterChip(
                        selected = direction == d,
                        onClick = { direction = d },
                        label = { Text(d.label()) },
                        modifier = Modifier.padding(end = 6.dp),
                    )
                }
            }

            Spacer(Modifier.height(14.dp))

            Row {
                FilterChip(
                    selected = smsOnly,
                    onClick = { smsOnly = !smsOnly },
                    label = { Text("只用于短信") },
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = "如果这条规则写得很宽松（比如只匹配「支出」两个字），" +
                    "建议勾上，否则可能抢走微信/支付宝通知的识别。",
                fontSize = 11.sp,
                lineHeight = 16.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(20.dp))

            Text("④ 用真实通知测一下", fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(6.dp))
            OutlinedTextField(
                value = testText,
                onValueChange = { testText = it },
                placeholder = { Text("把通知原文粘到这里") },
                minLines = 3,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(10.dp))

            Button(
                onClick = {
                    testResult = testRule(pattern, amountGroup.toIntOrNull() ?: 1, testText)
                },
                enabled = pattern.isNotBlank() && testText.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) { Text("测试这条规则") }

            testResult?.let {
                Spacer(Modifier.height(10.dp))
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

            Spacer(Modifier.height(20.dp))

            Button(
                onClick = {
                    onSaveRule(
                        name,
                        pattern,
                        amountGroup.toIntOrNull() ?: 1,
                        direction,
                        smsOnly,
                    )
                    savedHint = "已保存：${name.ifBlank { "自定义规则" }}"
                    name = ""
                    pattern = ""
                    testText = ""
                    testResult = null
                },
                enabled = pattern.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) { Text("保存规则") }

            savedHint?.let {
                Spacer(Modifier.height(10.dp))
                Text(it, fontSize = 13.sp, color = MaterialTheme.colorScheme.primary)
            }

            Spacer(Modifier.height(24.dp))

            Text("备份与分享", fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(8.dp))
            Text(
                text = "可以把规则导出成 JSON 发给别人，或从别人那里导入。",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(10.dp))
            Row {
                OutlinedButton(onClick = onExport, modifier = Modifier.weight(1f)) {
                    Text("导出规则")
                }
                Spacer(Modifier.width(8.dp))
                OutlinedButton(onClick = onImport, modifier = Modifier.weight(1f)) {
                    Text("导入规则")
                }
            }

            Spacer(Modifier.height(60.dp))
        }
    }
}

@Composable
private fun ExplanationCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text("什么时候需要自己写规则", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            Text(
                text = "如果某笔支付没被自动记账，先到「导出样本」看看原文。" +
                    "然后用这里的工具写一条规则，立刻就能生效，不用等 App 更新。",
                fontSize = 12.sp,
                lineHeight = 18.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(10.dp))
            Text("正则速查", fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(4.dp))
            Text(
                text = "\\d  任意数字\n" +
                    "+   前面出现一次或多次\n" +
                    "*   前面出现零次或多次\n" +
                    "?   非贪婪（尽量少匹配）\n" +
                    ".   任意一个字符\n" +
                    "( ) 括号内是要提取的内容\n" +
                    "[\\d,]  数字或逗号",
                fontSize = 12.sp,
                lineHeight = 18.sp,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun Direction.label(): String = when (this) {
    Direction.EXPENSE -> "支出"
    Direction.INCOME -> "收入"
}

/**
 * 用给定正则测试一段文本。
 *
 * 注意：这里直接用标准库正则测试，看到的结果与 App 实际解析一致。
 */
private fun testRule(pattern: String, amountGroup: Int, text: String): String {
    val regex = try {
        Regex(pattern)
    } catch (t: Throwable) {
        return "❌ 正则写法有误：${t.message}"
    }

    val match = regex.find(text)
        ?: return "❌ 没匹配上。检查正则是否和原文完全对应（注意标点和全角半角）。"

    if (amountGroup < 1 || amountGroup >= match.groups.size) {
        return "❌ 第 $amountGroup 个括号不存在。这条正则里只有 ${match.groups.size - 1} 个括号。"
    }

    val raw = match.groups[amountGroup]?.value
        ?: return "❌ 第 $amountGroup 个括号匹配到的是空内容。"

    val amount = raw.replace(",", "").trim().toDoubleOrNull()
        ?: return "⚠️ 匹配到了「$raw」，但它不是数字，无法作为金额。"

    return "✅ 匹配成功\n提取到金额：$amount\n匹配到的整段：${match.value}"
}
