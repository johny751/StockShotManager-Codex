package com.stockshot.manager.data

import android.content.Context
import com.stockshot.manager.data.entity.AppSetting

/**
 * 閰嶇疆椤瑰瓨鍙栥€傚熀浜?Room 鐨?app_settings 琛紝鍚屾椂鎻愪緵鍚屾缂撳瓨渚?Service 蹇€熻鍙栥€?
 * 閿€煎鍚箟瑙侀渶姹傛枃妗?AppSetting 琛ㄣ€?
 */
class SettingsStore(context: Context) {

    private val appContext = context.applicationContext
    private val dao = AppDatabase.getDatabase(appContext).appSettingDao()

    // 缂撳瓨涓€浠介暅鍍忓埌 SharedPreferences锛屼緵鏃犳硶璧板崗绋嬬殑鍦烘櫙锛圫ervice锛夊悓姝ヨ鍙?
    private val prefs = appContext.getSharedPreferences("settings_cache", Context.MODE_PRIVATE)

    suspend fun get(key: String, default: String): String {
        val v = dao.getValue(key)
        return v ?: prefs.getString(key, default) ?: default
    }

    suspend fun put(key: String, value: String) {
        dao.upsert(AppSetting(key, value))
        prefs.edit().putString(key, value).apply()
    }

    /** 鍚屾璇诲彇锛堜粎渚?Service 蹇収锛屼笌 Room 缂撳瓨鍙屽啓淇濇寔涓€鑷达級 */
    fun getSync(key: String, default: String): String = prefs.getString(key, default) ?: default

    suspend fun getInt(key: String, default: Int): Int =
        get(key, default.toString()).toIntOrNull() ?: default

    suspend fun getBool(key: String, default: Boolean): Boolean =
        get(key, default.toString()).toBooleanStrictOrNull() ?: default

    suspend fun getFloat(key: String, default: Float): Float =
        get(key, default.toString()).toFloatOrNull() ?: default

