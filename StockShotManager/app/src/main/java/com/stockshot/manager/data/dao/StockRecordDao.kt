package com.stockshot.manager.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.stockshot.manager.data.entity.StockRecord
import kotlinx.coroutines.flow.Flow

@Dao
interface StockRecordDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(record: StockRecord): Long

    @Update
    suspend fun update(record: StockRecord)

    @Query("DELETE FROM stock_records WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM stock_records WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>)

    @Query("DELETE FROM stock_records")
    suspend fun deleteAll()

    @Query("SELECT * FROM stock_records WHERE id = :id")
    suspend fun getById(id: Long): StockRecord?

    @Query("SELECT * FROM stock_records WHERE stock_code = :code AND date = :date ORDER BY created_at DESC, id DESC")
    suspend fun getByCodeAndDate(code: String, date: String): List<StockRecord>

    @Query("SELECT * FROM stock_records WHERE stock_code = :code AND date = :date ORDER BY created_at DESC, id DESC LIMIT 1")
    suspend fun getLatestForStock(code: String, date: String): StockRecord?

    /** 某日每只股票取最新一条（按 created_at 倒序）作为列表展示 */
    @Query(
        """
        SELECT * FROM stock_records t
        WHERE t.date = :date
          AND t.id = (
            SELECT s.id FROM stock_records s
            WHERE s.stock_code = t.stock_code AND s.date = :date
            ORDER BY s.created_at DESC, s.id DESC LIMIT 1
          )
        ORDER BY t.stock_code ASC
        """
    )
    fun observeLatestByDate(date: String): Flow<List<StockRecord>>

    /** 某日每只股票取最新一条（suspend 版本，供 ViewModel 主动刷新） */
    @Query(
        """
        SELECT * FROM stock_records t
        WHERE t.date = :date
          AND t.id = (
            SELECT s.id FROM stock_records s
            WHERE s.stock_code = t.stock_code AND s.date = :date
            ORDER BY s.created_at DESC, s.id DESC LIMIT 1
          )
        ORDER BY t.stock_code ASC
        """
    )
    suspend fun getLatestByDate(date: String): List<StockRecord>

    /** 某日某股票的全部截图记录（按代码升序 + 时间升序），用于全屏查看器同日切换 */
    @Query("SELECT * FROM stock_records WHERE date = :date ORDER BY stock_code ASC, created_at ASC")
    suspend fun getAllByDate(date: String): List<StockRecord>

    /** 某只股票的历史记录，按日期倒序（搜索结果用） */
    @Query("SELECT * FROM stock_records WHERE stock_code = :code ORDER BY date DESC, created_at DESC")
    suspend fun getByStockCode(code: String): List<StockRecord>

    /** 所有有截图数据的日期（去重，倒序） */
    @Query("SELECT DISTINCT date FROM stock_records ORDER BY date DESC")
    fun observeAvailableDates(): Flow<List<String>>

    @Query("SELECT DISTINCT date FROM stock_records ORDER BY date DESC")
    suspend fun getAvailableDates(): List<String>

    /** 模糊搜索：代码或名称，返回每(代码,日期)最新一条，按日期倒序 */
    @Query(
        """
        SELECT * FROM stock_records t
        WHERE (t.stock_code LIKE '%' || :q || '%' OR IFNULL(t.stock_name,'') LIKE '%' || :q || '%')
          AND t.id = (
            SELECT s.id FROM stock_records s
            WHERE s.stock_code = t.stock_code AND s.date = t.date
            ORDER BY s.created_at DESC, s.id DESC LIMIT 1
          )
        ORDER BY t.date DESC, t.stock_code ASC
        """
    )
    suspend fun search(q: String): List<StockRecord>

    @Query("SELECT * FROM stock_records ORDER BY date DESC, stock_code ASC")
    suspend fun getAll(): List<StockRecord>

    /** 更新某只股票在某日期的买入价 */
    @Query("UPDATE stock_records SET buy_price = :price WHERE stock_code = :code AND date = :date")
    suspend fun updateBuyPrice(code: String, date: String, price: Double)

    @Query("UPDATE stock_records SET sell_price = :price WHERE stock_code = :code AND date = :date")
    suspend fun updateSellPrice(code: String, date: String, price: Double)

    @Query("UPDATE stock_records SET profit_rate = :rate WHERE id = :id")
    suspend fun updateProfitRate(id: Long, rate: Double)

    @Query("UPDATE stock_records SET remarks = :text WHERE stock_code = :code AND date = :date")
    suspend fun updateRemarks(code: String, date: String, text: String)

    @Query("UPDATE stock_records SET stock_name = :name WHERE stock_code = :code")
    suspend fun updateName(code: String, name: String)
}
