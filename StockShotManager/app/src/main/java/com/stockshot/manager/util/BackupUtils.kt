package com.stockshot.manager.util

import android.content.Context
import android.net.Uri
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.stockshot.manager.data.SettingsStore
import com.stockshot.manager.data.StockRepository
import com.stockshot.manager.data.entity.Mark
import com.stockshot.manager.data.entity.StockRecord
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

/**
 * Data backup / restore.
 *
 * Scope by design: we do NOT pack image files into the backup. We only persist
 * the database association info (settings + stock records + marks). Each record
 * keeps a path RELATIVE to the storage root, so restore can rebuild an absolute
 * path under the (possibly different) root of the target install. After restore
 * the caller must tell the user to manually copy the old images back into that
 * root, preserving the {code}/{file} layout.
 */
object BackupUtils {

    private const val FORMAT_VERSION = 1

    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()

    data class SettingItem(val key: String, val value: String)

    data class RecordItem(
        val stockCode: String,
        val stockName: String?,
        val buyPrice: Double,
        val sellPrice: Double,
        val profitRate: Double,
        val date: String,
        // path relative to storage root, e.g. "600000/2026-07-25_093015.webp"
        val imageRelPath: String,
        val createdAt: Long,
        // Nullable because older Gson backups do not contain this field.
        val remarks: String? = null,
    )

    data class MarkItem(
        val stockCode: String,
        val date: String,
        val isMarked: Boolean,
    )

    data class BackupPayload(
        val formatVersion: Int = FORMAT_VERSION,
        val exportedAt: Long = System.currentTimeMillis(),
        val settings: List<SettingItem> = emptyList(),
        val records: List<RecordItem> = emptyList(),
        val marks: List<MarkItem> = emptyList(),
    )

    data class BackupResult(
        val ok: Boolean,
        val recordCount: Int = 0,
        val markCount: Int = 0,
        val settingCount: Int = 0,
        val rootPath: String = "",
        // Relative paths of records whose image file is missing on disk after restore.
        val missingImages: List<String> = emptyList(),
        val error: String? = null,
    )

    private val SETTING_KEYS = listOf(
        SettingsStore.KEY_DATA_SOURCE_TYPE,
        SettingsStore.KEY_API_BASE_URL,
        SettingsStore.KEY_API_KEY,
        SettingsStore.KEY_TARGET_APP_PACKAGE,
        SettingsStore.KEY_CODE_VIEW_ID,
        SettingsStore.KEY_NEXT_BUTTON_ID,
        SettingsStore.KEY_SCREENSHOT_DELAY_MS,
        SettingsStore.KEY_OCR_ENABLED,
        SettingsStore.KEY_VIEWER_PAGE_DIRECTION,
        SettingsStore.KEY_STORAGE_ROOT,
        SettingsStore.KEY_QUOTE_SOURCE_TYPE,
        SettingsStore.KEY_LAN_API_IP,
        SettingsStore.KEY_AUTO_DETECT_ENABLED,
        SettingsStore.KEY_AUTO_MARK_ENABLED,
        SettingsStore.KEY_AUTO_BUYPRICE_ENABLED,
        SettingsStore.KEY_NODE_REGION_LEFT,
        SettingsStore.KEY_NODE_REGION_TOP,
        SettingsStore.KEY_NODE_REGION_RIGHT,
        SettingsStore.KEY_NODE_REGION_BOTTOM,
        SettingsStore.KEY_NODE_CLASS,
        SettingsStore.KEY_NODE_WIDTH,
        SettingsStore.KEY_NODE_HEIGHT,
        SettingsStore.KEY_NODE_SIZE_TOL,
        SettingsStore.KEY_NODE_INDEX,
        SettingsStore.KEY_NODE_READ_DELAY_MS,
        SettingsStore.KEY_NODE_LONGPRESS_MS,
        SettingsStore.KEY_POST_READ_DELAY_MS,
        SettingsStore.KEY_CLOSE_CROSSHAIR_MODE,
        SettingsStore.KEY_CAPITAL_REQUIRE_YELLOW,
    )

    /** Compute a record's path relative to the current storage root. */
    private fun toRelPath(root: File, absPath: String): String {
        return try {
            val f = File(absPath)
            val rel = f.relativeToOrNull(root)
            if (rel != null) rel.path.replace(File.separatorChar, '/')
            else {
                // Fall back to {parentName}/{fileName} so the layout is still usable.
                val parent = f.parentFile?.name ?: ""
                if (parent.isBlank()) f.name else "$parent/${f.name}"
            }
        } catch (e: Exception) {
            File(absPath).name
        }
    }

