package com.stockshot.manager.ui.vm

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.stockshot.manager.StockShotApp
import com.stockshot.manager.data.SettingsStore
import com.stockshot.manager.net.StockQuoteSourceFactory
import com.stockshot.manager.service.ScreenshotAccessibilityService
import com.stockshot.manager.util.BackupUtils
import com.stockshot.manager.util.QrShareUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Settings screen state, backed by the app_settings table via SettingsStore.
 *
 * 数据源部分借鉴 ControlMonitor：三选一（接口/局域网/新浪兜底）+ 连接测试。
 * 新浪兜底为默认项，零配置即可使用。
 */
class SettingsViewModel(app: Application) : AndroidViewModel(app) {

    private val store: SettingsStore = (app as StockShotApp).settingsStore
    private val repository = (app as StockShotApp).repository

    data class UiState(
        val quoteSourceType: String = SettingsStore.DEFAULT_QUOTE_SOURCE_TYPE,
        val lanApiIp: String = "",
        val targetAppPackage: String = "",
        val codeViewId: String = "",
        val nextButtonId: String = "",
        val screenshotDelayMs: Int = SettingsStore.DEFAULT_SCREENSHOT_DELAY_MS,
        val ocrEnabled: Boolean = false,
        val viewerPageDirection: String = SettingsStore.DEFAULT_VIEWER_PAGE_DIRECTION,
        val storageRoot: String = "",
        // 读价后延迟 + 关闭十字光标方式
        val postReadDelayMs: Int = SettingsStore.DEFAULT_POST_READ_DELAY_MS,
        val capitalRequireYellow: Boolean = SettingsStore.DEFAULT_CAPITAL_REQUIRE_YELLOW,
        val capitalMethod: String = SettingsStore.DEFAULT_CAPITAL_METHOD,
        val capitalRule: String = SettingsStore.DEFAULT_CAPITAL_RULE,
        val signalSelection: String = SettingsStore.DEFAULT_SIGNAL_SELECTION,
        val capitalMajorityThreshold: Float = SettingsStore.DEFAULT_CAPITAL_MAJORITY_THRESHOLD,
        val dotToleranceScale: Float = SettingsStore.DEFAULT_DOT_TOL_SCALE,
        val minConfidence: Float = SettingsStore.DEFAULT_MIN_CONFIDENCE,
        val capitalWindowScale: Float = SettingsStore.DEFAULT_CAPITAL_WINDOW_SCALE,
        val closeCrosshairMode: String = SettingsStore.DEFAULT_CLOSE_CROSSHAIR_MODE,
        // 自动识别标记
        val autoDetectEnabled: Boolean = SettingsStore.DEFAULT_AUTO_DETECT_ENABLED,
        val autoMarkEnabled: Boolean = SettingsStore.DEFAULT_AUTO_MARK_ENABLED,
        val autoBuyPriceEnabled: Boolean = SettingsStore.DEFAULT_AUTO_BUYPRICE_ENABLED,
        // 买入价控件读取（无障碍）
        val nodeRegionLeft: Float = SettingsStore.DEFAULT_NODE_REGION_LEFT,
        val nodeRegionTop: Float = SettingsStore.DEFAULT_NODE_REGION_TOP,
        val nodeRegionRight: Float = SettingsStore.DEFAULT_NODE_REGION_RIGHT,
        val nodeRegionBottom: Float = SettingsStore.DEFAULT_NODE_REGION_BOTTOM,
        val nodeClass: String = "",
        val nodeWidth: Int = SettingsStore.DEFAULT_NODE_WIDTH,
        val nodeHeight: Int = SettingsStore.DEFAULT_NODE_HEIGHT,
        val nodeSizeTol: Int = SettingsStore.DEFAULT_NODE_SIZE_TOL,
        val nodeIndex: Int = SettingsStore.DEFAULT_NODE_INDEX,
        val nodeReadDelayMs: Int = SettingsStore.DEFAULT_NODE_READ_DELAY_MS,
        val nodeLongpressMs: Int = SettingsStore.DEFAULT_NODE_LONGPRESS_MS,
        val loaded: Boolean = false,
        val saved: Boolean = false,
        // 连接测试
        val testCode: String = "",
        val testing: Boolean = false,
        val testResult: String? = null,
        val testError: Boolean = false,
        // 控件读取测试
        val nodeTesting: Boolean = false,
        val nodeTestResult: String? = null,
        val nodeTestError: Boolean = false,
        // backup / restore
        val busy: Boolean = false,
        val opMessage: String? = null,
        // settings QR share (non-null value triggers the QR dialog)
        val qrShareText: String? = null,
    )

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    init {
        load()
    }

