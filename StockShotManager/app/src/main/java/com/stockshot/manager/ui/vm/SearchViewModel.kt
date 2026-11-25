package com.stockshot.manager.ui.vm

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.stockshot.manager.StockShotApp
import com.stockshot.manager.data.StockRepository
import com.stockshot.manager.data.entity.StockRecord
import com.stockshot.manager.util.ProfitCalc
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Search screen state: fuzzy match on code or name, results sorted by date descending.
 */
class SearchViewModel(app: Application) : AndroidViewModel(app) {

    private val repo: StockRepository = (app as StockShotApp).repository

    data class UiState(
        val query: String = "",
        val results: List<StockRecord> = emptyList(),
        val loading: Boolean = false,
        val snackbar: String? = null,
    )

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    private var searchJob: Job? = null

    fun onQueryChange(q: String) {
        _ui.value = _ui.value.copy(query = q)
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            delay(250L) // debounce
            if (q.isBlank()) {
                _ui.value = _ui.value.copy(results = emptyList(), loading = false)
                return@launch
            }
            _ui.value = _ui.value.copy(loading = true)
            val results = repo.search(q.trim())
            if (_ui.value.query == q) {
                _ui.value = _ui.value.copy(results = results, loading = false)
            }
        }
    }

    fun setRemarks(code: String, date: String, text: String) {
        viewModelScope.launch {
            try {
                repo.updateRemarks(code, date, text)
                val current = _ui.value
                _ui.value = current.copy(results = current.results.map { record ->
                    if (record.stockCode == code && record.date == date) record.copy(remarks = text)
                    else record
                })
                // Cancel an older in-flight search and refresh whichever query is now visible.
                onQueryChange(_ui.value.query)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _ui.value = _ui.value.copy(snackbar = "备注保存失败，请重试")
            }
        }
    }

    fun consumeSnackbar() {
        _ui.value = _ui.value.copy(snackbar = null)
    }

    fun displayProfit(r: StockRecord): String = ProfitCalc.displayRate(r.buyPrice, r.sellPrice)

    /** Build a viewer session from search results (kept in list order = date desc). */
    fun buildViewerSession(startIndex: Int): ViewerSession =
        ViewerSession(
            records = _ui.value.results,
            startIndex = startIndex.coerceAtLeast(0),
            fromSearch = true,
        )
}
