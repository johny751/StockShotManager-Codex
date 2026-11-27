package com.stockshot.indicator

import org.junit.Assert.assertEquals
import org.junit.Test

/** Business-rule examples; missing evidence must never silently become a positive column. */
class CapitalRuleEvaluatorTest {
    private val yes = CapitalColumnStatus.SATISFIED
    private val no = CapitalColumnStatus.VIOLATING
    private val missing = CapitalColumnStatus.MISSING

    @Test
    fun allRequiresEveryColumnToBePresentAndSatisfied() {
        assertEquals(DetectionStatus.MATCH, evaluate(listOf(yes, yes, yes), "all"))
        assertEquals(DetectionStatus.UNKNOWN, evaluate(listOf(yes, missing, yes), "all"))
        assertEquals(DetectionStatus.UNKNOWN, evaluate(listOf(missing, missing, missing), "all"))
    }

    @Test
    fun allRejectsOneVisibleCounterexampleEvenWhenAnotherColumnIsMissing() {
        assertEquals(DetectionStatus.NO_MATCH, evaluate(List(20) { if (it == 9) no else yes }, "all"))
        assertEquals(DetectionStatus.NO_MATCH, evaluate(listOf(yes, no, missing), "all"))
    }

    @Test
    fun allDoesNotTreatAnEmptyOrTinyPositiveWindowAsEvidence() {
        assertEquals(DetectionStatus.UNKNOWN, evaluate(emptyList(), "all"))
        assertEquals(DetectionStatus.UNKNOWN, evaluate(listOf(yes), "all"))
        assertEquals(DetectionStatus.UNKNOWN, evaluate(listOf(yes, yes), "all"))
        assertEquals(DetectionStatus.NO_MATCH, evaluate(listOf(no), "all"))
    }

    @Test
    fun majorityUsesAllColumnsAndAcceptsItsExactConfiguredBoundary() {
        val nineOfTen = List(10) { if (it == 4) no else yes }
        assertEquals(DetectionStatus.MATCH, evaluate(nineOfTen, "majority", 0.9f))
        assertEquals(DetectionStatus.NO_MATCH, evaluate(nineOfTen, "majority", 0.91f))
        assertEquals(DetectionStatus.NO_MATCH, evaluate(List(10) { if (it < 2) no else yes }, "majority", 0.9f))
        assertEquals(DetectionStatus.MATCH, evaluate(List(10) { if (it < 2) no else yes }, "majority", 0.8f))
    }

    @Test
    fun majorityReturnsUnknownForAnyMissingColumnRegardlessOfKnownRatio() {
        assertEquals(DetectionStatus.UNKNOWN, evaluate(List(10) { if (it == 9) missing else yes }, "majority"))
        assertEquals(DetectionStatus.UNKNOWN, evaluate(List(10) { if (it == 9) missing else no }, "majority"))
        assertEquals(DetectionStatus.UNKNOWN, evaluate(listOf(missing, missing, missing), "majority"))
    }

    @Test
    fun majorityNeedsEnoughSamplesEvenWhenTheKnownRatioIsPerfect() {
        assertEquals(DetectionStatus.UNKNOWN, evaluate(emptyList(), "majority"))
        assertEquals(DetectionStatus.UNKNOWN, evaluate(listOf(yes, yes), "majority"))
        assertEquals(DetectionStatus.MATCH, evaluate(listOf(yes, yes, yes), "majority"))
    }

    @Test
    fun callerCanRequireMoreEvidenceForWindowRules() {
        assertEquals(DetectionStatus.UNKNOWN, CapitalRuleEvaluator.evaluate(List(4) { yes }, "all", minimumSamples = 5))
        assertEquals(DetectionStatus.MATCH, CapitalRuleEvaluator.evaluate(List(5) { yes }, "all", minimumSamples = 5))
        assertEquals(DetectionStatus.UNKNOWN, CapitalRuleEvaluator.evaluate(List(4) { yes }, "majority", minimumSamples = 5))
    }

    @Test
    fun signalAcceptsExactlyOneColumnWithoutTheWindowSampleThreshold() {
        assertEquals(DetectionStatus.MATCH, evaluate(listOf(yes), "signal"))
        assertEquals(DetectionStatus.NO_MATCH, evaluate(listOf(no), "signal"))
        assertEquals(DetectionStatus.UNKNOWN, evaluate(listOf(missing), "signal"))
        assertEquals(DetectionStatus.MATCH, CapitalRuleEvaluator.evaluate(listOf(yes), "signal", minimumSamples = 20))
    }

    @Test
    fun signalDoesNotChooseAnArbitraryColumnFromAnInvalidInputWindow() {
        assertEquals(DetectionStatus.UNKNOWN, evaluate(emptyList(), "signal"))
        assertEquals(DetectionStatus.UNKNOWN, evaluate(listOf(yes, yes), "signal"))
        assertEquals(DetectionStatus.UNKNOWN, evaluate(listOf(yes, no, yes), "signal"))
    }

    private fun evaluate(columns: List<CapitalColumnStatus>, rule: String, threshold: Float = 0.9f) =
        CapitalRuleEvaluator.evaluate(columns, rule, majorityThreshold = threshold)
}
