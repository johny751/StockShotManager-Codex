package com.stockshot.manager.automation

import android.app.Application
import android.content.Context
import android.os.Looper
import android.provider.Settings
import com.stockshot.manager.capture.MediaProjectionRequestActivity
import com.stockshot.manager.service.ScreenCaptureService
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Job
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowSettings

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class AutomationControllerLifecycleTest {
    private val context: Application get() = RuntimeEnvironment.getApplication()
    private val mainLooper get() = shadowOf(Looper.getMainLooper())
    private val applicationShadow get() = shadowOf(context)

    @Before
    fun prepare() {
        clearControllerCallbacks()
        AutomationManager.stop("测试：上一轮已结束")
        ShadowSettings.setCanDrawOverlays(true)
        Settings.Secure.putString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            "${context.packageName}/com.stockshot.manager.service.ScreenshotAccessibilityService",
        )
        applicationShadow.clearNextStartedActivities()
        drainStoppedServices()
    }

    @After
    fun cleanUp() {
        // Cancel the real collector before changing progress or advancing the looper.
        clearControllerCallbacks()
        AutomationManager.stop("测试结束")
        ScreenCaptureService.getInstance()?.onDestroy()
        mainLooper.idle()
        ShadowSettings.setCanDrawOverlays(false)
        Settings.Secure.putString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            "",
        )
        applicationShadow.clearNextStartedActivities()
        drainStoppedServices()
    }

    @Test
    fun completedRunStopsCaptureServiceAfterTheGracePeriod() {
        val service = createCaptureService()
        watchPreviousRunCompletion()

        mainLooper.idleFor(799, TimeUnit.MILLISECONDS)
        assertNull("Cleanup must respect the grace period", applicationShadow.nextStoppedService)
        assertSame(service, ScreenCaptureService.getInstance())

        mainLooper.idleFor(1, TimeUnit.MILLISECONDS)
        assertEquals(
            ScreenCaptureService::class.java.name,
            applicationShadow.nextStoppedService?.component?.className,
        )
        assertNull("Cleanup should request a single stop", applicationShadow.nextStoppedService)
    }

    @Test
    fun newCaptureAuthorizationPreventsPreviousCleanupFromStoppingTheNewService() {
        val oldService = createCaptureService()
        watchPreviousRunCompletion()
        mainLooper.idleFor(100, TimeUnit.MILLISECONDS)
        assertNull(applicationShadow.nextStoppedService)

        assertNull(AutomationController.launch(context, targetDate = "2026-09-01"))
        assertEquals(
            MediaProjectionRequestActivity::class.java.name,
            applicationShadow.nextStartedActivity?.component?.className,
        )
        // Stop requests made before requesting new projection belong to the old run.
        drainStoppedServices()
        oldService.onDestroy()

        // Represent a fast grant starting a new service while its capture loop is
        // still waiting to start. onCreate registers the real service singleton;
        // no MediaProjection or screenshot hardware is needed for this race.
        val newService = createCaptureService()
        assertSame(newService, ScreenCaptureService.getInstance())
        mainLooper.idleFor(1, TimeUnit.SECONDS)

        assertNull(
            "The previous run must not stop the new service during projection startup",
            applicationShadow.nextStoppedService,
        )
        assertSame(newService, ScreenCaptureService.getInstance())
    }

    private fun createCaptureService(): ScreenCaptureService =
        Robolectric.buildService(ScreenCaptureService::class.java).create().get()

    private fun watchPreviousRunCompletion() {
        // Invoke the real completion observer as the fixture, rather than replacing
        // its Job or reproducing the production delay/cleanup in test code.
        AutomationController::class.java
            .getDeclaredMethod("watchCompletion", Context::class.java)
            .apply { isAccessible = true }
            .invoke(AutomationController, context)
        mainLooper.idle()
    }

    private fun drainStoppedServices() {
        while (applicationShadow.nextStoppedService != null) {
            // Consume old requests; only subsequent requests concern the next run.
        }
    }

    private fun clearControllerCallbacks() {
        val watchField = AutomationController::class.java.getDeclaredField("watchJob")
            .apply { isAccessible = true }
        (watchField.get(null) as? Job)?.cancel()
        watchField.set(null, null)
        setStaticField(AutomationController::class.java, "launchPending", false)
        setStaticField(MediaProjectionRequestActivity::class.java, "pendingGranted", null)
        setStaticField(MediaProjectionRequestActivity::class.java, "pendingDenied", null)
    }

    private fun setStaticField(owner: Class<*>, name: String, value: Any?) {
        owner.getDeclaredField(name).apply { isAccessible = true }.set(null, value)
    }
}
