package com.stockshot.manager.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.stockshot.manager.automation.AutomationManager
import com.stockshot.manager.util.AppLogger
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * 自动化任务的悬浮控制窗：显示进度文字 + “停止”按钮，可拖动。
 * 使用 SYSTEM_ALERT_WINDOW，纯代码构建视图，避免额外布局文件。
 */
class FloatControlWindow(private val context: Context) {

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var root: LinearLayout? = null
    private var params: WindowManager.LayoutParams? = null
    private var progressView: TextView? = null
    private var isShowing = false

    private var collectJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Main)

    // 拖动
    private var startParamX = 0; private var startParamY = 0
    private var startRawX = 0f; private var startRawY = 0f
    private var dragging = false

    private val screenWidth get() = context.resources.displayMetrics.widthPixels
    private val screenHeight get() = context.resources.displayMetrics.heightPixels
    private val density get() = context.resources.displayMetrics.density

    fun show() {
        if (isShowing) return
        try {
            buildView()
            windowManager.addView(root, params)
            isShowing = true
            observeProgress()
        } catch (e: Exception) {
            AppLogger.e("FloatControl", "addView 失败", e)
        }
    }

    fun hide() {
        collectJob?.cancel()
        collectJob = null
        if (!isShowing) return
        try { windowManager.removeView(root) } catch (_: Exception) {}
        root = null
        isShowing = false
    }

    private fun dp(v: Int): Int = (v * density).toInt()

    @SuppressLint("ClickableViewAccessibility")
    private fun buildView() {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

        params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(12)
            y = dp(80)
        }

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = GradientDrawable().apply {
                cornerRadius = dp(10).toFloat()
                setColor(Color.parseColor("#E6202124"))
                setStroke(dp(1), Color.parseColor("#3F51B5"))
            }
        }

        val title = TextView(context).apply {
            text = "股票自动采集"
            setTextColor(Color.WHITE)
            textSize = 13f
        }
        val progress = TextView(context).apply {
            text = "准备中…"
            setTextColor(Color.parseColor("#90CAF9"))
            textSize = 12f
            setPadding(0, dp(4), 0, dp(8))
        }
        progressView = progress

        val stopBtn = Button(context).apply {
            text = "停止"
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                cornerRadius = dp(8).toFloat()
                setColor(Color.parseColor("#C62828"))
            }
            setPadding(dp(18), dp(4), dp(18), dp(4))
            setOnClickListener {
                AutomationManager.stop("用户停止")
            }
        }

        container.addView(title)
        container.addView(progress)
        container.addView(
            stopBtn,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        container.setOnTouchListener { v, event ->
            val p = params ?: return@setOnTouchListener false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startParamX = p.x; startParamY = p.y
                    startRawX = event.rawX; startRawY = event.rawY
                    dragging = false
                    false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - startRawX
                    val dy = event.rawY - startRawY
                    if (!dragging && (abs(dx) > 8 || abs(dy) > 8)) dragging = true
                    if (dragging) {
                        val vw = v.width.takeIf { it > 0 } ?: dp(120)
                        val vh = v.height.takeIf { it > 0 } ?: dp(100)
                        p.x = (startParamX + dx).toInt().coerceIn(0, (screenWidth - vw).coerceAtLeast(0))
                        p.y = (startParamY + dy).toInt().coerceIn(0, (screenHeight - vh).coerceAtLeast(0))
                        try { windowManager.updateViewLayout(root, p) } catch (_: Exception) {}
                    }
                    dragging
                }
                else -> false
            }
        }

        root = container
    }

    private fun observeProgress() {
        collectJob = scope.launch {
            AutomationManager.progress.collectLatest { p ->
                progressView?.text = buildString {
                    append("已采集 ${p.count}")
                    if (!p.message.isNullOrBlank()) {
                        append("\n")
                        append(p.message)
                    }
                }
                if (!p.running && p.count == 0 && p.message.isBlank()) {
                    // 空闲状态，无操作
                }
            }
        }
    }
}
