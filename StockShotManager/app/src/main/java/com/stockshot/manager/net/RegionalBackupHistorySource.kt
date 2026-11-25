package com.stockshot.manager.net

import com.google.gson.JsonParser
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.ChronoUnit

internal class RegionalBackupHistorySource(client: HistoryHttpClient = UrlConnectionHistoryClient()) : HistoricalHighSource {
    private val tencent = TencentHistorySource(client)
    private val sina = SinaBeijingHistorySource(client)

    override suspend fun fetchHighest(code: String, window: HistoricalPriceWindow): HistoricalHighResult = when {
        tencentHistorySymbol(code) != null -> tencent.fetchHighest(code, window)
        beijingHistorySymbol(code) != null -> sina.fetchHighest(code, window)
        else -> HistoricalHighResult.Failure("行情源不支持此股票代码")
    }
}

internal fun tencentHistorySymbol(code: String): String? {
    if (!code.matches(Regex("[0-9]{6}"))) return null
    return when (code.first()) {
        '6' -> "sh$code"
        '0', '3' -> "sz$code"
        else -> null
    }
}

internal fun beijingHistorySymbol(code: String): String? =
    code.takeIf { it.matches(Regex("(?:[48][0-9]{5}|92[0-9]{4})")) }?.let { "bj$it" }

/** At most 365 calendar days per request is below Tencent's 640-bar cap, including leap years. */
internal fun historicalWindowChunks(window: HistoricalPriceWindow): List<HistoricalPriceWindow> {
    require(ChronoUnit.DAYS.between(window.startInclusive, window.endInclusive) <= 10_957) {
        "历史区间最多支持 30 年，请缩小最大天数"
    }
    val chunks = mutableListOf<HistoricalPriceWindow>()
    var start = window.startInclusive
    while (!start.isAfter(window.endInclusive)) {
        val remaining = ChronoUnit.DAYS.between(start, window.endInclusive)
        val end = start.plusDays(minOf(364, remaining))
        chunks += HistoricalPriceWindow(start, end)
        if (end == window.endInclusive) break
        start = end.plusDays(1)
    }
    return chunks
}

internal class TencentHistorySource(private val client: HistoryHttpClient = UrlConnectionHistoryClient()) : HistoricalHighSource {
    override suspend fun fetchHighest(code: String, window: HistoricalPriceWindow): HistoricalHighResult =
        safelyFetchHistory("腾讯") {
            val symbol = tencentHistorySymbol(code)
                ?: return@safelyFetchHistory HistoricalHighResult.Failure("仅支持沪深股票历史行情")
            var highest: Double? = null
            for (chunk in historicalWindowChunks(window)) {
                val result = client.request(HistoryHttpRequest(
                    source = "Tencent", code = code, window = chunk,
                    // Empty final adjustment parameter requests raw day, never qfqday/hfqday.
                    url = "https://web.ifzq.gtimg.cn/appstock/app/fqkline/get?param=" +
                        "$symbol,day,${chunk.startInclusive},${chunk.endInclusive},640,",
                    referer = "https://gu.qq.com/",
                )) { body -> parseTencentHighest(code, chunk, body) }
                when (result) {
                    is HistoricalHighResult.Success -> highest = highest?.let { maxOf(it, result.price) } ?: result.price
                    HistoricalHighResult.NoData -> Unit
                    is HistoricalHighResult.Failure -> return@safelyFetchHistory result
                }
            }
            highest?.let { HistoricalHighResult.Success(it) } ?: HistoricalHighResult.NoData
        }
}

internal fun parseTencentHighest(code: String, window: HistoricalPriceWindow, body: String): HistoricalHighResult = try {
    val symbol = tencentHistorySymbol(code) ?: error("股票代码不受腾讯历史行情支持")
    val root = JsonParser.parseString(body).asJsonObject
    require(root.get("code")?.asString?.toIntOrNull() == 0) { "历史行情返回错误：${root.get("code")}" }
    val data = root.getAsJsonObject("data")?.getAsJsonObject(symbol) ?: error("历史行情股票代码或市场不匹配")
    data.getAsJsonObject("qt")?.getAsJsonArray(symbol)?.let { quote ->
        require(quote.size() > 2 && quote[2].asString == code) { "历史行情报价代码不匹配" }
    }
    val lines = data.getAsJsonArray("day") ?: error("缺少不复权日 K 数据，拒绝使用复权价格")
    require(lines.size() < 640) { "日 K 达到条数上限，无法确认区间完整" }
    var previousDate: LocalDate? = null
    var highest: Double? = null
    for (line in lines) {
        val fields = line.asJsonArray
        require(fields.size() >= 6) { "历史行情日 K 格式无效" }
        val date = LocalDate.parse(fields[0].asString)
        require(previousDate == null || date.isAfter(previousDate)) { "日 K 日期重复或顺序无效" }
        previousDate = date
        if (date.isBefore(window.startInclusive) || date.isAfter(window.endInclusive)) continue
        val high = fields[3].asString.toDoubleOrNull()
        require(high != null && high.isFinite() && high > 0.0) { "历史行情最高价无效：$date" }
        highest = highest?.let { maxOf(it, high) } ?: high
    }
    highest?.let { HistoricalHighResult.Success(it) } ?: HistoricalHighResult.NoData
} catch (e: Exception) {
    HistoricalHighResult.Failure(e.message ?: "历史行情解析失败", e)
}

