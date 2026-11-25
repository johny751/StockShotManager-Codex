package com.stockshot.manager.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog as M3DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * \u65e5\u5386\u5f0f\u65e5\u671f\u9009\u62e9\uff1a\u57fa\u4e8e Material3 DatePicker\uff0c
 * \u4ec5\u5141\u8bb8\u6709\u622a\u56fe\u7684\u65e5\u671f\uff08availableDates\uff09\u53ef\u9009\uff0c\u5176\u4f59\u65e5\u671f\u7f6e\u7070\u3002
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DatePickerDialog(
    availableDates: List<String>,
    selectedDate: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    if (availableDates.isEmpty()) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("\u9009\u62e9\u65e5\u671f") },
            text = {
                Text(
                    "\u6682\u65e0\u622a\u56fe\u6570\u636e",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            confirmButton = {
                TextButton(onClick = onDismiss) { Text("\u5173\u95ed") }
            },
        )
        return
    }

    // \u6709\u622a\u56fe\u7684\u65e5\u671f -> UTC \u96f6\u70b9\u6beb\u79d2\u96c6\u5408
    val availableMillis = remember(availableDates) {
        availableDates.mapNotNull { dateToUtcMillis(it) }.toSet()
    }
    val availableYears = remember(availableDates) {
        availableDates.mapNotNull { it.take(4).toIntOrNull() }.toSet()
    }

    val selectableDates = remember(availableMillis, availableYears) {
        object : SelectableDates {
            override fun isSelectableDate(utcTimeMillis: Long): Boolean =
                utcTimeMillis in availableMillis

            override fun isSelectableYear(year: Int): Boolean =
                availableYears.isEmpty() || year in availableYears
        }
    }

    val initialMillis = remember(selectedDate) { dateToUtcMillis(selectedDate) }
    val state = rememberDatePickerState(
        initialSelectedDateMillis = initialMillis,
        selectableDates = selectableDates,
    )

    M3DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = {
                    state.selectedDateMillis?.let { millis ->
                        utcMillisToDate(millis)?.let(onSelect)
                    }
                }
            ) { Text("\u786e\u5b9a") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("\u53d6\u6d88") }
        },
    ) {
        DatePicker(state = state)
    }
}

private fun dateToUtcMillis(date: String): Long? = try {
    LocalDate.parse(date).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
} catch (e: Exception) {
    null
}

private fun utcMillisToDate(millis: Long): String? = try {
    Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate().toString()
} catch (e: Exception) {
    null
}

/**
 * \u4ef7\u683c\u7f16\u8f91\u5f39\u7a97\uff1a\u6570\u5b57\u8f93\u5165\uff0c\u786e\u8ba4\u56de\u4f20 Double\uff08\u65e0\u6548\u8f93\u5165\u6309 0 \u5904\u7406\uff09\u3002
 */
@Composable
fun PriceEditDialog(
    title: String,
    initial: Double,
    onConfirm: (Double) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember {
        mutableStateOf(if (initial > 0.0) formatPrice(initial) else "")
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { new ->
                    if (new.isEmpty() || new.matches(Regex("^\\d*\\.?\\d*$"))) {
                        text = new
                    }
                },
                singleLine = true,
                placeholder = { Text("\u8bf7\u8f93\u5165\u4ef7\u683c") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(text.toDoubleOrNull() ?: 0.0) }) {
                Text("\u786e\u5b9a")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("\u53d6\u6d88") }
        }
    )
}

private fun formatPrice(v: Double): String = String.format("%.2f", v)

internal data class RemarksEditTarget(val code: String, val date: String, val initial: String)

@Composable
internal fun RemarksEditDialog(
    target: RemarksEditTarget,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by rememberSaveable(target.code, target.date, target.initial) {
        mutableStateOf(target.initial)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("编辑备注") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("${target.code} · ${target.date}")
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("自由填写，支持换行") },
                    minLines = 3,
                    maxLines = 8,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(text) }) { Text("保存") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = { text = "" }) { Text("清空") }
                TextButton(onClick = onDismiss) { Text("取消") }
            }
        },
    )
}

@Composable
internal fun HighestPriceDialog(
    date: String,
    stockCount: Int,
    loading: Boolean,
    onConfirm: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by rememberSaveable(date) { mutableStateOf("10") }
    val days = text.toIntOrNull()?.takeIf { it > 0 }
    val range = days?.let {
        runCatching {
            val start = LocalDate.parse(date)
            "${start.plusDays(1)} 至 ${start.plusDays(it.toLong())}"
        }.getOrNull()
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("更新最高价") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("将更新 $date 页面全部 $stockCount 只股票，包含筛选后隐藏的股票。")
                OutlinedTextField(
                    value = text,
                    onValueChange = { value ->
                        if (value.all { it in '0'..'9' }) text = value
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("最大天数 N") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    isError = days == null,
                    supportingText = {
                        Text(if (days == null) "请输入有效的正整数" else "默认为 10 天，按自然日计算")
                    },
                )
                Text(
                    if (range != null) "查询范围：$range，不含记录当日 $date。"
                    else "查询记录日期之后第 1 至 N 个自然日，不含记录当日。"
                )
                Text("区间内的最高价将覆盖卖出价，并重新计算获利。不足 N 天时使用已有行情，尚无行情时保留原价。")
                if (stockCount == 0) Text("所选日期暂无股票，请先采集列表。")
            }
        },
        confirmButton = {
            TextButton(
                enabled = days != null && range != null && stockCount > 0 && !loading,
                onClick = { days?.let(onConfirm) },
            ) { Text("更新") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}
