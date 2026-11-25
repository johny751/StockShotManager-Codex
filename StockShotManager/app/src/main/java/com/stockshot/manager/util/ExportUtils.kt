package com.stockshot.manager.util

import android.content.Context
import android.net.Uri
import com.stockshot.manager.data.entity.StockRecord
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 数据导出/导入工具。
 * - CSV：股票代码、日期、名称、买入价、卖出价、获利比例、备注
 * - 图片 ZIP：按日期打包 StockScreenshots 下的图片
 * - 导入：解析之前导出的 CSV
 */
object ExportUtils {

    private const val CSV_HEADER = "stock_code,date,stock_name,buy_price,sell_price,profit_rate,remarks"

    data class ImportRow(
        val stockCode: String,
        val date: String,
        val stockName: String?,
        val buyPrice: Double,
        val sellPrice: Double,
        val profitRate: Double,
        val remarks: String = "",
    )

    /** 将记录写入 CSV，返回文本内容。 */
    fun buildCsv(records: List<StockRecord>): String {
        val sb = StringBuilder()
        sb.append(CSV_HEADER).append("\n")
        for (r in records) {
            sb.append(escape(r.stockCode)).append(",")
                .append(escape(r.date)).append(",")
                .append(escape(r.stockName ?: "")).append(",")
                .append(r.buyPrice).append(",")
                .append(r.sellPrice).append(",")
                .append(r.profitRate).append(",")
                .append(escape(r.remarks)).append("\n")
        }
        return sb.toString()
    }

    /** 写 CSV 到 Uri（SAF）。 */
    fun writeCsvToUri(context: Context, uri: Uri, records: List<StockRecord>): Boolean = try {
        context.contentResolver.openOutputStream(uri)?.use { out ->
            out.write(buildCsv(records).toByteArray(Charsets.UTF_8))
        }
        true
    } catch (e: Exception) {
        AppLogger.e("Export", "写 CSV 失败", e)
        false
    }

    /** 从 Uri 读取 CSV 并解析。 */
    fun readCsvFromUri(context: Context, uri: Uri): List<ImportRow> {
        return try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                val reader = BufferedReader(InputStreamReader(input, Charsets.UTF_8))
                parseCsv(reader.readText())
            } ?: emptyList()
        } catch (e: Exception) {
            AppLogger.e("Export", "读 CSV 失败", e)
            emptyList()
        }
    }

    /** Parse complete CSV records so quoted newlines stay inside remarks. */
    fun parseCsv(text: String): List<ImportRow> = parseCsvRecords(text.removePrefix("\uFEFF"))
        .drop(1)
        .filter { it.size >= 6 }
        .map { cols ->
            ImportRow(
                stockCode = cols[0],
                date = cols[1],
                stockName = cols[2].ifBlank { null },
                buyPrice = cols[3].toDoubleOrNull() ?: 0.0,
                sellPrice = cols[4].toDoubleOrNull() ?: 0.0,
                profitRate = cols[5].toDoubleOrNull() ?: 0.0,
                remarks = cols.getOrElse(6) { "" },
            )
        }

    /** 打包指定日期的图片为 ZIP，写入 Uri。传空日期表示全部。 */
    fun writeImagesZipToUri(context: Context, uri: Uri, date: String?): Int {
        var count = 0
        try {
            val root = StorageUtils.rootDir(context)
            context.contentResolver.openOutputStream(uri)?.use { out ->
                ZipOutputStream(out).use { zip ->
                    root.walkTopDown().filter { it.isFile && isImage(it) }
                        .forEach { file ->
                            if (date != null && !file.name.startsWith(date)) return@forEach
                            val rel = file.relativeTo(root).path.replace(File.separatorChar, '/')
                            zip.putNextEntry(ZipEntry(rel))
                            file.inputStream().use { it.copyTo(zip) }
                            zip.closeEntry()
                            count++
                        }
                }
            }
        } catch (e: Exception) {
            AppLogger.e("Export", "打包图片失败", e)
        }
        return count
    }

    private fun escape(v: String): String =
        if (v.contains(',') || v.contains('"') || v.contains('\n') || v.contains('\r')) {
            "\"" + v.replace("\"", "\"\"") + "\""
        } else v

    private fun isImage(f: File): Boolean {
        val ext = f.extension.lowercase()
        return ext == "webp" || ext == "png" || ext == "jpg" || ext == "jpeg"
    }

    private fun parseCsvRecords(text: String): List<List<String>> {
        val result = mutableListOf<List<String>>()
        val row = mutableListOf<String>()
        val sb = StringBuilder()
        var inQuotes = false
        var quoteClosed = false
        var i = 0
        fun finishField() {
            row.add(sb.toString())
            sb.clear()
            quoteClosed = false
        }
        while (i < text.length) {
            val c = text[i]
            if (inQuotes) {
                when {
                    c == '"' && i + 1 < text.length && text[i + 1] == '"' -> {
                        sb.append('"')
                        i++
                    }
                    c == '"' -> {
                        inQuotes = false
                        quoteClosed = true
                    }
                    else -> sb.append(c)
                }
            } else {
                when (c) {
                    '"' -> {
                        require(sb.isEmpty() && !quoteClosed) { "Invalid CSV quote" }
                        inQuotes = true
                    }
                    ',' -> finishField()
                    '\r', '\n' -> {
                        finishField()
                        result.add(row.toList())
                        row.clear()
                        if (c == '\r' && i + 1 < text.length && text[i + 1] == '\n') i++
                    }
                    else -> {
                        require(!quoteClosed) { "Unexpected character after CSV quote" }
                        sb.append(c)
                    }
                }
            }
            i++
        }
        require(!inQuotes) { "Unclosed CSV quote" }
        if (row.isNotEmpty() || sb.isNotEmpty() || quoteClosed) {
            finishField()
            result.add(row.toList())
        }
        return result
    }
}
