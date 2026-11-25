package com.stockshot.manager.ui.vm

import android.app.Activity
import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import com.androidshared.feedback.DefaultFeedbackStorage
import com.androidshared.feedback.FeedbackKit
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class FeedbackBackupState(
    val busy: Boolean = false,
    val message: String = "",
    val failed: Boolean = false,
)

/**
 * Owns one process-wide operation. The worker survives Activity/ViewModel recreation and
 * never retains an Activity. A finished/failed restore is never replayed on recreation.
 */
internal class FeedbackBackupWorker(
    private val executor: Executor,
    private val operation: (Uri, Boolean) -> Unit,
) {
    private val mutableState = MutableStateFlow(FeedbackBackupState())
    val state: StateFlow<FeedbackBackupState> = mutableState.asStateFlow()

    @Synchronized
    fun showMessage(message: String, failed: Boolean = false) {
        if (!mutableState.value.busy) mutableState.value = FeedbackBackupState(message = message, failed = failed)
    }

    @Synchronized
    fun start(uri: Uri, restore: Boolean): Boolean {
        if (mutableState.value.busy) return false
        mutableState.value = FeedbackBackupState(
            busy = true,
            message = if (restore) "正在检查并恢复优化建议，请稍候…" else "正在导出优化建议和附件，请稍候…",
        )
        try {
            executor.execute {
                val result = try {
                    operation(uri, restore)
                    FeedbackBackupState(message = if (restore) {
                        "优化建议恢复完成，重新打开即可查看。"
                    } else {
                        "优化建议备份已保存到所选文件。"
                    })
                } catch (error: Exception) {
                    failure(error, restore)
                }
                mutableState.value = result
            }
        } catch (error: Exception) {
            mutableState.value = failure(error, restore)
        }
        return true
    }

    private fun failure(error: Exception, restore: Boolean): FeedbackBackupState {
        val detail = error.message?.trim()?.takeIf { it.isNotEmpty() }?.take(240)
            ?: "请检查文件权限和剩余空间后重试。"
        return FeedbackBackupState(
            message = (if (restore) "恢复失败，当前优化建议未替换。" else "导出失败，所选文件可能不完整，请重新导出。") +
                "\n" + detail,
            failed = true,
        )
    }
}

private object FeedbackBackupJobs {
    private var worker: FeedbackBackupWorker? = null

    @Synchronized
    fun get(context: Context): FeedbackBackupWorker {
        return worker ?: run {
            val application = context.applicationContext
            FeedbackBackupWorker(
                Executors.newSingleThreadExecutor { task -> Thread(task, "feedback-backup-io") },
            ) { uri, restore ->
                // Initialization and all storage I/O stay on the worker, using the same
                // default namespace as FeedbackKit.openList/newIdea.
                val storage = DefaultFeedbackStorage(application)
                if (restore) storage.restoreBackup(uri) else storage.exportBackup(uri)
            }.also { worker = it }
        }
    }
}

class FeedbackBackupViewModel internal constructor(
    application: Application,
    private val savedState: SavedStateHandle,
    private val jobs: FeedbackBackupWorker,
) : AndroidViewModel(application) {
    constructor(application: Application, savedState: SavedStateHandle) :
        this(application, savedState, FeedbackBackupJobs.get(application))
    val operation: StateFlow<FeedbackBackupState> = jobs.state
    val pendingPicker: StateFlow<String> = savedState.getStateFlow(PICKER, "")
    val pendingRestore: StateFlow<String?> = savedState.getStateFlow(RESTORE_URI, null)

    fun canOpen(): Boolean = !operation.value.busy &&
        pendingPicker.value.isEmpty() && pendingRestore.value == null

    fun openList(activity: Activity) = openFeedback(activity, newIdea = false)
    fun newIdea(activity: Activity) = openFeedback(activity, newIdea = true)

    private fun openFeedback(activity: Activity, newIdea: Boolean) {
        if (!canOpen()) return
        try {
            if (newIdea) FeedbackKit.newIdea(activity, "股票截图设置")
            else FeedbackKit.openList(activity)
        } catch (error: Exception) {
            jobs.showMessage("无法打开优化建议：" + (error.message ?: "请稍后重试。").take(240), failed = true)
        }
    }

    fun beginExport(): Boolean = beginPicker(EXPORT)
    fun beginRestore(): Boolean = beginPicker(RESTORE)

    private fun beginPicker(kind: String): Boolean {
        if (!canOpen()) return false
        savedState[PICKER] = kind
        return true
    }

    fun pickerFailed() {
        savedState[PICKER] = ""
        jobs.showMessage("无法打开系统文件选择器，请检查设备是否提供文件管理功能。", failed = true)
    }

    fun exportSelected(uri: Uri?) = documentSelected(EXPORT, uri)
    fun restoreSelected(uri: Uri?) = documentSelected(RESTORE, uri)

    private fun documentSelected(expected: String, uri: Uri?) {
        // Ignore duplicate or late callbacks after an earlier result has been consumed.
        if (pendingPicker.value != expected) return
        savedState[PICKER] = ""
        if (operation.value.busy) return
        if (uri == null) {
            jobs.showMessage("已取消选择文件。")
            return
        }
        if (uri.scheme != "content" ||
            uri.authority == getApplication<Application>().packageName + ".feedback.files") {
            jobs.showMessage("请选择系统文件界面中的优化建议备份。", failed = true)
            return
        }
        if (expected == EXPORT) {
            jobs.start(uri, restore = false)
        } else {
            savedState[RESTORE_URI] = uri.toString()
        }
    }

    fun confirmRestore() {
        if (operation.value.busy) return
        val selected = pendingRestore.value ?: return
        // Consume confirmation before starting work: rotation or rapid repeated taps
        // cannot submit the same replacement twice.
        savedState[RESTORE_URI] = null
        jobs.start(Uri.parse(selected), restore = true)
    }

    fun cancelRestore() {
        if (operation.value.busy) return
        savedState[RESTORE_URI] = null
        jobs.showMessage("已取消恢复，当前优化建议保持不变。")
    }

    private companion object {
        const val PICKER = "feedback_pending_picker"
        const val RESTORE_URI = "feedback_pending_restore_uri"
        const val EXPORT = "export"
        const val RESTORE = "restore"
    }
}
