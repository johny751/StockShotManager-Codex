package com.stockshot.manager.indicator

import android.graphics.Bitmap
import com.stockshot.indicator.IndicatorDetector
import com.stockshot.indicator.IndicatorMatchResult
import com.stockshot.manager.data.SettingsStore

/** The exact options accompanying a saved result or a new evaluation. */
data class IndicatorDetectionOptions(
    val requireYellow: Boolean = false,
    val dotToleranceScale: Float = 1f,
    val minConfidence: Float = 0f,
    val capitalWindowScale: Float = 0.4f,
    val capitalMethod: String = "median",
    val capitalRule: String = "all",
    val signalSelection: String = "any",
    val majorityThreshold: Float = 0.9f,
) {
    fun detect(bitmap: Bitmap): IndicatorMatchResult = IndicatorDetector.detectIndicatorPattern(
        source = bitmap,
        requireYellow = requireYellow,
        dotToleranceScale = dotToleranceScale,
        minConfidence = minConfidence,
        capitalWindowScale = capitalWindowScale,
        capitalMethod = capitalMethod,
        capitalRule = capitalRule,
        signalSelection = signalSelection,
        majorityThreshold = majorityThreshold,
    )

    companion object {
        suspend fun fromSettings(store: SettingsStore): IndicatorDetectionOptions = IndicatorDetectionOptions(
            requireYellow = store.getBool(SettingsStore.KEY_CAPITAL_REQUIRE_YELLOW, SettingsStore.DEFAULT_CAPITAL_REQUIRE_YELLOW),
            dotToleranceScale = store.getFloat(SettingsStore.KEY_DOT_TOL_SCALE, SettingsStore.DEFAULT_DOT_TOL_SCALE),
            minConfidence = store.getFloat(SettingsStore.KEY_MIN_CONFIDENCE, SettingsStore.DEFAULT_MIN_CONFIDENCE),
            capitalWindowScale = store.getFloat(SettingsStore.KEY_CAPITAL_WINDOW_SCALE, SettingsStore.DEFAULT_CAPITAL_WINDOW_SCALE),
            capitalMethod = store.getCapitalMethod(),
            capitalRule = store.getCapitalRule(),
            signalSelection = store.getSignalSelection(),
            majorityThreshold = store.getCapitalMajorityThreshold(),
        )
    }
}
