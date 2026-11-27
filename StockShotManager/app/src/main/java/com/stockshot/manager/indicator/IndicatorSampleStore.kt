package com.stockshot.manager.indicator

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.stockshot.indicator.DetectionStatus
import com.stockshot.indicator.IndicatorMatchResult
import com.stockshot.manager.BuildConfig
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

enum class IndicatorSampleLabel { MATCH, NO_MATCH, BOUNDARY }
enum class IndicatorSampleSplit { TUNING, HOLDOUT }

/** Original evidence is immutable. New runs are recorded separately in an evaluation report. */
data class IndicatorSample(
    val schemaVersion: Int = 1,
    val id: String,
    val imageSha256: String,
    val pixelSha256: String,
    val createdAt: Long,
    val width: Int,
    val height: Int,
    val options: IndicatorDetectionOptions,
    val originalResult: IndicatorMatchResult,
    val label: IndicatorSampleLabel?,
    val split: IndicatorSampleSplit,
    val note: String,
    val versionName: String,
    val versionCode: Int,
    val gitCommit: String,
)

class DuplicateIndicatorSampleException(val existing: IndicatorSample) :
    IOException("同一原图已保存在 " + existing.split.name + "，不能重复保存或改变分组。")

data class IndicatorEvaluationRow(
    val sampleId: String,
    val label: IndicatorSampleLabel?,
    val split: IndicatorSampleSplit,
    val status: DetectionStatus,
    val result: IndicatorMatchResult?,
    val error: String? = null,
    val labelCompatible: Boolean = true,
)

data class IndicatorEvaluationCounts(
    val total: Int,
    val reviewed: Int,
    val unlabeled: Int,
    val boundary: Int,
    val incompatibleLabels: Int,
    val tp: Int,
    val fp: Int,
    val tn: Int,
    val fn: Int,
    /** All UNKNOWN outputs, including boundary and unlabeled samples. */
    val unknown: Int,
    /** UNKNOWN outputs for a binary human label; these never count as correct. */
    val unknownLabeled: Int,
    val binaryLabeled: Int,
    /** Null means there is no binary labeled evidence, not 100% accuracy. */
    val accuracy: Double?,
)

data class IndicatorEvaluationReport(
    val schemaVersion: Int = 1,
    val evaluatedAt: Long,
    val options: IndicatorDetectionOptions,
    val versionName: String,
    val versionCode: Int,
    val gitCommit: String,
    val tuning: IndicatorEvaluationCounts,
    val holdout: IndicatorEvaluationCounts,
    val rows: List<IndicatorEvaluationRow>,
)

/**
 * Private, append-only image evidence. The store never uploads or exports automatically.
 * A pixel SHA-256 deduplicates images even when their PNG encoding or metadata differs.
 */
