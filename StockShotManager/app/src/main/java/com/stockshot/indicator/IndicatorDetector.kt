package com.stockshot.indicator

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.RectF
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/** Result coordinates are normalized to 0..1 so callers can draw on any bitmap size. */
data class IndicatorMatchResult(
    val matched: Boolean,
    val trendDotsMatched: Boolean,
    val capitalLinesMatched: Boolean,
    val confidence: Float,
    val redPoint: PointF?,
    val greenPoint: PointF?,
    val purpleYRatio: Float?,
    val capitalGreenYRatio: Float?,
    val reason: String,
    val dotMatchPath: String? = null,
    val status: DetectionStatus = if (matched) DetectionStatus.MATCH else DetectionStatus.NO_MATCH,
    val trendStatus: DetectionStatus = if (trendDotsMatched) DetectionStatus.MATCH else DetectionStatus.NO_MATCH,
    val capitalStatus: DetectionStatus = if (capitalLinesMatched) DetectionStatus.MATCH else DetectionStatus.NO_MATCH,
    val diagnostics: IndicatorDiagnostics = IndicatorDiagnostics(),
)

/**
 * Fixed-layout, explainable image rules, not a calibrated prediction model.
 * The expected panel order is trend above capital. Structural checks reject obvious
 * mismatches; they do not establish the identity of every possible quote-app layout.
 */
object IndicatorDetector {
    private const val MAX_ANALYSIS_WIDTH = 720
    private const val MIN_INTERVAL_COLUMNS = 3

    private data class Region(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        val width: Int get() = right - left
        val height: Int get() = bottom - top
        fun normalized(w: Int, h: Int) = RectF(left.toFloat() / w, top.toFloat() / h, right.toFloat() / w, bottom.toFloat() / h)
    }

    private data class Blob(
        val x: Int,
        val y: Int,
        val width: Int,
        val height: Int,
        val area: Int,
        val centerX: Float,
        val centerY: Float,
        val pixels: IntArray,
        val split: Boolean = false,
    )

    private data class Dot(val blob: Blob, val color: IndicatorDotColor, val strict: Boolean)
    private data class DotPair(val red: Dot, val green: Dot, val dx: Float, val tolerance: Float, val path: String) {
        val signalX: Float get() = red.blob.centerX
    }
    private data class Baseline(val color: Int, val firstRow: Int, val lastRow: Int) {
        val center: Float get() = (firstRow + lastRow) / 2f
    }
    private data class Track(val medianY: Float?, val columns: Int)
    private data class CapitalEvaluation(
        val status: DetectionStatus,
        val window: Region,
        val stats: CapitalStats,
        val columns: List<CapitalColumnDiagnostic>,
        val purple: Track,
        val green: Track,
        val lineScore: Float,
        val reason: String,
    )
    private data class PairEvaluation(
        val pair: DotPair,
        val capital: CapitalEvaluation,
        val status: DetectionStatus,
        val score: Float,
        val reason: String,
    )

