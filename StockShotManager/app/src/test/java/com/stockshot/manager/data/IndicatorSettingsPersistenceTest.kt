package com.stockshot.manager.data

import android.app.Application
import android.content.Context
import android.net.Uri
import android.util.Base64
import androidx.room.Room
import com.stockshot.manager.data.entity.AppSetting
import com.stockshot.manager.util.BackupUtils
import com.stockshot.manager.util.QrShareUtils
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.zip.GZIPOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class IndicatorSettingsPersistenceTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val databases = mutableListOf<AppDatabase>()
    private val names = mutableListOf<String>()
    private val files = mutableListOf<File>()

    @Before
    fun clearCachedSettings() {
        context.getSharedPreferences("settings_cache", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @After
    fun closeDatabases() {
        databases.forEach { it.close() }
        names.forEach { context.deleteDatabase(it) }
        files.forEach { it.delete() }
        clearCachedSettings()
    }

    @Test
    fun missingNewSettingsUseCompatibleDefaults() = runBlocking {
        val store = store(openDatabase())
        assertDefaults(store)
        assertEquals(0.4f, store.getFloat(SettingsStore.KEY_CAPITAL_WINDOW_SCALE, SettingsStore.DEFAULT_CAPITAL_WINDOW_SCALE), 0f)
        assertFalse(store.getBool(SettingsStore.KEY_CAPITAL_REQUIRE_YELLOW, SettingsStore.DEFAULT_CAPITAL_REQUIRE_YELLOW))
    }

    @Test
    fun settingsSurviveDatabaseCloseAndReloadWithoutPreferenceCache() = runBlocking {
        val name = newDatabaseName()
        val database = openDatabase(name)
        val original = store(database)
        saveCustomOptions(original)
        assertEquals("pointwise", original.getSync(SettingsStore.KEY_CAPITAL_METHOD, ""))
        database.close()
        databases.remove(database)
        clearCachedSettings()

        val reloaded = store(openDatabase(name))
        assertCustomOptions(reloaded)
    }

    @Test
    fun invalidLegacyValuesFallBackAndInvalidWritesAreNormalized() = runBlocking {
        val database = openDatabase()
        val store = store(database)
        val invalid = listOf(
            SettingsStore.KEY_CAPITAL_METHOD to "unknown-method",
            SettingsStore.KEY_CAPITAL_RULE to "unknown-rule",
            SettingsStore.KEY_SIGNAL_SELECTION to "unknown-selection",
        )
        for ((key, value) in invalid) database.appSettingDao().upsert(AppSetting(key, value))
        assertDefaults(store)

        for ((key, value) in invalid) store.put(key, value)
        assertEquals("median", store.get(SettingsStore.KEY_CAPITAL_METHOD, ""))
        assertEquals("all", store.get(SettingsStore.KEY_CAPITAL_RULE, ""))
        assertEquals("any", store.get(SettingsStore.KEY_SIGNAL_SELECTION, ""))
    }

    @Test
    fun majorityThresholdRejectsNonFiniteAndOutOfRangeValuesOnReadAndWrite() = runBlocking {
        val database = openDatabase()
        val store = store(database)
        for (invalid in listOf("NaN", "Infinity", "-Infinity", "", "bad", "0.5", "0", "-1", "1.01")) {
            database.appSettingDao().upsert(AppSetting(SettingsStore.KEY_CAPITAL_MAJORITY_THRESHOLD, invalid))
            assertEquals(invalid, 0.9f, store.getCapitalMajorityThreshold(), 0f)
            store.put(SettingsStore.KEY_CAPITAL_MAJORITY_THRESHOLD, invalid)
            assertEquals("0.9", store.get(SettingsStore.KEY_CAPITAL_MAJORITY_THRESHOLD, ""))
        }
        for (valid in listOf(0.5001f, 0.9f, 1f)) {
            store.put(SettingsStore.KEY_CAPITAL_MAJORITY_THRESHOLD, valid.toString())
            assertEquals(valid, store.getCapitalMajorityThreshold(), 0f)
        }
        for (nonFinite in listOf("NaN", "Infinity", "-Infinity")) {
            database.appSettingDao().upsert(AppSetting(SettingsStore.KEY_DOT_TOL_SCALE, nonFinite))
            assertEquals(1f, store.getFloat(SettingsStore.KEY_DOT_TOL_SCALE, 1f), 0f)
        }
    }

    @Test
    fun qrRoundTripPreservesAllDetectionOptionsAndKeepsLocalStorageRoot() = runBlocking {
        val source = store(openDatabase())
        saveCustomOptions(source)
        source.put(SettingsStore.KEY_STORAGE_ROOT, "/source-only")
        val text = QrShareUtils.buildShareText(source)
        val parsed = QrShareUtils.parseShareText(text)
        assertNotNull(parsed)
        assertFalse(parsed!!.any { it.key == SettingsStore.KEY_STORAGE_ROOT })
        clearCachedSettings()

        val target = store(openDatabase())
        target.put(SettingsStore.KEY_STORAGE_ROOT, "/target-only")
        QrShareUtils.applyShared(target, parsed)
        clearCachedSettings()
        assertCustomOptions(target)
        assertEquals("/target-only", target.get(SettingsStore.KEY_STORAGE_ROOT, ""))
    }

    @Test
    fun legacyQrImportsOldOptionsWithoutInventingNewValues() = runBlocking {
        val legacy = encodeLegacyQr("""{"formatVersion":1,"settings":[{"key":"auto_mark_enabled","value":"false"}]}""")
        val parsed = QrShareUtils.parseShareText(legacy)!!
        val target = store(openDatabase())
        assertEquals(1, QrShareUtils.applyShared(target, parsed))
        assertFalse(target.getBool(SettingsStore.KEY_AUTO_MARK_ENABLED, true))
        assertDefaults(target)

        target.put(SettingsStore.KEY_CAPITAL_METHOD, "pointwise")
        target.put(SettingsStore.KEY_CAPITAL_RULE, "signal")
        QrShareUtils.applyShared(target, parsed)
        assertEquals("pointwise", target.getCapitalMethod())
        assertEquals("signal", target.getCapitalRule())
    }

    @Test
    fun jsonBackupRoundTripIncludesDetectionOptions() = runBlocking {
        val sourceDb = openDatabase()
        val source = store(sourceDb)
        saveCustomOptions(source)
        source.put(SettingsStore.KEY_STORAGE_ROOT, context.cacheDir.absolutePath)
        val file = temporaryJson()
        val backedUp = BackupUtils.backup(context, Uri.fromFile(file), StockRepository(sourceDb), source)
        assertTrue(backedUp.error, backedUp.ok)
        clearCachedSettings()

        val targetDb = openDatabase()
        val target = store(targetDb)
        val restored = BackupUtils.restore(context, Uri.fromFile(file), StockRepository(targetDb), target)
        assertTrue(restored.error, restored.ok)
        clearCachedSettings()
        assertCustomOptions(target)
    }

    @Test
    fun legacyJsonBackupWithoutNewFieldsRetainsDefaults() = runBlocking {
        val file = temporaryJson()
        file.writeText("""{"formatVersion":1,"settings":[{"key":"capital_require_yellow","value":"true"}],"records":[],"marks":[]}""")
        val database = openDatabase()
        val store = store(database)
        store.put(SettingsStore.KEY_STORAGE_ROOT, context.cacheDir.absolutePath)
        val restored = BackupUtils.restore(context, Uri.fromFile(file), StockRepository(database), store)
        assertTrue(restored.error, restored.ok)
        assertDefaults(store)
        assertTrue(store.getBool(SettingsStore.KEY_CAPITAL_REQUIRE_YELLOW, false))
    }

    private suspend fun saveCustomOptions(store: SettingsStore) {
        store.put(SettingsStore.KEY_CAPITAL_METHOD, "pointwise")
        store.put(SettingsStore.KEY_CAPITAL_RULE, "majority")
        store.put(SettingsStore.KEY_SIGNAL_SELECTION, "any")
        store.put(SettingsStore.KEY_CAPITAL_MAJORITY_THRESHOLD, "0.85")
        store.put(SettingsStore.KEY_CAPITAL_REQUIRE_YELLOW, "true")
        store.put(SettingsStore.KEY_DOT_TOL_SCALE, "1.3")
        store.put(SettingsStore.KEY_MIN_CONFIDENCE, "0.6")
        store.put(SettingsStore.KEY_CAPITAL_WINDOW_SCALE, "0.7")
    }

    private suspend fun assertCustomOptions(store: SettingsStore) {
        assertEquals("pointwise", store.getCapitalMethod())
        assertEquals("majority", store.getCapitalRule())
        assertEquals("any", store.getSignalSelection())
        assertEquals(0.85f, store.getCapitalMajorityThreshold(), 0f)
        assertTrue(store.getBool(SettingsStore.KEY_CAPITAL_REQUIRE_YELLOW, false))
        assertEquals(1.3f, store.getFloat(SettingsStore.KEY_DOT_TOL_SCALE, 1f), 0f)
        assertEquals(0.6f, store.getFloat(SettingsStore.KEY_MIN_CONFIDENCE, 0f), 0f)
        assertEquals(0.7f, store.getFloat(SettingsStore.KEY_CAPITAL_WINDOW_SCALE, 0.4f), 0f)
    }

    private suspend fun assertDefaults(store: SettingsStore) {
        assertEquals("median", store.getCapitalMethod())
        assertEquals("all", store.getCapitalRule())
        assertEquals("any", store.getSignalSelection())
        assertEquals(0.9f, store.getCapitalMajorityThreshold(), 0f)
    }

    private fun newDatabaseName(): String = ("indicator-settings-" + UUID.randomUUID() + ".db").also { names += it }

    private fun openDatabase(name: String = newDatabaseName()): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, name)
            .addMigrations(AppDatabase.MIGRATION_1_2)
            .build()
            .also { databases += it }

    private fun store(database: AppDatabase): SettingsStore = SettingsStore(context, database.appSettingDao())

    private fun temporaryJson(): File = File.createTempFile("indicator-settings-", ".json", context.cacheDir)
        .also { files += it }

    private fun encodeLegacyQr(json: String): String {
        val bytes = ByteArrayOutputStream().also { output ->
            GZIPOutputStream(output).use { it.write(json.toByteArray(Charsets.UTF_8)) }
        }.toByteArray()
        return "SSMSET1:" + Base64.encodeToString(bytes, Base64.NO_WRAP)
    }
}
