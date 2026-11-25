package com.stockshot.manager.capture

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import com.stockshot.manager.service.ScreenCaptureService
import com.stockshot.manager.util.AppLogger

/**
 * 透明 Activity：请求 MediaProjection 授权，把 resultCode/data 传给 ScreenCaptureService。
 * 授权成功后回调 onGranted，失败回调 onDenied。
 */
class MediaProjectionRequestActivity : ComponentActivity() {

    private val launcher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val svcIntent = Intent(this, ScreenCaptureService::class.java).apply {
                putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, result.resultCode)
                putExtra(ScreenCaptureService.EXTRA_RESULT_DATA, result.data)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(svcIntent)
            } else {
                startService(svcIntent)
            }
            AppLogger.i(TAG, "投屏授权成功")
            pendingGranted?.invoke()
        } else {
            AppLogger.w(TAG, "投屏授权被拒绝")
            pendingDenied?.invoke()
        }
        pendingGranted = null
        pendingDenied = null
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        launcher.launch(mpm.createScreenCaptureIntent())
    }

    companion object {
        private const val TAG = "MPRequest"

        @Volatile private var pendingGranted: (() -> Unit)? = null
        @Volatile private var pendingDenied: (() -> Unit)? = null

        /** 发起投屏授权请求。onGranted 在服务启动后回调。 */
        fun request(context: Context, onGranted: () -> Unit, onDenied: () -> Unit) {
            pendingGranted = onGranted
            pendingDenied = onDenied
            val intent = Intent(context, MediaProjectionRequestActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }
    }
}