    /**
     * median preserves the independent per-colour region-median comparison; its
     * diagnostic column counts do not determine that method's result.
     * pointwise/all and majority inspect [left, signalX), never the signal column.
     * pointwise/signal inspects only floor(signalX). No missing sample is interpolated.
     * any checks pairs chronologically and stops at the first full (including score) match.
     */
    fun detectIndicatorPattern(
        source: Bitmap,
        requireYellow: Boolean = false,
        dotToleranceScale: Float = 1f,
        minConfidence: Float = 0f,
        capitalWindowScale: Float = 0.4f,
        capitalMethod: String = "median",
        capitalRule: String = "all",
        signalSelection: String = "any",
        majorityThreshold: Float = 0.9f,
    ): IndicatorMatchResult {
        val method = capitalMethod.lowercase(java.util.Locale.ROOT)
        val rule = capitalRule.lowercase(java.util.Locale.ROOT)
        val selection = signalSelection.lowercase(java.util.Locale.ROOT)
        var diagnostics = IndicatorDiagnostics(
            capitalMethod = method, capitalRule = rule, signalSelection = selection,
            majorityThreshold = majorityThreshold,
        )
        if (method !in setOf("median", "pointwise") || rule !in setOf("all", "majority", "signal") ||
            selection !in setOf("latest", "any") || !dotToleranceScale.isFinite() ||
            !minConfidence.isFinite() || !capitalWindowScale.isFinite() ||
            !majorityThreshold.isFinite() || majorityThreshold !in 0f..1f) {
            return unknown("无法判断：识别参数无效", diagnostics)
        }
        if (source.width < 240 || source.height < 500) {
            return unknown("无法判断：图片尺寸过小", diagnostics)
        }
        val bitmap = scaleForAnalysis(source)
        try {
            val w = bitmap.width
            val h = bitmap.height
            if (h.toFloat() / w !in 1.75f..2.65f) {
                return unknown("无法判断：截图长宽比不适合当前固定版式", diagnostics)
            }
            val chartRight = (w * 0.685f).toInt().coerceIn(1, w)
            val trend = Region(0, (h * 0.565f).toInt(), chartRight, (h * 0.670f).toInt())
            val capital = Region((w * 0.02f).toInt(), (h * 0.700f).toInt(), chartRight, (h * 0.782f).toInt())
            diagnostics = diagnostics.copy(trendRegion = trend.normalized(w, h), capitalRegion = capital.normalized(w, h))
            // Cache classifications once: every candidate pair sees the identical image evidence.
            val pixels = IntArray(w * h)
            bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
            val classes = ByteArray(pixels.size) { classify(pixels[it]).toByte() }
            val trendBaselines = findBaselines(classes, w, trend)
            val capitalBaselines = findBaselines(classes, w, capital)
            val trendStructure = hasTrendStructure(trendBaselines, trend.height)
            val capitalHasTrendStructure = hasTrendStructure(capitalBaselines, capital.height)
            val dots = collectDots(classes, w, h, trend, trendBaselines)
            val pairs = pairDots(dots, w, h, trend, dotToleranceScale.coerceIn(0.1f, 5f))
            // Keep the actual local green evidence visible alongside its original connected blob.
            val diagnosticDots = dots + pairs.map { it.green }.filter { it !in dots }.distinct()
            val messages = mutableListOf<String>()
            if (!trendStructure) messages += "趋势区域缺少可核对的低位绿基线和另一条分离的蓝/红基线，可能是布局变化、遮挡或截图不完整。"
            if (capitalHasTrendStructure) messages += "资金区域出现趋势面板式多条水平基线，可能是两个面板顺序错位。"
            messages += "固定版式要求趋势在上、资金在下；结构校验只能拒绝明显异常，不能保证识别所有版式。"
            if (method == "median") messages += "方案一比较两种颜色各自的区域中位数；逐列满足/反例/缺失统计仅供诊断，不决定方案一结果。"
            diagnostics = diagnostics.copy(
                dotCandidates = diagnosticDots.sortedWith(compareBy<Dot> { it.blob.centerX }.thenBy { it.color.ordinal })
                    .map { it.toDiagnostic(trend, w, h) },
                trendPanelValid = trendStructure,
                capitalPanelValid = !capitalHasTrendStructure,
                candidatePairCount = pairs.size,
                messages = messages,
            )
            if (!trendStructure || capitalHasTrendStructure) {
                return unknown("无法判断：指标面板结构或顺序与当前识别版式不符", diagnostics.copy(capitalPanelValid = false))
            }
            if (pairs.isEmpty()) {
                return IndicatorMatchResult(
                    matched = false, trendDotsMatched = false, capitalLinesMatched = false, confidence = 0f,
                    redPoint = null, greenPoint = null, purpleYRatio = null, capitalGreenYRatio = null,
                    reason = "不符合：未找到可配对的底部红绿信号；没有信号位置，资金线未判断",
                    status = DetectionStatus.NO_MATCH, trendStatus = DetectionStatus.NO_MATCH,
                    capitalStatus = DetectionStatus.UNKNOWN,
                    diagnostics = diagnostics.copy(messages = messages + "候选点不等于已配对信号；无点时不再回退到图右侧判断资金。"),
                )
            }
            val ordered = if (selection == "latest") {
                listOf(pairs.maxWithOrNull(compareBy<DotPair> { it.signalX }.thenBy { -it.dx })!!)
            } else pairs.sortedWith(compareBy<DotPair> { it.signalX }.thenBy { it.dx })
            var selected: PairEvaluation? = null
            var firstUnknown: PairEvaluation? = null
            var checked = 0
            for (pair in ordered) {
                val evaluation = evaluateCapital(
                    classes, w, h, capital, pair.signalX, requireYellow,
                    capitalWindowScale.coerceIn(0.1f, 1f), method, rule, majorityThreshold,
                )
                val dotScore = (1f - pair.dx / pair.tolerance).coerceIn(0f, 1f)
                val score = if (evaluation.status == DetectionStatus.MATCH) {
                    (0.55f * dotScore + 0.45f * evaluation.lineScore).coerceIn(0f, 1f)
                } else (0.4f * dotScore + 0.3f * evaluation.lineScore).coerceIn(0f, 0.69f)
                val status = if (evaluation.status == DetectionStatus.MATCH && score < minConfidence.coerceIn(0f, 1f)) {
                    DetectionStatus.NO_MATCH
                } else evaluation.status
                val reason = if (evaluation.status == DetectionStatus.MATCH && status == DetectionStatus.NO_MATCH) {
                    "不符合：红绿点与资金规则满足，但规则分数低于设置门槛"
                } else evaluation.reason
                val current = PairEvaluation(pair, evaluation, status, score, reason)
                checked++
                if (selected == null) selected = current
                if (status == DetectionStatus.UNKNOWN && firstUnknown == null) firstUnknown = current
                if (status == DetectionStatus.MATCH) {
                    selected = current
                    break
                }
            }
            if (selected!!.status != DetectionStatus.MATCH && firstUnknown != null) selected = firstUnknown
            val chosen = selected!!
            val cap = chosen.capital
            val red = chosen.pair.red.blob
            val green = chosen.pair.green.blob
            val overallReason = if (selection == "any" && chosen.status != DetectionStatus.MATCH && checked > 1) {
                (if (chosen.status == DetectionStatus.UNKNOWN) "无法判断：所有信号均未确认满足，部分信号证据不足。" else "不符合：所有可配对信号均未满足。") + chosen.reason
            } else chosen.reason
            return IndicatorMatchResult(
                matched = chosen.status == DetectionStatus.MATCH,
                trendDotsMatched = true,
                capitalLinesMatched = cap.status == DetectionStatus.MATCH,
                confidence = chosen.score,
                redPoint = PointF(red.centerX / w, (red.centerY + trend.top) / h),
                greenPoint = PointF(green.centerX / w, (green.centerY + trend.top) / h),
                purpleYRatio = cap.purple.medianY?.let { (it + capital.top) / h },
                capitalGreenYRatio = cap.green.medianY?.let { (it + capital.top) / h },
                reason = overallReason,
                dotMatchPath = chosen.pair.path,
                status = chosen.status,
                trendStatus = DetectionStatus.MATCH,
                capitalStatus = cap.status,
                diagnostics = diagnostics.copy(
                    capitalWindow = cap.window.normalized(w, h), capitalStats = cap.stats,
                    columns = cap.columns, checkedPairCount = checked,
                    messages = messages + cap.reason +
                        (if (chosen.pair.path.endsWith("_local_pixels")) listOf("绿块粘连，按红点下方局部像素配对；绿点位置来自实际绿色像素，未按固定间距补点。") else emptyList()) +
                        "置信度为手工规则分数，不是正确率或成功概率。",
                ),
            )
        } finally {
            if (bitmap !== source) bitmap.recycle()
        }
    }

