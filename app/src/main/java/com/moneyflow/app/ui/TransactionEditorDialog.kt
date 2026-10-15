package com.moneyflow.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.moneyflow.app.data.Direction
import com.moneyflow.app.data.TransactionEntity

/**
 * 手动补录 / 编辑弹窗。
 *
 * 需求确认：必须支持手动补录（现金消费、遗漏的记录）。
 * 编辑时使用现有的来源名，新建时默认「手动记录」。
 */
@Composable
fun TransactionEditorDialog(
    existing: TransactionEntity?,
    onDismiss: () -> Unit,
    onSave: (
        id: Long,
        amount: String,
        direction: Direction,
        sourceName: String,
        merchant: String,
        note: String,
        occurredAt: Long,
    ) -> Unit,
    onDelete: (TransactionEntity) -> Unit,
) {
    // ⚠️ 所有状态都以记录 id 为 key。
    // 否则：编辑 A 记录 → 关闭 → 再编辑 B 记录，弹窗里还是 A 的内容。
    val stateKey = existing?.id ?: 0L

    var amount by remember(stateKey) {
        mutableStateOf(existing?.amount?.let { Fmt.amount(it) } ?: "")
    }
    var direction by remember(stateKey) {
        mutableStateOf(existing?.direction ?: Direction.EXPENSE)
    }
    var sourceName by remember(stateKey) {
        mutableStateOf(existing?.sourceName ?: "手动记录")
    }
    var merchant by remember(stateKey) { mutableStateOf(existing?.merchant ?: "") }
    var note by remember(stateKey) { mutableStateOf(existing?.note ?: "") }
    val occurredAt = remember(stateKey) {
        mutableLongStateOf(existing?.occurredAt ?: System.currentTimeMillis())
    }

    val isEdit = existing != null
    val amountValid = amount.replace(",", "").trim().toDoubleOrNull()?.let { it > 0 } == true

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isEdit) "编辑记录" else "手动补录") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
            ) {
                OutlinedTextField(
                    value = amount,
                    onValueChange = { amount = it },
                    label = { Text("金额（元）") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    isError = amount.isNotEmpty() && !amountValid,
                    modifier = Modifier.fillMaxWidth(),
                )

                Spacer(Modifier.height(12.dp))

                Text("方向", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Direction.entries.forEach { dir ->
                        FilterChip(
                            selected = direction == dir,
                            onClick = { direction = dir },
                            label = { Text(dir.label()) },
                        )
                    }
                }

                Spacer(Modifier.height(12.dp))

                OutlinedTextField(
                    value = sourceName,
                    onValueChange = { sourceName = it },
                    label = { Text("来源") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                Spacer(Modifier.height(12.dp))

                OutlinedTextField(
                    value = merchant,
                    onValueChange = { merchant = it },
                    label = { Text("商户 / 对手方（可选）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                Spacer(Modifier.height(12.dp))

                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text("备注（可选）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                if (isEdit) {
                    Spacer(Modifier.height(16.dp))
                    Text(
                        text = "记录时间：${Fmt.fullDate(occurredAt.value)} ${Fmt.dateTime(occurredAt.value)}",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    existing?.rawText?.let { raw ->
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "原始文本：$raw",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onSave(
                        existing?.id ?: 0L,
                        amount,
                        direction,
                        sourceName,
                        merchant,
                        note,
                        occurredAt.value,
                    )
                },
                enabled = amountValid,
            ) { Text("保存") }
        },
        dismissButton = {
            Row {
                if (isEdit) {
                    TextButton(onClick = { existing?.let(onDelete) }) {
                        Text("删除", color = MaterialTheme.colorScheme.error)
                    }
                }
                TextButton(onClick = onDismiss) { Text("取消") }
            }
        },
    )
}

private fun Direction.label(): String = when (this) {
    Direction.EXPENSE -> "支出"
    Direction.INCOME -> "收入"
}