    private fun load() {
        viewModelScope.launch {
            _ui.value = buildStateFromStore()
        }
    }

    /** Build a fresh UiState from the persisted settings (reused by load and after restore). */
    private suspend fun buildStateFromStore(): UiState = UiState(
        quoteSourceType = store.get(SettingsStore.KEY_QUOTE_SOURCE_TYPE, SettingsStore.DEFAULT_QUOTE_SOURCE_TYPE),
        lanApiIp = store.get(SettingsStore.KEY_LAN_API_IP, ""),
        targetAppPackage = store.get(SettingsStore.KEY_TARGET_APP_PACKAGE, ""),
        codeViewId = store.get(SettingsStore.KEY_CODE_VIEW_ID, ""),
        nextButtonId = store.get(SettingsStore.KEY_NEXT_BUTTON_ID, ""),
        screenshotDelayMs = store.getInt(SettingsStore.KEY_SCREENSHOT_DELAY_MS, SettingsStore.DEFAULT_SCREENSHOT_DELAY_MS),
        ocrEnabled = store.getBool(SettingsStore.KEY_OCR_ENABLED, false),
        viewerPageDirection = store.get(
            SettingsStore.KEY_VIEWER_PAGE_DIRECTION,
            SettingsStore.DEFAULT_VIEWER_PAGE_DIRECTION,
        ),
        storageRoot = store.get(SettingsStore.KEY_STORAGE_ROOT, ""),
        postReadDelayMs = store.getInt(SettingsStore.KEY_POST_READ_DELAY_MS, SettingsStore.DEFAULT_POST_READ_DELAY_MS),
        closeCrosshairMode = store.get(SettingsStore.KEY_CLOSE_CROSSHAIR_MODE, SettingsStore.DEFAULT_CLOSE_CROSSHAIR_MODE),
        capitalRequireYellow = store.getBool(SettingsStore.KEY_CAPITAL_REQUIRE_YELLOW, SettingsStore.DEFAULT_CAPITAL_REQUIRE_YELLOW),
        capitalMethod = store.getCapitalMethod(),
        capitalRule = store.getCapitalRule(),
        signalSelection = store.getSignalSelection(),
        capitalMajorityThreshold = store.getCapitalMajorityThreshold(),
        dotToleranceScale = store.getFloat(SettingsStore.KEY_DOT_TOL_SCALE, SettingsStore.DEFAULT_DOT_TOL_SCALE),
        minConfidence = store.getFloat(SettingsStore.KEY_MIN_CONFIDENCE, SettingsStore.DEFAULT_MIN_CONFIDENCE),
        capitalWindowScale = store.getFloat(SettingsStore.KEY_CAPITAL_WINDOW_SCALE, SettingsStore.DEFAULT_CAPITAL_WINDOW_SCALE),
        autoDetectEnabled = store.getBool(SettingsStore.KEY_AUTO_DETECT_ENABLED, SettingsStore.DEFAULT_AUTO_DETECT_ENABLED),
        autoMarkEnabled = store.getBool(SettingsStore.KEY_AUTO_MARK_ENABLED, SettingsStore.DEFAULT_AUTO_MARK_ENABLED),
        autoBuyPriceEnabled = store.getBool(SettingsStore.KEY_AUTO_BUYPRICE_ENABLED, SettingsStore.DEFAULT_AUTO_BUYPRICE_ENABLED),
        nodeRegionLeft = store.getFloat(SettingsStore.KEY_NODE_REGION_LEFT, SettingsStore.DEFAULT_NODE_REGION_LEFT),
        nodeRegionTop = store.getFloat(SettingsStore.KEY_NODE_REGION_TOP, SettingsStore.DEFAULT_NODE_REGION_TOP),
        nodeRegionRight = store.getFloat(SettingsStore.KEY_NODE_REGION_RIGHT, SettingsStore.DEFAULT_NODE_REGION_RIGHT),
        nodeRegionBottom = store.getFloat(SettingsStore.KEY_NODE_REGION_BOTTOM, SettingsStore.DEFAULT_NODE_REGION_BOTTOM),
        nodeClass = store.get(SettingsStore.KEY_NODE_CLASS, ""),
        nodeWidth = store.getInt(SettingsStore.KEY_NODE_WIDTH, SettingsStore.DEFAULT_NODE_WIDTH),
        nodeHeight = store.getInt(SettingsStore.KEY_NODE_HEIGHT, SettingsStore.DEFAULT_NODE_HEIGHT),
        nodeSizeTol = store.getInt(SettingsStore.KEY_NODE_SIZE_TOL, SettingsStore.DEFAULT_NODE_SIZE_TOL),
        nodeIndex = store.getInt(SettingsStore.KEY_NODE_INDEX, SettingsStore.DEFAULT_NODE_INDEX),
        nodeReadDelayMs = store.getInt(SettingsStore.KEY_NODE_READ_DELAY_MS, SettingsStore.DEFAULT_NODE_READ_DELAY_MS),
        nodeLongpressMs = store.getInt(SettingsStore.KEY_NODE_LONGPRESS_MS, SettingsStore.DEFAULT_NODE_LONGPRESS_MS),
        loaded = true,
    )

