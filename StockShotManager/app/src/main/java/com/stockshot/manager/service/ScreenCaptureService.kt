package com.stockshot.manager.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.DisplayMetrics
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * 前台服务，持有 MediaProjection，按需截取当前屏幕。
 * 通过 startService(Intent) 传入投屏授权结果启动；单例暴露给 AutomationManager 调用截图。
 */
class ScreenCaptureService : Service() {

    companion object {
        private const val CHANNEL_ID = "screen_capture"
        private const val NOTIF_ID = 1001

        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"

        @Volatile
        private var instance: ScreenCaptureService? = null

        fun getInstance(): ScreenCaptureService? = instance
        fun isRunning(): Boolean = instance?.projection != null
    }

    private var projection: MediaProjection? = null
    private var imageReader: ImageReader? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var handlerThread: HandlerThread? = null
    private var bgHandler: Handler? = null

    private var width = 0
    private var height = 0
    private var density = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIF_ID, buildNotification())

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
        val data = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent?.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra(EXTRA_RESULT_DATA)
        }

        if (resultCode != 0 && data != null && projection == null) {
            setupProjection(resultCode, data)
        }
        return START_NOT_STICKY
    }

    private fun setupProjection(resultCode: Int, data: Intent) {
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projection = mpm.getMediaProjection(resultCode, data)
        projection?.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                teardown()
            }
        }, null)

        val metrics = DisplayMetrics()
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(metrics)
        width = metrics.widthPixels
        height = metrics.heightPixels
        density = metrics.densityDpi

        handlerThread = HandlerThread("capture-bg").also { it.start() }
        bgHandler = Handler(handlerThread!!.looper)

        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        virtualDisplay = projection?.createVirtualDisplay(
            "stockshot-capture",
            width, height, density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader!!.surface,
            null,
            bgHandler
        )
    }

    /** 截取一帧当前屏幕，返回 Bitmap（失败返回 null）。 */
    suspend fun capture(): Bitmap? {
        val reader = imageReader ?: return null
        // 先清空旧帧
        repeat(2) { reader.acquireLatestImage()?.close() }
        return suspendCancellableCoroutine { cont ->
            val listener = ImageReader.OnImageAvailableListener { r ->
                val image = r.acquireLatestImage()
                if (image != null) {
                    val bmp = imageToBitmap(image)
                    image.close()
                    r.setOnImageAvailableListener(null, null)
                    if (cont.isActive) cont.resume(bmp)
                }
            }
            reader.setOnImageAvailableListener(listener, bgHandler)
            cont.invokeOnCancellation { reader.setOnImageAvailableListener(null, null) }
        }
    }

    private fun imageToBitmap(image: Image): Bitmap? {
        return try {
            val plane = image.planes[0]
            val buffer = plane.buffer
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val rowPadding = rowStride - pixelStride * width
            val bmpWidth = width + rowPadding / pixelStride
            val full = Bitmap.createBitmap(bmpWidth, height, Bitmap.Config.ARGB_8888)
            full.copyPixelsFromBuffer(buffer)
            if (rowPadding == 0) {
                full
            } else {
                val cropped = Bitmap.createBitmap(full, 0, 0, width, height)
                full.recycle()
                cropped
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun teardown() {
        try { virtualDisplay?.release() } catch (_: Exception) {}
        try { imageReader?.close() } catch (_: Exception) {}
        try { projection?.stop() } catch (_: Exception) {}
        virtualDisplay = null
        imageReader = null
        projection = null
        handlerThread?.quitSafely()
        handlerThread = null
        bgHandler = null
    }

    override fun onDestroy() {
        teardown()
        if (instance === this) instance = null
        super.onDestroy()
    }

    private fun createChannel() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID, "屏幕截图服务", NotificationManager.IMPORTANCE_LOW
        )
        nm.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("股票截图")
            .setContentText("截图服务运行中")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true)
            .build()
}