    private fun unknown(reason: String, diagnostics: IndicatorDiagnostics) = IndicatorMatchResult(
        false, false, false, 0f, null, null, null, null, reason,
        status = DetectionStatus.UNKNOWN, trendStatus = DetectionStatus.UNKNOWN,
        capitalStatus = DetectionStatus.UNKNOWN, diagnostics = diagnostics,
    )

    private fun Dot.toDiagnostic(region: Region, w: Int, h: Int) = IndicatorDotCandidate(
        RectF(blob.x.toFloat() / w, (blob.y + region.top).toFloat() / h,
            (blob.x + blob.width).toFloat() / w, (blob.y + blob.height + region.top).toFloat() / h),
        PointF(blob.centerX / w, (blob.centerY + region.top) / h), color, blob.split,
    )

    /** Only long, nearly continuous colour rows count as baselines; short text cannot supply one. */
    private fun findBaselines(classes: ByteArray, w: Int, region: Region): List<Baseline> {
        val result = mutableListOf<Baseline>()
        for (color in intArrayOf(1, 2, 5)) {
            var start = -1
            for (y in 0..region.height) {
                var count = 0
                var first = region.width
                var last = -1
                if (y < region.height) {
                    for (x in 0 until region.width) {
                        if (classes[(y + region.top) * w + x + region.left].toInt() == color) {
                            count++
                            first = min(first, x)
                            last = x
                        }
                    }
                }
                val continuous = last >= first && count >= region.width * 0.45f &&
                    count >= (last - first + 1) * 0.80f
                if (continuous && start < 0) start = y
                if (!continuous && start >= 0) {
                    // A broad solid band is an overlay/filled shape, not a thin chart baseline.
                    if (y - start <= max(4, (region.height * 0.035f).toInt())) result += Baseline(color, start, y - 1)
                    start = -1
                }
            }
        }
        return result
    }

