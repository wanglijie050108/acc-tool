package com.moneyflow.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.moneyflow.app.data.Direction
import com.moneyflow.app.data.TransactionEntity
import java.util.Calendar

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransactionListScreen(
    viewModel: TransactionViewModel,
    onOpenSettings: () -> Unit,
) {
    val transactions by viewModel.transactions.collectAsStateWithLifecycle()
    val editing by viewModel.editing.collectAsStateWithLifecycle()
    val creating by viewModel.creating.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("流水记账", fontWeight = FontWeight.SemiBold) },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "设置")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { viewModel.openCreate() }) {
                Icon(Icons.Default.Add, contentDescription = "手动补录")
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(MaterialTheme.colorScheme.background),
        ) {
            MonthSummary(transactions)

            if (transactions.isEmpty()) {
                EmptyState()
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(bottom = 96.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    val grouped = transactions
                        .groupBy { dayKey(it.occurredAt) }
                        .toSortedMap(compareByDescending { it })

                    grouped.forEach { (dayBucket, dayItems) ->
                        item(key = "header_$dayBucket") {
                            DayHeader(dayItems)
                        }
                        items(dayItems, key = { it.id }) { entity ->
                            TransactionRow(
                                entity = entity,
                                onClick = { viewModel.openEdit(entity) },
                            )
                        }
                    }
                }
            }
        }
    }

    if (creating || editing != null) {
        TransactionEditorDialog(
            existing = editing,
            onDismiss = { viewModel.closeEditor() },
            onSave = { id, amount, direction, sourceName, merchant, note, occurredAt ->
                viewModel.save(id, amount, direction, sourceName, merchant, note, occurredAt)
            },
            onDelete = { entity ->
                viewModel.delete(entity)
                viewModel.closeEditor()
            },
        )
    }
}

/** 按天分组用的 key，取当天零点的时间戳 */
private fun dayKey(millis: Long): Long = Calendar.getInstance().apply {
    timeInMillis = millis
    set(Calendar.HOUR_OF_DAY, 0)
    set(Calendar.MINUTE, 0)
    set(Calendar.SECOND, 0)
    set(Calendar.MILLISECOND, 0)
}.timeInMillis

@Composable
private fun MonthSummary(transactions: List<TransactionEntity>) {
    val cal = Calendar.getInstance()
    val year = cal.get(Calendar.YEAR)
    val month = cal.get(Calendar.MONTH)

    val monthItems = transactions.filter {
        val c = Calendar.getInstance().apply { timeInMillis = it.occurredAt }
        c.get(Calendar.YEAR) == year && c.get(Calendar.MONTH) == month
    }

    val expense = monthItems
        .filter { it.direction == Direction.EXPENSE }
        .sumOf { it.amount }
    val income = monthItems
        .filter { it.direction == Direction.INCOME }
        .sumOf { it.amount }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primary,
        ),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text(
                text = "本月支出",
                color = Color.White.copy(alpha = 0.75f),
                fontSize = 13.sp,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = "¥ ${Fmt.amount(expense)}",
                color = Color.White,
                fontSize = 30.sp,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(12.dp))
            Row {
                Text(
                    text = "收入 ¥${Fmt.amount(income)}",
                    color = Color.White.copy(alpha = 0.85f),
                    fontSize = 13.sp,
                )
                Spacer(Modifier.width(16.dp))
                Text(
                    text = "共 ${monthItems.size} 笔",
                    color = Color.White.copy(alpha = 0.85f),
                    fontSize = 13.sp,
                )
            }
        }
    }
}

@Composable
private fun DayHeader(items: List<TransactionEntity>) {
    val dayExpense = items
        .filter { it.direction == Direction.EXPENSE }
        .sumOf { it.amount }
    val dayIncome = items
        .filter { it.direction == Direction.INCOME }
        .sumOf { it.amount }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = Fmt.dayHeader(items.first().occurredAt),
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = buildString {
                if (dayExpense > 0) append("支出 ${Fmt.amount(dayExpense)}")
                if (dayExpense > 0 && dayIncome > 0) append("  ")
                if (dayIncome > 0) append("收入 ${Fmt.amount(dayIncome)}")
            },
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun TransactionRow(
    entity: TransactionEntity,
    onClick: () -> Unit,
) {
    val amountColor = when (entity.direction) {
        Direction.EXPENSE -> Color(0xFFD9534F)
        Direction.INCOME -> Color(0xFF2E7D52)
        Direction.NEUTRAL -> MaterialTheme.colorScheme.onSurface
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
        shape = RoundedCornerShape(12.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 来源首字图标
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = entity.sourceName.take(1),
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                )
            }

            Spacer(Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = entity.merchant ?: entity.sourceName,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = buildString {
                        append(entity.sourceName)
                        append(" · ")
                        append(Fmt.dateTime(entity.occurredAt))
                        entity.note?.let {
                            append(" · ")
                            append(it)
                        }
                    },
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }

            Spacer(Modifier.width(8.dp))

            Text(
                text = Fmt.signedAmount(entity.amount, entity.direction),
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                color = amountColor,
            )
        }
    }
}

@Composable
private fun EmptyState() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "还没有流水",
                fontSize = 16.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "去「设置」开启通知权限后，\n支付记录会自动出现在这里",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
    }
}