    /** Rebuild an absolute path under the given root from a stored relative path. */
    private fun toAbsPath(root: File, rel: String): String {
        val clean = rel.replace('\\', '/').trimStart('/')
        return File(root, clean).absolutePath
    }

    internal fun recordToBackup(root: File, record: StockRecord): RecordItem = RecordItem(
        stockCode = record.stockCode,
        stockName = record.stockName,
        buyPrice = record.buyPrice,
        sellPrice = record.sellPrice,
        profitRate = record.profitRate,
        date = record.date,
        imageRelPath = toRelPath(root, record.imagePath),
        createdAt = record.createdAt,
        remarks = record.remarks,
    )

    internal fun recordFromBackup(root: File, record: RecordItem): StockRecord = StockRecord(
        stockCode = record.stockCode,
        stockName = record.stockName,
        buyPrice = record.buyPrice,
        sellPrice = record.sellPrice,
        profitRate = record.profitRate,
        date = record.date,
        imagePath = toAbsPath(root, record.imageRelPath),
        createdAt = record.createdAt,
        remarks = record.remarks.orEmpty(),
    )

    suspend fun backup(
        context: Context,
        uri: Uri,
        repository: StockRepository,
        settingsStore: SettingsStore,
    ): BackupResult {
        return try {
            val root = StorageUtils.rootDir(context)

            val settings = mutableListOf<SettingItem>()
            for (k in SETTING_KEYS) {
                val v = settingsStore.get(k, "")
                if (v.isNotBlank()) settings.add(SettingItem(k, v))
            }

            val records = repository.getAll().map { recordToBackup(root, it) }

            val marks = repository.getAllMarks().map { m ->
                MarkItem(stockCode = m.stockCode, date = m.date, isMarked = m.isMarked)
            }

            val payload = BackupPayload(
                settings = settings,
                records = records,
                marks = marks,
            )

            context.contentResolver.openOutputStream(uri)?.use { out ->
                out.write(gson.toJson(payload).toByteArray(Charsets.UTF_8))
            } ?: return BackupResult(ok = false, error = "openOutputStream returned null")

            BackupResult(
                ok = true,
                recordCount = records.size,
                markCount = marks.size,
                settingCount = settings.size,
                rootPath = root.absolutePath,
            )
        } catch (e: Exception) {
            AppLogger.e("Backup", "backup failed", e)
            BackupResult(ok = false, error = e.message ?: e.toString())
        }
    }

    suspend fun restore(
        context: Context,
        uri: Uri,
        repository: StockRepository,
        settingsStore: SettingsStore,
    ): BackupResult {
        return try {
            val text = context.contentResolver.openInputStream(uri)?.use { input ->
                BufferedReader(InputStreamReader(input, Charsets.UTF_8)).readText()
            } ?: return BackupResult(ok = false, error = "openInputStream returned null")

            val payload = gson.fromJson(text, BackupPayload::class.java)
                ?: return BackupResult(ok = false, error = "empty or invalid backup file")

            // 1) Write settings first (storage_root included) so root resolves correctly.
            val settings = payload.settings ?: emptyList()
            for (s in settings) {
                settingsStore.put(s.key, s.value)
            }

            // 2) Resolve the (possibly new) root after settings are applied.
            val root = StorageUtils.rootDir(context)

            // 3) Convert every row before replacing records and marks in one transaction.
            val records = payload.records ?: emptyList()
            val marks = payload.marks ?: emptyList()
            repository.replaceRecordsAndMarks(
                records.map { recordFromBackup(root, it) },
                marks.map { Mark(stockCode = it.stockCode, date = it.date, isMarked = it.isMarked) },
            )

            // 4) Scan for image files referenced by records but missing on disk,
            //    so the caller can tell the user exactly which images to put back.
            val missing = records.mapNotNull { r ->
                val abs = toAbsPath(root, r.imageRelPath)
                if (!File(abs).exists()) r.imageRelPath else null
            }.distinct()

            BackupResult(
                ok = true,
                recordCount = records.size,
                markCount = marks.size,
                settingCount = settings.size,
                rootPath = root.absolutePath,
                missingImages = missing,
            )
        } catch (e: Exception) {
            AppLogger.e("Backup", "restore failed", e)
            BackupResult(ok = false, error = e.message ?: e.toString())
        }
    }
}
