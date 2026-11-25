package com.stockshot.manager.ui.screen

import android.content.Context
import android.content.ContextWrapper
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.stockshot.manager.BuildConfig
import com.stockshot.manager.ui.vm.FeedbackBackupViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Self-contained settings entry; suggestions and their backup are separate from stock data. */
@Composable
fun FeedbackSettingsSection(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val activity = remember(context) { context.feedbackActivity() }
    if (activity == null) {
        Text("当前页面无法打开优化建议。", color = MaterialTheme.colorScheme.error)
        return
    }
    // Use the Activity owner, not the navigation entry: leaving/re-entering settings
    // must keep pending document results and confirmation state.
    val vm: FeedbackBackupViewModel = viewModel(viewModelStoreOwner = activity)
    val operation by vm.operation.collectAsStateWithLifecycle()
    val picker by vm.pendingPicker.collectAsStateWithLifecycle()
    val pendingRestore by vm.pendingRestore.collectAsStateWithLifecycle()
    val enabled = !operation.busy && picker.isEmpty() && pendingRestore == null

    val exportPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip"),
        vm::exportSelected,
    )
    val restorePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
        vm::restoreSelected,
    )

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("优化建议", style = MaterialTheme.typography.titleMedium)
        Text(
            "记录使用中的问题和改进想法，支持文字、草稿和附件。内容仅保存在当前版本中。",
            style = MaterialTheme.typography.bodySmall,
        )
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { vm.openList(activity) }, enabled = enabled, modifier = Modifier.weight(1f)) {
                Text("查看优化建议")
            }
            OutlinedButton(onClick = { vm.newIdea(activity) }, enabled = enabled, modifier = Modifier.weight(1f)) {
                Text("新建建议")
            }
        }
        Text(
            "建议备份包含建议、草稿和附件，与股票记录备份分开保存。",
            style = MaterialTheme.typography.bodySmall,
        )
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                modifier = Modifier.weight(1f),
                enabled = enabled,
                onClick = {
                    if (vm.beginExport()) {
                        try {
                            val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date())
                            exportPicker.launch("StockShotManager-${BuildConfig.FLAVOR}-feedback-$stamp.zip")
                        } catch (_: Exception) {
                            vm.pickerFailed()
                        }
                    }
                },
            ) { Text("导出建议备份") }
            OutlinedButton(
                modifier = Modifier.weight(1f),
                enabled = enabled,
                onClick = {
                    if (vm.beginRestore()) {
                        try {
                            // ZIP providers can report either of these MIME types.
                            restorePicker.launch(arrayOf(
                                "application/zip", "application/x-zip-compressed", "application/octet-stream",
                            ))
                        } catch (_: Exception) {
                            vm.pickerFailed()
                        }
                    }
                },
            ) { Text("恢复建议备份") }
        }
        if (operation.busy) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                Text(operation.message, style = MaterialTheme.typography.bodySmall)
            }
        } else if (operation.message.isNotEmpty()) {
            Text(
                operation.message,
                style = MaterialTheme.typography.bodySmall,
                color = if (operation.failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
    }

    if (pendingRestore != null && !operation.busy) {
        AlertDialog(
            onDismissRequest = vm::cancelRestore,
            title = { Text("替换当前优化建议？") },
            text = {
                Text(
                    "恢复会完整替换当前版本的全部建议、草稿和附件，股票记录不受影响。建议先导出现有建议。\n\n" +
                        "将先检查整个备份，检查失败时保留当前建议。",
                )
            },
            confirmButton = {
                TextButton(onClick = vm::confirmRestore) { Text("检查并恢复") }
            },
            dismissButton = {
                TextButton(onClick = vm::cancelRestore) { Text("取消") }
            },
        )
    }
}

private fun Context.feedbackActivity(): ComponentActivity? {
    var current = this
    while (current is ContextWrapper) {
        if (current is ComponentActivity) return current
        val base = current.baseContext
        if (base === current) return null
        current = base
    }
    return current as? ComponentActivity
}
