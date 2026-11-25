package com.stockshot.manager.net

import java.io.IOException
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class HistoricalPriceFetcherTest {
    private val code = "600519"
    private val recordDate = LocalDate.parse("2026-07-03")
    private val window = HistoricalPriceWindow.after(recordDate, 10, LocalDate.parse("2026-08-01"))!!

    @Test
    fun securityIdsMapShanghaiShenzhenAndBeijingStocks() {
        val expected = mapOf(
            "600519" to "1.600519",
            "000001" to "0.000001",
            "300750" to "0.300750",
            "688981" to "1.688981",
            "920002" to "0.920002",
            "430047" to "0.430047",
        )
        for ((code, securityId) in expected) {
            assertEquals(code, securityId, eastmoneySecurityId(code))
        }
    }

    @Test
    fun securityIdsRejectMalformedCodes() {
        for (code in listOf("", "60051", "6005190", "sh600519", "60051x", " 600519", "600519 ", "６００５１９")) {
            assertNull(code, eastmoneySecurityId(code))
        }
    }

    @Test
    fun windowExcludesRecordDayAndCountsCalendarDays() {
        assertEquals(LocalDate.parse("2026-07-04"), window.startInclusive)
        assertEquals(LocalDate.parse("2026-07-13"), window.endInclusive)
    }

    @Test
    fun incompleteWindowIncludesAvailableDataThroughToday() {
        val today = LocalDate.parse("2026-07-07")
        val partial = HistoricalPriceWindow.after(recordDate, 10, today)!!
        assertEquals(today, partial.endInclusive)
        val result = parseEastmoneyHighest(code, partial, response(
            "2026-07-06,10,10.5,11,9,100",
            "2026-07-07,10.5,11,12.3456,10,200",
            "2026-07-08,11,12,99,10,300",
        ))
        assertEquals(HistoricalHighResult.Success(12.3456), result)
    }

    @Test
    fun currentAndFutureRecordDatesHaveNoAvailableWindow() {
        assertNull(HistoricalPriceWindow.after(recordDate, 10, recordDate))
        assertNull(HistoricalPriceWindow.after(recordDate.plusDays(1), 10, recordDate))
    }

    @Test
    fun largeDayCountStillEndsAtToday() {
        val today = LocalDate.parse("2026-09-27")
        assertEquals(today, HistoricalPriceWindow.after(recordDate, Int.MAX_VALUE, today)!!.endInclusive)
    }

    @Test(expected = IllegalArgumentException::class)
    fun zeroDaysIsRejected() {
        HistoricalPriceWindow.after(recordDate, 0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun negativeDaysIsRejected() {
        HistoricalPriceWindow.after(recordDate, -1)
    }

    @Test
    fun maximumUsesDailyHighAndBothRangeBoundaries() {
        val result = parseEastmoneyHighest(code, window, response(
            "2026-07-03,100,100,999,90,100",
            "2026-07-06,10,11,17,9,100",
            "2026-07-07,10,12,14,9,100",
            "2026-07-13,10,11,18.12,9,100",
            "2026-07-14,100,100,999,90,100",
        ))
        assertEquals(HistoricalHighResult.Success(18.12), result)
    }

    @Test
    fun singleDayRangeIncludesThatDaysHigh() {
        val singleDay = HistoricalPriceWindow.after(LocalDate.parse("2026-07-05"), 1, recordDate.plusDays(10))!!
        assertEquals(
            HistoricalHighResult.Success(11.0),
            parseEastmoneyHighest(code, singleDay, response("2026-07-06,9,10,11,8,100")),
        )
    }

    @Test
    fun validEmptyOrOutOfRangeHistoryIsNoData() {
        assertEquals(HistoricalHighResult.NoData, parseEastmoneyHighest(code, window, response()))
        assertEquals(
            HistoricalHighResult.NoData,
            parseEastmoneyHighest(code, window, response("2026-07-03,10,10,12,9,100")),
        )
    }

    @Test
    fun malformedOrFailedResponsesAreFailuresRatherThanNoData() {
        val bodies = listOf(
            "not json",
            "null",
            """{"rc":100,"data":null}""",
            """{"rc":0,"data":null}""",
            """{"data":{"code":"600519","klines":[]}}""",
            """{"rc":0,"data":{"code":"000001","klines":[]}}""",
            """{"rc":0,"data":{"code":"600519"}}""",
            """{"rc":0,"data":{"code":"600519","klines":{}}}""",
            response("2026-07-06,10"),
            response("invalid-date,10,11,12"),
        )
        for (body in bodies) {
            assertTrue(body, parseEastmoneyHighest(code, window, body) is HistoricalHighResult.Failure)
        }
    }

    @Test
    fun mismatchedMarketAndNonIntegerStatusAreFailures() {
        val valid = response("2026-07-06,10,11,12,9,100")
        val bodies = listOf(
            valid.replace("\"market\":1", "\"market\":0"),
            valid.replace("\"market\":1,", ""),
            valid.replace("\"market\":1", "\"market\":1.5"),
            valid.replace("\"rc\":0", "\"rc\":0.5"),
        )
        for (body in bodies) {
            assertTrue(body, parseEastmoneyHighest(code, window, body) is HistoricalHighResult.Failure)
        }
    }

    @Test
    fun acceptsBeijingHistoryWithItsOwnMarketAndCode() {
        val beijing = response("2026-07-06,49.12,49.92,50.68,48.70,8756")
            .replace("\"code\":\"600519\"", "\"code\":\"920002\"")
            .replace("\"market\":1", "\"market\":0")
        assertEquals(HistoricalHighResult.Success(50.68), parseEastmoneyHighest("920002", window, beijing))
    }

    @Test
    fun anyInvalidInRangeHighFailsInsteadOfReturningAPartialMaximum() {
        for (invalid in listOf("0", "-1", "NaN", "Infinity", "1e999", "bad-price", "")) {
            val result = parseEastmoneyHighest(code, window, response(
                "2026-07-06,10,11,12,9,100",
                "2026-07-07,10,11,$invalid,9,100",
            ))
            assertTrue(invalid, result is HistoricalHighResult.Failure)
        }
    }

    @Test
    fun batchKeepsSuccessNoDataAndFailuresSeparateAndFetchesEachCodeOnce() = runBlocking {
        val requested = mutableListOf<String>()
        val fetcher = HistoricalPriceFetcher(HistoricalHighSource { requestedCode, requestedWindow ->
            requested += requestedCode
            assertEquals(window, requestedWindow)
            when (requestedCode) {
                "600519" -> HistoricalHighResult.Success(18.12)
                "000001" -> HistoricalHighResult.NoData
                "920002" -> HistoricalHighResult.Failure("timeout")
                else -> throw IOException("connection failed")
            }
        })
        val results = fetcher.fetchHighest(listOf("600519", "000001", "920002", "300001", "600519"), window)
        assertEquals(4, requested.size)
        assertEquals(HistoricalHighResult.Success(18.12), results["600519"])
        assertEquals(HistoricalHighResult.NoData, results["000001"])
        assertEquals(HistoricalHighResult.Failure("timeout"), results["920002"])
        assertTrue(results["300001"] is HistoricalHighResult.Failure)
    }

    @Test
    fun noAvailableWindowSkipsNetworkForEveryStock() = runBlocking {
        val fetcher = HistoricalPriceFetcher(HistoricalHighSource { _, _ ->
            fail("No network request is needed when no later day has occurred")
            HistoricalHighResult.Failure("unexpected request")
        })
        assertEquals(
            mapOf("600519" to HistoricalHighResult.NoData, "000001" to HistoricalHighResult.NoData),
            fetcher.fetchHighest(listOf("600519", "000001"), null),
        )
    }

    @Test
    fun batchRunsAtMostFourRequestsAtOnce() = runBlocking {
        val active = AtomicInteger()
        val peak = AtomicInteger()
        val firstFourStarted = CompletableDeferred<Unit>()
        val fetcher = HistoricalPriceFetcher(HistoricalHighSource { _, _ ->
            val count = active.incrementAndGet()
            peak.updateAndGet { maxOf(it, count) }
            if (count == 4) firstFourStarted.complete(Unit)
            try {
                firstFourStarted.await()
                delay(10)
                HistoricalHighResult.Success(1.0)
            } finally {
                active.decrementAndGet()
            }
        })
        val results = withTimeout(5_000) {
            fetcher.fetchHighest((1..12).map { it.toString().padStart(6, '0') }, window)
        }
        assertEquals(12, results.size)
        assertEquals(4, peak.get())
        assertEquals(0, active.get())
    }

    @Test
    fun cancellationIsPropagated() = runBlocking {
        val fetcher = HistoricalPriceFetcher(HistoricalHighSource { _, _ -> throw CancellationException("cancelled") })
        try {
            fetcher.fetchHighest(listOf(code), window)
            fail("Cancellation must propagate")
        } catch (expected: CancellationException) {
            assertEquals("cancelled", expected.message)
        }
    }

    private fun response(vararg lines: String): String =
        """{"rc":0,"data":{"code":"$code","market":1,"klines":[${lines.joinToString(",") { "\"$it\"" }}]}}"""
}