internal data class DailyHistoryHigh(val date: LocalDate, val high: Double)

/**
 * Sina returns at most 1023 recent, unadjusted bars without an identity field. Identity is bound
 * to the strictly constructed HTTPS symbol request, not fabricated from the response. Refuse
 * insufficient history and unverified suspensions instead of returning a partial maximum.
 */
internal class SinaBeijingHistorySource(private val client: HistoryHttpClient = UrlConnectionHistoryClient()) : HistoricalHighSource {
    override suspend fun fetchHighest(code: String, window: HistoricalPriceWindow): HistoricalHighResult =
        safelyFetchHistory("新浪北市") {
            val symbol = beijingHistorySymbol(code)
                ?: return@safelyFetchHistory HistoricalHighResult.Failure("股票代码不是受支持的北交所代码")
            val stock = client.request(request(symbol, code, window)) { body ->
                parseSinaDailyHighs(body).also {
                    require(it.isNotEmpty()) { "未返回股票日 K，无法验证区间覆盖" }
                    require(!it.first().date.isAfter(window.startInclusive)) {
                        "最多 1023 条日 K 仅追溯至 ${it.first().date}，未覆盖区间起点 ${window.startInclusive}"
                    }
                }
            }
            client.request(request("sh000001", "sh000001(交易日历)", window)) { body ->
                sinaHighestWithCoverage(window, stock, parseSinaDailyHighs(body))
            }
        }

    private fun request(symbol: String, code: String, window: HistoricalPriceWindow) = HistoryHttpRequest(
        source = "Sina", code = code, window = window,
        url = "https://quotes.sina.cn/cn/api/json_v2.php/CN_MarketDataService.getKLineData" +
            "?symbol=$symbol&scale=240&ma=no&datalen=1023",
        referer = "https://finance.sina.com.cn/",
    )
}

internal fun parseSinaDailyHighs(body: String): List<DailyHistoryHigh> {
    val lines = JsonParser.parseString(body).asJsonArray
    require(lines.size() <= 1023) { "日 K 超出请求条数上限" }
    var previousDate: LocalDate? = null
    return lines.map { line ->
        val fields = line.asJsonObject
        val date = LocalDate.parse(fields.get("day")?.asString ?: error("日 K 缺少日期"))
        require(previousDate == null || date.isAfter(previousDate)) { "日 K 日期重复或顺序无效" }
        previousDate = date
        val high = fields.get("high")?.asString?.toDoubleOrNull()
        require(high != null && high.isFinite() && high > 0.0) { "历史行情最高价无效：$date" }
        DailyHistoryHigh(date, high)
    }
}

internal fun sinaHighestWithCoverage(
    window: HistoricalPriceWindow,
    stock: List<DailyHistoryHigh>,
    calendar: List<DailyHistoryHigh>,
): HistoricalHighResult = try {
    require(stock.isNotEmpty() && !stock.first().date.isAfter(window.startInclusive)) { "股票日 K 未覆盖区间起点" }
    require(calendar.isNotEmpty() && !calendar.first().date.isAfter(window.startInclusive)) { "交易日历未覆盖区间起点" }
    var requiredCalendarEnd = window.endInclusive
    while (requiredCalendarEnd.dayOfWeek == DayOfWeek.SATURDAY || requiredCalendarEnd.dayOfWeek == DayOfWeek.SUNDAY) {
        requiredCalendarEnd = requiredCalendarEnd.minusDays(1)
    }
    require(!calendar.last().date.isBefore(requiredCalendarEnd)) {
        "交易日历仅更新至 ${calendar.last().date}，未覆盖请求截止日 ${window.endInclusive}（可能休市、当日行情未发布或数据延迟），请稍后重试"
    }
    val lastTradingDay = calendar.lastOrNull { !it.date.isAfter(window.endInclusive) }?.date
        ?: error("交易日历未覆盖区间末端")
    val inRange = stock.filter { !it.date.isBefore(window.startInclusive) && !it.date.isAfter(window.endInclusive) }
    val tradingDates = calendar.filter { !it.date.isBefore(window.startInclusive) && !it.date.isAfter(window.endInclusive) }
        .mapTo(mutableSetOf()) { it.date }
    val unexpectedDay = inRange.firstOrNull { it.date !in tradingDates }?.date
    require(unexpectedDay == null) { "股票日 K 日期 $unexpectedDay 与交易日历不符，保留原价" }
    if (lastTradingDay.isBefore(window.startInclusive)) {
        HistoricalHighResult.NoData
    } else {
        require(inRange.isNotEmpty() && !inRange.last().date.isBefore(lastTradingDay)) {
            "股票日 K 未覆盖最后交易日 $lastTradingDay（可能停牌或数据不足），保留原价"
        }
        val stockDates = inRange.mapTo(mutableSetOf()) { it.date }
        val missingDay = tradingDates.firstOrNull { it !in stockDates }
        require(missingDay == null) {
            "股票日 K 缺少交易日 $missingDay（可能停牌或数据不足），无法确认区间最高价，保留原价"
        }
        HistoricalHighResult.Success(inRange.maxOf { it.high })
    }
} catch (e: Exception) {
    HistoricalHighResult.Failure(e.message ?: "历史行情覆盖检查失败", e)
}
