package com.stockshot.manager.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.stockshot.manager.data.entity.Mark
import kotlinx.coroutines.flow.Flow

@Dao
interface MarkDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(mark: Mark)

    @Query("SELECT is_marked FROM marks WHERE stock_code = :code AND date = :date LIMIT 1")
    suspend fun isMarked(code: String, date: String): Boolean?

    @Query("SELECT stock_code FROM marks WHERE date = :date AND is_marked = 1")
    suspend fun getMarkedCodes(date: String): List<String>

    @Query("SELECT stock_code FROM marks WHERE date = :date AND is_marked = 1")
    fun observeMarkedCodes(date: String): Flow<List<String>>

    @Query("DELETE FROM marks WHERE stock_code = :code AND date = :date")
    suspend fun delete(code: String, date: String)

    @Query("SELECT * FROM marks")
    suspend fun getAll(): List<Mark>

    @Query("DELETE FROM marks")
    suspend fun deleteAll()
}
