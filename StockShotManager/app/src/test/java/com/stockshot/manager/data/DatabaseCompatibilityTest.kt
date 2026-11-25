package com.stockshot.manager.data

import android.app.Application
import android.content.Context
import androidx.room.Room
import com.stockshot.manager.data.entity.AppSetting
import com.stockshot.manager.data.entity.Mark
import com.stockshot.manager.data.entity.StockRecord
import com.stockshot.manager.util.StorageUtils
import java.io.File
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class DatabaseCompatibilityTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val openedDatabases = mutableListOf<AppDatabase>()
    private val databaseNames = mutableListOf<String>()

    @After
    fun closeTestDatabases() {
        openedDatabases.forEach { it.close() }
        databaseNames.forEach { context.deleteDatabase(it) }
    }

    @Test
    fun versionOneUpgradePreservesRecordsMarksAndSettingsAndReopens() = runBlocking {
        val name = "stockshot-migration-${UUID.randomUUID()}.db"
        databaseNames += name
        val oldRecords = listOf(
            record(createdAt = 100, buyPrice = 10.5, sellPrice = 12.25, profitRate = 16.67)
                .copy(id = 41, stockName = "浦发银行", imagePath = "/legacy/600000/first.webp"),
            record(stockCode = "000001", date = OTHER_DATE, createdAt = 200,
                buyPrice = 0.0, sellPrice = 0.0, profitRate = 0.0)
                .copy(id = 42, stockName = null, imagePath = "/legacy/000001/second.webp"),
        )
        val oldMarks = listOf(
            Mark(id = 7, stockCode = "600000", date = DATE, isMarked = true),
            Mark(id = 8, stockCode = "000001", date = OTHER_DATE, isMarked = false),
        )
        val oldSettings = listOf(
            AppSetting("storage_root", "/legacy/custom screenshots"),
            AppSetting("screenshot_delay_ms", "750"),
        )
        createVersionOneDatabase(name, oldRecords, oldMarks, oldSettings)

        val migrated = openFileDatabase(name)
        assertEquals(2, migrated.openHelper.writableDatabase.version)
        assertContents(migrated, oldRecords, oldMarks, oldSettings)
        migrated.openHelper.writableDatabase.query("PRAGMA table_info(stock_records)").use { cursor ->
            var foundRemarks = false
            while (cursor.moveToNext()) {
                if (cursor.getString(cursor.getColumnIndexOrThrow("name")) == "remarks") {
                    foundRemarks = true
                    assertEquals("TEXT", cursor.getString(cursor.getColumnIndexOrThrow("type")))
                    assertEquals(1, cursor.getInt(cursor.getColumnIndexOrThrow("notnull")))
                    assertEquals("''", cursor.getString(cursor.getColumnIndexOrThrow("dflt_value")))
                }
            }
            assertTrue("Migration must add the non-null remarks column", foundRemarks)
        }
        val migratedIdentity = identityHash(migrated)
        assertNotEquals(VERSION_ONE_IDENTITY, migratedIdentity)
        migrated.stockRecordDao().updateRemarks("600000", DATE, "迁移后备注\n第二行")
        migrated.close()
        openedDatabases.remove(migrated)

        // A second Room open checks the new identity hash and persisted v2 data.
        val reopened = openFileDatabase(name)
        assertEquals(2, reopened.openHelper.writableDatabase.version)
        assertEquals(migratedIdentity, identityHash(reopened))
        assertContents(
            reopened,
            oldRecords.map { if (it.id == 41L) it.copy(remarks = "迁移后备注\n第二行") else it },
            oldMarks,
            oldSettings,
        )
    }

    @Test
    fun repeatedCaptureOfHistoricalDateNeverReusesAnImagePath() {
        val root = File(context.cacheDir, "capture-${UUID.randomUUID()}")
        val prefs = context.getSharedPreferences("settings_cache", Context.MODE_PRIVATE)
        prefs.edit().putString("storage_root", root.absolutePath).commit()
        var originalImage: File? = null
        try {
            val first = StorageUtils.newImageFile(context, "600000", DATE)
            originalImage = first
            first.writeText("original screenshot")
            val more = (1..30).map { StorageUtils.newImageFile(context, "600000", DATE) }
            assertEquals(more.size, more.map { it.absolutePath }.distinct().size)
            assertTrue(more.all { it != first && !it.exists() })
            assertTrue(more.all { it.name.startsWith("${DATE}_") && it.extension == "webp" })
            assertEquals("original screenshot", first.readText())
        } finally {
            prefs.edit().remove("storage_root").commit()
            originalImage?.delete()
            File(root, "600000").delete()
            root.delete()
        }
    }

    @Test
    fun remarksUpdateAndClearAllCapturesOnlyForTheChosenStockAndDate() = runBlocking {
        val repository = StockRepository(openMemoryDatabase())
        val first = seed(repository, record(createdAt = 10, remarks = "旧备注一"))
        val second = seed(repository, record(createdAt = 20, remarks = "旧备注二"))
        val otherDate = seed(repository, record(date = OTHER_DATE, remarks = "保留其他日期"))
        val otherStock = seed(repository, record(stockCode = "000001", remarks = "保留其他股票"))
        val text = "观察成交量，保留空格  \n第二行：等待确认"

        repository.updateRemarks("600000", DATE, text)

        assertEquals(first.copy(remarks = text), repository.getById(first.id))
        assertEquals(second.copy(remarks = text), repository.getById(second.id))
        assertEquals(otherDate, repository.getById(otherDate.id))
        assertEquals(otherStock, repository.getById(otherStock.id))

        repository.updateRemarks("600000", DATE, "")
        assertEquals(first.copy(remarks = ""), repository.getById(first.id))
        assertEquals(second.copy(remarks = ""), repository.getById(second.id))
        assertEquals(otherDate, repository.getById(otherDate.id))
        assertEquals(otherStock, repository.getById(otherStock.id))
    }

    @Test
    fun recaptureInheritsLatestSameDateRemarksIncludingAnEmptyRemark() = runBlocking {
        val repository = StockRepository(openMemoryDatabase())
        seed(repository, record(createdAt = 100, remarks = "较早的同时间截图"))
        seed(repository, record(createdAt = 100, remarks = "最近截图的备注"))
        val otherDate = seed(repository, record(date = OTHER_DATE, createdAt = 900, remarks = "其他日期"))

        val recaptureId = repository.insert(record(createdAt = 200, remarks = "不覆盖已有备注"))
        assertEquals("最近截图的备注", repository.getById(recaptureId)?.remarks)
        assertEquals(otherDate, repository.getById(otherDate.id))

        repository.updateRemarks("600000", DATE, "")
        val afterClearId = repository.insert(record(createdAt = 300, remarks = "不恢复已清空备注"))
        assertEquals("", repository.getById(afterClearId)?.remarks)
        val newDateId = repository.insert(record(date = "2026-09-03", remarks = "首次采集备注"))
        assertEquals("首次采集备注", repository.getById(newDateId)?.remarks)
    }

    @Test
    fun listsAndSearchChooseTheLastInsertedCaptureWhenTimestampsMatch() = runBlocking {
        val repository = StockRepository(openMemoryDatabase())
        seed(repository, record(createdAt = 100, remarks = "较早截图"))
        val latest = seed(repository, record(createdAt = 100, remarks = "最新截图"))
        seed(repository, record(createdAt = 90, remarks = "较晚导入的旧截图"))
        val otherDate = seed(repository, record(date = OTHER_DATE, createdAt = 100, remarks = "其他日期"))

        assertEquals(listOf(latest), repository.getLatestByDate(DATE))
        assertEquals(listOf(latest), repository.observeLatestByDate(DATE).first())
        assertEquals(listOf(otherDate, latest), repository.search("600000"))
    }

    @Test
    fun importKeepsItsExactRemarksEvenWhenAnExistingCaptureHasRemarks() = runBlocking {
        val repository = StockRepository(openMemoryDatabase())
        val original = seed(repository, record(createdAt = 100, remarks = "设备已有备注"))
        val imported = record(createdAt = 200, remarks = "  备份备注，含逗号\n\"引号\"和尾部空格  ")
        val importedId = repository.importRecord(imported)
        val emptyImported = record(createdAt = 300, remarks = "")
        val emptyImportedId = repository.importRecord(emptyImported)

        assertEquals(imported.copy(id = importedId), repository.getById(importedId))
        assertEquals(emptyImported.copy(id = emptyImportedId), repository.getById(emptyImportedId))
        assertEquals(original, repository.getById(original.id))
    }

    @Test
    fun failedBackupReplacementRollsBackBothRecordsAndMarks() = runBlocking {
        val database = openMemoryDatabase()
        val repository = StockRepository(database)
        val original = seed(repository, record(remarks = "设备已有备注"))
        repository.setMarked("600000", DATE, true)
        val originalMarks = repository.getAllMarks()
        database.openHelper.writableDatabase.execSQL(
            """CREATE TRIGGER reject_bad_backup_mark BEFORE INSERT ON marks
                WHEN NEW.stock_code = '999999'
                BEGIN SELECT RAISE(ABORT, 'invalid backup mark'); END""",
        )

        val result = runCatching {
            repository.replaceRecordsAndMarks(
                listOf(record(stockCode = "000001", remarks = "已经写入的新记录")),
                listOf(Mark(stockCode = "999999", date = DATE, isMarked = true)),
            )
        }

        assertTrue("A failed mark must abort the complete backup replacement", result.isFailure)
        assertEquals(listOf(original), repository.getAll())
        assertEquals(originalMarks, repository.getAllMarks())
    }

    @Test
    fun backupReplacementKeepsExactRemarksAndReplacesMarks() = runBlocking {
        val repository = StockRepository(openMemoryDatabase())
        seed(repository, record(remarks = "设备已有备注"))
        repository.setMarked("600000", DATE, true)
        val records = listOf(
            record(createdAt = 100, remarks = "  备份备注,\"价格\"\r\n第二行  "),
            record(createdAt = 200, remarks = ""),
        )
        val marks = listOf(Mark(stockCode = "000001", date = OTHER_DATE, isMarked = false))

        repository.replaceRecordsAndMarks(records, marks)

        assertEquals(records, repository.getAll().sortedBy { it.createdAt }.map { it.copy(id = 0) })
        assertEquals(marks, repository.getAllMarks().map { it.copy(id = 0) })
    }

    @Test
    fun lowerSellPriceReplacesOldValueAndRecomputesEachCapturesOwnProfit() = runBlocking {
        val repository = StockRepository(openMemoryDatabase())
        val lowBuy = seed(repository, record(createdAt = 10, buyPrice = 10.0, remarks = "保留备注"))
        val highBuy = seed(repository, record(createdAt = 20, buyPrice = 20.0, profitRate = 100.0))
        val missingBuy = seed(repository, record(createdAt = 30, buyPrice = 0.0, profitRate = 17.0))
        val otherDate = seed(repository, record(date = OTHER_DATE, sellPrice = 90.0, profitRate = 800.0))
        val otherStock = seed(repository, record(stockCode = "000001", sellPrice = 80.0, profitRate = 700.0))

        repository.updateSellPrice("600000", DATE, 15.0)
        assertEquals(lowBuy.copy(sellPrice = 15.0, profitRate = 50.0), repository.getById(lowBuy.id))
        assertEquals(highBuy.copy(sellPrice = 15.0, profitRate = -25.0), repository.getById(highBuy.id))
        assertEquals(missingBuy.copy(sellPrice = 15.0, profitRate = 0.0), repository.getById(missingBuy.id))

        repository.updateSellPrice("600000", DATE, 8.0)
        assertEquals(lowBuy.copy(sellPrice = 8.0, profitRate = -20.0), repository.getById(lowBuy.id))
        assertEquals(highBuy.copy(sellPrice = 8.0, profitRate = -60.0), repository.getById(highBuy.id))
        assertEquals(missingBuy.copy(sellPrice = 8.0, profitRate = 0.0), repository.getById(missingBuy.id))
        assertEquals(otherDate, repository.getById(otherDate.id))
        assertEquals(otherStock, repository.getById(otherStock.id))
    }

    private fun openMemoryDatabase(): AppDatabase =
        Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries().build().also { openedDatabases += it }

    private fun openFileDatabase(name: String): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, name)
            .addMigrations(AppDatabase.MIGRATION_1_2)
            .allowMainThreadQueries().build().also { openedDatabases += it }

    private suspend fun seed(repository: StockRepository, record: StockRecord): StockRecord =
        record.copy(id = repository.importRecord(record))

    private suspend fun assertContents(
        database: AppDatabase,
        records: List<StockRecord>,
        marks: List<Mark>,
        settings: List<AppSetting>,
    ) {
        assertEquals(records.sortedBy { it.id }, database.stockRecordDao().getAll().sortedBy { it.id })
        assertEquals(marks.sortedBy { it.id }, database.markDao().getAll().sortedBy { it.id })
        assertEquals(settings.sortedBy { it.key }, database.appSettingDao().getAll().sortedBy { it.key })
    }

    private fun identityHash(database: AppDatabase): String =
        database.openHelper.writableDatabase.query("SELECT identity_hash FROM room_master_table WHERE id = 42")
            .use { cursor ->
                assertTrue(cursor.moveToFirst())
                cursor.getString(0)
            }

    private fun createVersionOneDatabase(
        name: String,
        records: List<StockRecord>,
        marks: List<Mark>,
        settings: List<AppSetting>,
    ) {
        context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null).use { database ->
            // Frozen v1 schema from the original generated AppDatabase_Impl, before remarks existed.
            database.execSQL("CREATE TABLE IF NOT EXISTS `stock_records` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `stock_code` TEXT NOT NULL, `stock_name` TEXT, `buy_price` REAL NOT NULL, `sell_price` REAL NOT NULL, `profit_rate` REAL NOT NULL, `date` TEXT NOT NULL, `image_path` TEXT NOT NULL, `created_at` INTEGER NOT NULL)")
            database.execSQL("CREATE TABLE IF NOT EXISTS `marks` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `stock_code` TEXT NOT NULL, `date` TEXT NOT NULL, `is_marked` INTEGER NOT NULL)")
            database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_marks_stock_code_date` ON `marks` (`stock_code`, `date`)")
            database.execSQL("CREATE TABLE IF NOT EXISTS `app_settings` (`key` TEXT NOT NULL, `value` TEXT NOT NULL, PRIMARY KEY(`key`))")
            database.execSQL("CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)")
            database.execSQL("INSERT OR REPLACE INTO room_master_table (id,identity_hash) VALUES(42, ?)", arrayOf(VERSION_ONE_IDENTITY))
            records.forEach { record ->
                database.execSQL(
                    "INSERT INTO stock_records (id, stock_code, stock_name, buy_price, sell_price, profit_rate, date, image_path, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    arrayOf(record.id, record.stockCode, record.stockName, record.buyPrice, record.sellPrice,
                        record.profitRate, record.date, record.imagePath, record.createdAt),
                )
            }
            marks.forEach { mark ->
                database.execSQL("INSERT INTO marks (id, stock_code, date, is_marked) VALUES (?, ?, ?, ?)",
                    arrayOf(mark.id, mark.stockCode, mark.date, if (mark.isMarked) 1 else 0))
            }
            settings.forEach { setting ->
                database.execSQL("INSERT INTO app_settings (`key`, value) VALUES (?, ?)", arrayOf(setting.key, setting.value))
            }
            database.version = 1
        }
    }

    private fun record(
        stockCode: String = "600000",
        date: String = DATE,
        createdAt: Long = 1,
        remarks: String = "",
        buyPrice: Double = 10.0,
        sellPrice: Double = 40.0,
        profitRate: Double = 300.0,
    ) = StockRecord(
        stockCode = stockCode,
        stockName = "测试股票",
        date = date,
        imagePath = "/test/$stockCode/$date/$createdAt.webp",
        createdAt = createdAt,
        remarks = remarks,
        buyPrice = buyPrice,
        sellPrice = sellPrice,
        profitRate = profitRate,
    )

    private companion object {
        const val DATE = "2026-09-01"
        const val OTHER_DATE = "2026-09-02"
        const val VERSION_ONE_IDENTITY = "b86d2479187f49f7ed977c2d985d7262"
    }
}
