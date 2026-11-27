package com.stockshot.manager.automation

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.widget.Toast
import com.stockshot.indicator.DetectionStatus
import com.stockshot.manager.indicator.IndicatorDetectionOptions
import com.stockshot.manager.StockShotApp
import com.stockshot.manager.data.SettingsStore
import com.stockshot.manager.data.StockRepository
import com.stockshot.manager.data.entity.StockRecord
import com.stockshot.manager.net.QuoteFetcher
import com.stockshot.manager.service.ScreenCaptureService
import com.stockshot.manager.service.ScreenshotAccessibilityService
import com.stockshot.manager.util.AppLogger
import com.stockshot.manager.util.StorageUtils
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 自动化截图任务核心循环（单例）。
 *
 * 基础流程：读代码 -> 重复检测 -> 截图(重试3次) -> 存文件 -> 写库 -> 点"下一只" -> 延迟 -> 循环。
 * 命中识别时（本轮启用自动识别）：截图识别 -> 命中则在红绿点中点长按 -> 读买入价 ->
 *   重新截取含十字光标全屏图入库 -> 数据库标记 -> 写买入价。
 * 结束条件：用户停止 / 同一代码第 2 次出现（自动停止）/ 连续读码失败。
 */
object AutomationManager {

    private const val TAG = "Automation"
    private const val MAX_CAPTURE_RETRY = 3
    private const val MAX_EMPTY_CODE_RETRY = 3
    private const val CAPTURE_SETTLE_MS = 400L

    data class Progress(
        val running: Boolean = false,
        val count: Int = 0,
        val marked: Int = 0,
        val lastCode: String? = null,
        val message: String = "",
    )

    private val _progress = MutableStateFlow(Progress())
    val progress: StateFlow<Progress> = _progress.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @Volatile private var job: Job? = null

    // A cancelled run must finish cleanup before another date can start.
    fun isRunning(): Boolean = job?.isCompleted == false

    /**
     * 启动自动化任务。传入 applicationContext 及本轮识别开关。
     */
    @Synchronized
    fun start(
        context: Context,
        targetDate: String,
        autoDetect: Boolean = false,
        autoMark: Boolean = false,
        autoBuyPrice: Boolean = false,
        requireYellow: Boolean = false,
    ) {
        if (isRunning()) {
            AppLogger.w(TAG, "任务已在运行，忽略重复启动")
            return
        }
        AppLogger.i(TAG, "启动任务 targetDate=$targetDate autoDetect=$autoDetect autoMark=$autoMark autoBuyPrice=$autoBuyPrice")

        val app = context.applicationContext as StockShotApp
        val repo = app.repository
        val settings = app.settingsStore

        val nextJob = scope.launch(start = CoroutineStart.LAZY) {
            try {
                runLoop(app, repo, settings, targetDate, autoDetect, autoMark, autoBuyPrice, requireYellow)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.e(TAG, "任务启动异常", e)
                _progress.value = _progress.value.copy(running = false, message = "异常终止：${e.message}")
            } finally {
                _progress.value = _progress.value.copy(running = false)
            }
        }
        job = nextJob
        _progress.value = Progress(running = true, message = "任务开始")
        nextJob.start()
    }

    /** 停止任务。 */
    @Synchronized
    fun stop(reason: String = "已停止") {
        job?.cancel()
        _progress.value = _progress.value.copy(running = false, message = reason)
    }