class IndicatorSampleStore internal constructor(
    private val directory: File,
    private val versionName: String,
    private val versionCode: Int,
    private val gitCommit: String,
    private val detector: (Bitmap, IndicatorDetectionOptions) -> IndicatorMatchResult,
) {
    constructor(context: Context) : this(
        File(context.applicationContext.filesDir, "indicator-samples"),
        BuildConfig.VERSION_NAME,
        BuildConfig.VERSION_CODE,
        BuildConfig.GIT_COMMIT,
        { bitmap, options -> options.detect(bitmap) },
    )

    private val gson: Gson = GsonBuilder().serializeNulls().setPrettyPrinting().create()
    private val mutex = locks.getOrPut(directory.canonicalPath) { Mutex() }

    suspend fun listSamples(): List<IndicatorSample> = withContext(Dispatchers.IO) {
        mutex.withLock { listSamplesLocked() }
    }

    suspend fun save(
        bitmap: Bitmap,
        options: IndicatorDetectionOptions,
        result: IndicatorMatchResult,
        label: IndicatorSampleLabel?,
        split: IndicatorSampleSplit,
        note: String = "",
    ): IndicatorSample = withContext(Dispatchers.IO) {
        mutex.withLock {
            require(!bitmap.isRecycled) { "原图已被释放" }
            requireFiniteOptions(options)
            val root = rootDirectory()
            val snapshot = bitmap.copy(Bitmap.Config.ARGB_8888, false)
                ?: throw IOException("无法复制原始图片")
            var pending: File? = null
            try {
                val id = pixelDigest(snapshot)
                val target = sampleDirectory(root, id)
                if (target.exists()) throw DuplicateIndicatorSampleException(readSample(target))
                val staging = child(root, ".pending-" + UUID.randomUUID())
                if (!staging.mkdir()) throw IOException("无法创建样本临时目录")
                pending = staging
                val image = child(staging, IMAGE_NAME)
                FileOutputStream(image).use { output ->
                    if (!snapshot.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                        throw IOException("原图 PNG 保存失败")
                    }
                    output.fd.sync()
                }
                val sample = IndicatorSample(
                    id = id,
                    imageSha256 = fileDigest(image),
                    pixelSha256 = id,
                    createdAt = System.currentTimeMillis(),
                    width = snapshot.width,
                    height = snapshot.height,
                    options = options,
                    originalResult = result,
                    label = label,
                    split = split,
                    note = note,
                    versionName = versionName,
                    versionCode = versionCode,
                    gitCommit = gitCommit,
                )
                writeSynced(child(staging, METADATA_NAME), gson.toJson(sample))
                // Same-filesystem move publishes both files together; never replace existing evidence.
                if (target.exists()) throw DuplicateIndicatorSampleException(readSample(target))
                Files.move(staging.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
                pending = null
                sample
            } finally {
                snapshot.recycle()
                pending?.let { cleanupPending(it) }
            }
        }
    }

    /** Returns null for a missing, altered, or undecodable original; never returns a substitute. */
    suspend fun loadBitmap(sample: IndicatorSample): Bitmap? = withContext(Dispatchers.IO) {
        mutex.withLock {
            val root = rootDirectory()
            val saved = readSample(sampleDirectory(root, sample.id))
            readOriginalBitmap(root, saved)
        }
    }

    suspend fun evaluate(options: IndicatorDetectionOptions): IndicatorEvaluationReport = withContext(Dispatchers.IO) {
        mutex.withLock {
            requireFiniteOptions(options)
            val root = rootDirectory()
            val samples = listSamplesLocked()
            val rows = samples.map { sample ->
                coroutineContext.ensureActive()
                val labelCompatible = sample.label == null || sameLabelSemantics(sample.options, options)
                var bitmap: Bitmap? = null
                val row = try {
                    bitmap = readOriginalBitmap(root, sample)
                    if (bitmap == null) {
                        IndicatorEvaluationRow(sample.id, sample.label, sample.split, DetectionStatus.UNKNOWN,
                            result = null, error = "原图缺失、校验失败或无法解码")
                    } else {
                        val current = detector(bitmap, options)
                        IndicatorEvaluationRow(sample.id, sample.label, sample.split, current.status, current)
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    IndicatorEvaluationRow(sample.id, sample.label, sample.split, DetectionStatus.UNKNOWN,
                        result = null, error = error.message ?: error.javaClass.simpleName)
                } finally {
                    bitmap?.recycle()
                }
                if (labelCompatible) row else row.copy(
                    labelCompatible = false,
                    error = listOfNotNull(row.error, "标签规则不同，需重新确认；本次不纳入人工标签计分").joinToString("；"),
                )
            }
            val report = IndicatorEvaluationReport(
                evaluatedAt = System.currentTimeMillis(),
                options = options,
                versionName = versionName,
                versionCode = versionCode,
                gitCommit = gitCommit,
                tuning = counts(rows.filter { it.split == IndicatorSampleSplit.TUNING }),
                holdout = counts(rows.filter { it.split == IndicatorSampleSplit.HOLDOUT }),
                rows = rows,
            )
            coroutineContext.ensureActive()
            writeLatestReport(root, report)
            report
        }
    }

    /** Call only after the user explicitly chooses an export destination. Does not close [output]. */
    suspend fun exportZip(output: OutputStream): Unit = withContext(Dispatchers.IO) {
        mutex.withLock {
            val root = rootDirectory()
            val samples = listSamplesLocked()
            // Closing ZipOutputStream frees its deflater, while the caller retains its stream.
            val retainingOutput = object : java.io.FilterOutputStream(output) {
                override fun close() { flush() }
            }
            ZipOutputStream(retainingOutput).use { zip ->
                for (sample in samples) {
                    val folder = sampleDirectory(root, sample.id)
                    val image = child(folder, IMAGE_NAME)
                    if (!image.isFile || fileDigest(image) != sample.imageSha256) {
                        throw IOException("样本原图缺失或校验失败：" + sample.id)
                    }
                    addZipEntry(zip, sample.id + "/" + IMAGE_NAME, image)
                    addZipEntry(zip, sample.id + "/" + METADATA_NAME, child(folder, METADATA_NAME))
                }
                val latest = child(root, REPORT_NAME)
                if (latest.isFile) addZipEntry(zip, REPORT_NAME, latest)
                val manifest = "真实样本原图与原始参数/结果保留在各样本目录。" +
                    "\nTUNING 用于调参；HOLDOUT 单独统计，不能把调参结果视为独立验收。" +
                    "\nBOUNDARY 与未标注样本不纳入二分类分母；UNKNOWN 从不算正确。" +
                    "\nlatest-evaluation.json 仅在运行评估后存在，以其中时间与 sampleId 为准。\n"
                zip.putNextEntry(ZipEntry("README.txt"))
                zip.write(manifest.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
    }

    private fun listSamplesLocked(): List<IndicatorSample> {
        val root = rootDirectory()
        return (root.listFiles() ?: throw IOException("无法读取样本目录"))
            .filter { ID_PATTERN.matches(it.name) }
            .map { readSample(sampleDirectory(root, it.name)) }
            .sortedBy { it.createdAt }
    }

    private fun readSample(folder: File): IndicatorSample {
        if (!folder.isDirectory) throw IOException("样本目录损坏：" + folder.name)
        val metadata = child(folder, METADATA_NAME)
        val json = gson.fromJson(metadata.readText(Charsets.UTF_8), com.google.gson.JsonObject::class.java)
            ?: throw IOException("样本元数据为空")
        // Gson can create Kotlin objects without invoking constructors; validate required wire fields.
        for (field in listOf("schemaVersion", "id", "imageSha256", "pixelSha256", "createdAt", "width", "height",
            "options", "originalResult", "split", "note", "versionName", "versionCode", "gitCommit")) {
            if (!json.has(field) || json.get(field).isJsonNull) throw IOException("样本元数据缺少字段：" + field)
        }
        val sample = gson.fromJson(json, IndicatorSample::class.java)
        if (sample.schemaVersion != 1 || sample.id != folder.name || sample.pixelSha256 != sample.id ||
            !ID_PATTERN.matches(sample.imageSha256) || sample.width <= 0 || sample.height <= 0 ||
            json.get("split").asString !in IndicatorSampleSplit.values().map { it.name } ||
            (json.has("label") && !json.get("label").isJsonNull &&
                json.get("label").asString !in IndicatorSampleLabel.values().map { it.name })) {
            throw IOException("样本元数据无效：" + folder.name)
        }
        return sample
    }

    private fun readOriginalBitmap(root: File, sample: IndicatorSample): Bitmap? {
        val image = child(sampleDirectory(root, sample.id), IMAGE_NAME)
        if (!image.isFile || fileDigest(image) != sample.imageSha256) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(image.path, bounds)
        if (bounds.outWidth != sample.width || bounds.outHeight != sample.height) return null
        val bitmap = BitmapFactory.decodeFile(image.path) ?: return null
        if (pixelDigest(bitmap) != sample.pixelSha256) {
            bitmap.recycle()
            return null
        }
        return bitmap
    }

    private fun writeLatestReport(root: File, report: IndicatorEvaluationReport) {
        val temporary = child(root, ".report-" + UUID.randomUUID() + ".tmp")
        try {
            writeSynced(temporary, gson.toJson(report))
            Files.move(temporary.toPath(), child(root, REPORT_NAME).toPath(),
                StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            if (temporary.exists() && !temporary.delete()) temporary.deleteOnExit()
        }
    }

    private fun rootDirectory(): File {
        val root = directory.canonicalFile
        if (Files.isSymbolicLink(directory.toPath())) {
            throw IOException("样本目录不能使用符号链接或跳转路径")
        }
        if (!root.isDirectory && !root.mkdirs()) throw IOException("无法创建样本目录")
        return root
    }

    private fun sampleDirectory(root: File, id: String): File {
        require(ID_PATTERN.matches(id)) { "无效样本标识" }
        return child(root, id)
    }

    private fun child(parent: File, name: String): File {
        require(name.isNotBlank() && '/' !in name && '\\' !in name && name != "." && name != "..")
        val file = File(parent, name).absoluteFile.normalize()
        if (file.canonicalFile.parentFile != parent.canonicalFile || Files.isSymbolicLink(file.toPath())) {
            throw IOException("样本文件路径超出限定目录")
        }
        return file
    }

    private fun cleanupPending(folder: File) {
        // Only these two files were created by this save operation; never recursively delete evidence.
        for (name in listOf(IMAGE_NAME, METADATA_NAME)) child(folder, name).takeIf { it.isFile }?.delete()
        folder.delete()
    }

    private fun writeSynced(file: File, value: String) {
        FileOutputStream(file).use { output ->
            output.write(value.toByteArray(Charsets.UTF_8))
            output.fd.sync()
        }
    }

    private fun addZipEntry(zip: ZipOutputStream, name: String, file: File) {
        zip.putNextEntry(ZipEntry(name))
        FileInputStream(file).use { it.copyTo(zip) }
        zip.closeEntry()
    }

    private fun requireFiniteOptions(options: IndicatorDetectionOptions) {
        require(listOf(options.dotToleranceScale, options.minConfidence, options.capitalWindowScale,
            options.majorityThreshold).all { it.isFinite() }) { "检测参数必须是有限数值" }
    }

    private fun fileDigest(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(16 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().toHex()
    }

    private fun pixelDigest(bitmap: Bitmap): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(ByteBuffer.allocate(8).putInt(bitmap.width).putInt(bitmap.height).array())
        val pixels = IntArray(bitmap.width)
        val bytes = ByteBuffer.allocate(bitmap.width * 4)
        for (y in 0 until bitmap.height) {
            bitmap.getPixels(pixels, 0, bitmap.width, 0, y, bitmap.width, 1)
            bytes.clear()
            pixels.forEach { bytes.putInt(it) }
            digest.update(bytes.array())
        }
        return digest.digest().toHex()
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it.toInt() and 0xff) }

    companion object {
        private const val IMAGE_NAME = "original.png"
        private const val METADATA_NAME = "metadata.json"
        private const val REPORT_NAME = "latest-evaluation.json"
        private val ID_PATTERN = Regex("[0-9a-f]{64}")
        private val locks = ConcurrentHashMap<String, Mutex>()

        private fun sameLabelSemantics(original: IndicatorDetectionOptions, current: IndicatorDetectionOptions): Boolean =
            original.requireYellow == current.requireYellow && original.capitalMethod == current.capitalMethod &&
                original.capitalRule == current.capitalRule && original.signalSelection == current.signalSelection &&
                original.capitalWindowScale == current.capitalWindowScale && original.majorityThreshold == current.majorityThreshold

        private fun counts(rows: List<IndicatorEvaluationRow>): IndicatorEvaluationCounts {
            val compatible = rows.filter { it.labelCompatible }
            val tp = compatible.count { it.label == IndicatorSampleLabel.MATCH && it.status == DetectionStatus.MATCH }
            val fp = compatible.count { it.label == IndicatorSampleLabel.NO_MATCH && it.status == DetectionStatus.MATCH }
            val tn = compatible.count { it.label == IndicatorSampleLabel.NO_MATCH && it.status == DetectionStatus.NO_MATCH }
            val fn = compatible.count { it.label == IndicatorSampleLabel.MATCH && it.status == DetectionStatus.NO_MATCH }
            val unknownLabeled = compatible.count {
                it.label in setOf(IndicatorSampleLabel.MATCH, IndicatorSampleLabel.NO_MATCH) && it.status == DetectionStatus.UNKNOWN
            }
            val binaryLabeled = tp + fp + tn + fn + unknownLabeled
            return IndicatorEvaluationCounts(
                total = rows.size,
                reviewed = rows.count { it.label != null },
                unlabeled = rows.count { it.label == null },
                boundary = compatible.count { it.label == IndicatorSampleLabel.BOUNDARY },
                incompatibleLabels = rows.count { it.label != null && !it.labelCompatible },
                tp = tp, fp = fp, tn = tn, fn = fn,
                unknown = rows.count { it.status == DetectionStatus.UNKNOWN },
                unknownLabeled = unknownLabeled,
                binaryLabeled = binaryLabeled,
                accuracy = if (binaryLabeled == 0) null else (tp + tn).toDouble() / binaryLabeled,
            )
        }
    }
}