    private fun hasTrendStructure(baselines: List<Baseline>, height: Int): Boolean =
        baselines.any { green -> green.color == 2 && green.center >= height * 0.55f &&
            baselines.any { other -> other.color in intArrayOf(1, 5) && abs(other.center - green.center) >= max(4f, height * 0.035f) }
        }

    private fun collectDots(
        classes: ByteArray, w: Int, h: Int, region: Region, baselines: List<Baseline>,
    ): List<Dot> {
        val minArea = max(3, (w * h * 0.000002f).toInt())
        val maxArea = max(80, (w * h * 0.0003f).toInt())
        val strictW = max(14, (w * 0.025f).toInt())
        val strictH = max(14, (h * 0.012f).toInt())
        val relaxedW = max(28, (w * 0.055f).toInt())
        val relaxedH = max(20, (h * 0.020f).toInt())
        val result = mutableListOf<Dot>()
        for (color in 1..2) {
            val mask = ByteArray(region.width * region.height)
            for (y in 0 until region.height) for (x in 0 until region.width) {
                if (classes[(y + region.top) * w + x + region.left].toInt() == color) mask[y * region.width + x] = 1
            }
            // Remove true chart baselines before components, so an attached marker is not
            // discarded merely because the whole baseline exceeds maxArea.
            val removedRows = baselines.filter { it.color == color }.flatMap { it.firstRow..it.lastRow }.toSet()
            for (y in removedRows) for (x in 0 until region.width) mask[y * region.width + x] = 0
            val components = findComponents(mask, region.width, region.height)
            for (component in components) {
                val pieces = if (component.width > strictW && component.height <= relaxedH) {
                    splitConnectedMarkers(component, region.width, strictW)
                } else listOf(component)
                for (raw in pieces) {
                    val touchesRemovedLine = removedRows.any { it in (raw.y - 1)..(raw.y + raw.height) }
                    val blob = if (touchesRemovedLine) raw.copy(split = true) else raw
                    if (blob.area !in minArea..maxArea) continue
                    if (blob.y <= region.height * 0.55f || blob.width < 2 || blob.height < 2) continue
                    val ratio = blob.width.toFloat() / blob.height
                    val fill = blob.area.toFloat() / (blob.width * blob.height)
                    val strict = blob.y > region.height * 0.58f && blob.width <= strictW && blob.height <= strictH &&
                        ratio in 0.30f..3.5f && fill >= 0.25f
                    val relaxed = blob.width <= relaxedW && blob.height <= relaxedH &&
                        ratio in 0.25f..4.5f && fill >= 0.22f
                    if (!strict && !relaxed) continue
                    // Long, thin fragments and one-pixel line crossings are not dot markers.
                    if (min(blob.width, blob.height) <= 2 && max(blob.width, blob.height) >= 8) continue
                    result += Dot(blob, if (color == 1) IndicatorDotColor.RED else IndicatorDotColor.GREEN, strict)
                }
            }
        }
        return result.sortedWith(compareBy<Dot> { it.blob.centerX }.thenBy { it.color.ordinal })
    }

