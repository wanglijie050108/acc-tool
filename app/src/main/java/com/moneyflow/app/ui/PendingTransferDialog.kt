package com.moneyflow.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.moneyflow.app.data.Direction
import com.moneyflow.app.data.PendingTransferEntity

/**
 * 「检测到一笔转账，金额是多少？」补录弹窗。
 *
 * ── 设计目标：3 秒输完 ──
 * 用户是被一条通知拉进来的，此刻的心理预期是「赶紧弄完」。
 * 所以这个弹窗只保留**一个必填项**（金额），
 * 其余字段（方向、对方、备注）都有默认值且可以不管。
 *
 * 具体做法：
 *   - 金额框**自动获得焦点并弹出数字键盘**，用户点进来就能直接打字
 *   - 方向预填猜测值（见 TransferSignal.guessDirection），一键可改
 *   - 原文折叠展示在底部，供用户回忆「这是哪一笔」
 */
@Composable
fun PendingTransferDialog(
    pending: PendingTransferEntity,
    onDismiss: () -> Unit,
    onConfirm: (
        pendingId: Long,
        amount: String,
        direction: Direction,
        merchant: String,
        note: String,
    ) -> Unit,
    onIgnore: (Long) -> Unit,
) {
    // 以记录 id 为 key，避免切换不同转账时残留上一条的输入
    val stateKey = pending.id

    var amount by remember(stateKey) { mutableStateOf("") }
    var direction by remember(stateKey) { mutableStateOf(pending.direction) }
    var merchant by remember(stateKey) { mutableStateOf("") }
    var note by remember(stateKey) { mutableStateOf("") }

    val amountValid = amount.replace(",", "").trim().toDoubleOrNull()?.let { it > 0 } == true

    // 自动聚焦 + 自动弹键盘。这是「3 秒输完」的关键一步：
    // 用户不用再点一下输入框。
    val focusRequester = remember(stateKey) { FocusRequester() }
    LaunchedEffect(stateKey) {
        // 包一层 runCatching：部分 ROM 上弹窗还在动画中就请求焦点会抛异常
        runCatching { focusRequester.requestFocus() }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("补上这笔转账的金额") },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "微信/支付宝的转账通知里不含金额，" +
                        "所以需要你手动补一下。填完就是一条完整流水。",
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Spacer(Modifier.height(14.dp))

                OutlinedTextField(
                    value = amount,
                    onValueChange = { amount = it },
                    label = { Text("金额（元）") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    isError = amount.isNotEmpty() && !amountValid,
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester),
                )

                Spacer(Modifier.height(12.dp))

                Text(
                    text = "方向（不确定就按默认，之后可以改）",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Direction.entries.forEach { dir ->
                        FilterChip(
                            selected = direction == dir,
                            onClick = { direction = dir },
                            label = { Text(dir.pendingLabel()) },
                        )
                    }
                }

                Spacer(Modifier.height(12.dp))

                OutlinedTextField(
                    value = merchant,
                    onValueChange = { merchant = it },
                    label = { Text("对方（可选）") },
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

                Spacer(Modifier.height(14.dp))

                // 原文放在最后且用弱化样式：它是给用户回忆用的，不是重点
                Text(
                    text = "通知原文：${pending.rawText}",
                    fontSize = 11.sp,
                    lineHeight = 16.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "时间：${Fmt.fullDate(pending.detectedAt)} ${Fmt.dateTime(pending.detectedAt)}",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onConfirm(pending.id, amount, direction, merchant, note)
                },
                enabled = amountValid,
            ) { Text("记账") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = { onIgnore(pending.id) }) {
                    Text("不是转账", color = MaterialTheme.colorScheme.error)
                }
                TextButton(onClick = onDismiss) { Text("稍后") }
            }
        },
    )
}

/**
 * 方向标签（待确认场景专用）。
 *
 * 只有支出和收入两个方向 —— 「中性」已被删除，
 * 因为方向说不清的正确做法是问用户，而不是记一条方向不明的流水。
 */
private fun Direction.pendingLabel(): String = when (this) {
    Direction.EXPENSE -> "支出"
    Direction.INCOME -> "收入"
}
