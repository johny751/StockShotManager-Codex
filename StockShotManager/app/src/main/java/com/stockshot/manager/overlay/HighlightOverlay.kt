package com.stockshot.manager.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import com.stockshot.manager.util.AppLogger

/**
 * 全屏透明覆盖层：在指定屏幕坐标 Rect 上绘制红框，并在顶部显示 tip 文字。
 * 不拦截触摸（FLAG_NOT_TOUCHABLE），到时或再次调用自动清除。仅用于功能测试，不落库。
 */
object HighlightOverlay {

    private const val TAG = "HighlightOverlay"
    private const val DEFAULT_DURATION_MS = 10_000L

    private var windowManager: WindowManager? = null
    private var view: HighlightView? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var clearRunnable: Runnable? = null

    /** 在屏幕坐标 rects 上画红框，tip 显示说明文字，durationMs 后自动清除。 */
    fun show(context: Context, rects: List<Rect>, tip: String?, durationMs: Long = DEFAULT_DURATION_MS) {
        mainHandler.post {
            try {
                clearInternal()
                val appCtx = context.applicationContext
                val wm = appCtx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
                val v = HighlightView(appCtx, rects, tip)
                val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
                val params = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    type,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT
                ).apply { gravity = Gravity.TOP or Gravity.START }
                wm.addView(v, params)
                windowManager = wm
                view = v
                val r = Runnable { clear() }
                clearRunnable = r
                mainHandler.postDelayed(r, durationMs)
            } catch (e: Exception) {
                AppLogger.e(TAG, "show 失败", e)
            }
        }
    }

    fun clear() {
        mainHandler.post { clearInternal() }
    }

    private fun clearInternal() {
        clearRunnable?.let { mainHandler.removeCallbacks(it) }
        clearRunnable = null
        val v = view
        val wm = windowManager
        if (v != null && wm != null) {
            try { wm.removeView(v) } catch (_: Exception) {}
        }
        view = null
        windowManager = null
    }

    @SuppressLint("ViewConstructor")
    private class HighlightView(
        context: Context,
        private val rects: List<Rect>,
        private val tip: String?,
    ) : View(context) {

        private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 6f
            color = Color.parseColor("#FF1744")
        }
        private val tipBg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.parseColor("#E6202124")
        }
        private val tipText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 34f
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            for (r in rects) {
                if (!r.isEmpty) canvas.drawRect(r, boxPaint)
            }
            val t = tip
            if (!t.isNullOrBlank()) {
                val lines = wrap(t, width - 48)
                val lineH = tipText.textSize + 12f
                val boxH = lineH * lines.size + 24f
                canvas.drawRect(16f, 40f, width - 16f, 40f + boxH, tipBg)
                var y = 40f + lineH
                for (line in lines) {
                    canvas.drawText(line, 32f, y, tipText)
                    y += lineH
                }
            }
        }

        private fun wrap(text: String, maxWidth: Int): List<String> {
            if (maxWidth <= 0) return listOf(text)
            val out = ArrayList<String>()
            var cur = StringBuilder()
            for (ch in text) {
                if (ch == '\n') {
                    out.add(cur.toString()); cur = StringBuilder(); continue
                }
                cur.append(ch)
                if (tipText.measureText(cur.toString()) > maxWidth) {
                    val s = cur.toString()
                    out.add(s.dropLast(1))
                    cur = StringBuilder().append(s.last())
                }
            }
            if (cur.isNotEmpty()) out.add(cur.toString())
            return out
        }
    }
}