    private suspend fun runLoop(
        context: Context,
        repo: StockRepository,
        settings: SettingsStore,
        targetDate: String,
        autoDetect: Boolean,
        autoMark: Boolean,
        autoBuyPrice: Boolean,
        requireYellow: Boolean,
    ) {
        val date = targetDate
        val codeViewId = settings.get(SettingsStore.KEY_CODE_VIEW_ID, "")
        val nextButtonId = settings.get(SettingsStore.KEY_NEXT_BUTTON_ID, "")
        val delayMs = settings.getInt(
            SettingsStore.KEY_SCREENSHOT_DELAY_MS,
            SettingsStore.DEFAULT_SCREENSHOT_DELAY_MS
        ).toLong().coerceAtLeast(300L)

        // 识别/读价相关设置
        val longPressMs = settings.getInt(
            SettingsStore.KEY_NODE_LONGPRESS_MS,
            SettingsStore.DEFAULT_NODE_LONGPRESS_MS
        ).toLong().coerceAtLeast(200L)
        val readDelayMs = settings.getInt(
            SettingsStore.KEY_NODE_READ_DELAY_MS,
            SettingsStore.DEFAULT_NODE_READ_DELAY_MS
        ).toLong().coerceAtLeast(0L)
        val postReadDelayMs = settings.getInt(
            SettingsStore.KEY_POST_READ_DELAY_MS,
            SettingsStore.DEFAULT_POST_READ_DELAY_MS
        ).toLong().coerceAtLeast(0L)
        val closeCrosshairMode = settings.get(
            SettingsStore.KEY_CLOSE_CROSSHAIR_MODE,
            SettingsStore.DEFAULT_CLOSE_CROSSHAIR_MODE
        )
        val detectionOptions = IndicatorDetectionOptions.fromSettings(settings).copy(requireYellow = requireYellow)
        val nodeCfg = ScreenshotAccessibilityService.NodeQueryConfig(
            regionLeft = settings.getFloat(SettingsStore.KEY_NODE_REGION_LEFT, SettingsStore.DEFAULT_NODE_REGION_LEFT),
            regionTop = settings.getFloat(SettingsStore.KEY_NODE_REGION_TOP, SettingsStore.DEFAULT_NODE_REGION_TOP),
            regionRight = settings.getFloat(SettingsStore.KEY_NODE_REGION_RIGHT, SettingsStore.DEFAULT_NODE_REGION_RIGHT),
            regionBottom = settings.getFloat(SettingsStore.KEY_NODE_REGION_BOTTOM, SettingsStore.DEFAULT_NODE_REGION_BOTTOM),
            className = settings.get(SettingsStore.KEY_NODE_CLASS, "").ifBlank { null },
            width = settings.getInt(SettingsStore.KEY_NODE_WIDTH, SettingsStore.DEFAULT_NODE_WIDTH),
            height = settings.getInt(SettingsStore.KEY_NODE_HEIGHT, SettingsStore.DEFAULT_NODE_HEIGHT),
            sizeTolerancePx = settings.getInt(SettingsStore.KEY_NODE_SIZE_TOL, SettingsStore.DEFAULT_NODE_SIZE_TOL),
            resultIndex = settings.getInt(SettingsStore.KEY_NODE_INDEX, SettingsStore.DEFAULT_NODE_INDEX),
        )

        val seenOnce = HashSet<String>()
        var count = 0
        var marked = 0
        var detected = 0
        var undecidable = 0

        try {
            while (currentCoroutineContext().isActive) {
                val accessibility = ScreenshotAccessibilityService.getInstance()
                if (accessibility == null) {
                    toast(context, "无障碍服务未连接，任务停止")
                    stop("无障碍服务未连接")
                    return
                }

                // 1) 读取股票代码（含空值重试）
                var code: String? = null
                var emptyTries = 0
                while (code == null && emptyTries < MAX_EMPTY_CODE_RETRY && currentCoroutineContext().isActive) {
                    code = accessibility.readStockCode(codeViewId.ifBlank { null })
                    if (code == null) {
                        emptyTries++
                        _progress.value = _progress.value.copy(
                            message = "读取代码为空，重试 $emptyTries/$MAX_EMPTY_CODE_RETRY"
                        )
                        delay(600L)
                    }
                }
                if (code == null) {
                    toast(context, "无法读取股票代码，任务已暂停")
                    stop("读取代码失败")
                    return
                }

                // 2) 重复检测：第 2 次出现 -> 自动停止
                if (seenOnce.contains(code)) {
                    AppLogger.i(TAG, "检测到重复代码 $code，自动停止")
                    toast(context, "检测到重复，任务已自动停止")
                    stop("检测到重复：$code")
                    return
                }
                seenOnce.add(code)

                // 3) 截图（重试 3 次），先取 clean 图用于识别
                delay(CAPTURE_SETTLE_MS)
                var bitmap = captureWithRetry()
                if (bitmap == null) {
                    AppLogger.w(TAG, "截图失败，跳过 $code")
                    _progress.value = _progress.value.copy(message = "截图失败，跳过 $code")
                    if (!gotoNext(accessibility, nextButtonId)) {
                        toast(context, "无法点击下一只，任务停止")
                        stop("点击下一只失败")
                        return
                    }
                    delay(delayMs)
                    continue
                }

                // 3.5) 命中识别（仅本轮启用时）
                var hit = false
                var buyPrice: Double? = null
                var recognitionNote = ""
                if (autoDetect) {
                    val det = try {
                        detectionOptions.detect(bitmap)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        AppLogger.e(TAG, "指标识别异常 $code", e)
                        null
                    }
                    if (det != null) {
                        AppLogger.i(
                            TAG,
                            "识别 $code: status=${det.status} trend=${det.trendStatus} capitalStatus=${det.capitalStatus} " +
                                "method=${det.diagnostics.capitalMethod}/${det.diagnostics.capitalRule} selection=${det.diagnostics.signalSelection} " +
                                "stats=${det.diagnostics.capitalStats} matched=${det.matched} trendDots=${det.trendDotsMatched} " +
                                "capital=${det.capitalLinesMatched} conf=${det.confidence} " +
                                "red=${det.redPoint} green=${det.greenPoint} reason=${det.reason}"
                        )
                    }
                    if (det == null || det.status == DetectionStatus.UNKNOWN) {
                        undecidable++
                        recognitionNote = "；无法判断：" + (det?.reason ?: "检测异常")
                    } else if (!det.matched) recognitionNote = "；不符合：" + det.reason
                    val red = det?.redPoint
                    val green = det?.greenPoint
                    if (det != null && det.matched && red != null && green != null) {
                        hit = true
                        detected++
                        val w = bitmap.width
                        val h = bitmap.height
                        // 红绿点中点 -> 屏幕像素（bitmap 即全屏分辨率）
                        val cx = (red.x + green.x) / 2f * w
                        val cy = (red.y + green.y) / 2f * h
                        AppLogger.i(TAG, "命中 $code，长按坐标=($cx,$cy) w=$w h=$h longPress=${longPressMs}ms")
                        try {
                            accessibility.longPress(cx, cy, longPressMs)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            AppLogger.e(TAG, "长按失败 $code", e)
                        }
                        if (autoBuyPrice) {
                            delay(readDelayMs)
                            val nodeResult = try {
                                accessibility.findNodeText(nodeCfg)
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                AppLogger.e(TAG, "读取买入价控件异常 $code", e)
                                null
                            }
                            if (nodeResult != null) {
                                AppLogger.i(
                                    TAG,
                                    "读价 $code: text='${nodeResult.text}' candidates=${nodeResult.candidateCount} " +
                                        "class=${nodeResult.className} bounds=${nodeResult.bounds}"
                                )
                                buyPrice = parsePrice(nodeResult.text)
                                if (buyPrice == null) {
                                    AppLogger.w(TAG, "买入价解析失败 $code，原文='${nodeResult.text}'")
                                }
                            }
                        }
                        // 4) 入库图 = 长按后含十字光标的全屏图；重截
                        bitmap.recycle()
                        delay(CAPTURE_SETTLE_MS)
                        val shot = captureWithRetry()
                        if (shot != null) {
                            bitmap = shot
                        } else {
                            AppLogger.w(TAG, "命中后重截失败 $code，跳过")
                            _progress.value = _progress.value.copy(message = "重截失败，跳过 $code")
                            if (!gotoNext(accessibility, nextButtonId)) {
                                stop("点击下一只失败")
                                return
                            }
                            delay(delayMs)
                            continue
                        }
                    }
                }

                // 5) 存文件
                val file = StorageUtils.newImageFile(context, code, date)
                val saved = saveBitmap(bitmap, file)
                bitmap.recycle()
                if (!saved) {
                    AppLogger.w(TAG, "保存图片失败 $code")
                    _progress.value = _progress.value.copy(message = "保存失败，跳过 $code")
                    if (!gotoNext(accessibility, nextButtonId)) {
                        stop("点击下一只失败")
                        return
                    }
                    delay(delayMs)
                    continue
                }

                // 6) 写库（名称尽力从行情源补齐）
                val name = fetchNameSafe(code)
                repo.insert(
                    StockRecord(
                        stockCode = code,
                        stockName = name,
                        date = date,
                        imagePath = file.absolutePath,
                    )
                )
                count++

                // 6.5) 命中处理：数据库标记 + 写买入价（不改动图片）
                if (hit && autoMark) {
                    try {
                        repo.setMarked(code, date, true)
                        marked++
                        AppLogger.i(TAG, "已数据库标记 $code $date")
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        AppLogger.e(TAG, "数据库标记失败 $code", e)
                    }
                }
                val bp = buyPrice
                if (hit && autoBuyPrice && bp != null) {
                    try {
                        repo.updateBuyPrice(code, date, bp)
                        AppLogger.i(TAG, "已写买入价 $code=$bp")
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        AppLogger.e(TAG, "写买入价失败 $code", e)
                    }
                }

                _progress.value = _progress.value.copy(
                    running = true, count = count, marked = marked, lastCode = code,
                    message = if (autoDetect) "已采集 $count（命中 $detected，标记 $marked，无法判断 $undecidable）：$code$recognitionNote" else "已采集 $count：$code"
                )

                // 6.8) 读价后关闭十字光标 + 关闭拦路弹框，避免误点/遮挡影响下一只
                if (hit && autoDetect) {
                    // 读价后延迟：给行情页恢复的时间（可在设置中调整）
                    if (postReadDelayMs > 0) delay(postReadDelayMs)
                    try {
                        val (sw, sh) = screenSizeSafe()
                        closeCrosshair(accessibility, sw, sh, closeCrosshairMode)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        AppLogger.w(TAG, "关闭十字光标异常: ${e.message}")
                    }
                    // 关闭“深港通标的股票”等拦路提示框（找不到就跳过）
                    try {
                        dismissBlockingDialog(accessibility)
                    } catch (_: Exception) {
                    }
                }

                // 7) 点击下一只
                if (!gotoNext(accessibility, nextButtonId)) {
                    toast(context, "无法点击下一只，任务停止")
                    stop("点击下一只失败")
                    return
                }

                // 8) 延迟
                delay(delayMs)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLogger.e(TAG, "任务循环异常", e)
            stop("异常终止：${e.message}")
        }
    }

    private suspend fun captureWithRetry(): Bitmap? {
        val svc = ScreenCaptureService.getInstance() ?: return null
        repeat(MAX_CAPTURE_RETRY) { attempt ->
            val bmp = try {
                svc.capture()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.w(TAG, "截图第 ${attempt + 1} 次异常: ${e.message}")
                null
            }
            if (bmp != null) return bmp
            delay(300L)
        }
        return null
    }

    private fun saveBitmap(bitmap: Bitmap, file: File): Boolean = try {
        FileOutputStream(file).use { out ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSLESS, 100, out)
            } else {
                @Suppress("DEPRECATION")
                bitmap.compress(Bitmap.CompressFormat.WEBP, 100, out)
            }
        }
        true
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        AppLogger.e(TAG, "保存 Bitmap 失败", e)
        false
    }

    /** 从控件文本解析价格：去逗号，取首个合法小数/整数。 */
    private fun parsePrice(text: String?): Double? {
        if (text.isNullOrBlank()) return null
        val cleaned = text.replace(",", "")
        val m = Regex("[0-9]+(\\.[0-9]+)?").find(cleaned) ?: return null
        return m.value.toDoubleOrNull()
    }

    /**
     * 关闭十字光标/报价浮层。按设置 mode 选择方式：
     * back: 系统返回键（默认，最稳定）；tap_chart: 点图表中央安全区；none: 不处理。
     * 不再默认点屏幕顶部 6%（那里是股票名称/交易时间行，会误弹“深港通标的股票”等提示）。
     */
    private suspend fun closeCrosshair(
        accessibility: ScreenshotAccessibilityService,
        screenW: Int,
        screenH: Int,
        mode: String,
    ) {
        when (mode) {
            SettingsStore.CLOSE_CROSSHAIR_NONE -> return
            SettingsStore.CLOSE_CROSSHAIR_TAP_CHART -> {
                if (screenW <= 0 || screenH <= 0) return
                // 图表中央安全区（避开顶部标题行与底部按钮）
                val x = screenW * 0.5f
                val y = screenH * 0.45f
                accessibility.tap(x, y)
                delay(200L)
            }
            else -> {
                // 默认 back：系统返回键收起十字光标/报价浮层
                accessibility.back()
                delay(200L)
            }
        }
    }

    /**
     * 尝试关闭“深港通标的股票”等拦路提示框（找不到就跳过，不影响正常页面）。
     */
    private fun dismissBlockingDialog(accessibility: ScreenshotAccessibilityService) {
        accessibility.clickByText("关闭", "知道了", "我知道了", "确定")
    }

    private suspend fun screenSizeSafe(): Pair<Int, Int> {
        val bmp = ScreenCaptureService.getInstance()?.let {
            try { it.capture() } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
        }
        val size = if (bmp != null) Pair(bmp.width, bmp.height) else Pair(0, 0)
        bmp?.recycle()
        return size
    }

    private suspend fun fetchNameSafe(code: String): String? = try {
        QuoteFetcher.fetchName(code)
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

    /** 点击"下一只"：优先 viewId，退化为常见文本。 */
    private fun gotoNext(
        accessibility: ScreenshotAccessibilityService,
        nextButtonId: String,
    ): Boolean {
        if (accessibility.clickNext(nextButtonId.ifBlank { null })) return true
        return accessibility.clickByText("下一只", "下一个", "下一支", "下一頁", "下一页")
    }

    private suspend fun toast(context: Context, msg: String) {
        withContext(Dispatchers.Main) {
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
        }
    }
}