    companion object {
        const val KEY_DATA_SOURCE_TYPE = "data_source_type"     // api / manual
        const val KEY_API_BASE_URL = "api_base_url"
        const val KEY_API_KEY = "api_key"
        const val KEY_TARGET_APP_PACKAGE = "target_app_package"
        const val KEY_CODE_VIEW_ID = "code_view_id"
        const val KEY_NEXT_BUTTON_ID = "next_button_id"
        const val KEY_SCREENSHOT_DELAY_MS = "screenshot_delay_ms"
        const val KEY_OCR_ENABLED = "ocr_enabled"
        const val KEY_VIEWER_PAGE_DIRECTION = "viewer_page_direction"

        // 图片保存根目录（绝对路径）。空字符串表示使用默认 Downloads/StockScreenshots。
        const val KEY_STORAGE_ROOT = "storage_root"

        const val DEFAULT_DATA_SOURCE_TYPE = "api"
        const val DEFAULT_SCREENSHOT_DELAY_MS = 2000
        const val VIEWER_PAGE_HORIZONTAL = "horizontal"
        const val VIEWER_PAGE_VERTICAL = "vertical"
        const val DEFAULT_VIEWER_PAGE_DIRECTION = VIEWER_PAGE_HORIZONTAL

        // 行情数据源（借鉴 ControlMonitor）：provider / lan / sina
        const val KEY_QUOTE_SOURCE_TYPE = "quote_source_type"
        const val KEY_LAN_API_IP = "lan_api_ip"

        const val QUOTE_SOURCE_PROVIDER = "provider"
        const val QUOTE_SOURCE_LAN = "lan"
        const val QUOTE_SOURCE_SINA = "sina"

        // 默认新浪行情，零配置即可用
        const val DEFAULT_QUOTE_SOURCE_TYPE = QUOTE_SOURCE_SINA

        // 截图前自动识别 / 标记 / 读价的默认开关（确认框默认值）
        const val KEY_AUTO_DETECT_ENABLED = "auto_detect_enabled"
        const val KEY_AUTO_MARK_ENABLED = "auto_mark_enabled"
        const val KEY_AUTO_BUYPRICE_ENABLED = "auto_buyprice_enabled"
        const val DEFAULT_AUTO_DETECT_ENABLED = true
        const val DEFAULT_AUTO_MARK_ENABLED = true
        const val DEFAULT_AUTO_BUYPRICE_ENABLED = true

        // 买入价控件读取（无障碍）：查找区域为归一化 0..1
        const val KEY_NODE_REGION_LEFT = "node_region_left"
        const val KEY_NODE_REGION_TOP = "node_region_top"
        const val KEY_NODE_REGION_RIGHT = "node_region_right"
        const val KEY_NODE_REGION_BOTTOM = "node_region_bottom"
        const val KEY_NODE_CLASS = "node_class"
        const val KEY_NODE_WIDTH = "node_width"
        const val KEY_NODE_HEIGHT = "node_height"
        const val KEY_NODE_SIZE_TOL = "node_size_tol"
        const val KEY_NODE_INDEX = "node_index"
        const val KEY_NODE_READ_DELAY_MS = "node_read_delay_ms"
        const val KEY_NODE_LONGPRESS_MS = "node_longpress_ms"

        const val DEFAULT_NODE_REGION_LEFT = 0f
        const val DEFAULT_NODE_REGION_TOP = 0f
        const val DEFAULT_NODE_REGION_RIGHT = 1f
        const val DEFAULT_NODE_REGION_BOTTOM = 1f
        const val DEFAULT_NODE_WIDTH = 0
        const val DEFAULT_NODE_HEIGHT = 0
        const val DEFAULT_NODE_SIZE_TOL = 20
        const val DEFAULT_NODE_INDEX = 1
        const val DEFAULT_NODE_READ_DELAY_MS = 400
        const val DEFAULT_NODE_LONGPRESS_MS = 1000

        // 读价后延迟（ms）：读到买入价后、关闭十字光标/点下一只之前的等待
        const val KEY_POST_READ_DELAY_MS = "post_read_delay_ms"
        const val DEFAULT_POST_READ_DELAY_MS = 600

        // 关闭十字光标方式：back(返回键) / tap_chart(点图表中央) / none(不处理)
        const val KEY_CLOSE_CROSSHAIR_MODE = "close_crosshair_mode"
        const val CLOSE_CROSSHAIR_BACK = "back"
        const val CLOSE_CROSSHAIR_TAP_CHART = "tap_chart"
        const val CLOSE_CROSSHAIR_NONE = "none"
        const val DEFAULT_CLOSE_CROSSHAIR_MODE = CLOSE_CROSSHAIR_BACK

        // 资金博弈双模式：true=要求紫、黄两条都在绿线上，false=仅紫线在绿线上
        const val KEY_CAPITAL_REQUIRE_YELLOW = "capital_require_yellow"
        const val DEFAULT_CAPITAL_REQUIRE_YELLOW = false

        // Recognition tuning thresholds (scheme B primary, C fallback).
        // dotToleranceScale multiplies the horizontal red/green dot pairing window (B/C).
        // minConfidence gates a match: below it the frame is treated as not matched (0 = off).
        const val KEY_DOT_TOL_SCALE = "dot_tol_scale"
        const val KEY_MIN_CONFIDENCE = "min_confidence"
        const val DEFAULT_DOT_TOL_SCALE = 1f
        const val DEFAULT_MIN_CONFIDENCE = 0f

        // 资金线关系只分析红绿点左侧一段时间的窗口（占 plot 宽的比例，忽略红绿点右侧数据）
        const val KEY_CAPITAL_WINDOW_SCALE = "capital_window_scale"
        const val DEFAULT_CAPITAL_WINDOW_SCALE = 0.4f
    }
}