    fun update(transform: (UiState) -> UiState) {
        _ui.value = transform(_ui.value).copy(saved = false)
    }

    fun save() {
        val s = _ui.value.let { state ->
            state.copy(
                capitalMethod = SettingsStore.normalizeCapitalMethod(state.capitalMethod),
                capitalRule = SettingsStore.normalizeCapitalRule(state.capitalRule),
                signalSelection = SettingsStore.normalizeSignalSelection(state.signalSelection),
                capitalMajorityThreshold = SettingsStore.normalizeCapitalMajorityThreshold(state.capitalMajorityThreshold),
            )
        }
        _ui.value = s
        viewModelScope.launch {
            store.put(SettingsStore.KEY_QUOTE_SOURCE_TYPE, s.quoteSourceType)
            store.put(SettingsStore.KEY_LAN_API_IP, s.lanApiIp.trim())
            store.put(SettingsStore.KEY_TARGET_APP_PACKAGE, s.targetAppPackage)
            store.put(SettingsStore.KEY_CODE_VIEW_ID, s.codeViewId)
            store.put(SettingsStore.KEY_NEXT_BUTTON_ID, s.nextButtonId)
            store.put(SettingsStore.KEY_SCREENSHOT_DELAY_MS, s.screenshotDelayMs.toString())
            store.put(SettingsStore.KEY_OCR_ENABLED, s.ocrEnabled.toString())
            store.put(SettingsStore.KEY_VIEWER_PAGE_DIRECTION, s.viewerPageDirection)
            store.put(SettingsStore.KEY_STORAGE_ROOT, s.storageRoot.trim())
            store.put(SettingsStore.KEY_POST_READ_DELAY_MS, s.postReadDelayMs.toString())
            store.put(SettingsStore.KEY_CLOSE_CROSSHAIR_MODE, s.closeCrosshairMode)
            store.put(SettingsStore.KEY_CAPITAL_REQUIRE_YELLOW, s.capitalRequireYellow.toString())
            store.put(SettingsStore.KEY_CAPITAL_METHOD, s.capitalMethod)
            store.put(SettingsStore.KEY_CAPITAL_RULE, s.capitalRule)
            store.put(SettingsStore.KEY_SIGNAL_SELECTION, s.signalSelection)
            store.put(SettingsStore.KEY_CAPITAL_MAJORITY_THRESHOLD, s.capitalMajorityThreshold.toString())
            store.put(SettingsStore.KEY_DOT_TOL_SCALE, s.dotToleranceScale.toString())
            store.put(SettingsStore.KEY_MIN_CONFIDENCE, s.minConfidence.toString())
            store.put(SettingsStore.KEY_CAPITAL_WINDOW_SCALE, s.capitalWindowScale.toString())
            store.put(SettingsStore.KEY_AUTO_DETECT_ENABLED, s.autoDetectEnabled.toString())
            store.put(SettingsStore.KEY_AUTO_MARK_ENABLED, s.autoMarkEnabled.toString())
            store.put(SettingsStore.KEY_AUTO_BUYPRICE_ENABLED, s.autoBuyPriceEnabled.toString())
            store.put(SettingsStore.KEY_NODE_REGION_LEFT, s.nodeRegionLeft.toString())
            store.put(SettingsStore.KEY_NODE_REGION_TOP, s.nodeRegionTop.toString())
            store.put(SettingsStore.KEY_NODE_REGION_RIGHT, s.nodeRegionRight.toString())
            store.put(SettingsStore.KEY_NODE_REGION_BOTTOM, s.nodeRegionBottom.toString())
            store.put(SettingsStore.KEY_NODE_CLASS, s.nodeClass.trim())
            store.put(SettingsStore.KEY_NODE_WIDTH, s.nodeWidth.toString())
            store.put(SettingsStore.KEY_NODE_HEIGHT, s.nodeHeight.toString())
            store.put(SettingsStore.KEY_NODE_SIZE_TOL, s.nodeSizeTol.toString())
            store.put(SettingsStore.KEY_NODE_INDEX, s.nodeIndex.toString())
            store.put(SettingsStore.KEY_NODE_READ_DELAY_MS, s.nodeReadDelayMs.toString())
            store.put(SettingsStore.KEY_NODE_LONGPRESS_MS, s.nodeLongpressMs.toString())
            _ui.value = _ui.value.copy(saved = true)
        }
    }

