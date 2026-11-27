package com.stockshot.manager.overlay

import android.content.ComponentName
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import com.google.gson.Gson
import com.stockshot.manager.StockShotApp
import com.stockshot.manager.indicator.IndicatorDetectionOptions
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.LockSupport
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = StockShotApp::class)
@LooperMode(LooperMode.Mode.PAUSED)
class IndicatorReviewActivityTest {
    private val application: StockShotApp get() = RuntimeEnvironment.getApplication()
    private val controllers = mutableListOf<ActivityController<IndicatorReviewActivity>>()
    private val sessionFolders = mutableListOf<File>()

    @After
    fun closeActivitiesAndTestSessions() {
        controllers.forEach { controller ->
            if (!controller.get().isDestroyed) controller.pause().stop().destroy()
        }
        shadowOf(Looper.getMainLooper()).idle()
        sessionFolders.forEach { folder ->
            File(folder, "session.json").delete()
            File(folder, "source.png").delete()
            folder.delete()
        }
    }

    @Test
    fun loadsRealSessionJsonAndPngIntoImageAndHumanReadableReport() {
        val fixture = createSession()
        val controller = launch(fixture.id)
        val activity = controller.get()
        awaitUi { image(activity).bitmap != null }

        val shown = image(activity)
        assertEquals(240, shown.bitmap!!.width)
        assertEquals(500, shown.bitmap!!.height)
        assertEquals(Color.MAGENTA, shown.bitmap!!.getPixel(25, 30))
        assertEquals(fixture.expectedReport, report(activity).text.toString())
        assertNotNull(shown.result)
        assertTrue(button(activity, "保存人工标注").isEnabled)
        assertTrue(File(fixture.folder, "source.png").isFile)
    }

    @Test
    fun rotationRecreatesScreenAndReloadsSameScreenshotAndReportFromSession() {
        val fixture = createSession()
        val controller = launch(fixture.id)
        val previous = controller.get()
        awaitUi { image(previous).bitmap != null }
        val originalReport = report(previous).text.toString()
        val originalPixel = image(previous).bitmap!!.getPixel(25, 30)

        val rotated = Configuration(previous.resources.configuration).apply {
            orientation = if (orientation == Configuration.ORIENTATION_LANDSCAPE) {
                Configuration.ORIENTATION_PORTRAIT
            } else Configuration.ORIENTATION_LANDSCAPE
        }
        controller.configurationChange(rotated)
        val recreated = controller.get()
        assertNotSame(previous, recreated)
        awaitUi { image(recreated).bitmap != null }

        assertEquals(fixture.id, recreated.intent.getStringExtra("indicator_session"))
        assertEquals(originalPixel, image(recreated).bitmap!!.getPixel(25, 30))
        assertEquals(originalReport, report(recreated).text.toString())
        assertTrue(button(recreated, "评估样本库").isEnabled)
    }

    @Test
    fun emptyLibraryReportsNoEvidenceAndReviewActivityIsNotExported() {
        val info = application.packageManager.getActivityInfo(
            ComponentName(application, IndicatorReviewActivity::class.java), 0,
        )
        assertFalse("Original screenshots must not be exposed through an exported review activity", info.exported)
        val controller = launch(null)
        val activity = controller.get()
        assertEquals(View.GONE, image(activity).visibility)
        assertTrue(report(activity).text.contains("没有人工标签时不计算正确率"))
        assertFalse(report(activity).text.contains("100%"))

        assertTrue(button(activity, "评估样本库").performClick())
        awaitUi { report(activity).text.contains("调参：共 0") }
        val text = report(activity).text.toString()
        assertTrue(text.contains("独立验收：共 0"))
        assertTrue(text.contains("尚无独立验收样本"))
        assertFalse(text.contains("100%"))
        assertFalse(text.contains("100.0%"))
        assertTrue(button(activity, "评估样本库").isEnabled)
    }

    private fun launch(id: String?): ActivityController<IndicatorReviewActivity> {
        val intent = Intent(application, IndicatorReviewActivity::class.java)
        if (id != null) intent.putExtra("indicator_session", id)
        return Robolectric.buildActivity(IndicatorReviewActivity::class.java, intent).setup()
            .also { controllers += it }
    }

    private data class Fixture(val id: String, val folder: File, val expectedReport: String)

    private fun createSession(): Fixture {
        val id = UUID.randomUUID().toString()
        val folder = File(application.cacheDir, "indicator-review/" + id)
        check(folder.mkdirs())
        sessionFolders += folder
        val bitmap = Bitmap.createBitmap(240, 500, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(Color.WHITE)
            bitmap.setPixel(25, 30, Color.MAGENTA)
            val options = IndicatorDetectionOptions(capitalMethod = "pointwise", capitalRule = "all")
            val result = options.detect(bitmap)
            File(folder, "source.png").outputStream().use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
            }
            File(folder, "session.json").writeText(Gson().toJson(mapOf("options" to options, "result" to result)))
            return Fixture(id, folder, IndicatorReport.describe(result))
        } finally {
            bitmap.recycle()
        }
    }

    private fun views(activity: IndicatorReviewActivity): List<View> {
        fun descend(view: View): List<View> = listOf(view) + if (view is ViewGroup) {
            (0 until view.childCount).flatMap { descend(view.getChildAt(it)) }
        } else emptyList()
        return descend(activity.findViewById(android.R.id.content))
    }

    private fun image(activity: IndicatorReviewActivity): IndicatorImageView =
        views(activity).filterIsInstance<IndicatorImageView>().single()

    private fun report(activity: IndicatorReviewActivity): TextView = views(activity)
        .filterIsInstance<TextView>().single { it.isTextSelectable }

    private fun button(activity: IndicatorReviewActivity, text: String): Button = views(activity)
        .filterIsInstance<Button>().single { it.text.toString() == text }

    /** Wait for a UI outcome while draining Main; IO uses its real executor, with a bounded deadline. */
    private fun awaitUi(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(2))
        }
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue("Timed out waiting for the diagnostic screen to finish loading", condition())
    }
}
