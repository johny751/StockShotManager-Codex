package com.stockshot.manager.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import com.stockshot.manager.data.entity.StockRecord
import com.stockshot.manager.ui.Routes
import com.stockshot.manager.ui.nav.ViewerHolder
import com.stockshot.manager.ui.theme.ProfitDown
import com.stockshot.manager.ui.theme.ProfitFlat
import com.stockshot.manager.ui.theme.ProfitUp
import com.stockshot.manager.ui.vm.SearchViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(nav: NavHostController, vm: SearchViewModel = viewModel()) {
    val state by vm.ui.collectAsStateWithLifecycle()
    var remarksEdit by remember { mutableStateOf<RemarksEditTarget?>(null) }
    val snackbarHost = remember { SnackbarHostState() }

    LaunchedEffect(state.snackbar) {
        state.snackbar?.let {
            snackbarHost.showSnackbar(it)
            vm.consumeSnackbar()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHost) },
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = { nav.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                title = {
                    OutlinedTextField(
                        value = state.query,
                        onValueChange = { vm.onQueryChange(it) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        placeholder = { Text("输入股票代码或名称") },
                        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                        trailingIcon = {
                            if (state.query.isNotEmpty()) {
                                IconButton(onClick = { vm.onQueryChange("") }) {
                                    Icon(Icons.Filled.Clear, contentDescription = "清空")
                                }
                            }
                        }
                    )
                }
            )
        }
    ) { padding ->
        StockTableContainer(Modifier.fillMaxSize().padding(padding), search = true) { columns, tableScroll ->
            Column(Modifier.fillMaxSize()) {
                SearchHeaderRow(columns, tableScroll)
                when {
                    state.loading -> {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                    }
                    state.query.isBlank() -> {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("输入关键字开始搜索", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    state.results.isEmpty() -> {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("未找到匹配的记录", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    else -> {
                        LazyColumn(Modifier.fillMaxSize()) {
                            itemsIndexed(state.results, key = { _, it -> it.id }) { index, record ->
                                SearchRow(
                                    columns = columns,
                                    tableScroll = tableScroll,
                                    index = index + 1,
                                    record = record,
                                    profitText = vm.displayProfit(record),
                                    onClick = {
                                        ViewerHolder.session = vm.buildViewerSession(index)
                                        nav.navigate(Routes.VIEWER)
                                    },
                                    onRemarksClick = {
                                        remarksEdit = RemarksEditTarget(record.stockCode, record.date, record.remarks)
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    remarksEdit?.let { edit ->
        RemarksEditDialog(
            target = edit,
            onConfirm = { value ->
                vm.setRemarks(edit.code, edit.date, value)
                remarksEdit = null
            },
            onDismiss = { remarksEdit = null },
        )
    }
}

@Composable
private fun SearchHeaderRow(columns: StockTableColumns, scroll: StockTableScrollState) {
    StockTableRow(
        columns = columns,
        scroll = scroll,
        modifier = Modifier.background(MaterialTheme.colorScheme.surfaceVariant),
        verticalPadding = 8.dp,
        frozen = {
            HeaderCellS("#", columns.index)
            HeaderCellS("日期", columns.codeOrDate)
            HeaderCellS("名称", columns.name)
        },
        scrolling = {
            HeaderCellS("买入", columns.buy, TextAlign.End)
            HeaderCellS("卖出", columns.sell, TextAlign.End)
            HeaderCellS("获利", columns.profit, TextAlign.End)
            HeaderCellS("备注", columns.remarks)
        },
    )
}

@Composable
private fun HeaderCellS(
    text: String,
    width: androidx.compose.ui.unit.Dp,
    align: TextAlign = TextAlign.Start,
) {
    Text(
        text = text,
        modifier = Modifier.width(width),
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        textAlign = align,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun SearchRow(
    columns: StockTableColumns,
    tableScroll: StockTableScrollState,
    index: Int,
    record: StockRecord,
    profitText: String,
    onClick: () -> Unit,
    onRemarksClick: () -> Unit,
) {
    val profitColor = when {
        profitText == "--" -> ProfitFlat
        profitText.startsWith("+") -> ProfitUp
        profitText.startsWith("-") -> ProfitDown
        else -> ProfitFlat
    }
    StockTableRow(
        columns = columns,
        scroll = tableScroll,
        modifier = Modifier.clickable(onClick = onClick),
        frozen = {
            Text("$index", Modifier.width(columns.index), fontSize = 13.sp)
            Text(record.date, Modifier.width(columns.codeOrDate), fontSize = 13.sp)
            Text(record.stockName ?: record.stockCode, Modifier.width(columns.name), fontSize = 13.sp)
        },
        scrolling = {
            Text(
                text = if (record.buyPrice > 0) fmtS(record.buyPrice) else "--",
                modifier = Modifier.width(columns.buy),
                fontSize = 13.sp,
                textAlign = TextAlign.End,
            )
            Text(
                text = if (record.sellPrice > 0) fmtS(record.sellPrice) else "--",
                modifier = Modifier.width(columns.sell),
                fontSize = 13.sp,
                textAlign = TextAlign.End,
            )
            Text(
                text = profitText,
                modifier = Modifier.width(columns.profit),
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.End,
                color = profitColor,
            )
            Text(
                text = record.remarks.ifEmpty { "点击填写" },
                modifier = Modifier.width(columns.remarks).clickable(onClick = onRemarksClick).padding(start = 4.dp),
                fontSize = 13.sp,
                color = if (record.remarks.isEmpty()) MaterialTheme.colorScheme.onSurfaceVariant
                    else MaterialTheme.colorScheme.onSurface,
            )
        },
    )
}

private fun fmtS(v: Double): String = String.format("%.2f", v)
