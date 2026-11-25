package com.stockshot.manager.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.Path
import android.graphics.Rect
import android.provider.Settings
import android.util.DisplayMetrics
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * 无障碍服务：读取目标 APP 指定控件文本（股票代码），并模拟点击“下一只”按钮。
 * 借鉴 ControlMonitor 的控件查找 / viewId 文本读取 / 手势点击方式。
 */
class ScreenshotAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile
        private var instance: ScreenshotAccessibilityService? = null

        fun getInstance(): ScreenshotAccessibilityService? = instance

        fun isEnabled(context: Context): Boolean {
            val enabled = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            val target = "${context.packageName}/com.stockshot.manager.service.ScreenshotAccessibilityService"
            return enabled.split(":").any { it.equals(target, ignoreCase = true) }
        }

        private val CODE_REGEX = Regex("""(?<!\d)(\d{6})(?!\d)""")
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onDestroy() {
        super.onDestroy()
        if (instance === this) instance = null
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) { /* 主动轮询，无需响应事件 */ }

    override fun onInterrupt() {}

    /** 返回键：收起十字光标/报价浮层等临时界面。成功返回 true。 */
    fun back(): Boolean = try {
        performGlobalAction(GLOBAL_ACTION_BACK)
    } catch (_: Exception) {
        false
    }

    // ---------------------------------------------------------------------
    // 控件识别：多级备用方案
    // ---------------------------------------------------------------------

    /**
     * 读取当前页股票代码。
     * 优先级：1) viewId 文本  2) 正则匹配页面 6 位数字文本
     */
    fun readStockCode(codeViewId: String?): String? {
        val root = rootInActiveWindow ?: return null
        try {
            // 方案 1：viewId
            if (!codeViewId.isNullOrBlank()) {
                val byId = findTextByViewId(root, codeViewId)
                val code = byId?.let { extractCode(it) }
                if (code != null) return code
            }
            // 方案 2：正则扫描全部文本节点
            return scanForCode(root)
        } finally {
            root.recycle()
        }
    }

    private fun findTextByViewId(root: AccessibilityNodeInfo, viewId: String): String? {
        val id = if (viewId.contains("/")) viewId else "${packageName}:id/$viewId"
        val nodes = try {
            root.findAccessibilityNodeInfosByViewId(id)
        } catch (_: Exception) {
            null
        } ?: return null
        for (n in nodes) {
            val t = n.text?.toString() ?: n.contentDescription?.toString()
            n.recycle()
            if (!t.isNullOrBlank()) return t
        }
        return null
    }

    private fun scanForCode(root: AccessibilityNodeInfo): String? {
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.addLast(root)
        while (stack.isNotEmpty()) {
            val node = stack.removeLast()
            val text = node.text?.toString()
            if (!text.isNullOrBlank()) {
                val code = extractCode(text)
                if (code != null) return code
            }
            val desc = node.contentDescription?.toString()
            if (!desc.isNullOrBlank()) {
                val code = extractCode(desc)
                if (code != null) return code
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { stack.addLast(it) }
            }
        }
        return null
    }

    private fun extractCode(text: String): String? =
        CODE_REGEX.find(text)?.groupValues?.getOrNull(1)

    // ---------------------------------------------------------------------
    // 点击“下一只”按钮
    // ---------------------------------------------------------------------

    /** 通过 viewId 点击“下一只”。成功返回 true。 */
    fun clickNext(nextButtonId: String?): Boolean {
        if (nextButtonId.isNullOrBlank()) return false
        val root = rootInActiveWindow ?: return false
        try {
            val id = if (nextButtonId.contains("/")) nextButtonId else "${packageName}:id/$nextButtonId"
            val nodes = try {
                root.findAccessibilityNodeInfosByViewId(id)
            } catch (_: Exception) {
                null
            } ?: return false
            for (n in nodes) {
                if (performClickOnNode(n)) {
                    n.recycle()
                    return true
                }
                n.recycle()
            }
            return false
        } finally {
            root.recycle()
        }
    }

    /** 通过文本内容点击（备用，如“下一只 / 下一个 / 下一支”）。 */
    fun clickByText(vararg candidates: String): Boolean {
        val root = rootInActiveWindow ?: return false
        try {
            for (c in candidates) {
                val nodes = root.findAccessibilityNodeInfosByText(c) ?: continue
                for (n in nodes) {
                    if (n.text?.toString()?.contains(c) == true && performClickOnNode(n)) {
                        n.recycle()
                        return true
                    }
                    n.recycle()
                }
            }
            return false
        } finally {
            root.recycle()
        }
    }

    private fun performClickOnNode(node: AccessibilityNodeInfo): Boolean {
        var target: AccessibilityNodeInfo? = node
        var depth = 0
        while (target != null && depth < 6) {
            if (target.isClickable) {
                return target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
            target = target.parent
            depth++
        }
        // 退化为坐标点击（手势）
        val rect = Rect()
        node.getBoundsInScreen(rect)
        return false.also { /* 手势点击由 AutomationManager 触发 dispatchGesture */ }
    }

    fun nodeBounds(nextButtonId: String?): Rect? {
        if (nextButtonId.isNullOrBlank()) return null
        val root = rootInActiveWindow ?: return null
        try {
            val id = if (nextButtonId.contains("/")) nextButtonId else "${packageName}:id/$nextButtonId"
            val nodes = root.findAccessibilityNodeInfosByViewId(id) ?: return null
            for (n in nodes) {
                val rect = Rect()
                n.getBoundsInScreen(rect)
                n.recycle()
                if (!rect.isEmpty) return rect
            }
            return null
        } finally {
            root.recycle()
        }
    }

    // ---------------------------------------------------------------------
    // 通用控件读取（功能 1）：按区域/类名/宽高/序号条件查找并读取控件文本
    // ---------------------------------------------------------------------

    /**
     * 控件查找条件。
     * region* 为归一化坐标（0..1），按屏幕真实分辨率换算像素矩形；
     * 节点中心点落在区域内视为候选。
     * className 非空时精确匹配；width/height>0 时按 sizeTolerancePx 像素容差匹配宽/高。
     * 候选按 top→left 稳定排序，取第 resultIndex 个（从 1 开始）。
     */
    data class NodeQueryConfig(
        val regionLeft: Float = 0f,
        val regionTop: Float = 0f,
        val regionRight: Float = 1f,
        val regionBottom: Float = 1f,
        val className: String? = null,
        val width: Int = 0,
        val height: Int = 0,
        val sizeTolerancePx: Int = 20,
        val resultIndex: Int = 1,
    )

    data class NodeTextResult(
        val text: String,
        val bounds: Rect,
        val candidateCount: Int,
        val className: String?,
    )

    /** 屏幕真实分辨率（含状态栏/导航栏），与截图/识别坐标系保持一致。 */
    private fun realScreenSize(): Pair<Int, Int> {
        return try {
            val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(metrics)
            metrics.widthPixels to metrics.heightPixels
        } catch (_: Exception) {
            val dm = resources.displayMetrics
            dm.widthPixels to dm.heightPixels
        }
    }

    /**
     * 按条件查找控件并读取文本。找不到时返回候选数（可能为 0）与空文本，绝不抛异常。
     */
    fun findNodeText(cfg: NodeQueryConfig): NodeTextResult {
        val root = rootInActiveWindow ?: return NodeTextResult("", Rect(), 0, null)
        try {
            val (sw, sh) = realScreenSize()
            val left = (minOf(cfg.regionLeft, cfg.regionRight) * sw).toInt()
            val right = (maxOf(cfg.regionLeft, cfg.regionRight) * sw).toInt()
            val top = (minOf(cfg.regionTop, cfg.regionBottom) * sh).toInt()
            val bottom = (maxOf(cfg.regionTop, cfg.regionBottom) * sh).toInt()
            val region = Rect(left, top, right, bottom)

            val candidates = ArrayList<AccessibilityNodeInfo>()
            val stack = ArrayDeque<AccessibilityNodeInfo>()
            stack.addLast(root)
            while (stack.isNotEmpty()) {
                val node = stack.removeLast()
                for (i in 0 until node.childCount) {
                    node.getChild(i)?.let { stack.addLast(it) }
                }
                if (!node.isVisibleToUser) continue
                val rect = Rect()
                node.getBoundsInScreen(rect)
                if (rect.isEmpty) continue
                // 中心点落在区域内
                val cx = rect.centerX()
                val cy = rect.centerY()
                if (!region.isEmpty && !region.contains(cx, cy)) continue
                // className 精确匹配
                if (!cfg.className.isNullOrBlank() &&
                    node.className?.toString() != cfg.className
                ) continue
                // 宽/高像素容差匹配
                if (cfg.width > 0 && kotlin.math.abs(rect.width() - cfg.width) > cfg.sizeTolerancePx) continue
                if (cfg.height > 0 && kotlin.math.abs(rect.height() - cfg.height) > cfg.sizeTolerancePx) continue
                candidates.add(AccessibilityNodeInfo.obtain(node))
            }

            // 稳定排序：top → left
            candidates.sortWith(compareBy({ nodeTop(it) }, { nodeLeft(it) }))
            val count = candidates.size
            val idx = cfg.resultIndex - 1
            val chosen = candidates.getOrNull(idx)
            val result = if (chosen != null) {
                val rect = Rect()
                chosen.getBoundsInScreen(rect)
                val text = chosen.text?.toString()?.takeIf { it.isNotBlank() }
                    ?: chosen.contentDescription?.toString()?.takeIf { it.isNotBlank() }
                    ?: ""
                NodeTextResult(text, rect, count, chosen.className?.toString())
            } else {
                NodeTextResult("", Rect(), count, null)
            }
            candidates.forEach { runCatching { it.recycle() } }
            return result
        } catch (_: Exception) {
            return NodeTextResult("", Rect(), 0, null)
        } finally {
            root.recycle()
        }
    }

    private fun nodeTop(n: AccessibilityNodeInfo): Int {
        val r = Rect(); n.getBoundsInScreen(r); return r.top
    }

    private fun nodeLeft(n: AccessibilityNodeInfo): Int {
        val r = Rect(); n.getBoundsInScreen(r); return r.left
    }

    // ---------------------------------------------------------------------
    // 手势：长按 / 点击（dispatchGesture）
    // ---------------------------------------------------------------------

    /** 在屏幕像素坐标 (x,y) 处长按 durationMs 毫秒。成功返回 true。 */
    suspend fun longPress(x: Float, y: Float, durationMs: Long): Boolean =
        dispatchGestureAt(x, y, durationMs.coerceAtLeast(1L))

    /** 在屏幕像素坐标 (x,y) 处轻点。成功返回 true。 */
    suspend fun tap(x: Float, y: Float): Boolean = dispatchGestureAt(x, y, 60L)

    private suspend fun dispatchGestureAt(x: Float, y: Float, durationMs: Long): Boolean =
        suspendCancellableCoroutine { cont ->
            try {
                val path = Path().apply { moveTo(x, y) }
                val stroke = GestureDescription.StrokeDescription(path, 0L, durationMs)
                val gesture = GestureDescription.Builder().addStroke(stroke).build()
                val ok = dispatchGesture(gesture, object : GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        if (cont.isActive) cont.resume(true)
                    }

                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        if (cont.isActive) cont.resume(false)
                    }
                }, null)
                if (!ok && cont.isActive) cont.resume(false)
            } catch (_: Exception) {
                if (cont.isActive) cont.resume(false)
            }
        }
}
