package com.stockshot.manager.data

import android.content.Context
import androidx.room.withTransaction
import com.stockshot.manager.data.entity.Mark
import com.stockshot.manager.data.entity.StockRecord
import kotlinx.coroutines.flow.Flow

/**
 * 缁熶竴鏁版嵁鍏ュ彛銆傚皝瑁?DAO锛屾墍鏈夎皟鐢ㄩ兘鍦?IO 鍗忕▼鎵ц锛圧oom suspend / Flow 宸茶嚜甯?IO 璋冨害锛夈€?
 */
class StockRepository internal constructor(private val db: AppDatabase) {

    constructor(context: Context) : this(AppDatabase.getDatabase(context))
    private val recordDao = db.stockRecordDao()
    private val markDao = db.markDao()

    // ---- 璁板綍 ----
    fun observeLatestByDate(date: String): Flow<List<StockRecord>> =
        recordDao.observeLatestByDate(date)

    suspend fun getAllByDate(date: String): List<StockRecord> = recordDao.getAllByDate(date)

    suspend fun getLatestByDate(date: String): List<StockRecord> = recordDao.getLatestByDate(date)

    suspend fun getByStockCode(code: String): List<StockRecord> = recordDao.getByStockCode(code)

    suspend fun getById(id: Long): StockRecord? = recordDao.getById(id)

    /** New captures inherit the date-specific remarks; backup imports retain their own values. */
    suspend fun insert(record: StockRecord): Long = db.withTransaction {
        val previous = recordDao.getLatestForStock(record.stockCode, record.date)
        recordDao.insert(record.copy(remarks = previous?.remarks ?: record.remarks))
    }

    suspend fun update(record: StockRecord) = recordDao.update(record)

    suspend fun deleteById(id: Long) = recordDao.deleteById(id)

    suspend fun deleteByIds(ids: List<Long>) = recordDao.deleteByIds(ids)

    suspend fun clearAllRecords() = recordDao.deleteAll()

    suspend fun search(q: String): List<StockRecord> = recordDao.search(q)

    // ---- 鏃ユ湡 ----
    fun observeAvailableDates(): Flow<List<String>> = recordDao.observeAvailableDates()
    suspend fun getAvailableDates(): List<String> = recordDao.getAvailableDates()

    // ---- 鏍囪 ----
    suspend fun isMarked(code: String, date: String): Boolean =
        markDao.isMarked(code, date) ?: false

    suspend fun setMarked(code: String, date: String, marked: Boolean) =
        markDao.upsert(Mark(stockCode = code, date = date, isMarked = marked))

    fun observeMarkedCodes(date: String): Flow<List<String>> = markDao.observeMarkedCodes(date)
    suspend fun getMarkedCodes(date: String): List<String> = markDao.getMarkedCodes(date)

    suspend fun getAllMarks(): List<Mark> = markDao.getAll()
    suspend fun clearAllMarks() = markDao.deleteAll()

    // ---- price update (recompute profit) ----
    /** Update buy price for (code, date) and recompute profit rate on all matching rows. */
    suspend fun updateBuyPrice(code: String, date: String, price: Double) = db.withTransaction {
        recordDao.updateBuyPrice(code, date, price)
        recomputeProfit(code, date)
    }

    suspend fun updateSellPrice(code: String, date: String, price: Double) = db.withTransaction {
        recordDao.updateSellPrice(code, date, price)
        recomputeProfit(code, date)
    }

    private suspend fun recomputeProfit(code: String, date: String) {
        val rows = recordDao.getByCodeAndDate(code, date)
        for (r in rows) {
            val rate = com.stockshot.manager.util.ProfitCalc.profitRate(r.buyPrice, r.sellPrice) ?: 0.0
            recordDao.updateProfitRate(r.id, rate)
        }
    }

    suspend fun updateName(code: String, name: String) = recordDao.updateName(code, name)

    suspend fun updateRemarks(code: String, date: String, text: String) =
        recordDao.updateRemarks(code, date, text)

    // ---- import ----
    suspend fun getAll(): List<StockRecord> = recordDao.getAll()

    suspend fun importRecord(record: StockRecord): Long = recordDao.insert(record)

    /** Keep the existing records and marks if any row in a backup fails to restore. */
    suspend fun replaceRecordsAndMarks(records: List<StockRecord>, marks: List<Mark>) = db.withTransaction {
        recordDao.deleteAll()
        markDao.deleteAll()
        records.forEach { recordDao.insert(it) }
        marks.forEach { markDao.upsert(it) }
    }
}
