package com.stockshot.manager.ui.screen

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import com.stockshot.manager.automation.AutomationController
import com.stockshot.manager.data.SettingsStore
import com.stockshot.manager.overlay.FeatureTestOverlay
import com.stockshot.manager.util.PermissionUtils
import com.stockshot.manager.data.entity.StockRecord
import com.stockshot.manager.ui.Routes
import com.stockshot.manager.ui.SystemIntents
import com.stockshot.manager.ui.nav.ViewerHolder
import com.stockshot.manager.ui.theme.ProfitDown
import com.stockshot.manager.ui.theme.ProfitFlat
import com.stockshot.manager.ui.theme.ProfitUp
import com.stockshot.manager.ui.vm.MainViewModel
import com.stockshot.manager.util.ProfitCalc
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(nav: NavHostController, vm: MainViewModel = viewModel()) {
    val state by vm.ui.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHost = remember { SnackbarHostState() }
    val listState = rememberLazyListState()

    var menuOpen by remember { mutableStateOf(false) }
    var datePickerOpen by remember { mutableStateOf(false) }
    var priceEdit by remember { mutableStateOf<PriceEdit?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    var launchDate by remember { mutableStateOf<String?>(null) }
    var remarksEdit by remember { mutableStateOf<RemarksEditTarget?>(null) }
    var highestPriceRequest by remember { mutableStateOf<HighestPriceRequest?>(null) }
    var showDiagnostics by rememberSaveable { mutableStateOf(false) }

    // 返回主界面时刷新
    LaunchedEffect(Unit) { vm.refresh() }

    LaunchedEffect(state.snackbar) {
        state.snackbar?.let {
            snackbarHost.showSnackbar(it)
            vm.consumeSnackbar()
        }
    }

    val visible = vm.visibleRecords()

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHost) },
        topBar = {
            if (state.selectionMode) {
                TopAppBar(
                    title = { Text("已选 ${state.selectedIds.size} 项") },
                    navigationIcon = {
                        TextButton(onClick = { vm.exitSelection() }) { Text("取消") }
                    },
                    actions = {
                        IconButton(onClick = { confirmDelete = true }) {
                            Icon(Icons.Filled.Delete, contentDescription = "删除")
                        }
                    }
                )
            } else {
                TopAppBar(
                    title = {
                        TextButton(onClick = { datePickerOpen = true }) {
                            Text(state.date, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = { vm.toggleMarkedFilter() }) {
                            if (state.showMarkedOnly) {
                                Icon(Icons.Filled.Star, contentDescription = "只看标记", tint = Color(0xFFFFC107))
                            } else {
                                Icon(Icons.Outlined.StarBorder, contentDescription = "全部")
                            }
                        }
                    },
                    actions = {
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(Icons.Filled.MoreVert, contentDescription = "菜单")
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(text = { Text("搜索") }, onClick = {
                                menuOpen = false
                                nav.navigate(Routes.SEARCH)
                            })
                            DropdownMenuItem(enabled = !state.loading, text = { Text("更新买入价") }, onClick = {
                                menuOpen = false
                                vm.updateBuyFromQuotes()
                            })
                            DropdownMenuItem(enabled = !state.loading, text = { Text("更新卖出价") }, onClick = {
                                menuOpen = false
                                vm.updateSellFromQuotes()
                            })
                            DropdownMenuItem(enabled = !state.loading, text = { Text("更新最高价") }, onClick = {
                                menuOpen = false
                                highestPriceRequest = HighestPriceRequest(
                                    date = state.date,
                                    codes = state.records.map { it.stockCode }.distinct(),
                                )
                            })
                            DropdownMenuItem(text = { Text("获取当日列表（自动截图）") }, onClick = {
                                menuOpen = false
                                launchDate = state.date
                            })
                            DropdownMenuItem(
                                enabled = state.lastHighestPriceReport != null,
                                text = { Text("上次最高价结果") },
                                onClick = {
                                    menuOpen = false
                                    vm.showLastHighestPriceReport()
                                },
                            )
                            DropdownMenuItem(text = { Text("诊断日志") }, onClick = {
                                menuOpen = false
                                showDiagnostics = true
                            })
                            DropdownMenuItem(text = { Text("设置") }, onClick = {
                                menuOpen = false
                                nav.navigate(Routes.SETTINGS)
                            })
                            DropdownMenuItem(text = { Text("\u529f\u80fd\u6d4b\u8bd5") }, onClick = {
                                menuOpen = false
                                if (!PermissionUtils.hasOverlayPermission(context)) {
                                    Toast.makeText(context, "\u8bf7\u5148\u6388\u4e88\u60ac\u6d6e\u7a97\u6743\u9650", Toast.LENGTH_SHORT).show()
                                } else {
                                    FeatureTestOverlay.toggle(context)
                                }
                            })
                        }
                    }
                )
            }
        }
    ) { padding ->
        StockTableContainer(Modifier.fillMaxSize().padding(padding)) { columns, tableScroll ->
            Column(Modifier.fillMaxSize()) {
                if (state.loading) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text("正在更新价格，请稍候…", Modifier.padding(horizontal = 12.dp, vertical = 4.dp), fontSize = 12.sp)
                }
                HeaderRow(columns, tableScroll)
                if (visible.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("当日暂无截图数据", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else {
                    val orderedIds = visible.map { it.id }
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .fillMaxSize()
                            .pointerInput(orderedIds) {
                                var lastIndex: Int? = null
                                val edgeSize = 96.dp.toPx()
                                detectDragGesturesAfterLongPress(
                                    onDragStart = { position ->
                                        val index = listState.layoutInfo.visibleItemsInfo
                                            .firstOrNull {
                                                position.y.toInt() >= it.offset &&
                                                    position.y.toInt() < it.offset + it.size
                                            }
                                            ?.index
                                        if (index != null && index in orderedIds.indices) {
                                            vm.selectRange(orderedIds, orderedIds[index])
                                            lastIndex = index
                                        }
                                    },
                                    onDragEnd = { lastIndex = null },
                                    onDragCancel = { lastIndex = null },
                                    onDrag = { change, _ ->
                                        change.consume()
                                        val layoutInfo = listState.layoutInfo
                                        val y = change.position.y.toInt()
                                        val index = layoutInfo.visibleItemsInfo
                                            .firstOrNull { y >= it.offset && y < it.offset + it.size }
                                            ?.index
                                        val previous = lastIndex
                                        if (index != null && index in orderedIds.indices) {
                                            if (previous != null) {
                                                val from = minOf(previous, index)
                                                val to = maxOf(previous, index)
                                                vm.addToSelection(orderedIds.subList(from, to + 1))
                                            } else {
                                                vm.addToSelection(listOf(orderedIds[index]))
                                            }
                                            lastIndex = index
                                        }

                                        val viewportStart = layoutInfo.viewportStartOffset.toFloat()
                                        val viewportEnd = layoutInfo.viewportEndOffset.toFloat()
                                        val scrollDelta = when {
                                            change.position.y < viewportStart + edgeSize -> -24.dp.toPx()
                                            change.position.y > viewportEnd - edgeSize -> 24.dp.toPx()
                                            else -> 0f
                                        }
                                        if (scrollDelta != 0f) {
                                            scope.launch { listState.scrollBy(scrollDelta) }
                                        }
                                    },
                                )
                            },
                    ) {
                        items(visible, key = { it.id }) { record ->
                            val index = visible.indexOf(record) + 1
                            StockRow(
                                columns = columns,
                                tableScroll = tableScroll,
                                index = index,
                                record = record,
                                marked = record.stockCode in state.markedCodes,
                                selected = record.id in state.selectedIds,
                                selectionMode = state.selectionMode,
                                profitText = ProfitCalc.displayRate(record.buyPrice, record.sellPrice),
                                onClick = {
                                    if (state.selectionMode) {
                                        vm.toggleSelection(record.id)
                                    } else {
                                        scope.launch {
                                            ViewerHolder.session = vm.buildViewerSession(record.stockCode)
                                            nav.navigate(Routes.VIEWER)
                                        }
                                    }
                                },

                                onBuyClick = { priceEdit = PriceEdit(record.stockCode, true, record.buyPrice) },
                                onSellClick = { priceEdit = PriceEdit(record.stockCode, false, record.sellPrice) },
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

    if (showDiagnostics) {
        DiagnosticsLogDialog(onDismiss = { showDiagnostics = false })
    }

    if (!showDiagnostics && state.showHighestPriceReport && state.lastHighestPriceReport != null) {
        AlertDialog(
            onDismissRequest = vm::dismissHighestPriceReport,
            title = { Text("最高价更新结果") },
            text = {
                SelectionContainer {
                    Text(
                        state.lastHighestPriceReport.orEmpty(),
                        modifier = Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()),
                        fontSize = 14.sp,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = vm::dismissHighestPriceReport) { Text("关闭") }
            },
            dismissButton = {
                TextButton(onClick = {
                    vm.dismissHighestPriceReport()
                    showDiagnostics = true
                }) { Text("诊断日志") }
            },
        )
    }

    if (datePickerOpen) {
        DatePickerDialog(
            availableDates = state.availableDates,
            selectedDate = state.date,
            onSelect = { vm.selectDate(it); datePickerOpen = false },
            onDismiss = { datePickerOpen = false }
        )
    }

    priceEdit?.let { edit ->
        PriceEditDialog(
            title = if (edit.isBuy) "编辑买入价" else "编辑卖出价",
            initial = edit.current,
            onConfirm = { value ->
                if (edit.isBuy) vm.setBuyPrice(edit.code, value) else vm.setSellPrice(edit.code, value)
                priceEdit = null
            },
            onDismiss = { priceEdit = null }
        )
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

    highestPriceRequest?.let { request ->
        HighestPriceDialog(
            date = request.date,
            stockCount = request.codes.size,
            loading = state.loading,
            onConfirm = { days ->
                vm.updateHighestPrices(request.date, request.codes, days)
                highestPriceRequest = null
            },
            onDismiss = { highestPriceRequest = null },
        )
    }

    launchDate?.let { targetDate ->
        AutomationLaunchDialog(
            targetDate = targetDate,
            onDismiss = { launchDate = null },
            onConfirm = { d, m, b, ry ->
                launchDate = null
                if (!PermissionUtils.isAccessibilityEnabled(context)) {
                    Toast.makeText(context, "请开启本应用的无障碍服务", Toast.LENGTH_LONG).show()
                    SystemIntents.openAccessibilitySettings(context)
                    return@AutomationLaunchDialog
                }
                val err = AutomationController.launch(
                    context,
                    targetDate = targetDate,
                    autoDetect = d,
                    autoMark = m,
                    autoBuyPrice = b,
                    requireYellow = ry,
                )
                if (err != null) Toast.makeText(context, err, Toast.LENGTH_SHORT).show()
            },
        )
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("删除确认") },
            text = { Text("确定删除选中的 ${state.selectedIds.size} 条记录及其图片？") },
            confirmButton = {
                TextButton(onClick = { vm.deleteSelected(); confirmDelete = false }) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("取消") }
            }
        )
    }
}

private data class PriceEdit(val code: String, val isBuy: Boolean, val current: Double)
private data class HighestPriceRequest(val date: String, val codes: List<String>)

@Composable
private fun HeaderRow(columns: StockTableColumns, scroll: StockTableScrollState) {
    StockTableRow(
        columns = columns,
        scroll = scroll,
        modifier = Modifier.background(MaterialTheme.colorScheme.surfaceVariant),
        verticalPadding = 8.dp,
        frozen = {
            HeaderCell("#", columns.index)
            HeaderCell("代码", columns.codeOrDate)
            HeaderCell("名称", columns.name)
        },
        scrolling = {
            HeaderCell("买入", columns.buy, TextAlign.End)
            HeaderCell("卖出", columns.sell, TextAlign.End)
            HeaderCell("获利", columns.profit, TextAlign.End)
            HeaderCell("备注", columns.remarks)
        },
    )
}

@Composable
private fun HeaderCell(
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

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun StockRow(
    columns: StockTableColumns,
    tableScroll: StockTableScrollState,
    index: Int,
    record: StockRecord,
    marked: Boolean,
    selected: Boolean,
    selectionMode: Boolean,
    profitText: String,
    onClick: () -> Unit,
    onBuyClick: () -> Unit,
    onSellClick: () -> Unit,
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
        modifier = Modifier
            .background(
                if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f) else Color.Transparent
            )
            .clickable(onClick = onClick),
        frozen = {
            Row(Modifier.width(columns.index), verticalAlignment = Alignment.CenterVertically) {
                if (marked) {
                    Icon(Icons.Filled.Star, contentDescription = null, tint = Color(0xFFFFC107),
                        modifier = Modifier.width(14.dp).height(14.dp))
                    Spacer(Modifier.width(2.dp))
                }
                Text("$index", fontSize = 13.sp)
            }
            Text(record.stockCode, Modifier.width(columns.codeOrDate), fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Text(record.stockName ?: "--", Modifier.width(columns.name), fontSize = 13.sp)
        },
        scrolling = {
            Text(
                text = if (record.buyPrice > 0) fmt(record.buyPrice) else "--",
                modifier = Modifier.width(columns.buy).let { if (!selectionMode) it.combinedClickable(onClick = onBuyClick) else it },
                fontSize = 13.sp,
                textAlign = TextAlign.End,
            )
            Text(
                text = if (record.sellPrice > 0) fmt(record.sellPrice) else "--",
                modifier = Modifier.width(columns.sell).let { if (!selectionMode) it.combinedClickable(onClick = onSellClick) else it },
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
                modifier = Modifier.width(columns.remarks)
                    .let { if (!selectionMode) it.clickable(onClick = onRemarksClick) else it }
                    .padding(start = 4.dp),
                fontSize = 13.sp,
                color = if (record.remarks.isEmpty()) MaterialTheme.colorScheme.onSurfaceVariant
                    else MaterialTheme.colorScheme.onSurface,
            )
        },
    )
}

private fun fmt(v: Double): String = String.format("%.2f", v)


@Composable
private fun AutomationLaunchDialog(
    targetDate: String,
    onDismiss: () -> Unit,
    onConfirm: (Boolean, Boolean, Boolean, Boolean) -> Unit,
) {
    val store = com.stockshot.manager.StockShotApp.instance.settingsStore
    var detect by remember { mutableStateOf(true) }
    var mark by remember { mutableStateOf(true) }
    var buyPrice by remember { mutableStateOf(true) }
    var requireYellow by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        detect = store.getBool(SettingsStore.KEY_AUTO_DETECT_ENABLED, SettingsStore.DEFAULT_AUTO_DETECT_ENABLED)
        mark = store.getBool(SettingsStore.KEY_AUTO_MARK_ENABLED, SettingsStore.DEFAULT_AUTO_MARK_ENABLED)
        buyPrice = store.getBool(SettingsStore.KEY_AUTO_BUYPRICE_ENABLED, SettingsStore.DEFAULT_AUTO_BUYPRICE_ENABLED)
        requireYellow = store.getBool(SettingsStore.KEY_CAPITAL_REQUIRE_YELLOW, SettingsStore.DEFAULT_CAPITAL_REQUIRE_YELLOW)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("\u672c\u6b21\u91c7\u96c6\u9009\u9879") },
        text = {
            Column {
                Text(
                    "本次采集结果将添加到所选日期 $targetDate 页面。",
                    modifier = Modifier.padding(bottom = 8.dp),
                    fontWeight = FontWeight.Medium,
                )
                LaunchCheckRow("\u542f\u7528\u81ea\u52a8\u8bc6\u522b\uff08\u7ea2\u7eff\u70b9\u91cd\u5408\uff09", detect) { detect = it }
                LaunchCheckRow("\u547d\u4e2d\u540e\u6570\u636e\u5e93\u81ea\u52a8\u6807\u8bb0", mark) { mark = it }
                LaunchCheckRow("\u547d\u4e2d\u540e\u957f\u6309\u8bfb\u53d6\u4e70\u5165\u4ef7", buyPrice) { buyPrice = it }
                LaunchCheckRow("\u8981\u6c42\u7d2b\u3001\u9ec4\u4e24\u6761\u90fd\u5728\u7eff\u7ebf\u4e0a", requireYellow) { requireYellow = it }
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(detect, mark, buyPrice, requireYellow) }) { Text("\u5f00\u59cb") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("\u53d6\u6d88") } },
    )
}

@Composable
private fun LaunchCheckRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().clickable { onChange(!checked) }
    ) {
        Checkbox(checked = checked, onCheckedChange = onChange)
        Text(label)
    }
}
