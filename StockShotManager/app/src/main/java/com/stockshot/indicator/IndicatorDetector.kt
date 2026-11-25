package com.stockshot.indicator

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PointF
import kotlin.math.abs
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
    val dotMatchPath: String? = null
)

object IndicatorDetector {
    private const val MAX_ANALYSIS_WIDTH = 720

    private data class Blob(
        val x: Int,
        val y: Int,
        val width: Int,
        val height: Int,
        val area: Int,
        val centerX: Float,
        val centerY: Float
    )

    private data class Track(val medianY: Float?, val columns: Int)

    private data class DotMatch(
        val red: Blob,
        val green: Blob,
        val dx: Float,
        val tol: Float,
        val path: String
    )

    /**
     * Detects the combined indicator pattern in a quote screenshot.
     * The trend panel is scanned across its complete chart width. No time/x position is fixed.
     *
     * requireYellow=true 时资金博弈需紫、黄两条都在绿线上；否则仅要求紫线在绿线上（默认）。
     */
    fun detectIndicatorPattern(
        source: Bitmap,
        requireYellow: Boolean = false,
        dotToleranceScale: Float = 1f,
        minConfidence: Float = 0f,
        capitalWindowScale: Float = 0.4f
    ): IndicatorMatchResult {
        if (source.width < 240 || source.height < 500) {
            return failed("图片尺寸过小，无法识别")
        }
        val bitmap = scaleForAnalysis(source)
        val w = bitmap.width
        val h = bitmap.height
        val aspect = h.toFloat() / w
        if (aspect !in 1.75f..2.65f) {
            if (bitmap !== source) bitmap.recycle()
            return failed("截图长宽比与已标定版式不符")
        }

        val chartRight = (w * 0.685f).toInt().coerceIn(1, w)
        val trendY0 = (h * 0.565f).toInt()
        val trendY1 = (h * 0.670f).toInt()
        val trendH = trendY1 - trendY0
        val redMask = ByteArray(chartRight * trendH)
        val greenMask = ByteArray(chartRight * trendH)

        for (y in trendY0 until trendY1) {
            val row = (y - trendY0) * chartRight
            for (x in 0 until chartRight) {
                when (classify(bitmap.getPixel(x, y))) {
                    1 -> redMask[row + x] = 1
                    2 -> greenMask[row + x] = 1
                }
            }
        }

        val minArea = max(3, (w * h * 0.000002f).toInt())
        val maxArea = max(80, (w * h * 0.0003f).toInt())
        val maxBlobW = max(14, (w * 0.025f).toInt())
        val maxBlobH = max(14, (h * 0.012f).toInt())
        fun isDot(blob: Blob): Boolean {
            val ratio = blob.width.toFloat() / max(1, blob.height)
            val fill = blob.area.toFloat() / max(1, blob.width * blob.height)
            return blob.y > trendH * 0.58f && blob.width <= maxBlobW &&
                blob.height <= maxBlobH && ratio in 0.25f..4f && fill >= 0.22f
        }

        // 宽容差点：底部绿点常被连成宽色块，严格 isDot 会漏掉，这里放宽宽/高与填充率。
        val relaxedMaxBlobW = max(28, (w * 0.055f).toInt())
        val relaxedMaxBlobH = max(20, (h * 0.020f).toInt())
        fun isRelaxedDot(blob: Blob): Boolean {
            val ratio = blob.width.toFloat() / max(1, blob.height)
            val fill = blob.area.toFloat() / max(1, blob.width * blob.height)
            return blob.y > trendH * 0.55f && blob.width <= relaxedMaxBlobW &&
                blob.height <= relaxedMaxBlobH && ratio in 0.2f..6f && fill >= 0.18f
        }

        val allReds = findComponents(redMask, chartRight, trendH, minArea, maxArea)
        val allGreens = findComponents(greenMask, chartRight, trendH, minArea, maxArea)
        val reds = allReds.filter(::isDot)
        val greens = allGreens.filter(::isDot)
        val redsRelaxed = allReds.filter(::isRelaxedDot)
        val greensRelaxed = allGreens.filter(::isRelaxedDot)

        val tolScale = dotToleranceScale.coerceIn(0.1f, 5f)
        val tolerance = max(4f, chartRight * 0.012f) * tolScale
        val relaxedTolerance = max(6f, chartRight * 0.02f) * tolScale
        val band = trendH * 0.22f
        // B 为主（严格红点锚定 + 宽容差绿块），C 兜底（双宽容差）；A 最严格时优先。
        val match = pairDots(reds, greens, tolerance, "A_strict", Float.MAX_VALUE)
            ?: pairDots(reds, greensRelaxed, tolerance, "B_relaxed_green", band)
            ?: pairDots(redsRelaxed, greensRelaxed, relaxedTolerance, "C_relaxed_both", band)

        val bestRed = match?.red
        val bestGreen = match?.green
        val bestDx = match?.dx ?: Float.MAX_VALUE
        val usedTol = match?.tol ?: tolerance
        val dotsMatched = match != null

        val plotY0 = (h * 0.700f).toInt()
        val plotY1 = (h * 0.782f).toInt()
        val plotX0 = (w * 0.02f).toInt()
        // 只分析红绿点左侧一段时间的资金线数据，忽略红绿点右侧的数据
        val dotX = bestRed?.centerX ?: bestGreen?.centerX
        val windowRight = (dotX?.toInt() ?: chartRight).coerceIn(plotX0 + 1, chartRight)
        val capWinScale = capitalWindowScale.coerceIn(0.1f, 1f)
        val windowSpan = windowRight - plotX0
        val windowLeft = (windowRight - windowSpan * capWinScale).toInt().coerceIn(plotX0, windowRight - 1)
        val windowCols = max(1, windowRight - windowLeft)
        val purpleTrack = collectTrack(bitmap, windowLeft, windowRight, plotY0, plotY1, 3)
        val capitalGreenTrack = collectTrack(bitmap, windowLeft, windowRight, plotY0, plotY1, 2)
        val yellowTrack = collectTrack(bitmap, windowLeft, windowRight, plotY0, plotY1, 4)
        val minColumns = max(20, (windowCols * 0.08f).toInt())
        val requiredGap = max(3f, h * 0.003f)
        val greenReady = capitalGreenTrack.medianY != null && capitalGreenTrack.columns >= minColumns
        val purpleAbove = purpleTrack.medianY != null && greenReady &&
            purpleTrack.columns >= minColumns &&
            purpleTrack.medianY + requiredGap < capitalGreenTrack.medianY!!
        val yellowAbove = yellowTrack.medianY != null && greenReady &&
            yellowTrack.columns >= minColumns &&
            yellowTrack.medianY + requiredGap < capitalGreenTrack.medianY!!
        val capitalMatched = if (requireYellow) purpleAbove && yellowAbove else purpleAbove

        val dotConfidence = if (dotsMatched) (1f - bestDx / usedTol).coerceIn(0f, 1f) else 0f
        val lineGap = if (capitalMatched) {
            (capitalGreenTrack.medianY!! - purpleTrack.medianY!!) / max(1f, (plotY1 - plotY0).toFloat())
        } else 0f
        val lineCoverage = min(purpleTrack.columns, capitalGreenTrack.columns).toFloat() /
            max(1, windowCols)
        val lineConfidence = (lineGap * 2.2f + lineCoverage * 0.35f).coerceIn(0f, 1f)
        val confidence = if (dotsMatched && capitalMatched) {
            (dotConfidence * 0.55f + lineConfidence * 0.45f).coerceIn(0f, 1f)
        } else (dotConfidence * 0.4f + lineConfidence * 0.3f).coerceIn(0f, 0.69f)
        val baseMatched = dotsMatched && capitalMatched
        val confGate = minConfidence.coerceIn(0f, 1f)
        val matched = baseMatched && confidence >= confGate

        val capitalReason = if (requireYellow) "资金博弈（紫、黄两条均在绿线上）" else "资金博弈（紫线在绿线上）"
        val gatedReason = "\u4e0d\u7b26\u5408\uff1a\u7f6e\u4fe1\u5ea6\u4f4e\u4e8e\u9608\u503c"
        val reason = if (baseMatched && !matched) gatedReason else when {
            dotsMatched && capitalMatched -> "符合：底部红绿点横向重合，且" + capitalReason
            !dotsMatched && !capitalMatched -> "不符合：未找到重合的底部红绿点，资金线位置也不符合"
            !dotsMatched -> "不符合：未找到横向重合的底部红绿点"
            else -> "不符合：" + capitalReason + " 未满足"
        }
        val result = IndicatorMatchResult(
            matched = matched,
            trendDotsMatched = dotsMatched,
            capitalLinesMatched = capitalMatched,
            confidence = confidence,
            redPoint = bestRed?.let { PointF(it.centerX / w, (it.centerY + trendY0) / h) },
            greenPoint = bestGreen?.let { PointF(it.centerX / w, (it.centerY + trendY0) / h) },
            purpleYRatio = purpleTrack.medianY?.let { (it + plotY0) / h },
            capitalGreenYRatio = capitalGreenTrack.medianY?.let { (it + plotY0) / h },
            reason = reason,
            dotMatchPath = match?.path
        )
        if (bitmap !== source) bitmap.recycle()
        return result
    }

