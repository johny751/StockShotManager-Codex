package com.stockshot.manager.net

import android.content.Context
import android.database.Cursor
import android.net.Uri
import com.stockshot.manager.data.SettingsStore
import com.stockshot.manager.util.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * 行情数据源接口（借鉴主工程 ControlMonitor 的“数据来源选择 + 连接测试”设计）。
 *
 * 三种实现：
 *  - ContentProviderSource：同设备的提供方 APP（content://com.floatstocktip.provider）
 *  - LanApiSource：局域网 REST API（http://<IP>:8080/stock/{code}）
 *  - SinaFallbackSource：新浪行情兜底，零配置即可用（默认）
 *
 * 统一返回 code -> QuoteFetcher.Quote，其中 open=买入价（开盘）、current=卖出价（当前/收盘）。
 */
interface StockQuoteSource {
    suspend fun fetchQuotes(context: Context, codes: List<String>): Map<String, QuoteFetcher.Quote>

    /** 便捷方法：取单只行情，测试连接时使用。 */
    suspend fun fetchQuote(context: Context, code: String): QuoteFetcher.Quote? =
        fetchQuotes(context, listOf(code))[code]
}

object StockQuoteSourceFactory {
    private const val TAG = "StockQuoteSource"

    /** 依据设置类型与局域网 IP 构建数据源。 */
    fun create(type: String, lanIp: String): StockQuoteSource = when (type) {
        SettingsStore.QUOTE_SOURCE_PROVIDER -> ContentProviderSource()
        SettingsStore.QUOTE_SOURCE_LAN -> LanApiSource(lanIp)
        else -> SinaFallbackSource()
    }

    internal fun readOptionalDouble(cursor: Cursor, column: String): Double? {
        val idx = cursor.getColumnIndex(column)
        if (idx < 0) return null
        return try {
            cursor.getDouble(idx)
        } catch (e: Exception) {
            AppLogger.w(TAG, "读取列失败 column=$column: ${e.message}")
            null
        }
    }
}

// ---------------------------------------------------------------------------
// A：ContentProvider（同设备，content://com.floatstocktip.provider）
// ---------------------------------------------------------------------------

private class ContentProviderSource : StockQuoteSource {
    override suspend fun fetchQuotes(
        context: Context,
        codes: List<String>,
    ): Map<String, QuoteFetcher.Quote> = withContext(Dispatchers.IO) {
        val result = HashMap<String, QuoteFetcher.Quote>()
        for (code in codes) {
            try {
                val uri = Uri.parse("content://com.floatstocktip.provider/stock/$code")
                context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                    if (c.moveToFirst()) {
                        val price = c.getDouble(c.getColumnIndexOrThrow("currentPrice"))
                        val prev = StockQuoteSourceFactory.readOptionalDouble(c, "prevClose") ?: 0.0
                        // 提供方若给出今日开盘价则优先用，否则用昨收兜底
                        val open = StockQuoteSourceFactory.readOptionalDouble(c, "todayOpen")
                            ?: StockQuoteSourceFactory.readOptionalDouble(c, "open")
                            ?: prev
                        val ni = c.getColumnIndex("name")
                        val name = if (ni >= 0) c.getString(ni).orEmpty() else ""
                        if (price > 0) {
                            result[code] = QuoteFetcher.Quote(
                                code = code,
                                name = name,
                                open = open.coerceAtLeast(0.0),
                                current = price,
                            )
                        } else {
                            AppLogger.e("StockQuoteSource", "ContentProvider 无效价格 code=$code price=$price")
                        }
                    }
                }
            } catch (e: Exception) {
                AppLogger.e("StockQuoteSource", "ContentProvider 查询失败 code=$code", e)
            }
        }
        result
    }
}

// ---------------------------------------------------------------------------
// B：局域网 REST API（http://<IP>:8080/stock/{code}）
// ---------------------------------------------------------------------------

private class LanApiSource(private val ip: String) : StockQuoteSource {
    override suspend fun fetchQuotes(
        context: Context,
        codes: List<String>,
    ): Map<String, QuoteFetcher.Quote> = withContext(Dispatchers.IO) {
        val result = HashMap<String, QuoteFetcher.Quote>()
        val host = ip.trim()
        if (host.isEmpty()) {
            AppLogger.e("StockQuoteSource", "LAN API IP 未配置")
            return@withContext result
        }
        for (code in codes) {
            try {
                val conn = (URL("http://$host:8080/stock/$code").openConnection() as HttpURLConnection)
                    .apply { connectTimeout = 5000; readTimeout = 5000 }
                val body = conn.inputStream.bufferedReader().readText().also { conn.disconnect() }
                val json = JSONObject(body)
                val price = json.optDouble("currentPrice", 0.0)
                val open = json.optDouble(
                    "todayOpen",
                    json.optDouble("open", json.optDouble("prevClose", 0.0)),
                )
                val name = json.optString("name", "")
                if (price > 0) {
                    result[code] = QuoteFetcher.Quote(
                        code = code,
                        name = name,
                        open = open.coerceAtLeast(0.0),
                        current = price,
                    )
                } else {
                    AppLogger.e("StockQuoteSource", "LAN API 无效价格 code=$code price=$price")
                }
            } catch (e: Exception) {
                AppLogger.e("StockQuoteSource", "LAN API 请求失败 code=$code ip=$host", e)
            }
        }
        result
    }
}

// ---------------------------------------------------------------------------
// C：新浪行情（兜底，零配置，复用 QuoteFetcher）
// ---------------------------------------------------------------------------

private class SinaFallbackSource : StockQuoteSource {
    override suspend fun fetchQuotes(
        context: Context,
        codes: List<String>,
    ): Map<String, QuoteFetcher.Quote> = QuoteFetcher.fetchQuotes(codes)
}
