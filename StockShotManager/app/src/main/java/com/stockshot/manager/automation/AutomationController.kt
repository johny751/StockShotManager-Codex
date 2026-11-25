package com.stockshot.manager.automation

import android.content.Context
import android.widget.Toast
import com.stockshot.manager.capture.MediaProjectionRequestActivity
import com.stockshot.manager.overlay.FloatControlWindow
import com.stockshot.manager.service.ScreenCaptureService
import com.stockshot.manager.util.AppLogger
import com.stockshot.manager.util.PermissionUtils
import java.time.LocalDate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * 自动化任务编排器（单例）。
 *
 * 职责：权限校验 -> 申请投屏 -> 启动截图服务 -> 显示悬浮控制窗 -> 启动采集循环，
 * 并在任务结束时自动收起悬浮窗。
 */
object AutomationController {

    private const val TAG = "AutoController"
    private const val PROJECTION_SETTLE_MS = 1200L

    private var floatWindow: FloatControlWindow? = null
    private var watchJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Main)

    @Volatile private var launchPending = false

    /**
     * 从 UI 触发。返回 null 表示已发起流程；否则返回需要用户处理的错误提示。
     */
    fun launch(
        context: Context,
        targetDate: String,
        autoDetect: Boolean = false,
        autoMark: Boolean = false,
        autoBuyPrice: Boolean = false,
        requireYellow: Boolean = false,
    ): String? {
        val app = context.applicationContext
        if (!PermissionUtils.isAccessibilityEnabled(app)) {
            return "请先开启无障碍服务"
        }
        if (!PermissionUtils.hasOverlayPermission(app)) {
            return "请先授予悬浮窗权限"
        }
        if (AutomationManager.isRunning() || launchPending) {
            return "任务已在运行中"
        }
        if (runCatching { LocalDate.parse(targetDate) }.isFailure) {
            return "采集目标日期无效"
        }

        launchPending = true
        AppLogger.i(TAG, "launch targetDate=$targetDate autoDetect=$autoDetect autoMark=$autoMark autoBuyPrice=$autoBuyPrice")

        return try {
            // Finish the previous run's cleanup before requesting a new projection.
            // Its delayed observer must never stop the next run's capture service.
            watchJob?.cancel()
            watchJob = null
            floatWindow?.hide()
            stopCaptureService(app)
            MediaProjectionRequestActivity.request(
                app,
                onGranted = {
                    onProjectionGranted(app, targetDate, autoDetect, autoMark, autoBuyPrice, requireYellow)
                },
                onDenied = {
                    launchPending = false
                    Toast.makeText(app, "已取消屏幕录制授权", Toast.LENGTH_SHORT).show()
                }
            )
            null
        } catch (e: Exception) {
            launchPending = false
            AppLogger.e(TAG, "无法发起投屏授权", e)
            "无法发起屏幕录制授权，请重试"
        }
    }

    private fun onProjectionGranted(
        context: Context,
        targetDate: String,
        autoDetect: Boolean,
        autoMark: Boolean,
        autoBuyPrice: Boolean,
        requireYellow: Boolean,
    ) {
        scope.launch {
            try {
                // 等待截图服务建立 VirtualDisplay；本次目标日期与开关由授权回调固定传入。
                var waited = 0L
                while (!ScreenCaptureService.isRunning() && waited < 4000L) {
                    delay(200L)
                    waited += 200L
                }
                if (!ScreenCaptureService.isRunning()) {
                    Toast.makeText(context, "截图服务启动失败", Toast.LENGTH_SHORT).show()
                    return@launch
                }
                delay(PROJECTION_SETTLE_MS)

                showFloatWindow(context)
                AutomationManager.start(
                    context,
                    targetDate = targetDate,
                    autoDetect = autoDetect,
                    autoMark = autoMark,
                    autoBuyPrice = autoBuyPrice,
                    requireYellow = requireYellow,
                )
                watchCompletion(context)
            } finally {
                launchPending = false
            }
        }
    }

    private fun showFloatWindow(context: Context) {
        if (floatWindow == null) floatWindow = FloatControlWindow(context)
        floatWindow?.show()
    }

    private fun watchCompletion(context: Context) {
        watchJob?.cancel()
        watchJob = scope.launch {
            AutomationManager.progress.collectLatest { p ->
                // Even an immediately failed run needs its overlay and capture service cleaned up.
                if (!p.running) {
                    // 任务结束：收起悬浮窗，停止截图服务
                    delay(800L)
                    floatWindow?.hide()
                    stopCaptureService(context)
                    AppLogger.i(TAG, "任务结束，已清理悬浮窗与截图服务")
                }
            }
        }
    }

    private fun stopCaptureService(context: Context) {
        try {
            ScreenCaptureService.getInstance()?.let {
                context.stopService(
                    android.content.Intent(context, ScreenCaptureService::class.java)
                )
            }
        } catch (_: Exception) {
        }
    }

    /** 手动收起（例如页面退出时）。 */
    fun dismiss(context: Context) {
        AutomationManager.stop("已停止")
        floatWindow?.hide()
        stopCaptureService(context)
    }
}