    private fun pairDots(
        reds: List<Blob>,
        greens: List<Blob>,
        tolerance: Float,
        path: String,
        verticalBand: Float
    ): DotMatch? {
        var bestRed: Blob? = null
        var bestGreen: Blob? = null
        var bestDx = Float.MAX_VALUE
        var bestScore = Float.NEGATIVE_INFINITY
        for (red in reds) {
            for (green in greens) {
                val dx = abs(red.centerX - green.centerX)
                if (dx > tolerance) continue
                if (abs(red.centerY - green.centerY) > verticalBand) continue
                val score = (red.centerY + green.centerY) * 2f - dx
                if (score > bestScore) {
                    bestScore = score
                    bestDx = dx
                    bestRed = red
                    bestGreen = green
                }
            }
        }
        val r = bestRed ?: return null
        val g = bestGreen ?: return null
        return DotMatch(r, g, bestDx, tolerance, path)
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
        val label = if (result.matched) "匹配  ${(result.confidence * 100).toInt()}%" else "不匹配"
        val padding = 14f * scale
        val textWidth = paint.measureText(label)
        val top = 18f * scale
        val background = Paint().apply { color = 0xCCFFFFFF.toInt() }
        canvas.drawRect(12f * scale, top, 12f * scale + textWidth + padding * 2, top + textSize + padding * 2, background)
        canvas.drawText(label, 12f * scale + padding, top + textSize + padding * 0.65f, paint)
        return output
    }

