package com.stockshot.manager.indicator

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PointF
import android.graphics.RectF
import androidx.room.Room
import com.google.gson.Gson
import com.google.gson.JsonParser
import com.stockshot.indicator.CapitalColumnDiagnostic
import com.stockshot.indicator.CapitalColumnStatus
import com.stockshot.indicator.CapitalStats
import com.stockshot.indicator.DetectionStatus
import com.stockshot.indicator.IndicatorDiagnostics
import com.stockshot.indicator.IndicatorMatchResult
import com.stockshot.manager.data.AppDatabase
import com.stockshot.manager.data.SettingsStore
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.zip.ZipInputStream
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import android.app.Application

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class IndicatorSampleStoreTest {
    @get:Rule val temporary = TemporaryFolder()
    private val bitmaps = mutableListOf<Bitmap>()
    private val options = IndicatorDetectionOptions(capitalMethod = "pointwise", capitalRule = "all")

    @After
    fun recycleImages() { bitmaps.filterNot { it.isRecycled }.forEach { it.recycle() } }

    @Test
    fun savesOriginalPixelsParametersFullDiagnosticsAndBuildIdentityAcrossReopen() = runBlocking {
        val directory = File(temporary.root, "samples")
        val source = image(1)
        val original = result(DetectionStatus.NO_MATCH).copy(
            diagnostics = IndicatorDiagnostics(
                trendRegion = RectF(0f, 0.5f, 0.7f, 0.7f),
                capitalWindow = RectF(0.1f, 0.7f, 0.4f, 0.8f),
                capitalStats = CapitalStats(totalColumns = 2, validColumns = 1, missingColumns = 1),
                columns = listOf(CapitalColumnDiagnostic(0.2f, CapitalColumnStatus.MISSING, purpleY = 0.72f)),
                messages = listOf("人工确认：严格整段不符合"),
            ),
        )
        val saved = store(directory).save(source, options, original,
            IndicatorSampleLabel.NO_MATCH, IndicatorSampleSplit.HOLDOUT, "保留原图")
        assertFalse(source.isRecycled)
        val reopened = store(directory)
        val restored = reopened.listSamples().single()
        assertEquals(saved.id, restored.id)
        assertEquals(options, restored.options)
        assertEquals(original, restored.originalResult)
        assertEquals(IndicatorSampleLabel.NO_MATCH, restored.label)
        assertEquals(IndicatorSampleSplit.HOLDOUT, restored.split)
        assertEquals("test-version", restored.versionName)
        assertEquals(42, restored.versionCode)
        assertEquals("test-commit", restored.gitCommit)
        assertEquals("保留原图", restored.note)
        val decoded = reopened.loadBitmap(restored)!!
        bitmaps += decoded
        assertEquals(source.width, decoded.width)
        assertEquals(source.height, decoded.height)
        assertEquals(source.getPixel(0, 0), decoded.getPixel(0, 0))
        assertEquals(source.getPixel(17, 10), decoded.getPixel(17, 10))
        val json = JsonParser.parseString(File(directory, saved.id + "/metadata.json").readText()).asJsonObject
        assertTrue(json.getAsJsonObject("originalResult").getAsJsonObject("diagnostics").has("columns"))
        assertTrue(saved.imageSha256.matches(Regex("[a-f0-9]{64}")))
        assertEquals(saved.pixelSha256, saved.id)
    }

    @Test
    fun duplicatePixelsCannotMoveBetweenTuningAndHoldoutOrOverwriteOriginalLabel() = runBlocking {
        val directory = File(temporary.root, "samples")
        val source = image(1)
        val saved = store(directory).save(source, options, result(DetectionStatus.MATCH),
            IndicatorSampleLabel.MATCH, IndicatorSampleSplit.TUNING, "首次")
        val metadata = File(directory, saved.id + "/metadata.json").readBytes()
        val duplicate = source.copy(Bitmap.Config.ARGB_8888, false)!!
        bitmaps += duplicate
        duplicate.density = source.density + 100
        try {
            store(directory).save(duplicate, options.copy(capitalRule = "signal"), result(DetectionStatus.NO_MATCH),
                IndicatorSampleLabel.NO_MATCH, IndicatorSampleSplit.HOLDOUT, "不能覆盖")
            fail("Duplicate image must be rejected even across store instances and splits")
        } catch (error: DuplicateIndicatorSampleException) {
            assertEquals(saved.id, error.existing.id)
            assertEquals(IndicatorSampleSplit.TUNING, error.existing.split)
            assertEquals(IndicatorSampleLabel.MATCH, error.existing.label)
        }
        assertTrue(metadata.contentEquals(File(directory, saved.id + "/metadata.json").readBytes()))
        assertEquals(1, store(directory).listSamples().size)
    }

    @Test
    fun evaluatesConfusionCountsWithUnknownBoundaryAndHoldoutKeptSeparate() = runBlocking {
        val directory = File(temporary.root, "samples")
        val store = store(directory) { bitmap, _ ->
            when (Color.red(bitmap.getPixel(0, 0))) {
                1, 2, 6, 8 -> result(DetectionStatus.MATCH)
                3, 4, 7 -> result(DetectionStatus.NO_MATCH)
                else -> result(DetectionStatus.UNKNOWN)
            }
        }
        val labels = listOf(IndicatorSampleLabel.MATCH, IndicatorSampleLabel.NO_MATCH, IndicatorSampleLabel.NO_MATCH,
            IndicatorSampleLabel.MATCH, IndicatorSampleLabel.MATCH, IndicatorSampleLabel.BOUNDARY, null,
            IndicatorSampleLabel.NO_MATCH)
        labels.forEachIndexed { index, label ->
            store.save(image(index + 1), options, result(DetectionStatus.UNKNOWN), label,
                if (index == 7) IndicatorSampleSplit.HOLDOUT else IndicatorSampleSplit.TUNING)
        }
        val report = store.evaluate(options)
        val tuning = report.tuning
        assertEquals(7, tuning.total)
        assertEquals(6, tuning.reviewed)
        assertEquals(1, tuning.unlabeled)
        assertEquals(1, tuning.boundary)
        assertEquals(1, tuning.tp)
        assertEquals(1, tuning.fp)
        assertEquals(1, tuning.tn)
        assertEquals(1, tuning.fn)
        assertEquals(1, tuning.unknown)
        assertEquals(1, tuning.unknownLabeled)
        assertEquals(5, tuning.binaryLabeled)
        assertEquals(0.4, tuning.accuracy!!, 0.0)
        assertEquals(1, report.holdout.total)
        assertEquals(1, report.holdout.fp)
        assertEquals(0.0, report.holdout.accuracy!!, 0.0)
    }

    @Test
    fun changedRuleSemanticsExcludeLabelsButToleranceAndScoreTuningRemainComparable() = runBlocking {
        val directory = File(temporary.root, "samples")
        val store = store(directory) { _, _ -> result(DetectionStatus.MATCH) }
        store.save(image(1), options, result(DetectionStatus.NO_MATCH), IndicatorSampleLabel.NO_MATCH, IndicatorSampleSplit.HOLDOUT)
        val changedSemantics = listOf(
            options.copy(requireYellow = true),
            options.copy(capitalMethod = "median"),
            options.copy(capitalRule = "signal"),
            options.copy(signalSelection = "latest"),
            options.copy(capitalWindowScale = 0.8f),
            options.copy(majorityThreshold = 0.8f),
        )
        for (changed in changedSemantics) {
            val report = store.evaluate(changed)
            assertEquals(1, report.holdout.reviewed)
            assertEquals(1, report.holdout.incompatibleLabels)
            assertEquals(0, report.holdout.binaryLabeled)
            assertEquals(0, report.holdout.fp)
            assertNull(report.holdout.accuracy)
            assertFalse(report.rows.single().labelCompatible)
            assertTrue(report.rows.single().error!!.contains("标签规则不同"))
        }
        val comparable = store.evaluate(options.copy(dotToleranceScale = 1.4f, minConfidence = 0.5f))
        assertEquals(0, comparable.holdout.incompatibleLabels)
        assertEquals(1, comparable.holdout.fp)
        assertTrue(comparable.rows.single().labelCompatible)
    }

    @Test
    fun rerunsDoNotMutateOriginalEvidenceAndExportContainsOriginalsAndLatestReport() = runBlocking {
        val directory = File(temporary.root, "samples")
        val original = result(DetectionStatus.NO_MATCH)
        val store = store(directory) { _, _ -> result(DetectionStatus.MATCH) }
        val saved = store.save(image(1), options, original, IndicatorSampleLabel.NO_MATCH, IndicatorSampleSplit.TUNING)
        val metadataFile = File(directory, saved.id + "/metadata.json")
        val originalBytes = metadataFile.readBytes()
        val changed = options.copy(dotToleranceScale = 1.6f)
        val report = store.evaluate(changed)
        assertEquals(DetectionStatus.MATCH, report.rows.single().status)
        assertEquals(original, store.listSamples().single().originalResult)
        assertTrue(originalBytes.contentEquals(metadataFile.readBytes()))
        val output = TrackingOutput()
        store.exportZip(output)
        assertFalse(output.closed)
        val zip = zipEntries(output.toByteArray())
        assertTrue(zip.containsKey(saved.id + "/original.png"))
        assertTrue(originalBytes.contentEquals(zip.getValue(saved.id + "/metadata.json")))
        assertTrue(zip.containsKey("latest-evaluation.json"))
        val exportedReport = Gson().fromJson(zip.getValue("latest-evaluation.json").toString(Charsets.UTF_8), IndicatorEvaluationReport::class.java)
        assertEquals(changed, exportedReport.options)
        assertEquals(1, exportedReport.tuning.fp)
        assertFalse(zip.keys.any { it.contains("..") || it.startsWith("/") || it.contains(".pending") })
    }

    @Test
    fun emptyOrOnlyBoundaryAndUnlabeledSamplesNeverReportPerfectAccuracy() = runBlocking {
        val directory = File(temporary.root, "samples")
        val store = store(directory) { _, _ -> result(DetectionStatus.MATCH) }
        val empty = store.evaluate(options)
        assertEquals(0, empty.tuning.total)
        assertNull(empty.tuning.accuracy)
        assertNull(empty.holdout.accuracy)
        store.save(image(1), options, result(DetectionStatus.MATCH), IndicatorSampleLabel.BOUNDARY, IndicatorSampleSplit.TUNING)
        store.save(image(2), options, result(DetectionStatus.MATCH), null, IndicatorSampleSplit.TUNING)
        val unscored = store.evaluate(options).tuning
        assertEquals(2, unscored.total)
        assertEquals(1, unscored.boundary)
        assertEquals(1, unscored.unlabeled)
        assertEquals(0, unscored.binaryLabeled)
        assertNull(unscored.accuracy)
    }

    @Test
    fun corruptImageAndDetectorFailureBecomeUnknownNeverTrueNegatives() = runBlocking {
        val directory = File(temporary.root, "samples")
        val store = store(directory) { _, _ -> throw IOException("识别失败") }
        val first = store.save(image(1), options, result(DetectionStatus.NO_MATCH), IndicatorSampleLabel.NO_MATCH, IndicatorSampleSplit.TUNING)
        store.save(image(2), options, result(DetectionStatus.NO_MATCH), IndicatorSampleLabel.NO_MATCH, IndicatorSampleSplit.TUNING)
        File(directory, first.id + "/original.png").writeText("corrupt")
        assertNull(store.loadBitmap(first))
        val report = store.evaluate(options)
        assertEquals(2, report.tuning.unknown)
        assertEquals(2, report.tuning.unknownLabeled)
        assertEquals(0, report.tuning.tn)
        assertEquals(0.0, report.tuning.accuracy!!, 0.0)
        assertTrue(report.rows.all { it.error != null })
    }

    @Test
    fun serializationFailureCleansPendingFilesAndAllowsRetryWithoutPartialSample() = runBlocking {
        val directory = File(temporary.root, "samples")
        val store = store(directory)
        val source = image(1)
        try {
            store.save(source, options, result(DetectionStatus.MATCH).copy(confidence = Float.NaN),
                IndicatorSampleLabel.MATCH, IndicatorSampleSplit.TUNING)
            fail("NaN must not produce a partial JSON sample")
        } catch (_: IllegalArgumentException) { }
        assertTrue(store.listSamples().isEmpty())
        assertTrue(directory.listFiles()!!.isEmpty())
        assertNotNull(store.save(source, options, result(DetectionStatus.MATCH),
            IndicatorSampleLabel.MATCH, IndicatorSampleSplit.TUNING))
    }

    @Test
    fun rejectsTraversalIdsAndSymbolicLinksInsteadOfReadingOutsidePrivateSampleRoot() = runBlocking {
        val directory = File(temporary.root, "samples")
        val store = store(directory)
        val saved = store.save(image(1), options, result(DetectionStatus.MATCH), IndicatorSampleLabel.MATCH, IndicatorSampleSplit.TUNING)
        try {
            store.loadBitmap(saved.copy(id = "../outside"))
            fail("Traversal identifiers must be rejected")
        } catch (_: IllegalArgumentException) { }
        val imageFile = File(directory, saved.id + "/original.png")
        assertTrue(imageFile.delete())
        val outside = File(temporary.root, "outside.png").apply { writeText("private unrelated file") }
        Files.createSymbolicLink(imageFile.toPath(), outside.toPath())
        try {
            store.loadBitmap(saved)
            fail("Symlinked sample files must be rejected")
        } catch (_: IOException) { }
        assertEquals("private unrelated file", outside.readText())
    }

    @Test
    fun detectionOptionsReadPersistedSettingsAndApplyThemToDetector() = runBlocking {
        val context: Application = RuntimeEnvironment.getApplication()
        val database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val settings = SettingsStore(context, database.appSettingDao())
            settings.put(SettingsStore.KEY_CAPITAL_REQUIRE_YELLOW, "true")
            settings.put(SettingsStore.KEY_DOT_TOL_SCALE, "1.5")
            settings.put(SettingsStore.KEY_MIN_CONFIDENCE, "0.2")
            settings.put(SettingsStore.KEY_CAPITAL_WINDOW_SCALE, "0.8")
            settings.put(SettingsStore.KEY_CAPITAL_METHOD, "pointwise")
            settings.put(SettingsStore.KEY_CAPITAL_RULE, "majority")
            settings.put(SettingsStore.KEY_SIGNAL_SELECTION, "latest")
            settings.put(SettingsStore.KEY_CAPITAL_MAJORITY_THRESHOLD, "0.75")
            val loaded = IndicatorDetectionOptions.fromSettings(settings)
            assertEquals(IndicatorDetectionOptions(true, 1.5f, 0.2f, 0.8f, "pointwise", "majority", "latest", 0.75f), loaded)
            val detected = loaded.detect(image(1))
            assertEquals(DetectionStatus.UNKNOWN, detected.status)
            assertEquals("pointwise", detected.diagnostics.capitalMethod)
            assertEquals("majority", detected.diagnostics.capitalRule)
            assertEquals("latest", detected.diagnostics.signalSelection)
            assertEquals(0.75f, detected.diagnostics.majorityThreshold, 0f)
        } finally {
            database.close()
            context.getSharedPreferences("settings_cache", android.content.Context.MODE_PRIVATE).edit().clear().commit()
        }
    }

    private fun store(
        directory: File,
        detect: (Bitmap, IndicatorDetectionOptions) -> IndicatorMatchResult = { _, _ -> result(DetectionStatus.MATCH) },
    ): IndicatorSampleStore = IndicatorSampleStore(directory, "test-version", 42, "test-commit", detect)

    private fun image(marker: Int): Bitmap = Bitmap.createBitmap(32, 24, Bitmap.Config.ARGB_8888).apply {
        eraseColor(Color.rgb(marker, 20, 30))
        setPixel(17, 10, Color.rgb(80, marker, 90))
        bitmaps += this
    }

    private fun result(status: DetectionStatus): IndicatorMatchResult = IndicatorMatchResult(
        matched = status == DetectionStatus.MATCH,
        trendDotsMatched = true,
        capitalLinesMatched = status == DetectionStatus.MATCH,
        confidence = 0.75f,
        redPoint = PointF(0.3f, 0.65f),
        greenPoint = PointF(0.3f, 0.64f),
        purpleYRatio = 0.72f,
        capitalGreenYRatio = 0.75f,
        reason = "test result",
        status = status,
        trendStatus = DetectionStatus.MATCH,
        capitalStatus = status,
    )

    private fun zipEntries(bytes: ByteArray): Map<String, ByteArray> = buildMap {
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                put(entry.name, zip.readBytes())
                zip.closeEntry()
            }
        }
    }

    private class TrackingOutput : ByteArrayOutputStream() {
        var closed = false
        override fun close() { closed = true; super.close() }
    }
}
