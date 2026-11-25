package com.stockshot.manager.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URL

/**
 * 琛屾儏鑾峰彇锛堝€熼壌鑷富宸ョ▼ ControlMonitor 鐨?QuoteFetcher锛夈€?
 * 鏁版嵁婧愶細鏂版氮璐㈢粡 https://hq.sinajs.cn/list=sh600519,sz000001,...
 * 杩斿洖姣忚锛歷ar hq_str_sh600519="鍚嶇О,浠婃棩寮€鐩?鏄ㄦ棩鏀剁洏,褰撳墠浠?鏈€楂?鏈€浣?..";
 *
 * 瀛楁绱㈠紩锛?鍚嶇О 1浠婃棩寮€鐩樹环 2鏄ㄦ棩鏀剁洏 3褰撳墠浠?褰撴棩鏀剁洏) 4鏈€楂?5鏈€浣?
 *
 * 涔板叆浠凤紙寮€鐩樹环锛?= index 1锛涘崠鍑轰环锛堝綋鍓?鏀剁洏浠凤級 = index 3銆?
 */
object QuoteFetcher {

    private val LINE = Regex("""var hq_str_(\w{2}\d{6})="([^"]*)";""")

    data class Quote(
        val code: String,
        val name: String,
        val open: Double,      // 涔板叆浠凤紙寮€鐩橈級
        val current: Double    // 鍗栧嚭浠凤紙褰撳墠/鏀剁洏锛?
    )

    private fun toSinaSymbol(code: String): String = when (code.firstOrNull()) {
        '6' -> "sh$code"
        '0', '3' -> "sz$code"
        '8', '4' -> "bj$code"
        else -> "sz$code"
    }

    /** 鎵归噺鑾峰彇琛屾儏銆傝繑鍥?code -> Quote */
    suspend fun fetchQuotes(codes: List<String>): Map<String, Quote> =
        withContext(Dispatchers.IO) {
            if (codes.isEmpty()) return@withContext emptyMap()
            val symbolList = codes.joinToString(",") { toSinaSymbol(it) }
            val url = "https://hq.sinajs.cn/list=$symbolList"
            val result = mutableMapOf<String, Quote>()
            try {
                val conn = URL(url).openConnection()
                conn.setRequestProperty("Referer", "https://finance.sina.com.cn")
                conn.setRequestProperty(
                    "User-Agent",
                    "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36"
                )
                conn.connectTimeout = 8000
                conn.readTimeout = 8000
                val text = conn.getInputStream().bufferedReader(charset("GBK")).readText()
                LINE.findAll(text).forEach { m ->
                    val symbol = m.groupValues[1]
                    val code = symbol.takeLast(6)
                    val fields = m.groupValues[2].split(",")
                    if (fields.size < 4) return@forEach
                    val name = fields.getOrNull(0)?.trim().orEmpty()
                    val open = fields.getOrNull(1)?.toDoubleOrNull() ?: 0.0
                    val prevClose = fields.getOrNull(2)?.toDoubleOrNull() ?: 0.0
                    val current = fields.getOrNull(3)?.toDoubleOrNull()?.takeIf { it > 0 }
                        ?: prevClose
                    result[code] = Quote(code, name, open.coerceAtLeast(0.0), current.coerceAtLeast(0.0))
                }
            } catch (_: Exception) {
            }
            result
        }

    /** 鑾峰彇鍗曞彧鑲＄エ鍚嶇О */
    suspend fun fetchName(code: String): String? =
        fetchQuotes(listOf(code))[code]?.name?.takeIf { it.isNotEmpty() }
}