    private fun failed(reason: String) = IndicatorMatchResult(
        false, false, false, 0f, null, null, null, null, reason
    )

    private fun scaleForAnalysis(source: Bitmap): Bitmap {
        if (source.width <= MAX_ANALYSIS_WIDTH) return source
        val targetHeight = (source.height * (MAX_ANALYSIS_WIDTH.toFloat() / source.width)).toInt()
        return Bitmap.createScaledBitmap(source, MAX_ANALYSIS_WIDTH, targetHeight, true)
    }

    /** Returns 1=red, 2=green, 3=purple, 4=yellow, 0=other. */
    private fun classify(color: Int): Int {
        val r = Color.red(color)
        val g = Color.green(color)
        val b = Color.blue(color)
        val maxValue = max(r, max(g, b))
        val minValue = min(r, min(g, b))
        val delta = maxValue - minValue
        if (maxValue < 70 || delta == 0) return 0
        val saturation = delta.toFloat() / maxValue
        var hue = when (maxValue) {
            r -> 60f * ((g - b).toFloat() / delta)
            g -> 60f * (2f + (b - r).toFloat() / delta)
            else -> 60f * (4f + (r - g).toFloat() / delta)
        }
        if (hue < 0f) hue += 360f
        return when {
            (hue <= 14f || hue >= 346f) && saturation >= 0.50f && maxValue >= 90 -> 1
            hue in 84f..176f && saturation >= 0.45f && maxValue >= 70 -> 2
            hue in 274f..338f && saturation >= 0.27f && maxValue >= 90 -> 3
            hue in 28f..52f && saturation >= 0.45f && maxValue >= 120 -> 4
            else -> 0
        }
    }

    private fun findComponents(
        source: ByteArray,
        width: Int,
        height: Int,
        minArea: Int,
        maxArea: Int
    ): List<Blob> {
        val mask = source.copyOf()
        val queue = IntArray(mask.size)
        val result = mutableListOf<Blob>()
        for (start in mask.indices) {
            if (mask[start].toInt() == 0) continue
            var head = 0
            var tail = 0
            queue[tail++] = start
            mask[start] = 0
            var area = 0
            var sumX = 0L
            var sumY = 0L
            var minX = width
            var minY = height
            var maxX = 0
            var maxY = 0
            while (head < tail) {
                val index = queue[head++]
                val x = index % width
                val y = index / width
                area++
                sumX += x
                sumY += y
                minX = min(minX, x)
                maxX = max(maxX, x)
                minY = min(minY, y)
                maxY = max(maxY, y)
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
            if (area in minArea..maxArea) {
                result += Blob(
                    minX, minY, maxX - minX + 1, maxY - minY + 1, area,
                    sumX.toFloat() / area, sumY.toFloat() / area
                )
            }
        }
        return result
    }

    private fun collectTrack(
        bitmap: Bitmap,
        x0: Int,
        x1: Int,
        y0: Int,
        y1: Int,
        wantedClass: Int
    ): Track {
        val columnMedians = ArrayList<Int>(x1 - x0)
        val ys = IntArray(y1 - y0)
        for (x in x0 until x1) {
            var count = 0
            for (y in y0 until y1) {
                if (classify(bitmap.getPixel(x, y)) == wantedClass) ys[count++] = y - y0
            }
            if (count > 0) columnMedians += ys[count / 2]
        }
        if (columnMedians.isEmpty()) return Track(null, 0)
        columnMedians.sort()
        return Track(columnMedians[columnMedians.size / 2].toFloat(), columnMedians.size)
    }
}
