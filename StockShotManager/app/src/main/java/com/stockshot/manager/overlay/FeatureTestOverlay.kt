package com.stockshot.manager.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.text.InputType
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.stockshot.manager.indicator.IndicatorDetectionOptions
import com.stockshot.manager.StockShotApp
import com.stockshot.manager.capture.MediaProjectionRequestActivity
import com.stockshot.manager.data.SettingsStore
import com.stockshot.manager.service.ScreenCaptureService
import com.stockshot.manager.service.ScreenshotAccessibilityService
import com.stockshot.manager.util.AppLogger
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 功能测试常驻悬浮工具（单例）。
 * - 常驻可拖动的小图标；点击弹出测试菜单。
 * - 测试项：控件查找（带齿轮配置）、指标识别、截图测试、读取股票代码。
 * - 测试用红框/tip 走 HighlightOverlay，10 秒后消失，不写数据库、不入库。
 */
object FeatureTestOverlay {

    private const val TAG = "FeatureTest"

    private var windowManager: WindowManager? = null
    private var iconView: View? = null
    private var iconParams: WindowManager.LayoutParams? = null
    private var menuView: View? = null
    private var configView: View? = null
    private var showing = false
    private var indicatorTesting = false

    private val scope = CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.Main)

    fun isShowing(): Boolean = showing

    /** 从三点菜单调用：切换常驻图标显隐。 */
    fun toggle(context: Context) {
        if (showing) hide() else show(context)
    }

    fun show(context: Context) {
        if (showing) return
        val appCtx = context.applicationContext
        try {
            val wm = appCtx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            windowManager = wm
            addIcon(appCtx, wm)
            showing = true
        } catch (e: Exception) {
            AppLogger.e(TAG, "show 失败", e)
        }
    }

    fun hide() {
        removeView(configView); configView = null
        removeView(menuView); menuView = null
        removeView(iconView); iconView = null
        HighlightOverlay.clear()
        windowManager = null
        showing = false
    }

    private fun removeView(v: View?) {
        val wm = windowManager ?: return
        if (v != null) try { wm.removeView(v) } catch (_: Exception) {}
    }

    private fun overlayType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

    private fun dp(context: Context, v: Int): Int =
        (v * context.resources.displayMetrics.density).toInt()

    // ----------------------------------------------------------------- 图标
    @SuppressLint("ClickableViewAccessibility")
    private fun addIcon(context: Context, wm: WindowManager) {
        val icon = TextView(context).apply {
            text = "测"
            setTextColor(Color.WHITE)
            textSize = 16f
            gravity = Gravity.CENTER
            val size = dp(context, 48)
            width = size; height = size
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#E63F51B5"))
                setStroke(dp(context, 1), Color.WHITE)
            }
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(context, 16)
            y = dp(context, 160)
        }
        iconParams = params

        var startX = 0; var startY = 0
        var rawX = 0f; var rawY = 0f
        var dragging = false
        icon.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = params.x; startY = params.y
                    rawX = e.rawX; rawY = e.rawY; dragging = false; true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - rawX; val dy = e.rawY - rawY
                    if (!dragging && (abs(dx) > 8 || abs(dy) > 8)) dragging = true
                    if (dragging) {
                        params.x = (startX + dx).toInt()
                        params.y = (startY + dy).toInt()
                        try { wm.updateViewLayout(icon, params) } catch (_: Exception) {}
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!dragging) toggleMenu(context)
                    true
                }
                else -> false
            }
        }
        wm.addView(icon, params)
        iconView = icon
    }

    // ----------------------------------------------------------------- 菜单
    private fun toggleMenu(context: Context) {
        if (menuView != null) { removeView(menuView); menuView = null; return }
        val wm = windowManager ?: return
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(context, 8), dp(context, 8), dp(context, 8), dp(context, 8))
            background = GradientDrawable().apply {
                cornerRadius = dp(context, 10).toFloat()
                setColor(Color.parseColor("#F2202124"))
                setStroke(dp(context, 1), Color.parseColor("#3F51B5"))
            }
        }
        container.addView(titleRow(context, "功能测试"))
        container.addView(controlFinderRow(context))
        container.addView(menuItem(context, "指标识别") { runIndicatorTest(context) })
        container.addView(menuItem(context, "指标样本库 / 验收") {
            removeView(menuView); menuView = null
            IndicatorReviewActivity.openLibrary(context)
        })
        container.addView(menuItem(context, "截图测试") { runCaptureTest(context) })
        container.addView(menuItem(context, "读取股票代码") { runReadCodeTest(context) })
        container.addView(menuItem(context, "点击下一只") { runClickNextTest(context) })
        container.addView(menuItem(context, "关闭菜单") { removeView(menuView); menuView = null })

        val iconY = iconParams?.y ?: dp(context, 160)
        val params = WindowManager.LayoutParams(
            dp(context, 220),
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(context, 72)
            y = iconY
        }
        wm.addView(container, params)
        menuView = container
    }

    private fun titleRow(context: Context, text: String): TextView =
        TextView(context).apply {
            this.text = text
            setTextColor(Color.parseColor("#90CAF9"))
            textSize = 13f
            setPadding(dp(context, 8), dp(context, 4), dp(context, 8), dp(context, 8))
        }

    private fun menuItem(context: Context, text: String, onClick: () -> Unit): TextView =
        TextView(context).apply {
            this.text = text
            setTextColor(Color.WHITE)
            textSize = 15f
            setPadding(dp(context, 12), dp(context, 12), dp(context, 12), dp(context, 12))
            setOnClickListener { onClick() }
        }

    /** 控件查找行：文字点击执行查找，右侧齿轮点击弹出参数配置。 */
    @SuppressLint("SetTextI18n")
    private fun controlFinderRow(context: Context): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val label = TextView(context).apply {
            text = "控件查找"
            setTextColor(Color.WHITE)
            textSize = 15f
            setPadding(dp(context, 12), dp(context, 12), dp(context, 12), dp(context, 12))
            setOnClickListener { runControlFinderTest(context) }
        }
        val gear = TextView(context).apply {
            text = "\u2699"  // gear glyph
            setTextColor(Color.parseColor("#90CAF9"))
            textSize = 20f
            setPadding(dp(context, 10), dp(context, 8), dp(context, 12), dp(context, 8))
            setOnClickListener { showConfig(context) }
        }
        row.addView(label, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(gear)
        return row
    }

    // ----------------------------------------------------------------- 配置小窗
    private val editRefs = HashMap<String, EditText>()

    @SuppressLint("SetTextI18n")
    private fun showConfig(context: Context) {
        if (configView != null) { removeView(configView); configView = null }
        val wm = windowManager ?: return
        val store = StockShotApp.instance.settingsStore
        editRefs.clear()

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(context, 14), dp(context, 12), dp(context, 14), dp(context, 12))
            background = GradientDrawable().apply {
                cornerRadius = dp(context, 12).toFloat()
                setColor(Color.parseColor("#F7202124"))
                setStroke(dp(context, 1), Color.parseColor("#3F51B5"))
            }
        }
        root.addView(titleRow(context, "控件查找参数"))

        val scroll = ScrollView(context)
        val form = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(form)

        scope.launch {
            val values = withContext(Dispatchers.IO) {
                linkedMapOf(
                    SettingsStore.KEY_NODE_REGION_LEFT to store.getFloat(SettingsStore.KEY_NODE_REGION_LEFT, SettingsStore.DEFAULT_NODE_REGION_LEFT).toString(),
                    SettingsStore.KEY_NODE_REGION_TOP to store.getFloat(SettingsStore.KEY_NODE_REGION_TOP, SettingsStore.DEFAULT_NODE_REGION_TOP).toString(),
                    SettingsStore.KEY_NODE_REGION_RIGHT to store.getFloat(SettingsStore.KEY_NODE_REGION_RIGHT, SettingsStore.DEFAULT_NODE_REGION_RIGHT).toString(),
                    SettingsStore.KEY_NODE_REGION_BOTTOM to store.getFloat(SettingsStore.KEY_NODE_REGION_BOTTOM, SettingsStore.DEFAULT_NODE_REGION_BOTTOM).toString(),
                    SettingsStore.KEY_NODE_CLASS to store.get(SettingsStore.KEY_NODE_CLASS, ""),
                    SettingsStore.KEY_NODE_WIDTH to store.getInt(SettingsStore.KEY_NODE_WIDTH, SettingsStore.DEFAULT_NODE_WIDTH).toString(),
                    SettingsStore.KEY_NODE_HEIGHT to store.getInt(SettingsStore.KEY_NODE_HEIGHT, SettingsStore.DEFAULT_NODE_HEIGHT).toString(),
                    SettingsStore.KEY_NODE_SIZE_TOL to store.getInt(SettingsStore.KEY_NODE_SIZE_TOL, SettingsStore.DEFAULT_NODE_SIZE_TOL).toString(),
                    SettingsStore.KEY_NODE_INDEX to store.getInt(SettingsStore.KEY_NODE_INDEX, SettingsStore.DEFAULT_NODE_INDEX).toString(),
                    SettingsStore.KEY_NODE_READ_DELAY_MS to store.getInt(SettingsStore.KEY_NODE_READ_DELAY_MS, SettingsStore.DEFAULT_NODE_READ_DELAY_MS).toString(),
                )
            }
            val labels = mapOf(
                SettingsStore.KEY_NODE_REGION_LEFT to "区域 left (0-1)",
                SettingsStore.KEY_NODE_REGION_TOP to "区域 top (0-1)",
                SettingsStore.KEY_NODE_REGION_RIGHT to "区域 right (0-1)",
                SettingsStore.KEY_NODE_REGION_BOTTOM to "区域 bottom (0-1)",
                SettingsStore.KEY_NODE_CLASS to "控件 class",
                SettingsStore.KEY_NODE_WIDTH to "宽 (px, 0=不限)",
                SettingsStore.KEY_NODE_HEIGHT to "高 (px, 0=不限)",
                SettingsStore.KEY_NODE_SIZE_TOL to "宽高容差 (px)",
                SettingsStore.KEY_NODE_INDEX to "第几个 (从1)",
                SettingsStore.KEY_NODE_READ_DELAY_MS to "读取延迟 (ms)",
            )
            for ((key, value) in values) {
                form.addView(TextView(context).apply {
                    text = labels[key] ?: key
                    setTextColor(Color.parseColor("#B0BEC5"))
                    textSize = 12f
                    setPadding(0, dp(context, 6), 0, dp(context, 2))
                })
                val et = EditText(context).apply {
                    setText(value)
                    setTextColor(Color.WHITE)
                    textSize = 14f
                    setPadding(dp(context, 8), dp(context, 6), dp(context, 8), dp(context, 6))
                    inputType = if (key == SettingsStore.KEY_NODE_CLASS)
                        InputType.TYPE_CLASS_TEXT
                    else
                        InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
                    background = GradientDrawable().apply {
                        cornerRadius = dp(context, 6).toFloat()
                        setColor(Color.parseColor("#33FFFFFF"))
                    }
                }
                editRefs[key] = et
                form.addView(et)
            }
        }

        root.addView(scroll, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(context, 320)
        ))

        val btnRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(context, 8), 0, 0)
        }
        val saveBtn = Button(context).apply {
            text = "保存"
            setOnClickListener { saveConfig(context) }
        }
        val closeBtn = Button(context).apply {
            text = "关闭"
            setOnClickListener { removeView(configView); configView = null }
        }
        btnRow.addView(saveBtn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        btnRow.addView(closeBtn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(btnRow)

        // 可输入：不能设置 FLAG_NOT_FOCUSABLE，否则软键盘无法输入。
        val params = WindowManager.LayoutParams(
            dp(context, 300),
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        }
        wm.addView(root, params)
        configView = root
    }

    private fun saveConfig(context: Context) {
        val store = StockShotApp.instance.settingsStore
        val snapshot = editRefs.mapValues { it.value.text.toString().trim() }
        scope.launch {
            withContext(Dispatchers.IO) {
                for ((key, value) in snapshot) store.put(key, value)
            }
            Toast.makeText(context, "已保存控件查找参数", Toast.LENGTH_SHORT).show()
            removeView(configView); configView = null
        }
    }

    // ----------------------------------------------------------------- 测试逻辑
    private suspend fun buildNodeConfig(): ScreenshotAccessibilityService.NodeQueryConfig {
        val store = StockShotApp.instance.settingsStore
        return ScreenshotAccessibilityService.NodeQueryConfig(
            regionLeft = store.getFloat(SettingsStore.KEY_NODE_REGION_LEFT, SettingsStore.DEFAULT_NODE_REGION_LEFT),
            regionTop = store.getFloat(SettingsStore.KEY_NODE_REGION_TOP, SettingsStore.DEFAULT_NODE_REGION_TOP),
            regionRight = store.getFloat(SettingsStore.KEY_NODE_REGION_RIGHT, SettingsStore.DEFAULT_NODE_REGION_RIGHT),
            regionBottom = store.getFloat(SettingsStore.KEY_NODE_REGION_BOTTOM, SettingsStore.DEFAULT_NODE_REGION_BOTTOM),
            className = store.get(SettingsStore.KEY_NODE_CLASS, "").trim().ifBlank { null },
            width = store.getInt(SettingsStore.KEY_NODE_WIDTH, SettingsStore.DEFAULT_NODE_WIDTH),
            height = store.getInt(SettingsStore.KEY_NODE_HEIGHT, SettingsStore.DEFAULT_NODE_HEIGHT),
            sizeTolerancePx = store.getInt(SettingsStore.KEY_NODE_SIZE_TOL, SettingsStore.DEFAULT_NODE_SIZE_TOL),
            resultIndex = store.getInt(SettingsStore.KEY_NODE_INDEX, SettingsStore.DEFAULT_NODE_INDEX),
        )
    }

    private fun runControlFinderTest(context: Context) {
        removeView(menuView); menuView = null
        val svc = ScreenshotAccessibilityService.getInstance()
        if (svc == null) {
            Toast.makeText(context, "无障碍服务未连接", Toast.LENGTH_SHORT).show()
            return
        }
        scope.launch {
            val cfg = buildNodeConfig()
            val r = withContext(Dispatchers.Default) { svc.findNodeText(cfg) }
            if (r.candidateCount == 0 && r.text.isBlank()) {
                HighlightOverlay.show(context, emptyList(), "控件查找：未命中（候选 0）")
            } else {
                val tip = "命中: ${r.text.ifBlank { "(空)" }}\n候选: ${r.candidateCount}  类: ${r.className ?: "--"}"
                HighlightOverlay.show(context, listOf(r.bounds), tip)
            }
        }
    }

    private fun runIndicatorTest(context: Context) {
        if (indicatorTesting) return
        indicatorTesting = true
        removeView(menuView); menuView = null
        HighlightOverlay.clear()
        iconView?.visibility = View.GONE
        scope.launch {
            kotlinx.coroutines.delay(250L)
            captureFrame(context) { bmp ->
                iconView?.visibility = View.VISIBLE
                if (bmp == null) {
                    indicatorTesting = false
                    Toast.makeText(context, "截图失败，无法识别", Toast.LENGTH_SHORT).show()
                } else scope.launch {
                    try {
                        val options = IndicatorDetectionOptions.fromSettings(StockShotApp.instance.settingsStore)
                        val result = withContext(Dispatchers.Default) { options.detect(bmp) }
                        IndicatorReviewActivity.open(context, bmp, options, result)
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        AppLogger.e(TAG, "指标测试失败", e)
                        Toast.makeText(context, "指标测试失败", Toast.LENGTH_LONG).show()
                    } finally {
                        if (!bmp.isRecycled) bmp.recycle()
                        indicatorTesting = false
                    }
                }
            }
        }
    }

    private fun runCaptureTest(context: Context) {
        removeView(menuView); menuView = null
        captureFrame(context) { bmp ->
            if (bmp == null) {
                Toast.makeText(context, "截图失败", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(context, "截图成功: ${bmp.width} x ${bmp.height}", Toast.LENGTH_LONG).show()
                if (!bmp.isRecycled) bmp.recycle()
            }
        }
    }

    private fun runReadCodeTest(context: Context) {
        removeView(menuView); menuView = null
        val svc = ScreenshotAccessibilityService.getInstance()
        if (svc == null) {
            Toast.makeText(context, "无障碍服务未连接", Toast.LENGTH_SHORT).show()
            return
        }
        val code = svc.readStockCode(null)
        HighlightOverlay.show(context, emptyList(), "读取股票代码: ${code ?: "未识别"}", 6000L)
    }

    /** 单独测试“点击下一只”按钮：验证按钮 ID 是否正确、是否会误触。不写库不入图。 */
    private fun runClickNextTest(context: Context) {
        removeView(menuView); menuView = null
        val svc = ScreenshotAccessibilityService.getInstance()
        if (svc == null) {
            Toast.makeText(context, "无障碍服务未连接", Toast.LENGTH_SHORT).show()
            return
        }
        val nextId = StockShotApp.instance.settingsStore.getSync(SettingsStore.KEY_NEXT_BUTTON_ID, "")
        val bounds = svc.nodeBounds(nextId.ifBlank { null })
        val byId = svc.clickNext(nextId.ifBlank { null })
        val ok = byId || svc.clickByText("下一只", "下一个", "下一支", "下一頁", "下一页")
        val tip = if (ok) {
            "点击下一只: 成功" + (if (byId) "(ID)" else "(文本)") + "\nID=" + nextId.ifBlank { "(未设置)" } + "\nbounds=" + (bounds?.toString() ?: "--")
        } else {
            "点击下一只: 失败\nID=" + nextId.ifBlank { "(未设置)" } + "  未找到按钮"
        }
        val rects = if (bounds != null) listOf(bounds) else emptyList()
        HighlightOverlay.show(context, rects, tip)
    }

    /** 抓一帧：截图服务已在运行则直接抓；否则请求投屏授权后再抓。 */
    private suspend fun captureSafely(service: ScreenCaptureService?): android.graphics.Bitmap? = try {
        service?.capture()
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        AppLogger.e(TAG, "测试截图失败", e)
        null
    }

    private fun captureFrame(context: Context, onResult: (android.graphics.Bitmap?) -> Unit) {
        val svc = ScreenCaptureService.getInstance()
        if (ScreenCaptureService.isRunning() && svc != null) {
            scope.launch { onResult(captureSafely(svc)) }
            return
        }
        Toast.makeText(context, "请求屏幕录制授权…", Toast.LENGTH_SHORT).show()
        MediaProjectionRequestActivity.request(
            context.applicationContext,
            onGranted = {
                scope.launch {
                    var waited = 0L
                    while (!ScreenCaptureService.isRunning() && waited < 4000L) {
                        kotlinx.coroutines.delay(200L); waited += 200L
                    }
                    kotlinx.coroutines.delay(600L)
                    onResult(captureSafely(ScreenCaptureService.getInstance()))
                }
            },
            onDenied = {
                indicatorTesting = false
                iconView?.visibility = View.VISIBLE
                Toast.makeText(context, "已取消授权", Toast.LENGTH_SHORT).show()
            }
        )
    }
}