    fun consumeSaved() {
        if (_ui.value.saved) _ui.value = _ui.value.copy(saved = false)
    }

    fun consumeOpMessage() {
        if (_ui.value.opMessage != null) _ui.value = _ui.value.copy(opMessage = null)
    }

    /** Build a share code string for the current sharable settings and open the QR dialog. */
    fun generateShareCode() {
        if (_ui.value.busy) return
        viewModelScope.launch {
            val text = withContext(Dispatchers.IO) { QrShareUtils.buildShareText(store) }
            _ui.value = _ui.value.copy(qrShareText = text)
        }
    }

    fun consumeShareText() {
        if (_ui.value.qrShareText != null) _ui.value = _ui.value.copy(qrShareText = null)
    }

    /** Apply settings decoded from a scanned QR code, then refresh the UI state. */
    fun importFromScan(scanned: String?) {
        if (_ui.value.busy) return
        val items = QrShareUtils.parseShareText(scanned)
        if (items == null) {
            _ui.value = _ui.value.copy(
                opMessage = "\u4e8c\u7ef4\u7801\u65e0\u6548\u6216\u975e\u672c\u7a0b\u5e8f\u8bbe\u7f6e\u7801",
            )
            return
        }
        _ui.value = _ui.value.copy(busy = true, opMessage = null)
        viewModelScope.launch {
            val applied = withContext(Dispatchers.IO) { QrShareUtils.applyShared(store, items) }
            val fresh = buildStateFromStore()
            _ui.value = fresh.copy(
                busy = false,
                opMessage = "\u5df2\u5bfc\u5165 $applied \u9879\u8bbe\u7f6e",
            )
        }
    }

