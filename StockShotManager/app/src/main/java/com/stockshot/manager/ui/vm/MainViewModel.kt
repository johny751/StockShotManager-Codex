package com.stockshot.manager.ui.vm

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.stockshot.manager.StockShotApp
import com.stockshot.manager.data.StockRepository
import com.stockshot.manager.data.SettingsStore
import com.stockshot.manager.data.entity.StockRecord
import com.stockshot.manager.net.HistoricalHighResult
import com.stockshot.manager.net.HistoricalPriceFetcher
import com.stockshot.manager.net.HistoricalPriceWindow
import com.stockshot.manager.net.StockQuoteSourceFactory
import com.stockshot.manager.util.AppLogger
import com.stockshot.manager.util.ProfitCalc
import com.stockshot.manager.util.StorageUtils
import java.io.File
import java.time.LocalDate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 主界面状态：当日股票列表（每只取最新一条）、日期、标记过滤、多选删除。
 */
class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val repo: StockRepository = (app as StockShotApp).repository
    private val settings: SettingsStore = (app as StockShotApp).settingsStore

    data class UiState(
        val date: String = StorageUtils.today(),
        val records: List<StockRecord> = emptyList(),
        val markedCodes: Set<String> = emptySet(),
        val showMarkedOnly: Boolean = false,
        val availableDates: List<String> = emptyList(),
        val selectionMode: Boolean = false,
        val selectedIds: Set<Long> = emptySet(),
        val snackbar: String? = null,
        val loading: Boolean = false,
        val lastHighestPriceReport: String? = null,
        val showHighestPriceReport: Boolean = false,
    )

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()
    private var selectionAnchorId: Long? = null
    private var dateJob: Job? = null
    private val historicalPrices = HistoricalPriceFetcher()

    private data class DateSnapshot(
        val records: List<StockRecord>,
        val markedCodes: Set<String>,
        val availableDates: List<String>,
    )

    init {
        refresh()
    }

    /** Observe the selected date continuously, including screenshots saved while this screen stays open. */
    fun refresh() {
        val date = _ui.value.date
        dateJob?.cancel()
        dateJob = viewModelScope.launch {
            combine(
                repo.observeLatestByDate(date),
                repo.observeMarkedCodes(date),
                repo.observeAvailableDates(),
            ) { records, marked, dates ->
                DateSnapshot(records, marked.toSet(), dates)
            }.collect { snapshot ->
                _ui.update { state ->
                    if (state.date != date) state
                    else {
                        val selected = state.selectedIds.intersect(snapshot.records.map { it.id }.toSet())
                        state.copy(
                            records = snapshot.records,
                            markedCodes = snapshot.markedCodes,
                            availableDates = (snapshot.availableDates + StorageUtils.today() + date)
                                .distinct().sortedDescending(),
                            selectedIds = selected,
                            selectionMode = state.selectionMode && selected.isNotEmpty(),
                        )
                    }
                }
            }
        }
    }

    fun selectDate(date: String) {
        selectionAnchorId = null
        _ui.value = _ui.value.copy(
            date = date,
            records = emptyList(),
            markedCodes = emptySet(),
            selectionMode = false,
            selectedIds = emptySet(),
        )
        refresh()
    }

    fun toggleMarkedFilter() {
        _ui.value = _ui.value.copy(showMarkedOnly = !_ui.value.showMarkedOnly)
    }

    /** 应用当前过滤后的可见列表。 */
    fun visibleRecords(): List<StockRecord> {
        val s = _ui.value
        return if (s.showMarkedOnly) s.records.filter { it.stockCode in s.markedCodes }
        else s.records
    }

    fun toggleMark(code: String) {
        val date = _ui.value.date
        val now = code in _ui.value.markedCodes
        viewModelScope.launch {
            repo.setMarked(code, date, !now)
        }
    }

    // ---- 手动编辑价格 ----
    fun setBuyPrice(code: String, price: Double) {
        val date = _ui.value.date
        viewModelScope.launch {
            repo.updateBuyPrice(code, date, price)
        }
    }

    fun setSellPrice(code: String, price: Double) {
        val date = _ui.value.date
        viewModelScope.launch {
            repo.updateSellPrice(code, date, price)
        }
    }

    fun setRemarks(code: String, date: String, text: String) {
        viewModelScope.launch {
            try {
                repo.updateRemarks(code, date, text)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.e("MainViewModel", "保存备注失败 code=$code date=$date", e)
                _ui.update { it.copy(snackbar = "备注保存失败，请重试") }
            }
        }
    }

    // ---- 批量从行情源更新买入/卖出价 ----
    fun updateBuyFromQuotes() = updateFromQuotes(buy = true)
    fun updateSellFromQuotes() = updateFromQuotes(buy = false)

    private fun updateFromQuotes(buy: Boolean) {
        if (_ui.value.loading) return
        val date = _ui.value.date
        val codes = _ui.value.records.map { it.stockCode }.distinct()
        if (codes.isEmpty()) {
            _ui.value = _ui.value.copy(snackbar = "当前列表为空")
            return
        }
        _ui.value = _ui.value.copy(loading = true)
        viewModelScope.launch {
            try {
                val type = settings.get(
                    SettingsStore.KEY_QUOTE_SOURCE_TYPE,
                    SettingsStore.DEFAULT_QUOTE_SOURCE_TYPE,
                )
                val ip = settings.get(SettingsStore.KEY_LAN_API_IP, "")
                val source = StockQuoteSourceFactory.create(type, ip)
                val quotes = source.fetchQuotes(getApplication(), codes)
                if (quotes.isEmpty()) {
                    _ui.update { it.copy(snackbar = "行情获取失败，请检查网络") }
                    return@launch
                }
                for (code in codes) {
                    val q = quotes[code] ?: continue
                    if (buy) repo.updateBuyPrice(code, date, q.open)
                    else repo.updateSellPrice(code, date, q.current)
                    if (q.name.isNotBlank()) repo.updateName(code, q.name)
                }
                _ui.update { it.copy(snackbar = if (buy) "买入价已更新" else "卖出价已更新") }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.e("MainViewModel", "更新行情失败 date=$date", e)
                _ui.update { it.copy(snackbar = "行情更新失败，请重试") }
            } finally {
                _ui.update { it.copy(loading = false) }
            }
        }
    }

    /** The dialog supplies a snapshot so date switches and filters cannot retarget this update. */
    fun updateHighestPrices(date: String, codes: List<String>, days: Int) {
        if (_ui.value.loading) return
        val targetCodes = codes.distinct()
        if (targetCodes.isEmpty()) {
            _ui.update { it.copy(snackbar = "当前列表为空") }
            return
        }
        val (window, partialWindow) = try {
            val recordDate = LocalDate.parse(date)
            val today = LocalDate.now()
            HistoricalPriceWindow.after(recordDate, days, today) to
                recordDate.plusDays(days.toLong()).isAfter(today)
        } catch (_: Exception) {
            _ui.update { it.copy(snackbar = "日期无效或最大天数不是正整数") }
            return
        }
        _ui.update { it.copy(loading = true, showHighestPriceReport = false) }
        AppLogger.i("HighestPrice", "开始更新 date=$date days=$days stocks=${targetCodes.size} window=$window")
        viewModelScope.launch {
            try {
                val results = historicalPrices.fetchHighest(targetCodes, window)
                var updated = 0
                var noData = 0
                var failed = 0
                val failureDetails = mutableListOf<String>()
                fun rememberFailure(code: String, reason: String) {
                    if (failureDetails.size < 10) failureDetails += "$code：${reason.take(500)}"
                }
                for ((code, result) in results) {
                    when (result) {
                        is HistoricalHighResult.Success -> {
                            try {
                                repo.updateSellPrice(code, date, result.price)
                                updated++
                                AppLogger.i("HighestPrice", "卖出价已保存 code=$code date=$date price=${result.price}")
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                failed++
                                rememberFailure(code, "保存价格失败：${e.javaClass.simpleName}: ${e.message.orEmpty()}")
                                AppLogger.e("MainViewModel", "最高价保存失败 code=$code date=$date", e)
                            }
                        }
                        HistoricalHighResult.NoData -> {
                            noData++
                            AppLogger.i("HighestPrice", "区间无行情 code=$code date=$date")
                        }
                        is HistoricalHighResult.Failure -> {
                            failed++
                            rememberFailure(code, result.reason)
                            AppLogger.w("MainViewModel", "最高价获取失败 code=$code date=$date: ${result.reason}")
                        }
                    }
                }
                val summary = "$date 最高价：已更新 $updated 只，无行情 $noData 只，失败 $failed 只"
                AppLogger.i("HighestPrice", "更新结束 $summary")
                val report = buildString {
                    appendLine(summary)
                    appendLine("最大天数：$days（自然日，不含记录当天）")
                    appendLine(if (window == null) "记录日期之后暂无可查询日期。" else
                        "实际查询区间：${window.startInclusive} 至 ${window.endInclusive}")
                    if (partialWindow) appendLine("区间尚未满 $days 天，已使用已有行情。")
                    if (failureDetails.isNotEmpty()) {
                        appendLine()
                        appendLine("失败原因：")
                        failureDetails.forEach { appendLine(it) }
                        if (failed > failureDetails.size) appendLine("此处仅列出前 ${failureDetails.size} 只失败股票。")
                        appendLine()
                        append("可打开“诊断日志”，开启记录后重试，再导出或分享日志。")
                    }
                }.trim()
                _ui.update { it.copy(lastHighestPriceReport = report, showHighestPriceReport = true) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.e("MainViewModel", "最高价更新失败 date=$date", e)
                _ui.update { it.copy(
                    lastHighestPriceReport = "$date 最高价更新失败：${e.javaClass.simpleName}: ${e.message.orEmpty().take(500)}\n\n请开启“诊断日志”后重试，再导出或分享日志。",
                    showHighestPriceReport = true,
                ) }
            } finally {
                _ui.update { it.copy(loading = false) }
            }
        }
    }

    fun dismissHighestPriceReport() {
        _ui.update { it.copy(showHighestPriceReport = false) }
    }

    fun showLastHighestPriceReport() {
        _ui.update { it.copy(showHighestPriceReport = it.lastHighestPriceReport != null) }
    }

    // ---- 多选删除 ----
    fun selectRange(orderedIds: List<Long>, targetId: Long) {
        if (targetId !in orderedIds) return
        val state = _ui.value
        if (!state.selectionMode || state.selectedIds.isEmpty()) {
            selectionAnchorId = targetId
            _ui.value = state.copy(selectionMode = true, selectedIds = setOf(targetId))
            return
        }

        val anchorId = selectionAnchorId
            ?.takeIf { it in orderedIds }
            ?: orderedIds.firstOrNull { it in state.selectedIds }
            ?: targetId
        selectionAnchorId = anchorId
        val anchorIndex = orderedIds.indexOf(anchorId)
        val targetIndex = orderedIds.indexOf(targetId)
        val range = orderedIds.subList(
            minOf(anchorIndex, targetIndex),
            maxOf(anchorIndex, targetIndex) + 1,
        )
        _ui.value = state.copy(
            selectionMode = true,
            selectedIds = state.selectedIds + range,
        )
    }

    fun addToSelection(ids: Collection<Long>) {
        if (ids.isEmpty()) return
        val state = _ui.value
        if (selectionAnchorId == null) selectionAnchorId = ids.first()
        _ui.value = state.copy(
            selectionMode = true,
            selectedIds = state.selectedIds + ids,
        )
    }

    fun toggleSelection(id: Long) {
        val cur = _ui.value.selectedIds
        val next = if (id in cur) cur - id else cur + id
        _ui.value = _ui.value.copy(
            selectedIds = next,
            selectionMode = next.isNotEmpty()
        )
        if (next.isEmpty()) selectionAnchorId = null
        else if (selectionAnchorId !in next) selectionAnchorId = next.first()
    }

    fun exitSelection() {
        selectionAnchorId = null
        _ui.value = _ui.value.copy(selectionMode = false, selectedIds = emptySet())
    }

    /** 删除选中记录及其图片文件（同代码同日期的全部截图一并删除）。 */
    fun deleteSelected() {
        val ids = _ui.value.selectedIds.toList()
        if (ids.isEmpty()) return
        val date = _ui.value.date
        val selectedRecords = _ui.value.records.filter { it.id in ids }
        viewModelScope.launch {
            for (rec in selectedRecords) {
                // 删除该(code,date)下的全部截图记录与文件
                val all = repo.getAllByDate(date).filter { it.stockCode == rec.stockCode }
                val fileIds = all.map { it.id }
                repo.deleteByIds(fileIds)
                for (r in all) runCatching { File(r.imagePath).delete() }
            }
            _ui.update { state ->
                if (state.date == date) state.copy(
                    selectionMode = false,
                    selectedIds = emptySet(),
                    snackbar = "已删除 ${selectedRecords.size} 条记录",
                ) else state.copy(snackbar = "已删除 ${selectedRecords.size} 条记录")
            }
            if (_ui.value.date == date) selectionAnchorId = null
        }
    }

    fun consumeSnackbar() {
        if (_ui.value.snackbar != null) _ui.value = _ui.value.copy(snackbar = null)
    }

    fun displayProfit(r: StockRecord): String = ProfitCalc.displayRate(r.buyPrice, r.sellPrice)

    /** 构建同日期查看器会话（按代码升序，即列表顺序）。 */
    suspend fun buildViewerSession(startCode: String): ViewerSession {
        val list = visibleRecords()
        val index = list.indexOfFirst { it.stockCode == startCode }.coerceAtLeast(0)
        return ViewerSession(records = list, startIndex = index, fromSearch = false)
    }
}
