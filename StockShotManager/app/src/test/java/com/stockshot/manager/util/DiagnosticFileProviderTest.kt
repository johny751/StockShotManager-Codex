package com.stockshot.manager.util

import android.app.Application
import android.content.pm.PackageManager
import androidx.core.content.FileProvider
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class DiagnosticFileProviderTest {
    @Before
    fun attachProviderForThisTestApplication() {
        val context = RuntimeEnvironment.getApplication()
        val info = requireNotNull(context.packageManager.resolveContentProvider(
            "${context.packageName}.fileprovider", PackageManager.GET_META_DATA,
        ))
        // Robolectric changes the app's temp directory between methods; attachInfo refreshes
        // FileProvider's static path cache through the same lifecycle used on Android.
        FileProvider().attachInfo(context, info)
    }

    @Test
    fun logsCanBeReadUsingTheCurrentEditionsProvider() {
        val context = RuntimeEnvironment.getApplication()
        val file = File(context.cacheDir, "diagnostic-logs/provider-fixture.log")
        file.parentFile!!.mkdirs()
        try {
            file.writeText("HTTP 503\n诊断日志", Charsets.UTF_8)
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            assertEquals("${context.packageName}.fileprovider", uri.authority)
            assertTrue(uri.path!!.startsWith("/diagnostic_logs/"))
            assertEquals("HTTP 503\n诊断日志", context.contentResolver.openInputStream(uri)!!
                .bufferedReader(Charsets.UTF_8).use { it.readText() })
        } finally {
            file.delete()
        }
    }

    @Test
    fun filesOutsideTheDiagnosticCacheCannotBeShared() {
        val context = RuntimeEnvironment.getApplication()
        val file = File(context.filesDir, "private-provider-fixture.txt")
        try {
            file.writeText("private app data")
            try {
                FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                fail("The provider must only expose diagnostic snapshots")
            } catch (_: IllegalArgumentException) {
                // The app's private files are outside the declared provider root.
            }
        } finally {
            file.delete()
        }
    }
}
