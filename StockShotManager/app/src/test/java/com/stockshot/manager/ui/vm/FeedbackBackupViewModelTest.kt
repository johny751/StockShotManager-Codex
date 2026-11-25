package com.stockshot.manager.ui.vm

import android.app.Application
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import java.io.IOException
import java.util.ArrayDeque
import java.util.concurrent.Executor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class FeedbackBackupViewModelTest {
    private val application: Application get() = RuntimeEnvironment.getApplication()

    @Test
    fun recreationKeepsConfirmationAndBusyWithoutReplayingRestore() {
        val executor = PendingExecutor()
        var restoreCalls = 0
        val worker = FeedbackBackupWorker(executor) { _, restore ->
            assertTrue(restore)
            restoreCalls++
        }
        val savedState = SavedStateHandle()
        val first = FeedbackBackupViewModel(application, savedState, worker)
        assertTrue(first.beginRestore())
        first.restoreSelected(Uri.parse("content://documents/feedback.zip"))
        assertEquals(0, executor.tasks.size)
        assertFalse(first.canOpen())

        val recreatedState = copyState(savedState)
        val recreated = FeedbackBackupViewModel(application, recreatedState, worker)
        assertEquals(first.pendingRestore.value, recreated.pendingRestore.value)
        assertEquals(0, restoreCalls)
        recreated.confirmRestore()
        assertTrue(recreated.operation.value.busy)
        assertNull(recreated.pendingRestore.value)

        val duringRestore = FeedbackBackupViewModel(application, copyState(recreatedState), worker)
        assertTrue(duringRestore.operation.value.busy)
        assertFalse(duringRestore.beginExport())
        assertFalse(duringRestore.beginRestore())
        duringRestore.confirmRestore()
        assertEquals(1, executor.tasks.size)

        executor.tasks.removeFirst().run()
        assertEquals(1, restoreCalls)
        assertFalse(duringRestore.operation.value.busy)
        assertTrue(duringRestore.canOpen())
        val afterRestore = FeedbackBackupViewModel(application, copyState(recreatedState), worker)
        afterRestore.confirmRestore()
        assertEquals(0, executor.tasks.size)
        assertEquals(1, restoreCalls)
    }

    @Test
    fun workerRejectsOverlapAndAllowsRetryAfterVisibleFailure() {
        val executor = PendingExecutor()
        var calls = 0
        val worker = FeedbackBackupWorker(executor) { _, _ ->
            calls++
            if (calls == 1) throw IOException("文件读取失败")
        }
        val uri = Uri.parse("content://documents/feedback.zip")
        assertTrue(worker.start(uri, restore = true))
        assertFalse(worker.start(uri, restore = true))
        assertFalse(worker.start(uri, restore = false))
        assertEquals(1, executor.tasks.size)
        executor.tasks.removeFirst().run()
        assertFalse(worker.state.value.busy)
        assertTrue(worker.state.value.failed)
        assertTrue(worker.state.value.message.contains("文件读取失败"))
        assertTrue(worker.start(uri, restore = false))
        executor.tasks.removeFirst().run()
        assertEquals(2, calls)
        assertFalse(worker.state.value.busy)
        assertFalse(worker.state.value.failed)
    }

    @Test
    fun cancelInvalidDocumentAndDuplicateResultNeverStartRestore() {
        val executor = PendingExecutor()
        val worker = FeedbackBackupWorker(executor) { _, _ -> throw AssertionError("Unexpected data operation") }
        val vm = FeedbackBackupViewModel(application, SavedStateHandle(), worker)
        assertTrue(vm.beginRestore())
        vm.restoreSelected(null)
        assertTrue(vm.canOpen())
        assertTrue(vm.beginRestore())
        vm.restoreSelected(Uri.parse("file:///private/feedback.zip"))
        assertTrue(vm.operation.value.failed)
        assertTrue(vm.canOpen())
        assertTrue(vm.beginRestore())
        vm.restoreSelected(Uri.parse("content://documents/feedback.zip"))
        vm.cancelRestore()
        vm.confirmRestore()
        vm.restoreSelected(Uri.parse("content://documents/feedback.zip"))
        assertTrue(vm.canOpen())
        assertEquals(0, executor.tasks.size)
    }

    private fun copyState(source: SavedStateHandle): SavedStateHandle =
        SavedStateHandle(source.keys().associateWith { source.get<Any?>(it) })

    private class PendingExecutor : Executor {
        val tasks = ArrayDeque<Runnable>()
        override fun execute(command: Runnable) { tasks.addLast(command) }
    }
}
