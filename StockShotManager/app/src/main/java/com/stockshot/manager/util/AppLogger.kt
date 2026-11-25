package com.stockshot.manager.util

import android.content.Context
import android.net.Uri
import android.os.Build
import android.util.Log
import com.stockshot.manager.BuildConfig
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executors
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Optional local diagnostics. Turning logging off preserves existing files for export. */
object AppLogger {
    private const val PREFS = "diagnostic_logging"
    private const val KEY_ENABLED = "enabled"
    private val enabledState = MutableStateFlow(false)
    private val errorState = MutableStateFlow<String?>(null)
    val enabled: StateFlow<Boolean> = enabledState.asStateFlow()
    val lastError: StateFlow<String?> = errorState.asStateFlow()
    @Volatile private var logger: DiagnosticLogger? = null

    @Synchronized
    fun initialize(context: Context) {
        if (logger != null) return
        try {
            val app = context.applicationContext
            val preferences = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            logger = DiagnosticLogger(
                directory = File(app.noBackupFilesDir, "diagnostic-logs"),
                snapshotDirectory = File(app.cacheDir, "diagnostic-logs"),
                initialEnabled = preferences.getBoolean(KEY_ENABLED, false),
                persistEnabled = { value -> preferences.edit().putBoolean(KEY_ENABLED, value).commit() },
                sessionInfo = "package=${app.packageName} version=${BuildConfig.VERSION_NAME} " +
                    "(${BuildConfig.VERSION_CODE}) git=${BuildConfig.GIT_COMMIT} build=${BuildConfig.BUILD_TIME}\n" +
                    "Android=${Build.VERSION.RELEASE} sdk=${Build.VERSION.SDK_INT} " +
                    "device=${Build.MANUFACTURER} ${Build.MODEL}",
                enabledState = enabledState,
                errorState = errorState,
                logcat = { priority, message -> Log.println(priority, "StockShot", message) },
            )
        } catch (e: Exception) {
            errorState.value = "初始化日志失败：${redactDiagnosticText(e.message ?: e.javaClass.simpleName)}"
        }
    }

    /** Immediate switch state; preference writes and file writes share the same IO queue. */
    fun setEnabled(value: Boolean) {
        val backend = requireLogger() ?: return
        backend.setEnabled(value)
    }

    fun d(tag: String, msg: String): Int = logger?.log(Log.DEBUG, tag, msg) ?: 0
    fun i(tag: String, msg: String): Int = logger?.log(Log.INFO, tag, msg) ?: 0
    fun w(tag: String, msg: String): Int = logger?.log(Log.WARN, tag, msg) ?: 0
    fun e(tag: String, msg: String, tr: Throwable? = null): Int =
        logger?.log(Log.ERROR, tag, msg, tr) ?: 0

    /** A bounded preview; exporting includes every retained rotation file. */
    suspend fun readLogs(): String = requireLogger()?.readLogs().orEmpty()
    suspend fun clearLogs(): Boolean = requireLogger()?.clearLogs() ?: false
    suspend fun createSnapshot(): File? = requireLogger()?.createSnapshot()

    suspend fun exportLog(context: Context, uri: Uri): Boolean {
        val backend = requireLogger() ?: return false
        return backend.export { text ->
            context.contentResolver.openOutputStream(uri, "wt")?.use {
                it.write(text.toByteArray(Charsets.UTF_8))
            } ?: throw IOException("无法打开导出文件")
        }
    }

    private fun requireLogger(): DiagnosticLogger? = logger.also {
        if (it == null) errorState.value = "诊断日志尚未初始化"
    }
}