    /** Split only at observed thin bridges between thick lobes; never invent regularly spaced dots. */
    private fun splitConnectedMarkers(blob: Blob, stride: Int, nominalWidth: Int): List<Blob> {
        val counts = IntArray(blob.width)
        blob.pixels.forEach { counts[it % stride - blob.x]++ }
        val peak = counts.maxOrNull() ?: return listOf(blob)
        if (peak < 4) return listOf(blob)
        val bridgeMax = max(1, (peak * 0.35f).toInt())
        val lobes = mutableListOf<IntRange>()
        var start = -1
        for (x in 0..counts.size) {
            val thick = x < counts.size && counts[x] > bridgeMax
            if (thick && start < 0) start = x
            if (!thick && start >= 0) {
                if (x - start >= 2) lobes += start until x
                start = -1
            }
        }
        if (lobes.size < 2 || lobes.any { it.last - it.first + 1 > nominalWidth * 2 }) return listOf(blob)
        val cuts = lobes.zipWithNext { left, right -> (left.last + right.first + 1) / 2 }
        val pieces = mutableListOf<Blob>()
        var left = 0
        for (right in cuts + blob.width) {
            val points = blob.pixels.filter { (it % stride - blob.x) in left until right }.toIntArray()
            if (points.isNotEmpty()) pieces += toBlob(points, stride, split = true)
            left = right
        }
        return pieces
    }

    private fun pairDots(
        dots: List<Dot>, analysisWidth: Int, analysisHeight: Int, trend: Region, toleranceScale: Float,
    ): List<DotPair> {
        val reds = dots.filter { it.color == IndicatorDotColor.RED }
        val greens = dots.filter { it.color == IndicatorDotColor.GREEN }
        val strictTolerance = max(4f, trend.right * 0.012f) * toleranceScale
        val relaxedTolerance = max(6f, trend.right * 0.020f) * toleranceScale
        val verticalBand = max(8f, trend.height * 0.22f)
        return reds.mapNotNull { red ->
            // Each red anchor defines one chronological signal. Pick its best nearby green
            // candidate so the same red marker cannot create several contradictory signals.
            greens.mapNotNull greenCandidate@ { originalGreen ->
                val tolerance = if (red.strict) strictTolerance else relaxedTolerance
                val green = if (abs(red.blob.centerX - originalGreen.blob.centerX) > tolerance) {
                    localGreenForAnchor(red, originalGreen, trend.width, analysisWidth, analysisHeight)
                        ?: return@greenCandidate null
                } else originalGreen
                val dx = abs(red.blob.centerX - green.blob.centerX)
                if (dx > tolerance || abs(red.blob.centerY - green.blob.centerY) > verticalBand) null
                else DotPair(red, green, dx, tolerance, when {
                    red.strict && green.strict -> "A_strict"
                    red.strict -> "B_relaxed_green"
                    else -> "C_relaxed_both"
                } + when {
                    green !== originalGreen -> "_local_pixels"
                    red.blob.split || green.blob.split -> "_split"
                    else -> ""
                })
            }.minWithOrNull(compareBy<DotPair> { it.dx }.thenBy { if (it.green.strict) 0 else 1 })
        }
    }

    /**
     * A short, solid green marker group may have no thin bridge to split. Recover only
     * the pixels directly below an existing strict red anchor, never spaced-out dots.
     * The source blob must already pass collectDots and these tighter shape checks.
     */
    private fun localGreenForAnchor(
        red: Dot, green: Dot, stride: Int, analysisWidth: Int, analysisHeight: Int,
    ): Dot? {
        if (!red.strict || green.strict) return null
        val anchor = red.blob
        val source = green.blob
        val strictWidth = max(14, (analysisWidth * 0.025f).toInt())
        val maxWidth = max(28, (analysisWidth * 0.055f).toInt())
        val maxHeight = max(14, (analysisHeight * 0.012f).toInt())
        if (source.width <= strictWidth || source.width > maxWidth || source.height !in 3..maxHeight) return null
        if (source.height < anchor.height * 0.5f || source.height > anchor.height * 2f) return null
        if (source.area.toFloat() / (source.width * source.height) < 0.65f) return null
        // Require a nearby marker below the red, not another curve at a similar x.
        val gap = source.y - (anchor.y + anchor.height)
        if (source.centerY <= anchor.centerY || gap !in -1..max(2, min(anchor.height, source.height) / 2)) return null
        if (anchor.centerX < source.x || anchor.centerX >= source.x + source.width) return null
        val left = max(anchor.x, source.x)
        val right = min(anchor.x + anchor.width, source.x + source.width)
        if (right - left < max(2, ceil(anchor.width * 0.6f).toInt())) return null
        val pixels = source.pixels.filter { it % stride in left until right }.toIntArray()
        if (pixels.isEmpty()) return null
        val counts = IntArray(right - left)
        pixels.forEach { counts[it % stride - left]++ }
        if (counts.any { it < 2 }) return null
        val local = toBlob(pixels, stride, split = true)
        if (local.height < 3 || local.area.toFloat() / (local.width * local.height) < 0.65f) return null
        return green.copy(blob = local)
    }

