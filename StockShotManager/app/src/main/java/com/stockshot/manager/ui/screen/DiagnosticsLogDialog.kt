package com.stockshot.manager.ui.screen

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.stockshot.manager.util.AppLogger
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Keep the diagnostic switch independent from the settings page's Save button. */
@Composable
internal fun DiagnosticsLogSettings() {
    val enabled by AppLogger.enabled.collectAsStateWithLifecycle()
    val error by AppLogger.lastError.collectAsStateWithLifecycle()
    var showLogs by rememberSaveable { mutableStateOf(false) }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            DiagnosticLogToggle(enabled)
            Text(
                "开启后返回列表重试“更新最高价”，再查看、导出或分享日志。开关立即生效，无需保存设置。",
                fontSize = 13.sp,
            )
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
            OutlinedButton(
                onClick = { showLogs = true },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("查看 / 复制 / 导出 / 分享日志") }
        }
    }
    if (showLogs) DiagnosticsLogDialog(onDismiss = { showLogs = false })
}

/** Public entry shared by the main page shortcut and the settings page. */
@Composable
fun DiagnosticsLogDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val enabled by AppLogger.enabled.collectAsStateWithLifecycle()
    val backendError by AppLogger.lastError.collectAsStateWithLifecycle()
    var preview by remember { mutableStateOf("") }
    var operation by remember { mutableStateOf<String?>("读取日志") }
    var notice by remember { mutableStateOf<String?>(null) }
    var localError by remember { mutableStateOf<String?>(null) }
    var confirmClear by rememberSaveable { mutableStateOf(false) }
    var pendingExportUri by rememberSaveable { mutableStateOf<String?>(null) }

    fun runOperation(label: String, action: suspend () -> Unit) {
        if (operation != null) return
        operation = label
        notice = null
        localError = null
        scope.launch {
            try {
                action()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                localError = "$label 失败：${e.localizedMessage ?: "请重试"}"
            } finally {
                operation = null
            }
        }
    }

    val exportPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { uri ->
        pendingExportUri = uri?.toString()
    }

    LaunchedEffect(Unit) {
        try {
            preview = AppLogger.readLogs()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            localError = "读取日志失败：${e.localizedMessage ?: "请重试"}"
        } finally {
            operation = null
        }
    }

    // A picker result can arrive while a recreated dialog is still loading its preview.
    LaunchedEffect(pendingExportUri, operation) {
        val destination = pendingExportUri
        if (destination != null && operation == null) {
            runOperation("导出日志") {
                try {
                    val exported = AppLogger.exportLog(context, Uri.parse(destination))
                    pendingExportUri = null
                    if (exported) notice = "日志已导出到所选位置"
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    pendingExportUri = null
                    throw e
                }
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("诊断日志") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                DiagnosticLogToggle(enabled)
                Text(
                    "开启后重试“更新最高价”，完成后回到这里刷新日志。关闭会停止记录，已有日志仍可查看和导出。",
                    fontSize = 13.sp,
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedButton(
                        onClick = { runOperation("刷新日志") { preview = AppLogger.readLogs() } },
                        enabled = operation == null,
                        modifier = Modifier.weight(1f),
                    ) { Text("刷新") }
                    OutlinedButton(
                        onClick = {
                            try {
                                clipboard.setText(AnnotatedString(preview))
                                notice = "已复制当前日志预览"
                                localError = null
                            } catch (e: Exception) {
                                localError = "复制失败：${e.localizedMessage ?: "请重试"}"
                            }
                        },
                        enabled = operation == null && preview.isNotEmpty(),
                        modifier = Modifier.weight(1f),
                    ) { Text("复制") }
                    OutlinedButton(
                        onClick = { confirmClear = true },
                        enabled = operation == null,
                        modifier = Modifier.weight(1f),
                    ) { Text("清空") }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = {
                            try {
                                val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ROOT).format(Date())
                                exportPicker.launch("stockshot-diagnostics-$stamp.log")
                            } catch (e: Exception) {
                                localError = "无法打开导出位置：${e.localizedMessage ?: "请重试"}"
                            }
                        },
                        enabled = operation == null,
                        modifier = Modifier.weight(1f),
                    ) { Text("导出文件") }
                    OutlinedButton(
                        onClick = {
                            runOperation("分享日志") {
                                val file = AppLogger.createSnapshot() ?: return@runOperation
                                val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                                val send = Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(Intent.EXTRA_STREAM, uri)
                                    clipData = ClipData.newRawUri("诊断日志", uri)
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                context.startActivity(Intent.createChooser(send, "分享诊断日志"))
                            }
                        },
                        enabled = operation == null,
                        modifier = Modifier.weight(1f),
                    ) { Text("分享日志") }
                }
                Text(
                    "下方仅预览最近日志；复制为当前预览，导出和分享包含完整保留日志。长按文本可选择。",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                operation?.let {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text("$it…", fontSize = 12.sp)
                }
                (localError ?: backendError)?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                }
                notice?.let { Text(it, color = MaterialTheme.colorScheme.primary, fontSize = 12.sp) }
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = MaterialTheme.shapes.small,
                ) {
                    Box(Modifier.fillMaxWidth().height(260.dp).padding(10.dp)) {
                        if (preview.isEmpty()) {
                            Text(
                                if (operation != null) "正在读取…" else "暂无日志。开启记录并重试后点击“刷新”。",
                                fontSize = 12.sp,
                            )
                        } else {
                            SelectionContainer {
                                Text(
                                    text = preview,
                                    modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.sp,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("清空诊断日志？") },
            text = { Text("将删除本应用当前保留的诊断日志。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    runOperation("清空日志") {
                        if (AppLogger.clearLogs()) {
                            preview = ""
                            notice = "诊断日志已清空"
                        }
                    }
                }) { Text("清空") }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun DiagnosticLogToggle(enabled: Boolean) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("诊断日志", fontWeight = FontWeight.SemiBold)
            Text(
                if (enabled) "已开启，正在记录" else "已关闭，保留已有日志",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = enabled, onCheckedChange = AppLogger::setEnabled)
    }
}
