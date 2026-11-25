package com.stockshot.manager.ui

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.NavHostController
import com.stockshot.manager.ui.screen.ImageViewerScreen
import com.stockshot.manager.ui.screen.MainScreen
import com.stockshot.manager.ui.screen.SearchScreen
import com.stockshot.manager.service.ScreenshotAccessibilityService
import com.stockshot.manager.ui.screen.SettingsScreen
import com.stockshot.manager.ui.theme.StockShotTheme

class MainActivity : ComponentActivity() {

    private val notifPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        maybeRequestNotificationPermission()
        setContent {
            StockShotTheme {
                AppNavHost()
            }
        }
    }

    private fun maybeRequestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}

object Routes {
    const val MAIN = "main"
    const val SEARCH = "search"
    const val SETTINGS = "settings"
    const val VIEWER = "viewer"
}

@Composable
private fun AppNavHost() {
    val nav: NavHostController = rememberNavController()
    NavHost(navController = nav, startDestination = Routes.MAIN) {
        composable(Routes.MAIN) { MainScreen(nav) }
        composable(Routes.SEARCH) { SearchScreen(nav) }
        composable(Routes.SETTINGS) { SettingsScreen(nav) }
        composable(Routes.VIEWER) { ImageViewerScreen(nav) }
    }
}

/** Helpers for launching system permission settings from Compose. */
object SystemIntents {
    private const val ACTION_ACCESSIBILITY_DETAILS_SETTINGS =
        "android.settings.ACCESSIBILITY_DETAILS_SETTINGS"

    fun overlaySettings(packageName: String): Intent =
        Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:$packageName")
        )

    /**
     * 优先直达本应用的无障碍服务详情页；系统不支持时依次回退到无障碍列表和设置首页。
     */
    fun openAccessibilitySettings(context: Context) {
        val service = ComponentName(
            context.packageName,
            ScreenshotAccessibilityService::class.java.name,
        )
        val directIntent = Intent(ACTION_ACCESSIBILITY_DETAILS_SETTINGS).apply {
            putExtra(Intent.EXTRA_COMPONENT_NAME, service.flattenToString())
        }
        val candidates = listOf(
            directIntent,
            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS),
            Intent(Settings.ACTION_SETTINGS),
        )
        for (intent in candidates) {
            try {
                context.startActivity(intent)
                return
            } catch (_: Exception) {
                // OEM settings implementations differ; try the next broader destination.
            }
        }
    }

    /**
     * 打开“所有文件访问”授权页（API 30+）。优先定位到本应用，失败时回退到全局列表页。
     */
    fun allFilesAccessSettings(packageName: String): Intent =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Intent(
                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                Uri.parse("package:$packageName")
            )
        } else {
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
        }
}
