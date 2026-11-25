package com.stockshot.manager.util

import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.Settings

/** 权限检查工具：悬浮窗 + 无障碍服务。 */
object PermissionUtils {

    fun hasOverlayPermission(context: Context): Boolean =
        Settings.canDrawOverlays(context)

    fun isAccessibilityEnabled(context: Context): Boolean {
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        val target =
            "${context.packageName}/com.stockshot.manager.service.ScreenshotAccessibilityService"
        return enabled.split(":").any { it.equals(target, ignoreCase = true) }
    }

    /**
     * 是否具备把截图写入公共目录（如 Downloads）所需的存储权限。
     * API 30+ 需要“所有文件访问”(MANAGE_EXTERNAL_STORAGE)；更低版本用 WRITE_EXTERNAL_STORAGE。
     */
    fun hasStorageAccess(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            context.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        }
}
