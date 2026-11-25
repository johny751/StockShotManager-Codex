package com.stockshot.manager.net

import com.google.gson.JsonParser
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** Calendar-day range after the record date, limited to dates that have already occurred. */
data class HistoricalPriceWindow(val startInclusive: LocalDate, val endInclusive: LocalDate) {
    init {
        require(!endInclusive.isBefore(startInclusive))
    }

    companion object {
        fun after(recordDate: LocalDate, days: Int, today: LocalDate = LocalDate.now()): HistoricalPriceWindow? {
            require(days > 0) { "最大天数必须是正整数" }
            if (!recordDate.isBefore(today)) return null
            val start = recordDate.plusDays(1)
            val end = minOf(recordDate.plusDays(days.toLong()), today)
            return HistoricalPriceWindow(start, end)
        }
    }
}

sealed interface HistoricalHighResult {
    data class Success(val price: Double) : HistoricalHighResult
    data object NoData : HistoricalHighResult
    data class Failure(val reason: String, val cause: Throwable? = null) : HistoricalHighResult
}

fun interface HistoricalHighSource {
    suspend fun fetchHighest(code: String, window: HistoricalPriceWindow): HistoricalHighResult
}

/** Each stock has its own outcome, so one failed request cannot erase another stock's result. */
class HistoricalPriceFetcher(private val source: HistoricalHighSource = FallbackHistoricalHighSource()) {
    suspend fun fetchHighest(
        codes: List<String>,
        window: HistoricalPriceWindow?,
    ): Map<String, HistoricalHighResult> = coroutineScope {
        val distinctCodes = codes.distinct()
        if (window == null) return@coroutineScope distinctCodes.associateWith { HistoricalHighResult.NoData }
        if (java.time.temporal.ChronoUnit.DAYS.between(window.startInclusive, window.endInclusive) > 10_957) {
            return@coroutineScope distinctCodes.associateWith { HistoricalHighResult.Failure("历史区间最多支持 30 年，请缩小最大天数") }
        }
        val permits = Semaphore(4)
        distinctCodes.map { code ->
            async {
                code to permits.withPermit {
                    try {
                        source.fetchHighest(code, window)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        HistoricalHighResult.Failure(e.message ?: "历史行情获取失败")
                    }
                }
            }
        }.awaitAll().toMap()
    }
}

internal fun eastmoneySecurityId(code: String): String? {
    if (!code.matches(Regex("[0-9]{6}"))) return null
    val market = if (code.startsWith("6")) 1 else 0
    return "$market.$code"
}

/** Unadjusted daily highs. Explicit date bounds return the complete range without a bar-count limit. */
internal class EastmoneyHistorySource(private val client: HistoryHttpClient = UrlConnectionHistoryClient()) : HistoricalHighSource {
    override suspend fun fetchHighest(code: String, window: HistoricalPriceWindow): HistoricalHighResult =
        safelyFetchHistory("东方财富") {
            val securityId = eastmoneySecurityId(code)
                ?: return@safelyFetchHistory HistoricalHighResult.Failure("股票代码无效")
            val start = window.startInclusive.format(DateTimeFormatter.BASIC_ISO_DATE)
            val end = window.endInclusive.format(DateTimeFormatter.BASIC_ISO_DATE)
            client.request(HistoryHttpRequest(
                source = "Eastmoney", code = code, window = window,
                url = "https://push2his.eastmoney.com/api/qt/stock/kline/get" +
                    "?secid=$securityId&fields1=f1,f2,f3,f4,f5,f6" +
                    "&fields2=f51,f52,f53,f54,f55,f56&klt=101&fqt=0&beg=$start&end=$end",
                referer = "https://quote.eastmoney.com/",
            )) { body -> parseEastmoneyHighest(code, window, body) }
        }
}

/** Only a failed source triggers fallback; verified empty history remains a no-data outcome. */
internal class FallbackHistoricalHighSource(
    private val primary: HistoricalHighSource = EastmoneyHistorySource(),
    private val backup: HistoricalHighSource = RegionalBackupHistorySource(),
) : HistoricalHighSource {
    override suspend fun fetchHighest(code: String, window: HistoricalPriceWindow): HistoricalHighResult {
        val first = primary.fetchHighest(code, window)
        if (first !is HistoricalHighResult.Failure) return first
        return when (val second = backup.fetchHighest(code, window)) {
            is HistoricalHighResult.Failure -> HistoricalHighResult.Failure(
                "${first.reason}；备用${second.reason}", second.cause ?: first.cause,
            )
            else -> second
        }
    }
}

/** Invalid prices fail the stock rather than silently underestimating its maximum. */
internal fun parseEastmoneyHighest(
    code: String,
    window: HistoricalPriceWindow,
    body: String,
): HistoricalHighResult = try {
    val root = JsonParser.parseString(body).asJsonObject
    val rc = root.get("rc")?.asString?.toIntOrNull()
    if (rc != 0) {
        HistoricalHighResult.Failure("历史行情返回错误（${rc ?: "缺少状态码"}）")
    } else {
        val data = root.getAsJsonObject("data")
        require(data != null && data.get("code")?.asString == code) { "历史行情股票代码不匹配" }
        val expectedMarket = eastmoneySecurityId(code)?.substringBefore('.')?.toIntOrNull()
        require(expectedMarket != null && data.get("market")?.asString?.toIntOrNull() == expectedMarket) {
            "历史行情股票市场不匹配"
        }
        val lines = data.getAsJsonArray("klines") ?: error("历史行情缺少日 K 数据")
        var highest: Double? = null
        for (line in lines) {
            val fields = line.asString.split(',')
            require(fields.size >= 4) { "历史行情日 K 格式无效" }
            val date = LocalDate.parse(fields[0])
            if (date.isBefore(window.startInclusive) || date.isAfter(window.endInclusive)) continue
            val high = fields[3].toDoubleOrNull()
            require(high != null && high.isFinite() && high > 0.0) { "历史行情最高价无效" }
            highest = highest?.let { maxOf(it, high) } ?: high
        }
        highest?.let { HistoricalHighResult.Success(it) } ?: HistoricalHighResult.NoData
    }
} catch (e: Exception) {
    HistoricalHighResult.Failure(e.message ?: "历史行情解析失败", e)
}
