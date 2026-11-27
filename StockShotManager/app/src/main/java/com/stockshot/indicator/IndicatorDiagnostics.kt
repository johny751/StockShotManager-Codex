package com.stockshot.indicator

import android.graphics.PointF
import android.graphics.RectF

/** UNKNOWN is insufficient or ambiguous image evidence, never a positive match. */
enum class DetectionStatus { MATCH, NO_MATCH, UNKNOWN }
enum class IndicatorDotColor { RED, GREEN }
enum class CapitalColumnStatus { SATISFIED, VIOLATING, MISSING }

/** All drawing coordinates are normalized to the complete input image. */
data class IndicatorDotCandidate(
    val bounds: RectF,
    val center: PointF,
    val color: IndicatorDotColor,
    val split: Boolean,
)

data class CapitalColumnDiagnostic(
    val x: Float,
    val status: CapitalColumnStatus,
    val purpleY: Float? = null,
    val greenY: Float? = null,
    val yellowY: Float? = null,
    val hasMissingData: Boolean = status == CapitalColumnStatus.MISSING,
)

data class CapitalStats(
    val totalColumns: Int = 0,
    val validColumns: Int = 0,
    val satisfiedColumns: Int = 0,
    val longestSatisfiedRun: Int = 0,
    val missingColumns: Int = 0,
    val violatingColumns: Int = 0,
)

data class IndicatorDiagnostics(
    val trendRegion: RectF? = null,
    val capitalRegion: RectF? = null,
    val dotCandidates: List<IndicatorDotCandidate> = emptyList(),
    val capitalWindow: RectF? = null,
    val capitalStats: CapitalStats = CapitalStats(),
    val columns: List<CapitalColumnDiagnostic> = emptyList(),
    val messages: List<String> = emptyList(),
    val trendPanelValid: Boolean = false,
    val capitalPanelValid: Boolean = false,
    val candidatePairCount: Int = 0,
    val checkedPairCount: Int = 0,
    val capitalMethod: String = "median",
    val capitalRule: String = "all",
    val signalSelection: String = "any",
    val majorityThreshold: Float = 0.9f,
)

/** Pure rule semantics; MISSING is never imputed, interpolated, or counted as satisfied. */
internal object CapitalRuleEvaluator {
    fun evaluate(
        columns: List<CapitalColumnStatus>,
        rule: String,
        majorityThreshold: Float = 0.9f,
        minimumSamples: Int = 3,
    ): DetectionStatus {
        if (rule == "signal") {
            if (columns.size != 1) return DetectionStatus.UNKNOWN
            return when (columns.single()) {
                CapitalColumnStatus.SATISFIED -> DetectionStatus.MATCH
                CapitalColumnStatus.VIOLATING -> DetectionStatus.NO_MATCH
                CapitalColumnStatus.MISSING -> DetectionStatus.UNKNOWN
            }
        }
        if (rule !in setOf("all", "majority")) return DetectionStatus.UNKNOWN
        if (rule == "all" && CapitalColumnStatus.VIOLATING in columns) return DetectionStatus.NO_MATCH
        if (columns.size < minimumSamples.coerceAtLeast(1) || CapitalColumnStatus.MISSING in columns) {
            return DetectionStatus.UNKNOWN
        }
        if (rule == "all") return DetectionStatus.MATCH
        if (!majorityThreshold.isFinite() || majorityThreshold !in 0f..1f) return DetectionStatus.UNKNOWN
        val satisfied = columns.count { it == CapitalColumnStatus.SATISFIED }
        return if (satisfied.toFloat() / columns.size >= majorityThreshold) {
            DetectionStatus.MATCH
        } else DetectionStatus.NO_MATCH
    }
}
