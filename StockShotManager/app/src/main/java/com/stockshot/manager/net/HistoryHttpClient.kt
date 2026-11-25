package com.stockshot.manager.net

import com.stockshot.manager.util.AppLogger
import java.io.EOFException
import java.io.IOException
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import javax.net.ssl.SSLException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

internal data class HistoryHttpRequest(
    val source: String,
    val code: String,
    val window: HistoricalPriceWindow,
    val url: String,
    val referer: String,
)

/** The generic parser also lets bounded-history sources validate coverage before accepting data. */
internal interface HistoryHttpClient {
    suspend fun <T> request(request: HistoryHttpRequest, parse: (String) -> T): T
}

internal class UrlConnectionHistoryClient : HistoryHttpClient {
    override suspend fun <T> request(request: HistoryHttpRequest, parse: (String) -> T): T =
        withContext(Dispatchers.IO) {
            currentCoroutineContext().ensureActive()
            val url = URL(request.url)
            require(url.protocol == "https" && url.userInfo == null) { "历史行情仅允许无凭据的 HTTPS 请求" }
            val detail = "source=${request.source} host=${url.host} code=${request.code} " +
                "range=${request.window.startInclusive}..${request.window.endInclusive}"
            val started = System.nanoTime()
            var connection: HttpURLConnection? = null
            var status: Int? = null
            fun elapsed() = (System.nanoTime() - started) / 1_000_000
            AppLogger.i(TAG, "request $detail")
            try {
                connection = (url.openConnection() as HttpURLConnection).apply {
                    connectTimeout = 8_000
                    readTimeout = 10_000
                    // Do not follow an unexpected redirect to a different source or cleartext URL.
                    instanceFollowRedirects = false
                    setRequestProperty("Referer", request.referer)
                    setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36")
                    setRequestProperty("Accept", "application/json,text/plain,*/*")
                }
                status = connection.responseCode
                val stream = if (status in 200..299) connection.inputStream else connection.errorStream
                val body = stream?.bufferedReader(Charsets.UTF_8)?.use { reader ->
                    val text = StringBuilder()
                    val buffer = CharArray(8192)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = reader.read(buffer)
                        if (count < 0) break
                        require(text.length + count <= MAX_BODY_CHARS) { "历史行情响应过大，拒绝不完整数据" }
                        text.append(buffer, 0, count)
                    }
                    text.toString()
                }.orEmpty()
                AppLogger.i(TAG, "response $detail http=$status elapsedMs=${elapsed()} chars=${body.length} " +
                    "type=${connection.contentType.orEmpty()} summary=${historyResponseSummary(body)}")
                if (status !in 200..299) throw IOException("HTTP $status")
                currentCoroutineContext().ensureActive()
                val result = parse(body)
                if (result is HistoricalHighResult.Failure) {
                    AppLogger.e(TAG, "parse failed $detail http=$status elapsedMs=${elapsed()} reason=${result.reason}", result.cause)
                } else {
                    AppLogger.i(TAG, "parsed $detail http=$status elapsedMs=${elapsed()} outcome=" +
                        when (result) {
                            is HistoricalHighResult.Success -> "high=${result.price}"
                            HistoricalHighResult.NoData -> "no_data"
                            is Collection<*> -> "rows=${result.size} " +
                                "first=${(result.firstOrNull() as? DailyHistoryHigh)?.date} " +
                                "last=${(result.lastOrNull() as? DailyHistoryHigh)?.date}"
                            else -> "ok"
                        })
                }
                result
            } catch (e: CancellationException) {
                AppLogger.i(TAG, "cancelled $detail http=$status elapsedMs=${elapsed()}")
                throw e
            } catch (e: Exception) {
                currentCoroutineContext().ensureActive()
                AppLogger.e(TAG, "failed $detail http=${status ?: "unavailable"} elapsedMs=${elapsed()} " +
                    "exception=${e.javaClass.name} reason=${readableHistoryError(e)}", e)
                throw e
            } finally {
                connection?.disconnect()
            }
        }

    private companion object {
        const val TAG = "HistoryHttp"
        const val MAX_BODY_CHARS = 2_000_000
    }
}

internal fun historyResponseSummary(body: String): String =
    body.take(768).replace(Regex("[\\r\\n\\t]+"), " ") + if (body.length > 768) "…" else ""

internal fun readableHistoryError(error: Exception): String = when (error) {
    is UnknownHostException -> "域名解析失败，请检查网络或 DNS"
    is SocketTimeoutException -> "连接或读取超时，请稍后重试"
    is SSLException -> "HTTPS 安全连接失败，请检查网络与系统时间"
    is ConnectException -> "无法连接行情服务器"
    is EOFException -> "行情服务器提前关闭连接"
    is SocketException -> if (error.message.orEmpty().contains("end of file", ignoreCase = true) ||
        error.message.orEmpty().contains("reset", ignoreCase = true)) "行情服务器提前关闭连接"
        else "网络连接中断（${error.javaClass.simpleName}）"
    else -> error.message?.take(180)?.takeIf { it.isNotBlank() } ?: "请求失败（${error.javaClass.simpleName}）"
}

internal suspend fun safelyFetchHistory(source: String, block: suspend () -> HistoricalHighResult): HistoricalHighResult =
    try {
        when (val result = block()) {
            is HistoricalHighResult.Failure -> result.copy(reason = "$source：${result.reason}")
            else -> result
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        HistoricalHighResult.Failure("$source：${readableHistoryError(e)}", e)
    }
