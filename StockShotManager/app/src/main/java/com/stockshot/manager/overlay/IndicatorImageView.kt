package com.stockshot.manager.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import com.stockshot.indicator.CapitalColumnStatus
import com.stockshot.indicator.IndicatorDotColor
import com.stockshot.indicator.IndicatorMatchResult
import kotlin.math.min

/** Draws diagnostics on the exact captured image; underlying app scrolling cannot move the evidence. */
@SuppressLint("ViewConstructor")
internal class IndicatorImageView(context: Context) : View(context) {
    var bitmap: Bitmap? = null
        set(value) { field = value; reset() }
    var result: IndicatorMatchResult? = null
        set(value) { field = value; invalidate() }
    var annotations: Boolean = true
        set(value) { field = value; invalidate() }
    private var zoom = 1f
    private var panX = 0f
    private var panY = 0f
    private val pen = Paint(Paint.ANTI_ALIAS_FLAG)
    private val scaleGesture = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val old = zoom
            zoom = (zoom * detector.scaleFactor).coerceIn(1f, 8f)
            val ratio = zoom / old
            panX = detector.focusX - width / 2f - (detector.focusX - width / 2f - panX) * ratio
            panY = detector.focusY - height / 2f - (detector.focusY - height / 2f - panY) * ratio
            if (zoom == 1f) { panX = 0f; panY = 0f }
            invalidate()
            return true
        }
    })
    private val gesture = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean = true
        override fun onDoubleTap(e: MotionEvent): Boolean { reset(); return true }
        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            if (!scaleGesture.isInProgress && zoom > 1f) {
                panX -= dx; panY -= dy; invalidate()
            }
            return true
        }
    })
    init { setBackgroundColor(Color.rgb(26, 28, 31)); contentDescription = "检测截图，可双指缩放查看候选点和资金窗口" }
    fun reset() { zoom = 1f; panX = 0f; panY = 0f; invalidate() }
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        parent?.requestDisallowInterceptTouchEvent(true)
        scaleGesture.onTouchEvent(event)
        gesture.onTouchEvent(event)
        return true
    }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val b = bitmap ?: return
        if (b.isRecycled) return
        val scale = min(width.toFloat() / b.width, height.toFloat() / b.height) * zoom
        canvas.save()
        canvas.translate(width / 2f + panX - b.width * scale / 2f, height / 2f + panY - b.height * scale / 2f)
        canvas.scale(scale, scale)
        canvas.drawBitmap(b, 0f, 0f, null)
        if (annotations) result?.let { drawEvidence(canvas, b, it, scale) }
        canvas.restore()
    }
    private fun drawEvidence(canvas: Canvas, b: Bitmap, r: IndicatorMatchResult, scale: Float) {
        val density = resources.displayMetrics.density
        val stroke = (2f * density / scale).coerceAtMost(b.width * .012f)
        fun rect(v: RectF?, color: Int, label: String? = null) {
            if (v == null) return
            val box = RectF(v.left * b.width, v.top * b.height, v.right * b.width, v.bottom * b.height)
            pen.color = color; pen.strokeWidth = stroke; pen.style = Paint.Style.STROKE
            canvas.drawRect(box, pen)
            if (label != null) {
                pen.style = Paint.Style.FILL
                pen.textSize = (12f * density / scale).coerceAtMost(b.width * .035f)
                canvas.drawText(label, box.left + stroke, box.top + pen.textSize, pen)
            }
        }
        val d = r.diagnostics
        rect(d.trendRegion, Color.CYAN, "趋势扫描")
        rect(d.capitalRegion, Color.rgb(60, 130, 255), "资金扫描")
        rect(d.capitalWindow, Color.rgb(255, 140, 0), "资金窗口")
        d.dotCandidates.forEach { candidate ->
            rect(candidate.bounds, if (candidate.color == IndicatorDotColor.RED) Color.RED else Color.rgb(0, 160, 40))
            if (candidate.split) {
                pen.style = Paint.Style.FILL; pen.textSize = stroke * 5
                canvas.drawText("拆", candidate.bounds.left * b.width, candidate.bounds.top * b.height - stroke, pen)
            }
        }
        listOfNotNull(r.redPoint, r.greenPoint).forEach { point ->
            pen.style = Paint.Style.STROKE; pen.strokeWidth = stroke * 2; pen.color = Color.BLACK
            val radius = b.width * .012f
            canvas.drawCircle(point.x * b.width, point.y * b.height, radius, pen)
            pen.strokeWidth = stroke; pen.color = Color.WHITE
            canvas.drawCircle(point.x * b.width, point.y * b.height, radius, pen)
        }
        d.capitalWindow?.let { window ->
            d.columns.forEach { col ->
                pen.color = when (col.status) {
                    CapitalColumnStatus.SATISFIED -> Color.rgb(0, 170, 50)
                    CapitalColumnStatus.VIOLATING -> Color.RED
                    CapitalColumnStatus.MISSING -> Color.GRAY
                }
                pen.strokeWidth = (b.width / 720f).coerceAtLeast(1f)
                val x = col.x * b.width
                canvas.drawLine(x, window.bottom * b.height - stroke * 4, x, window.bottom * b.height, pen)
                pen.style = Paint.Style.FILL
                if (col.status != CapitalColumnStatus.SATISFIED) {
                    pen.alpha = 110
                    canvas.drawLine(x, window.top * b.height, x, window.bottom * b.height, pen)
                    pen.alpha = 255
                }
                if (col.hasMissingData) {
                    pen.color = Color.GRAY
                    canvas.drawLine(x, window.bottom * b.height, x, window.bottom * b.height + stroke * 3, pen)
                }
            }
        }
    }
}