    private fun evaluateCapital(
        classes: ByteArray, w: Int, h: Int, region: Region, signalX: Float,
        requireYellow: Boolean, windowScale: Float, method: String, rule: String, majorityThreshold: Float,
    ): CapitalEvaluation {
        val signalColumn = signalX.toInt().coerceIn(0, w - 1)
        val singleColumn = method == "pointwise" && rule == "signal"
        val right = signalColumn.coerceIn(region.left, region.right)
        val left = (right - (right - region.left) * windowScale).toInt().coerceIn(region.left, right)
        val window = if (singleColumn) Region(signalColumn, region.top, signalColumn + 1, region.bottom)
            else Region(left, region.top, right, region.bottom)
        val count = window.width
        val requiredGap = max(3f, h * 0.003f)
        val maxStroke = max(6, (region.height * 0.08f).toInt())
        val diagnostics = mutableListOf<CapitalColumnDiagnostic>()
        val purpleMedians = mutableListOf<Float>()
        val greenMedians = mutableListOf<Float>()
        val yellowMedians = mutableListOf<Float>()
        for (x in window.left until window.right) {
            val purpleHits = colourRows(classes, w, x, region, 3)
            val greenHits = colourRows(classes, w, x, region, 2)
            val yellowHits = colourRows(classes, w, x, region, 4)
            if (purpleHits.isNotEmpty()) purpleMedians += purpleHits[purpleHits.size / 2].toFloat()
            if (greenHits.isNotEmpty()) greenMedians += greenHits[greenHits.size / 2].toFloat()
            if (yellowHits.isNotEmpty()) yellowMedians += yellowHits[yellowHits.size / 2].toFloat()
            // Pointwise evidence must be a single thin, unambiguous run. Multiple separated
            // runs (text, a second curve, overlays) are missing evidence, not a guessed median.
            val purple = singleRunCenter(purpleHits, maxStroke)
            val green = singleRunCenter(greenHits, maxStroke)
            val yellow = singleRunCenter(yellowHits, maxStroke)
            val purpleViolates = purple != null && green != null && purple + requiredGap >= green
            val yellowViolates = requireYellow && yellow != null && green != null && yellow + requiredGap >= green
            val missing = purple == null || green == null || (requireYellow && yellow == null)
            val columnStatus = when {
                purpleViolates || yellowViolates -> CapitalColumnStatus.VIOLATING
                missing -> CapitalColumnStatus.MISSING
                else -> CapitalColumnStatus.SATISFIED
            }
            diagnostics += CapitalColumnDiagnostic(
                x = x.toFloat() / w, status = columnStatus,
                purpleY = purple?.let { (it + region.top) / h },
                greenY = green?.let { (it + region.top) / h },
                yellowY = yellow?.let { (it + region.top) / h },
                hasMissingData = missing,
            )
        }
        val purple = track(purpleMedians)
        val green = track(greenMedians)
        val yellow = track(yellowMedians)
        val stats = capitalStats(diagnostics)
        val status: DetectionStatus
        val reason: String
        if (signalColumn !in region.left until region.right || count <= 0) {
            status = DetectionStatus.UNKNOWN
            reason = "无法判断：信号位于资金绘图区边缘，左侧没有可用区间"
        } else if (method == "median") {
            val minimum = max(MIN_INTERVAL_COLUMNS, ceil(count * 0.5f).toInt())
            val enough = purple.columns >= minimum && green.columns >= minimum && (!requireYellow || yellow.columns >= minimum)
            if (!enough) {
                status = DetectionStatus.UNKNOWN
                reason = "无法判断：方案一每条所需资金线至少需覆盖窗口一半且不少于3列，当前数据不足"
            } else {
                val greenY = green.medianY!!
                val above = purple.medianY!! + requiredGap < greenY &&
                    (!requireYellow || yellow.medianY!! + requiredGap < greenY)
                status = if (above) DetectionStatus.MATCH else DetectionStatus.NO_MATCH
                reason = if (above) "符合：红绿点配对，资金区域中位数规则满足" else "不符合：资金区域中位数上下关系未满足"
            }
        } else {
            val ruleColumns = diagnostics.map {
                if (rule == "majority" && it.hasMissingData) CapitalColumnStatus.MISSING else it.status
            }
            status = CapitalRuleEvaluator.evaluate(ruleColumns, rule, majorityThreshold, MIN_INTERVAL_COLUMNS)
            reason = when (status) {
                DetectionStatus.MATCH -> when (rule) {
                    "all" -> "符合：信号左侧区间每一列均可识别且满足资金规则"
                    "majority" -> "符合：资金区间完整可读，满足列比例达到设定阈值"
                    else -> "符合：信号当列资金关系满足"
                }
                DetectionStatus.NO_MATCH -> if (rule == "majority") "不符合：完整区间的满足列比例低于设定阈值" else "不符合：存在可见的资金线反例"
                DetectionStatus.UNKNOWN -> if (stats.missingColumns > 0) "无法判断：所需资金区间存在缺失、遮挡或曲线归属不明的列" else "无法判断：信号左侧有效区间不足3列"
            }
        }
        val gap = if (status == DetectionStatus.MATCH && purple.medianY != null && green.medianY != null) {
            ((green.medianY - purple.medianY) / region.height).coerceAtLeast(0f)
        } else 0f
        val coverage = if (count == 0) 0f else min(purple.columns, green.columns).toFloat() / count
        val score = (gap * 2.2f + coverage * 0.35f).coerceIn(0f, 1f)
        return CapitalEvaluation(status, window, stats, diagnostics, purple, green, score, reason)
    }

