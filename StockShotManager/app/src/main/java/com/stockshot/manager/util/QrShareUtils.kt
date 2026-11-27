package com.stockshot.manager.util

import android.graphics.Bitmap
import android.graphics.Color
import android.util.Base64
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.stockshot.manager.data.SettingsStore
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * Settings QR share.
 *
 * Exports the sharable settings (all persisted keys EXCEPT the storage root,
 * which is a device-local absolute path and differs per A/B/C/D install) as a
 * compact code string that another install can scan to import in one shot.
 *
 * Wire format:  "SSMSET1:" + Base64(NO_WRAP)( gzip( json ) )
 * json = { "formatVersion": 1, "settings": [ {"key","value"}, ... ] }
 *
 * Import only overwrites the sharable setting keys; it never touches records,
 * marks or the storage root.
 */
object QrShareUtils {

    private const val TAG = "QrShare"
    private const val PREFIX = "SSMSET1:"
    private const val FORMAT_VERSION = 1

    private val gson: Gson = GsonBuilder().create()

    data class ShareItem(val key: String, val value: String)

    private data class SharePayload(
        val formatVersion: Int = FORMAT_VERSION,
        val settings: List<ShareItem> = emptyList(),
    )

    /** Keys included in a share code: every backup setting key except the storage root. */
    val SHARE_KEYS: List<String> = listOf(
        SettingsStore.KEY_DATA_SOURCE_TYPE,
        SettingsStore.KEY_API_BASE_URL,
        SettingsStore.KEY_API_KEY,
        SettingsStore.KEY_TARGET_APP_PACKAGE,
        SettingsStore.KEY_CODE_VIEW_ID,
        SettingsStore.KEY_NEXT_BUTTON_ID,
        SettingsStore.KEY_SCREENSHOT_DELAY_MS,
        SettingsStore.KEY_OCR_ENABLED,
        SettingsStore.KEY_VIEWER_PAGE_DIRECTION,
        SettingsStore.KEY_QUOTE_SOURCE_TYPE,
        SettingsStore.KEY_LAN_API_IP,
        SettingsStore.KEY_AUTO_DETECT_ENABLED,
        SettingsStore.KEY_AUTO_MARK_ENABLED,
        SettingsStore.KEY_AUTO_BUYPRICE_ENABLED,
        SettingsStore.KEY_NODE_REGION_LEFT,
        SettingsStore.KEY_NODE_REGION_TOP,
        SettingsStore.KEY_NODE_REGION_RIGHT,
        SettingsStore.KEY_NODE_REGION_BOTTOM,
        SettingsStore.KEY_NODE_CLASS,
        SettingsStore.KEY_NODE_WIDTH,
        SettingsStore.KEY_NODE_HEIGHT,
        SettingsStore.KEY_NODE_SIZE_TOL,
        SettingsStore.KEY_NODE_INDEX,
        SettingsStore.KEY_NODE_READ_DELAY_MS,
        SettingsStore.KEY_NODE_LONGPRESS_MS,
        SettingsStore.KEY_POST_READ_DELAY_MS,
        SettingsStore.KEY_CLOSE_CROSSHAIR_MODE,
        SettingsStore.KEY_CAPITAL_REQUIRE_YELLOW,
        SettingsStore.KEY_DOT_TOL_SCALE,
        SettingsStore.KEY_MIN_CONFIDENCE,
        SettingsStore.KEY_CAPITAL_WINDOW_SCALE,
        SettingsStore.KEY_CAPITAL_METHOD,
        SettingsStore.KEY_CAPITAL_RULE,
        SettingsStore.KEY_SIGNAL_SELECTION,
        SettingsStore.KEY_CAPITAL_MAJORITY_THRESHOLD,
    )

    /** Serialize current sharable settings into a scannable code string. */
    suspend fun buildShareText(store: SettingsStore): String {
        val items = mutableListOf<ShareItem>()
        for (k in SHARE_KEYS) {
            val v = store.get(k, "")
            if (v.isNotBlank()) items.add(ShareItem(k, v))
        }
        val json = gson.toJson(SharePayload(settings = items))
        val gz = ByteArrayOutputStream().also { bos ->
            GZIPOutputStream(bos).use { it.write(json.toByteArray(Charsets.UTF_8)) }
        }.toByteArray()
        val b64 = Base64.encodeToString(gz, Base64.NO_WRAP)
        return PREFIX + b64
    }

    /** Parse a scanned code string back into share items, or null if invalid. */
    fun parseShareText(text: String?): List<ShareItem>? {
        if (text == null) return null
        val trimmed = text.trim()
        if (!trimmed.startsWith(PREFIX)) return null
        return try {
            val b64 = trimmed.substring(PREFIX.length)
            val gz = Base64.decode(b64, Base64.NO_WRAP)
            val json = GZIPInputStream(ByteArrayInputStream(gz)).use {
                it.readBytes().toString(Charsets.UTF_8)
            }
            val payload = gson.fromJson(json, SharePayload::class.java) ?: return null
            payload.settings.filter { it.key in SHARE_KEYS }
        } catch (e: Exception) {
            AppLogger.e(TAG, "parseShareText failed", e)
            null
        }
    }

    /** Render the code string as a black/white QR bitmap. */
    fun encodeQrBitmap(text: String, sizePx: Int): Bitmap {
        val hints = mapOf(
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
            EncodeHintType.CHARACTER_SET to "UTF-8",
            EncodeHintType.MARGIN to 1,
        )
        val matrix = MultiFormatWriter().encode(
            text, BarcodeFormat.QR_CODE, sizePx, sizePx, hints
        )
        val w = matrix.width
        val h = matrix.height
        val pixels = IntArray(w * h)
        for (y in 0 until h) {
            val offset = y * w
            for (x in 0 until w) {
                pixels[offset + x] = if (matrix.get(x, y)) Color.BLACK else Color.WHITE
            }
        }
        return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply {
            setPixels(pixels, 0, w, 0, 0, w, h)
        }
    }

    /** Apply scanned share items, overwriting only sharable setting keys. Returns applied count. */
    suspend fun applyShared(store: SettingsStore, items: List<ShareItem>): Int {
        var count = 0
        for (item in items) {
            if (item.key in SHARE_KEYS) {
                store.put(item.key, item.value)
                count++
            }
        }
        return count
    }
}