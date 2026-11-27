package com.stockshot.indicator

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class IndicatorDetectorRegressionTest {
    @Test
    fun completeWindowMatchesAndReportsNormalizedEvidence() = withChart { chart ->
        chart.signal(200)
        chart.capital()
        val result = detect(chart)
        assertEquals(result.reason, DetectionStatus.MATCH, result.status)
        assertTrue(result.matched)
        assertTrue(result.diagnostics.trendPanelValid)
        assertTrue(result.diagnostics.capitalPanelValid)
        assertEquals(1, result.diagnostics.checkedPairCount)
        val stats = result.diagnostics.capitalStats
        assertEquals(stats.totalColumns, stats.validColumns)
        assertEquals(stats.totalColumns, stats.satisfiedColumns)
        assertEquals(stats.totalColumns, stats.longestSatisfiedRun)
        assertEquals(0, stats.missingColumns)
        assertEquals(0, stats.violatingColumns)
        assertEquals(125f / WIDTH, result.diagnostics.capitalWindow!!.left, 0.00001f)
        assertEquals(200f / WIDTH, result.diagnostics.capitalWindow!!.right, 0.00001f)
        assertEquals(199f / WIDTH, result.diagnostics.columns.last().x, 0.00001f)
        assertNormalized(result)
    }

    @Test
    fun oneCounterexampleRejectsAllButMedianAndMajorityRetainTheirOwnSemantics() = withChart { chart ->
        chart.signal(200)
        chart.capital(purple = { if (it == 180) 1220 else 1150 })
        val strict = detect(chart)
        assertEquals(DetectionStatus.NO_MATCH, strict.capitalStatus)
        assertEquals(1, strict.diagnostics.capitalStats.violatingColumns)
        assertFalse(strict.matched)
        assertEquals(DetectionStatus.MATCH, detect(chart, method = "median").status)
        assertEquals(DetectionStatus.MATCH, detect(chart, rule = "majority").status)
    }

    @Test
    fun oneMissingColumnIsUnknownInsteadOfBeingInterpolated() = withChart { chart ->
        chart.signal(200)
        chart.capital(purple = { if (it == 180) null else 1150 })
        val result = detect(chart)
        assertEquals(DetectionStatus.UNKNOWN, result.status)
        assertEquals(DetectionStatus.MATCH, result.trendStatus)
        assertEquals(DetectionStatus.UNKNOWN, result.capitalStatus)
        assertEquals(1, result.diagnostics.capitalStats.missingColumns)
        assertFalse(result.matched)
        assertEquals(DetectionStatus.UNKNOWN, detect(chart, rule = "majority").status)
    }

    @Test
    fun aVisibleCounterexampleWinsOverOtherMissingColumnsForAll() = withChart { chart ->
        chart.signal(200)
        chart.capital(purple = { when (it) { 175 -> null; 180 -> 1220; else -> 1150 } })
        val result = detect(chart)
        assertEquals(DetectionStatus.NO_MATCH, result.status)
        assertEquals(1, result.diagnostics.capitalStats.missingColumns)
        assertEquals(1, result.diagnostics.capitalStats.violatingColumns)
    }

    @Test
    fun intervalExcludesSignalColumnWhileSignalRuleChecksIt() = withChart { chart ->
        chart.signal(200)
        chart.capital(purple = { if (it == 200) 1220 else 1150 })
        val interval = detect(chart)
        val signal = detect(chart, rule = "signal")
        assertEquals(DetectionStatus.MATCH, interval.status)
        assertEquals(DetectionStatus.NO_MATCH, signal.status)
        assertEquals(1, signal.diagnostics.capitalStats.totalColumns)
        assertEquals(200f / WIDTH, signal.diagnostics.columns.single().x, 0.00001f)
    }

    @Test
    fun anEarlierCounterexampleDoesNotOverrideTheSignalOnlyRule() = withChart { chart ->
        chart.signal(200)
        chart.capital(purple = { if (it < 200) 1220 else 1150 })
        assertEquals(DetectionStatus.NO_MATCH, detect(chart).status)
        assertEquals(DetectionStatus.MATCH, detect(chart, rule = "signal").status)
    }

    @Test
    fun shortReadableEarlyWindowCanMatchWithoutTheOldTwentyColumnMinimum() = withChart { chart ->
        chart.signal(50)
        chart.capital()
        val result = detect(chart)
        assertEquals(result.reason, DetectionStatus.MATCH, result.status)
        assertTrue(result.diagnostics.capitalStats.totalColumns in 3..19)
    }

    @Test
    fun fewerThanThreeIntervalColumnsAreUnknownButSignalCanStillBeRead() = withChart { chart ->
        chart.signal(18)
        chart.capital()
        assertEquals(DetectionStatus.UNKNOWN, detect(chart).capitalStatus)
        assertEquals(DetectionStatus.MATCH, detect(chart, rule = "signal").capitalStatus)
    }

    @Test
    fun noSignalDoesNotFallBackToAnUnrelatedRightHandCapitalWindow() = withChart { chart ->
        chart.capital()
        val result = detect(chart)
        assertEquals(DetectionStatus.NO_MATCH, result.trendStatus)
        assertEquals(DetectionStatus.UNKNOWN, result.capitalStatus)
        assertNull(result.diagnostics.capitalWindow)
        assertEquals(0, result.diagnostics.capitalStats.totalColumns)
        assertFalse(result.matched)
    }

    @Test
    fun anyStopsAtTheFirstLeftToRightMatchWhileLatestChecksTheRightmostSignal() = withChart { chart ->
        chart.signal(200)
        chart.signal(400)
        chart.capital(purple = { if (it >= 300) 1220 else 1150 })
        val any = detect(chart)
        assertEquals(DetectionStatus.MATCH, any.status)
        assertEquals(200f / WIDTH, any.redPoint!!.x, 0.00001f)
        assertEquals(1, any.diagnostics.checkedPairCount)
        val latest = detect(chart, selection = "latest")
        assertEquals(DetectionStatus.NO_MATCH, latest.status)
        assertEquals(400f / WIDTH, latest.redPoint!!.x, 0.00001f)
    }

    @Test
    fun anyContinuesPastAnEarlierFailureToTheNextMatch() = withChart { chart ->
        chart.signal(200)
        chart.signal(400)
        chart.capital(purple = { if (it == 180) 1220 else 1150 })
        val result = detect(chart)
        assertEquals(DetectionStatus.MATCH, result.status)
        assertEquals(400f / WIDTH, result.redPoint!!.x, 0.00001f)
        assertEquals(2, result.diagnostics.checkedPairCount)
    }

    @Test
    fun anyKeepsUnknownWhenNoPairMatchesAndSomeEvidenceIsMissing() = withChart { chart ->
        chart.signal(200)
        chart.signal(400)
        chart.capital(purple = { when { it == 180 -> null; it >= 300 -> 1220; else -> 1150 } })
        val result = detect(chart)
        assertEquals(DetectionStatus.UNKNOWN, result.status)
        assertFalse(result.matched)
        assertEquals(2, result.diagnostics.checkedPairCount)
    }

    @Test
    fun defaultAnyContinuesWhenFirstPairPassesFundsButFailsScoreGate() = withChart { chart ->
        chart.signal(200, greenX = 205)
        chart.signal(400)
        chart.capital()
        val result = IndicatorDetector.detectIndicatorPattern(
            chart.bitmap, minConfidence = 0.8f, capitalMethod = "pointwise", capitalRule = "all",
        )
        assertEquals(DetectionStatus.MATCH, result.status)
        assertEquals(400f / WIDTH, result.redPoint!!.x, 0.00001f)
        assertEquals(2, result.diagnostics.checkedPairCount)
    }

    @Test
    fun thinBridgeBetweenGreenMarkersIsSplitIntoObservedLobes() = withChart { chart ->
        chart.signal(200)
        chart.signal(216)
        chart.rect(204, 1055, 213, 1056, GREEN)
        chart.capital()
        val result = detect(chart)
        assertEquals(DetectionStatus.MATCH, result.status)
        val splitGreens = result.diagnostics.dotCandidates.filter { it.color == IndicatorDotColor.GREEN && it.split }
        assertEquals(2, splitGreens.size)
        assertEquals(2, result.diagnostics.candidatePairCount)
    }

    @Test
    fun aMarkerTouchingBaselineIsRetainedWithoutTreatingTheWholeLineAsADot() = withChart { chart ->
        chart.rect(198, 1047, 203, 1052, RED)
        chart.rect(197, 1055, 204, 1064, GREEN)
        chart.capital()
        val result = detect(chart)
        assertEquals(DetectionStatus.MATCH, result.trendStatus)
        assertTrue(result.diagnostics.dotCandidates.any { it.color == IndicatorDotColor.GREEN && it.split })
        assertTrue(result.diagnostics.dotCandidates.all { it.bounds.width() < 0.1f })
    }

    @Test
    fun plainBaselinesAloneAreNotRedGreenSignalMarkers() = withChart { chart ->
        chart.rect(0, 1020, 493, 1021, RED)
        chart.capital()
        val result = detect(chart)
        assertTrue(result.diagnostics.trendPanelValid)
        assertEquals(DetectionStatus.NO_MATCH, result.trendStatus)
        assertTrue(result.diagnostics.dotCandidates.isEmpty())
    }

    @Test
    fun missingTrendStructureAndTrendBaselinesInCapitalBothPreventAMatch() = withChart { chart ->
        chart.signal(200)
        chart.capital()
        chart.rect(0, 1040, 493, 1041, WHITE)
        assertEquals(DetectionStatus.UNKNOWN, detect(chart).status)
        chart.rect(0, 1040, 493, 1041, BLUE)
        chart.rect(14, 1200, 493, 1201, BLUE)
        val reversedStructure = detect(chart)
        assertEquals(DetectionStatus.UNKNOWN, reversedStructure.status)
        assertFalse(reversedStructure.diagnostics.capitalPanelValid)
    }

    @Test
    fun twoSeparatedSameColourRunsAreUnknownInsteadOfInventingACurve() = withChart { chart ->
        chart.signal(200)
        chart.capital()
        chart.rect(180, 1170, 181, 1171, PURPLE)
        val result = detect(chart)
        assertEquals(DetectionStatus.UNKNOWN, result.status)
        assertEquals(1, result.diagnostics.capitalStats.missingColumns)
    }

    @Test
    fun yellowRequirementNeedsYellowEvidenceAndDetectsItsOwnCounterexample() = withChart { chart ->
        chart.signal(200)
        chart.capital()
        assertEquals(DetectionStatus.UNKNOWN, detect(chart, requireYellow = true).status)
        chart.rect(14, 1180, 493, 1181, YELLOW)
        assertEquals(DetectionStatus.MATCH, detect(chart, requireYellow = true).status)
        chart.rect(180, 1180, 181, 1181, WHITE)
        chart.rect(180, 1220, 181, 1221, YELLOW)
        assertEquals(DetectionStatus.NO_MATCH, detect(chart, requireYellow = true).status)
    }

    @Test
    fun knownCounterexampleAndMissingRequiredYellowCanOverlapWithoutMakingMajorityPass() = withChart { chart ->
        chart.signal(200)
        chart.capital(purple = { if (it == 180) 1220 else 1150 })
        chart.rect(14, 1180, 493, 1181, YELLOW)
        chart.rect(180, 1180, 181, 1181, WHITE)
        val strict = detect(chart, requireYellow = true)
        assertEquals(DetectionStatus.NO_MATCH, strict.status)
        assertEquals(1, strict.diagnostics.capitalStats.violatingColumns)
        assertEquals(1, strict.diagnostics.capitalStats.missingColumns)
        assertEquals(strict.diagnostics.capitalStats.totalColumns - 1, strict.diagnostics.capitalStats.validColumns)
        val mixed = strict.diagnostics.columns.single { it.x == 180f / WIDTH }
        assertEquals(CapitalColumnStatus.VIOLATING, mixed.status)
        assertTrue(mixed.hasMissingData)
        assertEquals(DetectionStatus.UNKNOWN, detect(chart, rule = "majority", requireYellow = true).status)
    }

    @Test
    fun medianNeedsAtLeastHalfOfEachColourAndDoesNotClaimAnEmptyWindow() = withChart { chart ->
        chart.signal(200)
        chart.capital(purple = { if (it < 160) 1150 else null })
        val result = detect(chart, method = "median")
        assertEquals(DetectionStatus.UNKNOWN, result.capitalStatus)
        assertFalse(result.matched)
    }

    @Test
    fun realCapturedWrongPanelOrderIsUnknownWithoutAssigningAStockTruthLabel() {
        val bitmap = loadCapturedFixture("panels-capital-above-trend")
        try {
            val result = IndicatorDetector.detectIndicatorPattern(bitmap, capitalMethod = "pointwise", capitalRule = "all")
            assertEquals(result.reason, DetectionStatus.UNKNOWN, result.status)
            assertFalse(result.matched)
            assertFalse(result.diagnostics.trendPanelValid && result.diagnostics.capitalPanelValid)
        } finally { bitmap.recycle() }
    }

    @Test
    fun realReorderedCaptureMatchesTheUsersStrictNoMatchLabel() {
        val bitmap = loadCapturedFixture("panels-trend-above-capital")
        try {
            val result = IndicatorDetector.detectIndicatorPattern(bitmap, capitalMethod = "pointwise", capitalRule = "all", signalSelection = "any")
            assertEquals(result.reason, DetectionStatus.NO_MATCH, result.status)
            assertTrue("The human-labelled failure is a visible capital counterexample", result.diagnostics.capitalStats.violatingColumns > 0)
            assertFalse(result.matched)
        } finally { bitmap.recycle() }
    }

    private fun detect(chart: Chart, method: String = "pointwise", rule: String = "all", selection: String = "any", requireYellow: Boolean = false) =
        IndicatorDetector.detectIndicatorPattern(chart.bitmap, requireYellow = requireYellow, capitalMethod = method, capitalRule = rule, signalSelection = selection)

    private fun assertNormalized(result: IndicatorMatchResult) {
        for (point in listOfNotNull(result.redPoint, result.greenPoint)) {
            assertTrue(point.x in 0f..1f && point.y in 0f..1f)
        }
        for (candidate in result.diagnostics.dotCandidates) {
            assertTrue(candidate.bounds.left in 0f..1f && candidate.bounds.right in 0f..1f)
            assertTrue(candidate.bounds.top in 0f..1f && candidate.bounds.bottom in 0f..1f)
            assertTrue(candidate.bounds.left <= candidate.center.x && candidate.center.x < candidate.bounds.right)
            assertTrue(candidate.bounds.top <= candidate.center.y && candidate.center.y < candidate.bounds.bottom)
        }
        for (column in result.diagnostics.columns) {
            assertTrue(column.x in 0f..1f)
            assertTrue(listOfNotNull(column.purpleY, column.greenY, column.yellowY).all { it in 0f..1f })
        }
    }

    private fun loadCapturedFixture(id: String): Bitmap {
        val loader = javaClass.classLoader!!
        val manifest = loader.getResourceAsStream("indicator/fixtures.json")!!.bufferedReader().use {
            JsonParser.parseReader(it).asJsonObject
        }
        val source = manifest.getAsJsonObject("originalDimensions")
        val crop = manifest.getAsJsonObject("crop")
        val selected = manifest.getAsJsonArray("fixtures").map { it.asJsonObject }.single { it["id"].asString == id }
        val image = loader.getResourceAsStream("indicator/" + selected["file"].asString)!!.use { BitmapFactory.decodeStream(it) }
        assertNotNull(image)
        val decoded = image!!
        assertEquals(crop["width"].asInt, decoded.width)
        assertEquals(crop["height"].asInt, decoded.height)
        val bitmap = Bitmap.createBitmap(source["width"].asInt, source["height"].asInt, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(WHITE)
        val pixels = IntArray(decoded.width * decoded.height)
        decoded.getPixels(pixels, 0, decoded.width, 0, 0, decoded.width, decoded.height)
        bitmap.setPixels(pixels, 0, decoded.width, crop["left"].asInt, crop["top"].asInt, decoded.width, decoded.height)
        decoded.recycle()
        return bitmap
    }

    private inline fun withChart(block: (Chart) -> Unit) {
        val chart = Chart()
        try { block(chart) } finally { chart.bitmap.recycle() }
    }

    /** Pixel-exact fixtures isolate image evidence; they do not imitate detector implementation. */
    private class Chart {
        val bitmap: Bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888).apply { eraseColor(WHITE) }
        init {
            rect(0, 1040, 493, 1041, BLUE)
            rect(0, 1060, 493, 1061, GREEN)
        }
        fun signal(redX: Int, greenX: Int = redX) {
            rect(redX - 2, 1047, redX + 3, 1052, RED)
            rect(greenX - 3, 1053, greenX + 4, 1058, GREEN)
        }
        fun capital(purple: (Int) -> Int? = { 1150 }, green: (Int) -> Int? = { 1210 }) {
            for (x in 14 until 493) {
                purple(x)?.let { bitmap.setPixel(x, it, PURPLE) }
                green(x)?.let { bitmap.setPixel(x, it, GREEN) }
            }
        }
        fun rect(left: Int, top: Int, right: Int, bottom: Int, colour: Int) {
            for (y in top until bottom) for (x in left until right) bitmap.setPixel(x, y, colour)
        }
    }

    private companion object {
        const val WIDTH = 720
        const val HEIGHT = 1600
        const val WHITE = -1
        val RED = android.graphics.Color.rgb(240, 0, 0)
        val GREEN = android.graphics.Color.rgb(0, 208, 0)
        val BLUE = android.graphics.Color.rgb(0, 160, 255)
        val PURPLE = android.graphics.Color.rgb(230, 0, 230)
        val YELLOW = android.graphics.Color.rgb(255, 192, 0)
    }
}
