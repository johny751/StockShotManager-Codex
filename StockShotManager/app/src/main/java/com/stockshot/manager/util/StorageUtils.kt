package com.stockshot.manager.util

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import com.stockshot.manager.BuildConfig
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * 截图文件存储位置管理。
 *
 * 根目录可由用户在设置中指定；默认落在公共下载目录：
 *   {Downloads}/StockScreenshots/{股票代码}/{日期}_{时分秒}_{唯一编号}.webp
 *
 * 用户自定义根目录后，直接以该目录为根（不再额外套一层 StockScreenshots）：
 *   {自定义根}/{股票代码}/{日期}_{时分秒}_{唯一编号}.webp
 *
 * 说明：根目录以绝对路径形式持久化在 settings 里（key=storage_root），
 * 因此这里直接从 SharedPreferences 缓存同步读取，避免与 SettingsStore 形成循环依赖。
 */
object StorageUtils {

    // Per-flavor default dir name so the four installs (A/B/C/D) never collide by default.
    private val ROOT_DIR: String = BuildConfig.DEFAULT_ROOT_DIR_NAME
    private const val PREFS = "settings_cache"
    private const val KEY_STORAGE_ROOT = "storage_root"

    /** 公共下载目录下的默认截图根目录（绝对路径）。 */
    fun defaultRootPath(): String {
        val downloads = Environment.getExternalStoragePublicDirectory(
            Environment.DIRECTORY_DOWNLOADS
        )
        return File(downloads, ROOT_DIR).absolutePath
    }

    /** 读取用户配置的根目录路径；未配置时返回默认（下载目录）。 */
    fun configuredRootPath(context: Context): String {
        val prefs = context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val v = prefs.getString(KEY_STORAGE_ROOT, null)
        return if (v.isNullOrBlank()) defaultRootPath() else v
    }

    /**
     * 解析出真正可用的根目录 File 并确保存在。
     * 若配置目录无法创建（如无权限），退化到应用私有外部目录，最后兜底内部目录，
     * 保证截图流程永远有地方落盘、不至于整体失败。
     */
    fun rootDir(context: Context): File {
        val configured = File(configuredRootPath(context))
        if (ensureDir(configured)) return configured

        val ext = context.getExternalFilesDir(null)
        if (ext != null) {
            val fallback = File(ext, ROOT_DIR)
            if (ensureDir(fallback)) return fallback
        }
        val internal = File(context.filesDir, ROOT_DIR)
        ensureDir(internal)
        return internal
    }

    fun stockDir(context: Context, code: String): File {
        val dir = File(rootDir(context), code)
        ensureDir(dir)
        return dir
    }

    /** 生成一张新截图的目标文件（尚未创建文件本身，仅确保目录存在）。 */
    fun newImageFile(context: Context, code: String, date: String): File {
        val ts = SimpleDateFormat("HHmmss", Locale.getDefault()).format(Date())
        // Historical pages can be collected on different real dates at the same clock time.
        return File(stockDir(context, code), "${date}_${ts}_${UUID.randomUUID()}.webp")
    }

    fun today(): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())

    private fun ensureDir(dir: File): Boolean =
        (dir.exists() && dir.isDirectory) || dir.mkdirs()

    /**
     * 将 SAF 文件夹选择器返回的 tree Uri 转成主存储上的绝对路径。
     * 仅支持主存储（primary），形如 content://.../tree/primary:Download/Foo。
     * 无法解析（如 SD 卡、特殊提供方）时返回 null，由调用方提示用户。
     */
    fun treeUriToPath(uri: Uri): String? {
        return try {
            val docId = DocumentsContract.getTreeDocumentId(uri)
            val parts = docId.split(":", limit = 2)
            if (parts.size != 2) return null
            val type = parts[0]
            val rel = parts[1]
            if (!type.equals("primary", ignoreCase = true)) return null
            val base = Environment.getExternalStorageDirectory().absolutePath
            if (rel.isEmpty()) base else "$base/$rel"
        } catch (e: Exception) {
            AppLogger.e("StorageUtils", "解析目录 Uri 失败: $uri", e)
            null
        }
    }
}
