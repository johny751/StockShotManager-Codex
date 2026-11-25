package com.stockshot.manager.util

import com.google.gson.Gson
import com.stockshot.manager.data.entity.StockRecord
import java.io.File
import org.junit.Assert.*
import org.junit.Test

class RemarksSerializationTest {
    private val root = File("test-images").absoluteFile

    private fun record(remarks: String) = StockRecord(
        stockCode = "000001",
        stockName = "名称,含\"引号\"",
        buyPrice = 12.0,
        sellPrice = 15.0,
        profitRate = 25.0,
        date = "2026-09-01",
        imagePath = File(root, "000001/2026-09-01_120000.webp").absolutePath,
        createdAt = 123L,
        remarks = remarks,
    )

    @Test fun csvRoundTripPreservesUnicodeQuotesCommasAndAllNewlines() {
        val remarks = "  第一行,\"价格\"\r\n第二行\n第三行\r末行  "
        val records = listOf(record(remarks), record("").copy(stockCode = "600000"))
        val restored = ExportUtils.parseCsv(ExportUtils.buildCsv(records))
        assertEquals(2, restored.size)
        assertEquals(remarks, restored[0].remarks)
        assertEquals(records[0].stockName, restored[0].stockName)
        assertEquals("000001", restored[0].stockCode)
        assertEquals("", restored[1].remarks)
        assertEquals(25.0, restored[0].profitRate, 0.0)
    }

    @Test fun oldSixColumnCsvWithBomAndCrlfHasEmptyRemarks() {
        val csv = "\uFEFFstock_code,date,stock_name,buy_price,sell_price,profit_rate\r\n" +
            "000001,2026-09-01,平安银行,12,15,25\r\n"
        val restored = ExportUtils.parseCsv(csv).single()
        assertEquals("000001", restored.stockCode)
        assertEquals("", restored.remarks)
        assertEquals(12.0, restored.buyPrice, 0.0)
    }

    @Test fun csvLastQuotedEmptyCellDoesNotNeedTrailingNewline() {
        val csv = "stock_code,date,stock_name,buy_price,sell_price,profit_rate,remarks\n" +
            "000001,2026-09-01,,12,15,25,\"\""
        assertEquals("", ExportUtils.parseCsv(csv).single().remarks)
    }

    @Test(expected = IllegalArgumentException::class)
    fun malformedCsvDoesNotSilentlyDropTheEndOfRemarks() {
        ExportUtils.parseCsv("header\n000001,2026-09-01,,12,15,25,\"unfinished\ntext")
    }

    @Test fun backupRoundTripRetainsRemarksAndRelocatesImage() {
        val original = record("观察成交量\n突破\"阻力\",再确认")
        val payload = BackupUtils.BackupPayload(records = listOf(BackupUtils.recordToBackup(root, original)))
        val restoredPayload = Gson().fromJson(Gson().toJson(payload), BackupUtils.BackupPayload::class.java)
        val newRoot = File("other-install-images").absoluteFile
        val restored = BackupUtils.recordFromBackup(newRoot, restoredPayload.records.single())
        assertEquals(original.remarks, restored.remarks)
        assertEquals(original.buyPrice, restored.buyPrice, 0.0)
        assertEquals(original.createdAt, restored.createdAt)
        assertEquals(File(newRoot, "000001/2026-09-01_120000.webp").absolutePath, restored.imagePath)
    }

    @Test fun oldBackupWithoutRemarksRestoresEmptyString() {
        val json = """{"stockCode":"000001","stockName":"平安银行","buyPrice":12,"sellPrice":15,"profitRate":25,"date":"2026-09-01","imageRelPath":"000001/image.webp","createdAt":123}"""
        val old = Gson().fromJson(json, BackupUtils.RecordItem::class.java)
        assertNull(old.remarks)
        assertEquals("", BackupUtils.recordFromBackup(root, old).remarks)
    }
}
