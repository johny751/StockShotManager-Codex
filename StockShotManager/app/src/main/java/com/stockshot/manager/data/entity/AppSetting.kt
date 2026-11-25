package com.stockshot.manager.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/** 键值对配置表 */
@Entity(tableName = "app_settings")
data class AppSetting(
    @PrimaryKey val key: String,
    @ColumnInfo(name = "value") val value: String
)