/** Single writer shared by all operations, including read/clear/export barriers. */
internal class DiagnosticLogger(
    private val directory: File,
    private val snapshotDirectory: File,
    initialEnabled: Boolean,
    private val persistEnabled: (Boolean) -> Boolean,
    private val sessionInfo: String,
    private val maxFileBytes: Int = 512 * 1024,
    private val fileCount: Int = 3,
    private val enabledState: MutableStateFlow<Boolean> = MutableStateFlow(false),
    private val errorState: MutableStateFlow<String?> = MutableStateFlow(null),
    private val logcat: (Int, String) -> Int = { _, _ -> 0 },
) {
    val enabled: StateFlow<Boolean> = enabledState.asStateFlow()
    val lastError: StateFlow<String?> = errorState.asStateFlow()
    private val gate = Any()
    private val errors = linkedMapOf<String, String>()
    private val writer = Executors.newSingleThreadExecutor { task ->
        Thread(task, "stockshot-diagnostic-log").apply { isDaemon = true }
    }
    private var headerPending = true

    init {
        require(maxFileBytes >= 256 && fileCount >= 1)
        enabledState.value = initialEnabled
        if (initialEnabled) enqueue("写入日志") { appendSessionHeader() }
    }

    fun setEnabled(value: Boolean) = synchronized(gate) {
        if (enabledState.value == value) return@synchronized
        enabledState.value = value
        enqueue("保存日志开关") {
            if (!persistEnabled(value)) throw IOException("无法保存开关，重启后可能恢复旧状态")
        }
        if (value) enqueue("写入日志") { appendSessionHeader() }
    }

    fun log(priority: Int, tag: String, message: String, throwable: Throwable? = null): Int = synchronized(gate) {
        if (!enabledState.value) return@synchronized 0
        val text = buildString {
            append('[').append(tag).append("] ").append(message)
            if (throwable != null) append('\n').append(throwable.stackTraceToString())
        }.let(::redactDiagnosticText)
        val timestamp = System.currentTimeMillis()
        val thread = Thread.currentThread().name
        enqueue("写入日志") {
            if (headerPending) appendSessionHeader()
            appendRecord(priority, text, timestamp, thread)
        }
        // Never emit an unredacted Throwable to logcat through a separate overload.
        runCatching { logcat(priority, text) }.getOrDefault(0)
    }

    suspend fun readLogs(): String = submit("读取日志", "") {
        val text = readAll()
        if (text.length <= PREVIEW_CHAR_LIMIT) text else {
            var start = text.length - PREVIEW_CHAR_LIMIT
            if (text[start].isLowSurrogate()) start++
            "[仅显示最近 $PREVIEW_CHAR_LIMIT 个字符，导出可获取全部保留日志]\n" + text.substring(start)
        }
    }

    suspend fun clearLogs(): Boolean = submit("清空日志", false) {
        for (file in files() + snapshots()) {
            if (file.exists() && !file.delete()) throw IOException("无法清空 ${file.name}")
        }
        // The next event starts a new session; a successful clear itself leaves an empty log.
        headerPending = true
        true
    }

    suspend fun createSnapshot(): File? = submit("生成分享文件", null as File?) {
        val text = exportText()
        ensureDirectory(snapshotDirectory)
        val file = File(snapshotDirectory, "stockshot-diagnostics-${System.currentTimeMillis()}-${UUID.randomUUID()}.log")
        file.writeText(text, Charsets.UTF_8)
        val obsolete = snapshots().filter { it != file }.sortedByDescending { it.lastModified() }.drop(SNAPSHOT_COUNT - 1)
        for (old in obsolete) if (!old.delete()) throw IOException("无法清理旧分享文件")
        file
    }

    suspend fun export(write: (String) -> Unit): Boolean = submit("导出日志", false) {
        write(exportText())
        true
    }

    /** Tests can dispose their private writer only after all accepted work has completed. */
    internal suspend fun close() {
        submit("关闭日志队列", Unit) { Unit }
        writer.shutdown()
    }

    private fun appendSessionHeader() {
        appendRecord(Log.INFO, "[Diagnostics] 诊断日志已开启\n${redactDiagnosticText(sessionInfo)}")
        headerPending = false
    }

    private fun appendRecord(
        priority: Int,
        text: String,
        timestamp: Long = System.currentTimeMillis(),
        thread: String = Thread.currentThread().name,
    ) {
        ensureDirectory(directory)
        val time = formatTime(timestamp)
        val level = when (priority) {
            Log.DEBUG -> "D"
            Log.INFO -> "I"
            Log.WARN -> "W"
            else -> "E"
        }
        val bytes = boundedUtf8("$time $level [$thread] $text\n")
        val current = files().first()
        if (current.exists() && current.length() + bytes.size > maxFileBytes) rotate()
        current.appendBytes(bytes)
    }

    private fun boundedUtf8(text: String): ByteArray {
        val bytes = text.toByteArray(Charsets.UTF_8)
        if (bytes.size <= maxFileBytes) return bytes
        val suffix = "\n[单条日志超过容量，后续内容已截断]\n".toByteArray(Charsets.UTF_8)
        var end = maxFileBytes - suffix.size
        // Do not split a multibyte code point at the file size boundary.
        while (end > 0 && (bytes[end].toInt() and 0xC0) == 0x80) end--
        return bytes.copyOfRange(0, end) + suffix
    }

    private fun files(): List<File> = (0 until fileCount).map {
        File(directory, if (it == 0) "diagnostic.log" else "diagnostic.$it.log")
    }

    private fun snapshots(): List<File> = snapshotDirectory.listFiles()?.filter {
        it.isFile && it.name.startsWith("stockshot-diagnostics-") && it.extension == "log"
    }.orEmpty()

    private fun rotate() {
        val files = files()
        if (files.last().exists() && !files.last().delete()) throw IOException("无法移除最旧日志")
        for (index in files.lastIndex downTo 1) {
            val source = files[index - 1]
            if (source.exists() && !source.renameTo(files[index])) throw IOException("无法轮转日志")
        }
    }

    private fun readAll(): String = buildString {
        for (file in files().asReversed()) if (file.exists()) append(file.readText(Charsets.UTF_8))
    }

    private fun exportText(): String = "StockShot 诊断日志导出\n${redactDiagnosticText(sessionInfo)}\n" +
        "loggingEnabled=${enabledState.value} exportedAt=${formatTime(System.currentTimeMillis())}\n\n" + readAll()

    private fun formatTime(timestamp: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS Z", Locale.US).format(Date(timestamp))

    private fun ensureDirectory(file: File) {
        if (!file.isDirectory && !file.mkdirs()) throw IOException("日志目录不可用")
    }

    private fun enqueue(operation: String, block: () -> Unit) {
        try {
            writer.execute { perform(operation, Unit, block) }
        } catch (e: Exception) {
            report(operation, e)
        }
    }

    private suspend fun <T> submit(operation: String, fallback: T, block: () -> T): T {
        val result = CompletableDeferred<T>()
        synchronized(gate) {
            try {
                writer.execute { result.complete(perform(operation, fallback, block)) }
            } catch (e: Exception) {
                report(operation, e)
                result.complete(fallback)
            }
        }
        return result.await()
    }

    private fun <T> perform(operation: String, fallback: T, block: () -> T): T = try {
        block().also {
            synchronized(errors) {
                errors.remove(operation)
                errorState.value = errors.values.takeIf { values -> values.isNotEmpty() }?.joinToString("\n")
            }
        }
    } catch (e: Exception) {
        report(operation, e)
        fallback
    }

    private fun report(operation: String, error: Exception) = synchronized(errors) {
        errors[operation] = "$operation 失败：" +
            redactDiagnosticText(error.message ?: error.javaClass.simpleName).take(300)
        errorState.value = errors.values.joinToString("\n")
    }

    internal companion object {
        const val PREVIEW_CHAR_LIMIT = 65_536
        private const val SNAPSHOT_COUNT = 4
    }
}

/** Redact common credentials in messages and exception text without logging settings or payloads. */
internal fun redactDiagnosticText(text: String): String {
    var result = text.replace(Regex("(?i)(https?://)[^/@\\s]+@"), "$1[REDACTED]@")
    result = result.replace(Regex("(?i)(\\b(?:Bearer|Basic)\\s+)[A-Za-z0-9._~+/=-]+")) {
        it.groupValues[1] + "[REDACTED]"
    }
    result = result.replace(Regex("(?i)((?:api[_-]?key|access[_-]?token|refresh[_-]?token|token|password|passwd|secret|authorization)[\\\"']?\\s*[:=]\\s*)(?:\\\"[^\\\"]*\\\"|'[^']*'|[^\\s,;&]+)")) {
        it.groupValues[1] + "[REDACTED]"
    }
    return result.replace(Regex("(?i)([?&](?:key|apikey|api_key|token|access_token|secret|password)=)[^&#\\s]+")) {
        it.groupValues[1] + "[REDACTED]"
    }
}
