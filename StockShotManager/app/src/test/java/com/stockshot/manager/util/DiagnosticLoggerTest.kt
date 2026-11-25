package com.stockshot.manager.util

import android.util.Log
import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DiagnosticLoggerTest {
    @get:Rule val temporary = TemporaryFolder()
    private val loggers = mutableListOf<DiagnosticLogger>()

    @After
    fun closeWriters() = runBlocking {
        loggers.forEach { it.close() }
    }

    @Test
    fun defaultOffAndDisabledLoggingNeverEmitButExistingLogsStayReadable() = runBlocking {
        val emitted = mutableListOf<String>()
        val logger = newLogger(logcat = { _, text -> emitted.add(text); 17 })
        assertFalse(logger.enabled.value)
        assertEquals(0, logger.log(Log.INFO, "test", "before enabling"))
        assertEquals("", logger.readLogs())
        assertTrue(emitted.isEmpty())

        logger.setEnabled(true)
        assertEquals(17, logger.log(Log.DEBUG, "test", "accepted before off"))
        logger.setEnabled(false)
        assertFalse(logger.enabled.value)
        assertEquals(0, logger.log(Log.ERROR, "test", "rejected after off", IOException("hidden failure")))
        val text = logger.readLogs()
        assertTrue(text.contains("accepted before off"))
        assertFalse(text.contains("rejected after off"))
        assertFalse(text.contains("hidden failure"))
        assertEquals(1, emitted.size)
        assertEquals(text, logger.readLogs())
    }

    @Test
    fun enabledAndDisabledPreferenceSurviveLoggerRecreation() = runBlocking {
        val preferences = temporary.newFile("enabled.txt")
        preferences.writeText("false")
        val directory = temporary.newFolder("persisted-logs")
        val snapshots = temporary.newFolder("persisted-snapshots")
        fun reopen() = newLogger(
            directory = directory,
            snapshotDirectory = snapshots,
            initialEnabled = preferences.readText().toBooleanStrict(),
            persistEnabled = { value -> preferences.writeText(value.toString()); true },
        )
        val first = reopen()
        assertFalse(first.enabled.value)
        first.setEnabled(true)
        first.log(Log.INFO, "test", "persisted entry")
        first.close()
        loggers.remove(first)

        val second = reopen()
        assertTrue(second.enabled.value)
        assertTrue(second.readLogs().contains("persisted entry"))
        second.setEnabled(false)
        second.close()
        loggers.remove(second)

        val third = reopen()
        assertFalse(third.enabled.value)
        assertTrue(third.readLogs().contains("persisted entry"))
    }

    @Test(timeout = 10_000)
    fun exportAndClearWaitForAllPreviouslyAcceptedWrites() = runBlocking {
        val writingStarted = CountDownLatch(1)
        val permitWrites = CountDownLatch(1)
        val logger = newLogger(persistEnabled = {
            writingStarted.countDown()
            check(permitWrites.await(5, TimeUnit.SECONDS))
            true
        })
        logger.setEnabled(true)
        assertTrue(writingStarted.await(5, TimeUnit.SECONDS))
        repeat(25) { logger.log(Log.INFO, "queued", "entry-$it") }
        val exported = AtomicReference<String>()
        val export = async(start = CoroutineStart.UNDISPATCHED) { logger.export { exported.set(it) } }
        val clear = async(start = CoroutineStart.UNDISPATCHED) { logger.clearLogs() }
        assertFalse(export.isCompleted)
        assertFalse(clear.isCompleted)
        permitWrites.countDown()

        assertTrue(export.await())
        assertTrue(clear.await())
        assertTrue(exported.get().contains("entry-0"))
        assertTrue(exported.get().contains("entry-24"))
        assertEquals("", logger.readLogs())
        logger.log(Log.INFO, "after", "new session")
        val afterClear = logger.readLogs()
        assertTrue(afterClear.contains("new session"))
        assertTrue(afterClear.contains("package=com.stockshot.test"))
        assertFalse(afterClear.contains("entry-24"))
    }

    @Test
    fun snapshotsAreUniqueBoundedAndContainEnvironmentEvenWhenDisabledAndEmpty() = runBlocking {
        val snapshotDirectory = temporary.newFolder("share")
        val logger = newLogger(snapshotDirectory = snapshotDirectory)
        val first = requireNotNull(logger.createSnapshot())
        val firstText = first.readText()
        assertTrue(firstText.contains("version=1.8"))
        assertTrue(firstText.contains("package=com.stockshot.test"))
        assertTrue(firstText.contains("git=abcdef0"))
        assertTrue(firstText.contains("Android=14"))
        assertTrue(firstText.contains("loggingEnabled=false"))
        val second = requireNotNull(logger.createSnapshot())
        assertNotEquals(first, second)
        assertEquals(firstText, first.readText())
        repeat(6) { assertNotNull(logger.createSnapshot()) }
        assertEquals(4, snapshotDirectory.listFiles()?.size)

        assertTrue(logger.clearLogs())
        assertEquals(0, snapshotDirectory.listFiles()?.size)
        assertEquals("", logger.readLogs())
    }

    @Test
    fun exceptionIncludesStackCauseAndSuppressedFailuresWithCredentialsRedacted() = runBlocking {
        val logcat = mutableListOf<String>()
        val logger = newLogger(initialEnabled = true, logcat = { _, text -> logcat.add(text); 1 })
        val cause = IOException("https://user:passwordvalue@example.com/?api_key=topsecret&q=600000")
        val failure = IllegalStateException("Authorization: Bearer bearersecret", cause)
        failure.addSuppressed(IOException("password=secretpass"))
        failure.stackTrace = arrayOf(StackTraceElement("StockFetcher", "request", "StockFetcher.kt", 42))
        logger.log(Log.ERROR, "network", "request failed token=tokensecret", failure)

        val text = logger.readLogs()
        assertTrue(text.contains("StockFetcher.request(StockFetcher.kt:42)"))
        assertTrue(text.contains("Caused by: java.io.IOException"))
        assertTrue(text.contains("Suppressed: java.io.IOException"))
        assertTrue(text.contains("q=600000"))
        for (secret in listOf("passwordvalue", "topsecret", "bearersecret", "secretpass", "tokensecret")) {
            assertFalse("File leaked $secret", text.contains(secret))
            assertFalse("Logcat leaked $secret", logcat.single().contains(secret))
        }
        assertTrue(text.contains("[REDACTED]"))
    }

    @Test
    fun rotationRetainsTheNewestRecordsInOrderAndLimitsEveryFile() = runBlocking {
        val directory = temporary.newFolder("rotation")
        val logger = newLogger(directory = directory, initialEnabled = true, maxFileBytes = 350)
        repeat(15) { logger.log(Log.INFO, "rotate", "sequence-$it " + "x".repeat(100)) }
        val text = logger.readLogs()
        val sequences = Regex("sequence-(\\d+)").findAll(text).map { it.groupValues[1].toInt() }.toList()
        assertTrue(sequences.size in 2..3)
        assertEquals(14, sequences.last())
        assertEquals(sequences.sorted(), sequences)
        assertFalse(text.contains("sequence-0 "))
        val files = directory.listFiles().orEmpty()
        assertEquals(3, files.size)
        assertTrue(files.all { it.length() <= 350 })
    }

    @Test
    fun oversizedUnicodeEntryStaysValidUtf8AndWithinCapacity() = runBlocking {
        val directory = temporary.newFolder("oversized")
        val logger = newLogger(directory = directory, initialEnabled = true, maxFileBytes = 512)
        logger.log(Log.WARN, "large", "🚀汉".repeat(2000))
        val text = logger.readLogs()
        assertTrue(text.contains("单条日志超过容量"))
        assertFalse(text.contains('\uFFFD'))
        assertTrue(directory.listFiles().orEmpty().all { it.length() <= 512 })
    }

    @Test
    fun previewIsBoundedButExportIncludesTheCompleteRetainedLog() = runBlocking {
        val logger = newLogger(initialEnabled = true)
        logger.log(Log.INFO, "start", "retained-start-marker")
        repeat(120) { logger.log(Log.INFO, "preview", "x".repeat(1000)) }
        logger.log(Log.INFO, "end", "retained-end-marker")
        val preview = logger.readLogs()
        assertTrue(preview.length <= DiagnosticLogger.PREVIEW_CHAR_LIMIT + 100)
        assertTrue(preview.startsWith("[仅显示最近"))
        assertFalse(preview.contains("retained-start-marker"))
        assertTrue(preview.contains("retained-end-marker"))
        val export = requireNotNull(logger.createSnapshot()).readText()
        assertTrue(export.contains("retained-start-marker"))
        assertTrue(export.contains("retained-end-marker"))
    }

    @Test
    fun writeAndExportFailuresAreVisibleWithoutCrashingAndCanRecover() = runBlocking {
        val directory = temporary.newFile("blocked-directory")
        val logger = newLogger(directory = directory, initialEnabled = true)
        logger.log(Log.INFO, "test", "cannot write")
        assertEquals("", logger.readLogs())
        assertTrue(logger.lastError.value.orEmpty().contains("写入日志"))
        assertTrue(directory.delete())
        logger.log(Log.INFO, "test", "recovered write")
        assertTrue(logger.readLogs().contains("recovered write"))
        assertNull(logger.lastError.value)

        assertFalse(logger.export { throw IOException("token=exportsecret") })
        assertTrue(logger.lastError.value.orEmpty().contains("导出日志"))
        assertFalse(logger.lastError.value.orEmpty().contains("exportsecret"))
        var recoveredExport = ""
        assertTrue(logger.export { recoveredExport = it })
        assertTrue(recoveredExport.contains("recovered write"))
        assertNull(logger.lastError.value)
    }

    @Test
    fun failedPreferenceWriteRemainsVisibleAfterSuccessfulReads() = runBlocking {
        val logger = newLogger(persistEnabled = { false })
        logger.setEnabled(true)
        logger.log(Log.INFO, "test", "runtime logging still available")
        assertTrue(logger.readLogs().contains("runtime logging still available"))
        assertTrue(logger.lastError.value.orEmpty().contains("保存日志开关"))
    }

    private fun newLogger(
        directory: File = temporary.newFolder(),
        snapshotDirectory: File = temporary.newFolder(),
        initialEnabled: Boolean = false,
        persistEnabled: (Boolean) -> Boolean = { true },
        maxFileBytes: Int = 512 * 1024,
        logcat: (Int, String) -> Int = { _, _ -> 0 },
    ) = DiagnosticLogger(
        directory = directory,
        snapshotDirectory = snapshotDirectory,
        initialEnabled = initialEnabled,
        persistEnabled = persistEnabled,
        sessionInfo = "package=com.stockshot.test version=1.8 (9) git=abcdef0\nAndroid=14 sdk=34 device=test",
        maxFileBytes = maxFileBytes,
        logcat = logcat,
    ).also { loggers += it }
}
