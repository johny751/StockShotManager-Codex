package com.stockshot.manager.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.stockshot.manager.data.dao.AppSettingDao
import com.stockshot.manager.data.dao.MarkDao
import com.stockshot.manager.data.dao.StockRecordDao
import com.stockshot.manager.data.entity.AppSetting
import com.stockshot.manager.data.entity.Mark
import com.stockshot.manager.data.entity.StockRecord

@Database(
    entities = [StockRecord::class, Mark::class, AppSetting::class],
    version = 2,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun stockRecordDao(): StockRecordDao
    abstract fun markDao(): MarkDao
    abstract fun appSettingDao(): AppSettingDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE stock_records ADD COLUMN remarks TEXT NOT NULL DEFAULT ''")
            }
        }

        @Volatile private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "stock_shot.db"
                ).addMigrations(MIGRATION_1_2).build().also { INSTANCE = it }
            }
    }
}
