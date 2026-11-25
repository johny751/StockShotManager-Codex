package com.stockshot.manager.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 标记状态。一只股票在不同日期有独立的标记状态，互不影响。
 * 唯一索引 (stock_code, date)。
 */
@Entity(
    tableName = "marks",
    indices = [Index(value = ["stock_code", "date"], unique = true)]
)
data class Mark(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "stock_code") val stockCode: String,
    @ColumnInfo(name = "date") val date: String,
    @ColumnInfo(name = "is_marked") val isMarked: Boolean = false
)
