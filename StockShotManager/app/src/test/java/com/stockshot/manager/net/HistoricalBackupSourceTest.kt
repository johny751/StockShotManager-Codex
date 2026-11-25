package com.stockshot.manager.net

import java.io.EOFException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.time.LocalDate
import javax.net.ssl.SSLHandshakeException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class HistoricalBackupSourceTest {
    private val window = HistoricalPriceWindow(LocalDate.parse("2026-09-17"), LocalDate.parse("2026-09-27"))

    @Test
    fun connectionClosedByPrimaryFallsBackToUnadjustedTencentHigh() = runBlocking {
        val client = StubClient { request ->
            if (request.source == "Eastmoney") throw SocketException("Unexpected end of file from server")
            tencentResponse("sh600519", "2026-09-17,1257.98,1266.98,1267.60,1254,17554", "2026-09-24,1250.01,1237,1256.13,1231.05,31239")
        }
        val result = FallbackHistoricalHighSource(EastmoneyHistorySource(client), RegionalBackupHistorySource(client))
            .fetchHighest("600519", window)
        assertEquals(HistoricalHighResult.Success(1267.60), result)
        assertEquals(listOf("Eastmoney", "Tencent"), client.requests.map { it.source })
        assertTrue(client.requests.last().url.endsWith(",640,"))
        assertFalse(client.requests.last().url.contains("qfq"))
    }

    @Test
    fun verifiedNoDataAndSuccessDoNotQueryAnotherSource() = runBlocking {
        for (primaryResult in listOf(HistoricalHighResult.NoData, HistoricalHighResult.Success(12.0))) {
            val source = FallbackHistoricalHighSource(
                HistoricalHighSource { _, _ -> primaryResult },
                HistoricalHighSource { _, _ -> fail("No fallback is needed"); HistoricalHighResult.NoData },
            )
            assertEquals(primaryResult, source.fetchHighest("600519", window))
        }
    }

    @Test
    fun bothFailedSourcesKeepReadableReasonsAndCause() = runBlocking {
        val client = StubClient { request ->
            if (request.source == "Eastmoney") throw EOFException()
            throw UnknownHostException("web.ifzq.gtimg.cn")
        }
        val result = FallbackHistoricalHighSource(EastmoneyHistorySource(client), RegionalBackupHistorySource(client))
            .fetchHighest("600519", window) as HistoricalHighResult.Failure
        assertTrue(result.reason.contains("东方财富：行情服务器提前关闭连接"))
        assertTrue(result.reason.contains("腾讯：域名解析失败"))
        assertTrue(result.cause is UnknownHostException)
    }

    @Test
    fun cancellationNeverTriggersFallback() = runBlocking {
        val source = FallbackHistoricalHighSource(
            HistoricalHighSource { _, _ -> throw CancellationException("stop") },
            HistoricalHighSource { _, _ -> fail("Cancellation must not try another source"); HistoricalHighResult.NoData },
        )
        try {
            source.fetchHighest("600519", window)
            fail("Cancellation must propagate")
        } catch (expected: CancellationException) {
            assertEquals("stop", expected.message)
        }
    }

    @Test
    fun tencentRejectsAdjustedOnlyWrongIdentityAndInvalidHigh() {
        val raw = tencentResponse("sz000001", "2026-09-17,11.680,11.610,11.740,11.570,691925")
        assertEquals(HistoricalHighResult.Success(11.740), parseTencentHighest("000001", window, raw))
        for (bad in listOf(
            raw.replace("\"day\"", "\"qfqday\""),
            raw.replace("\"day\"", "\"hfqday\""),
            raw.replace("sz000001", "sh000001"),
            raw.replace("11.740", "NaN"),
            raw.replace("11.740", "0"),
            raw.replace("2026-09-17", "invalid-date"),
            raw.replace("\"code\":0", "\"code\":0.1"),
        )) assertTrue(bad, parseTencentHighest("000001", window, bad) is HistoricalHighResult.Failure)
        assertEquals(HistoricalHighResult.NoData, parseTencentHighest("000001", window, tencentResponse("sz000001")))
    }

    @Test
    fun tencentUsesBothBoundariesAndExcludesOutsideHighs() {
        val body = tencentResponse("sh600519",
            "2026-09-16,10,10,999,9,100", "2026-09-17,10,10,20,9,100",
            "2026-09-27,10,10,21,9,100", "2026-09-28,10,10,999,9,100")
        assertEquals(HistoricalHighResult.Success(21.0), parseTencentHighest("600519", window, body))
    }

    @Test
    fun longTencentWindowsAreContiguousAndNeverHitTheBarLimit() = runBlocking {
        val longWindow = HistoricalPriceWindow(LocalDate.parse("2024-01-01"), LocalDate.parse("2026-09-27"))
        val client = StubClient { request ->
            tencentResponse("sh600519", "${request.window.startInclusive},10,10,${if (request.window.startInclusive.year == 2024) 99 else 20},9,100")
        }
        val result = TencentHistorySource(client).fetchHighest("600519", longWindow)
        assertEquals(HistoricalHighResult.Success(99.0), result)
        val chunks = client.requests.map { it.window }
        assertEquals(3, chunks.size)
        assertEquals(longWindow.startInclusive, chunks.first().startInclusive)
        assertEquals(longWindow.endInclusive, chunks.last().endInclusive)
        chunks.zipWithNext().forEach { (left, right) -> assertEquals(left.endInclusive.plusDays(1), right.startInclusive) }
        chunks.forEach { assertTrue(it.endInclusive <= it.startInclusive.plusDays(364)) }
    }

    @Test
    fun failureInALaterChunkNeverReturnsAPartialMaximum() = runBlocking {
        val longWindow = HistoricalPriceWindow(LocalDate.parse("2024-01-01"), LocalDate.parse("2026-09-27"))
        val client = StubClient { request ->
            if (request.window.startInclusive == longWindow.startInclusive) {
                tencentResponse("sh600519", "2024-01-01,10,10,99,9,100")
            } else throw SocketTimeoutException()
        }
        assertTrue(TencentHistorySource(client).fetchHighest("600519", longWindow) is HistoricalHighResult.Failure)
    }

    @Test
    fun enormousDateRangesFailBeforeAnyNetworkRequest() = runBlocking {
        val fetcher = HistoricalPriceFetcher(HistoricalHighSource { _, _ -> fail("No request for unbounded windows"); HistoricalHighResult.NoData })
        val result = fetcher.fetchHighest(listOf("600519"), HistoricalPriceWindow(LocalDate.parse("1900-01-01"), LocalDate.parse("2026-09-27")))
        assertTrue(result["600519"] is HistoricalHighResult.Failure)
        assertTrue((result["600519"] as HistoricalHighResult.Failure).reason.contains("30 年"))
    }

    @Test
    fun beijingFallbackUsesSinaAndCalendarCoverageAcrossAHoliday() = runBlocking {
        val client = StubClient { request ->
            when {
                request.source == "Eastmoney" -> throw EOFException()
                request.url.contains("symbol=bj920002&") -> sinaResponse("2026-09-16:49.28", "2026-09-17:50.68", "2026-09-24:52.88", "2026-09-28:55.03")
                request.url.contains("symbol=sh000001&") -> sinaResponse("2026-09-16:3900", "2026-09-17:3910", "2026-09-24:3930", "2026-09-28:3878")
                else -> error("Unexpected request ${request.url}")
            }
        }
        val result = FallbackHistoricalHighSource(EastmoneyHistorySource(client), RegionalBackupHistorySource(client))
            .fetchHighest("920002", window)
        assertEquals(HistoricalHighResult.Success(52.88), result)
        assertEquals(listOf("Eastmoney", "Sina", "Sina"), client.requests.map { it.source })
        client.requests.drop(1).forEach {
            assertTrue(it.url.startsWith("https://quotes.sina.cn/"))
            assertTrue(it.url.endsWith("&scale=240&ma=no&datalen=1023"))
        }
    }

    @Test
    fun sinaRefusesInsufficientStartStaleCalendarAndUnverifiedSuspension() {
        val stock = bars("2026-09-16:10", "2026-09-17:12", "2026-09-24:20", "2026-09-28:99")
        val calendar = bars("2026-09-16:10", "2026-09-17:10", "2026-09-24:10", "2026-09-28:10")
        for (result in listOf(
            sinaHighestWithCoverage(window, stock.drop(2), calendar),
            sinaHighestWithCoverage(window, stock, calendar.drop(2)),
            sinaHighestWithCoverage(window, stock, calendar.dropLast(1)),
            sinaHighestWithCoverage(window, stock.filter { it.date != LocalDate.parse("2026-09-24") }, calendar),
        )) assertTrue(result.toString(), result is HistoricalHighResult.Failure)
    }

    @Test
    fun sinaRejectsMissingInteriorTradingDaysInsteadOfReturningALowMaximum() {
        val range = HistoricalPriceWindow(LocalDate.parse("2026-09-17"), LocalDate.parse("2026-09-21"))
        val stock = bars("2026-09-16:10", "2026-09-17:12", "2026-09-21:15")
        val calendar = bars("2026-09-16:10", "2026-09-17:10", "2026-09-18:10", "2026-09-21:10")
        val result = sinaHighestWithCoverage(range, stock, calendar)
        assertTrue(result is HistoricalHighResult.Failure)
        assertTrue((result as HistoricalHighResult.Failure).reason.contains("2026-09-18"))
    }

    @Test
    fun sinaRejectsStockBarsOutsidePublishedTradingDays() {
        val weekend = HistoricalPriceWindow(LocalDate.parse("2026-09-19"), LocalDate.parse("2026-09-20"))
        val stock = bars("2026-09-18:10", "2026-09-19:999", "2026-09-21:11")
        val calendar = bars("2026-09-18:10", "2026-09-21:10")
        val result = sinaHighestWithCoverage(weekend, stock, calendar)
        assertTrue(result is HistoricalHighResult.Failure)
        assertTrue((result as HistoricalHighResult.Failure).reason.contains("2026-09-19"))
    }

    @Test
    fun sinaCanConfirmANonTradingWindowWithoutInventingAHigh() {
        val weekend = HistoricalPriceWindow(LocalDate.parse("2026-09-19"), LocalDate.parse("2026-09-20"))
        val stock = bars("2026-09-18:10", "2026-09-21:999")
        val calendar = bars("2026-09-18:10", "2026-09-21:10")
        assertEquals(HistoricalHighResult.NoData, sinaHighestWithCoverage(weekend, stock, calendar))
    }

    @Test
    fun sinaRejectsMalformedRowsAndInvalidPrices() {
        for (body in listOf("null", "{}", sinaResponse("invalid-date:12"), sinaResponse("2026-09-17:NaN"),
            sinaResponse("2026-09-17:0"), sinaResponse("2026-09-17:12", "2026-09-17:13"))) {
            try { parseSinaDailyHighs(body); fail("Invalid history was accepted: $body") } catch (_: Exception) { }
        }
    }

    @Test
    fun errorReasonsAreReadableAndResponseSummariesAreBounded() {
        assertTrue(readableHistoryError(UnknownHostException()).contains("域名解析失败"))
        assertTrue(readableHistoryError(SocketTimeoutException()).contains("超时"))
        assertTrue(readableHistoryError(SSLHandshakeException("bad certificate")).contains("HTTPS"))
        assertTrue(readableHistoryError(SocketException("Connection reset")).contains("提前关闭"))
        val summary = historyResponseSummary("a\nb\r\tc" + "x".repeat(2000))
        assertTrue(summary.length <= 769)
        assertFalse(summary.contains('\n'))
        assertFalse(summary.contains('\r'))
        assertFalse(summary.contains('\t'))
    }

    private class StubClient(private val respond: (HistoryHttpRequest) -> String) : HistoryHttpClient {
        val requests = mutableListOf<HistoryHttpRequest>()
        override suspend fun <T> request(request: HistoryHttpRequest, parse: (String) -> T): T {
            requests += request
            return parse(respond(request))
        }
    }

    private fun tencentResponse(symbol: String, vararg rows: String): String =
        """{"code":0,"data":{"$symbol":{"day":[${rows.joinToString(",") { row -> row.split(',').joinToString(",", "[", "]") { "\"$it\"" } }}]}}}"""

    private fun sinaResponse(vararg rows: String): String = rows.joinToString(",", "[", "]") { row ->
        val (date, high) = row.split(':')
        """{"day":"$date","high":"$high"}"""
    }

    private fun bars(vararg rows: String) = parseSinaDailyHighs(sinaResponse(*rows))
}