    private fun colourRows(classes: ByteArray, w: Int, x: Int, region: Region, color: Int): List<Int> {
        val rows = mutableListOf<Int>()
        for (y in region.top until region.bottom) if (classes[y * w + x].toInt() == color) rows += y - region.top
        return rows
    }

    private fun singleRunCenter(rows: List<Int>, maxStroke: Int): Float? {
        if (rows.isEmpty() || rows.size > maxStroke) return null
        for (i in 1 until rows.size) if (rows[i] != rows[i - 1] + 1) return null
        return (rows.first() + rows.last()) / 2f
    }

    private fun track(values: List<Float>): Track = if (values.isEmpty()) Track(null, 0)
        else Track(values.sorted()[values.size / 2], values.size)

    private fun capitalStats(columns: List<CapitalColumnDiagnostic>): CapitalStats {
        var run = 0
        var longest = 0
        for (column in columns) {
            run = if (column.status == CapitalColumnStatus.SATISFIED) run + 1 else 0
            longest = max(longest, run)
        }
        val missing = columns.count { it.hasMissingData }
        return CapitalStats(
            totalColumns = columns.size, validColumns = columns.size - missing,
            satisfiedColumns = columns.count { it.status == CapitalColumnStatus.SATISFIED },
            longestSatisfiedRun = longest, missingColumns = missing,
            violatingColumns = columns.count { it.status == CapitalColumnStatus.VIOLATING },
        )
    }

    private fun findComponents(source: ByteArray, width: Int, height: Int): List<Blob> {
        val mask = source.copyOf()
        val queue = IntArray(mask.size)
        val result = mutableListOf<Blob>()
        for (start in mask.indices) {
            if (mask[start].toInt() == 0) continue
            var head = 0
            var tail = 0
            queue[tail++] = start
            mask[start] = 0
            while (head < tail) {
                val index = queue[head++]
                val x = index % width
                val y = index / width
                for (dy in -1..1) {
                    val ny = y + dy
                    if (ny !in 0 until height) continue
                    for (dx in -1..1) {
                        if (dx == 0 && dy == 0) continue
                        val nx = x + dx
                        if (nx !in 0 until width) continue
                        val next = ny * width + nx
                        if (mask[next].toInt() != 0) {
                            mask[next] = 0
                            queue[tail++] = next
                        }
                    }
                }
            }
            result += toBlob(queue.copyOf(tail), width)
        }
        return result
    }

