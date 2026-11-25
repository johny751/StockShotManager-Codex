package com.stockshot.manager

import android.app.Application
import com.stockshot.manager.data.SettingsStore
import com.stockshot.manager.data.StockRepository
import com.stockshot.manager.util.AppLogger

/**
 * 应用入口。持有全局单例：Repository 与 SettingsStore。
 */
class StockShotApp : Application() {

    val repository: StockRepository by lazy { StockRepository(this) }
    val settingsStore: SettingsStore by lazy { SettingsStore(this) }

    override fun onCreate() {
        super.onCreate()
        instance = this
        AppLogger.initialize(this)
    }

    companion object {
        @Volatile
        lateinit var instance: StockShotApp
            private set
    }
}