    /** Export settings + records + marks (no image files) to a user-chosen JSON file. */
    fun backupTo(uri: Uri) {
        if (_ui.value.busy) return
        _ui.value = _ui.value.copy(busy = true, opMessage = null)
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                BackupUtils.backup(getApplication(), uri, repository, store)
            }
            val msg = if (result.ok) {
                "\u5907\u4efd\u5b8c\u6210\uff1a\u8bb0\u5f55 ${result.recordCount} \u6761\uff0c\u6807\u8bb0 ${result.markCount} \u6761\uff0c\u8bbe\u7f6e ${result.settingCount} \u9879\u3002\u672a\u5305\u542b\u56fe\u7247\u6587\u4ef6\uff08\u4ec5\u5907\u4efd\u5173\u8054\u4fe1\u606f\uff09\u3002"
            } else {
                "\u5907\u4efd\u5931\u8d25\uff1a${result.error ?: "\u672a\u77e5\u9519\u8bef"}"
            }
            _ui.value = _ui.value.copy(busy = false, opMessage = msg)
        }
    }

    /** Restore settings + records + marks from a JSON backup, then reload UI state. */
    fun restoreFrom(uri: Uri) {
        if (_ui.value.busy) return
        _ui.value = _ui.value.copy(busy = true, opMessage = null)
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                BackupUtils.restore(getApplication(), uri, repository, store)
            }
            val fresh = buildStateFromStore()
            val msg = if (result.ok) {
                val sb = StringBuilder()
                sb.append("\u6062\u590d\u5b8c\u6210\uff1a\u8bb0\u5f55 ${result.recordCount} \u6761\uff0c\u6807\u8bb0 ${result.markCount} \u6761\uff0c\u8bbe\u7f6e ${result.settingCount} \u9879\u3002")
                sb.append("\n\u56fe\u7247\u672a\u542b\u5728\u5907\u4efd\u5185\uff0c\u8bf7\u5c06\u65e7\u56fe\u7247\u6309 {\u4ee3\u7801}/{\u6587\u4ef6\u540d} \u7684\u76ee\u5f55\u7ed3\u6784\u653e\u56de\uff1a\n${result.rootPath}")
                if (result.missingImages.isNotEmpty()) {
                    sb.append("\n\n\u26a0\ufe0f \u68c0\u6d4b\u5230 ${result.missingImages.size} \u5f20\u56fe\u7247\u5c1a\u672a\u5c31\u4f4d\uff0c\u9700\u624b\u52a8\u653e\u56de\u4ee5\u4e0b\u4f4d\u7f6e\uff1a")
                    result.missingImages.take(20).forEach { sb.append("\n\u2022 $it") }
                    if (result.missingImages.size > 20) {
                        sb.append("\n\u2026 \u7b49\u5171 ${result.missingImages.size} \u5f20")
                    }
                }
                sb.toString()
            } else {
                "\u6062\u590d\u5931\u8d25\uff1a${result.error ?: "\u672a\u77e5\u9519\u8bef"}"
            }
            _ui.value = fresh.copy(busy = false, opMessage = msg)
        }
    }

    /** 无障碍控件读取测试：用当前界面参数查找控件并回显命中文本/候选数。 */
    fun runNodeReadTest() {
        val s = _ui.value
        val svc = ScreenshotAccessibilityService.getInstance()
        if (svc == null) {
            _ui.value = s.copy(nodeTestResult = "无障碍服务未连接，请先在系统设置开启", nodeTestError = true)
            return
        }
        _ui.value = s.copy(nodeTesting = true, nodeTestResult = null, nodeTestError = false)
        viewModelScope.launch {
            val cfg = ScreenshotAccessibilityService.NodeQueryConfig(
                regionLeft = s.nodeRegionLeft,
                regionTop = s.nodeRegionTop,
                regionRight = s.nodeRegionRight,
                regionBottom = s.nodeRegionBottom,
                className = s.nodeClass.trim().ifBlank { null },
                width = s.nodeWidth,
                height = s.nodeHeight,
                sizeTolerancePx = s.nodeSizeTol,
                resultIndex = s.nodeIndex,
            )
            val sb = StringBuilder()
            var isError = false
            try {
                val r = svc.findNodeText(cfg)
                if (r.text.isBlank() && r.candidateCount == 0) {
                    isError = true
                    sb.appendLine("未找到匹配控件（候选 0 个）")
                    sb.appendLine("请检查区域范围/类名/宽高/容差设置")
                } else {
                    sb.appendLine("命中文本: ${r.text.ifBlank { "(空)" }}")
                    sb.appendLine("候选数量: ${r.candidateCount}")
                    sb.appendLine("控件类名: ${r.className ?: "--"}")
                    sb.appendLine("控件区域: ${r.bounds}")
                }
            } catch (e: Exception) {
                isError = true
                sb.appendLine("测试异常：${e.message}")
            }
            _ui.value = _ui.value.copy(
                nodeTesting = false,
                nodeTestResult = sb.toString().trimEnd(),
                nodeTestError = isError,
            )
        }
    }

    /** 连接测试：用当前界面选择的数据源拉取单只股票，展示结果与耗时。 */
    fun runQuoteSourceTest() {
        val s = _ui.value
        val code = s.testCode.trim()
        if (!Regex("\\d{6}").matches(code)) {
            _ui.value = s.copy(testResult = "请输入 6 位数字股票代码", testError = true)
            return
        }
        val type = s.quoteSourceType
        val ip = s.lanApiIp.trim()
        _ui.value = s.copy(testing = true, testResult = null, testError = false)
        viewModelScope.launch {
            val sb = StringBuilder()
            val label = when (type) {
                SettingsStore.QUOTE_SOURCE_PROVIDER -> "接口获取 (ContentProvider)"
                SettingsStore.QUOTE_SOURCE_LAN -> "局域网 API ($ip:8080)"
                else -> "新浪行情"
            }
            sb.appendLine("数据源: $label")
            sb.appendLine("股票代码: $code")
            sb.appendLine("---")
            var isError = false
            val start = System.currentTimeMillis()
            try {
                val source = StockQuoteSourceFactory.create(type, ip)
                val quote = source.fetchQuote(getApplication(), code)
                val elapsed = System.currentTimeMillis() - start
                if (quote != null) {
                    sb.appendLine("获取成功（${elapsed}ms）")
                    if (!quote.name.isNullOrBlank()) sb.appendLine("名称:   ${quote.name}")
                    sb.appendLine("买入价(开盘): ${quote.open}")
                    sb.appendLine("卖出价(当前): ${quote.current}")
                } else {
                    isError = true
                    sb.appendLine("返回空值（${elapsed}ms）")
                    sb.appendLine()
                    sb.appendLine("可能原因：")
                    when (type) {
                        SettingsStore.QUOTE_SOURCE_PROVIDER ->
                            sb.appendLine("- 数据提供方 APP 未安装或未运行\n- 该股票代码在提供方 APP 中不存在")
                        SettingsStore.QUOTE_SOURCE_LAN ->
                            sb.appendLine("- 服务端 IP 不正确\n- 服务端未启动或端口不通\n- 不在同一 Wi-Fi 网络")
                        else ->
                            sb.appendLine("- 网络不可用\n- 股票代码不存在\n- 新浪行情接口临时不可用")
                    }
                }
            } catch (e: Exception) {
                isError = true
                sb.appendLine("请求异常：${e.message}")
            }
            _ui.value = _ui.value.copy(
                testing = false,
                testResult = sb.toString().trimEnd(),
                testError = isError,
            )
        }
    }
}