    private fun toBlob(points: IntArray, stride: Int, split: Boolean = false): Blob {
        var minX = Int.MAX_VALUE
        var minY = Int.MAX_VALUE
        var maxX = -1
        var maxY = -1
        var sumX = 0L
        var sumY = 0L
        for (p in points) {
            val x = p % stride
            val y = p / stride
            minX = min(minX, x)
            maxX = max(maxX, x)
            minY = min(minY, y)
            maxY = max(maxY, y)
            sumX += x
            sumY += y
        }
        return Blob(minX, minY, maxX - minX + 1, maxY - minY + 1, points.size,
            sumX.toFloat() / points.size, sumY.toFloat() / points.size, points, split)
    }

    private fun scaleForAnalysis(source: Bitmap): Bitmap {
        if (source.width <= MAX_ANALYSIS_WIDTH) return source
        val height = (source.height * (MAX_ANALYSIS_WIDTH.toFloat() / source.width)).toInt()
        return Bitmap.createScaledBitmap(source, MAX_ANALYSIS_WIDTH, height, true)
    }

    /** Returns 1=red, 2=green, 3=purple, 4=yellow/orange, 5=blue/cyan, 0=other. */
    private fun classify(color: Int): Int {
        val r = Color.red(color)
        val g = Color.green(color)
        val b = Color.blue(color)
        val maximum = max(r, max(g, b))
        val delta = maximum - min(r, min(g, b))
        if (maximum < 70 || delta == 0) return 0
        val saturation = delta.toFloat() / maximum
        var hue = when (maximum) {
            r -> 60f * (g - b).toFloat() / delta
            g -> 60f * (2f + (b - r).toFloat() / delta)
            else -> 60f * (4f + (r - g).toFloat() / delta)
        }
        if (hue < 0f) hue += 360f
        return when {
            (hue <= 16f || hue >= 344f) && saturation >= 0.45f && maximum >= 90 -> 1
            hue in 80f..176f && saturation >= 0.40f -> 2
            hue in 268f..340f && saturation >= 0.25f && maximum >= 90 -> 3
            hue in 24f..66f && saturation >= 0.40f && maximum >= 110 -> 4
            hue in 178f..260f && saturation >= 0.35f && maximum >= 80 -> 5
            else -> 0
        }
    }

    fun drawAnnotation(source: Bitmap, result: IndicatorMatchResult): Bitmap {
        val output = source.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(output)
        val scale = output.width / 720f
        val stroke = max(3f, 4f * scale)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = stroke
        }
        fun circle(point: PointF?, color: Int) {
            if (point == null) return
            paint.color = color
            canvas.drawCircle(point.x * output.width, point.y * output.height, max(14f, 18f * scale), paint)
        }
        circle(result.redPoint, Color.RED)
        circle(result.greenPoint, Color.rgb(0, 170, 40))
        paint.color = if (result.matched) Color.rgb(0, 150, 45) else Color.rgb(210, 40, 45)
        paint.style = Paint.Style.FILL
        val textSize = max(28f, 34f * scale)
        paint.textSize = textSize
        paint.isFakeBoldText = true
        val label = when (result.status) {
            DetectionStatus.MATCH -> "匹配  ${(result.confidence * 100).toInt()}分"
            DetectionStatus.NO_MATCH -> "不匹配"
            DetectionStatus.UNKNOWN -> "无法判断"
        }
        val padding = 14f * scale
        val textWidth = paint.measureText(label)
        val top = 18f * scale
        val background = Paint().apply { color = 0xCCFFFFFF.toInt() }
        canvas.drawRect(12f * scale, top, 12f * scale + textWidth + padding * 2, top + textSize + padding * 2, background)
        canvas.drawText(label, 12f * scale + padding, top + textSize + padding * 0.65f, paint)
        return output
    }

}
