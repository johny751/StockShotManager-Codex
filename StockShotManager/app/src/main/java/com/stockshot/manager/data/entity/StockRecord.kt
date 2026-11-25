package com.stockshot.manager.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 一条股票截图记录。同一股票同一天可能有多张截图，
 * 因此 (stock_code, date) 不作为唯一约束；列表展示时按 created_at 取最新一条。
 */
@Entity(tableName = "stock_records")
data class StockRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "stock_code") val stockCode: String,
    @ColumnInfo(name = "stock_name") val stockName: String? = null,
    @ColumnInfo(name = "buy_price") val buyPrice: Double = 0.0,
    @ColumnInfo(name = "sell_price") val sellPrice: Double = 0.0,
    @ColumnInfo(name = "profit_rate") val profitRate: Double = 0.0,
    @ColumnInfo(name = "date") val date: String,           // yyyy-MM-dd
    @ColumnInfo(name = "image_path") val imagePath: String,
    @ColumnInfo(name = "created_at") val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "remarks", defaultValue = "''") val remarks: String = "",
)
