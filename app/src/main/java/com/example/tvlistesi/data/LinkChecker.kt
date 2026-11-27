package com.example.tvlistesi.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/**
 * Tek bir yayın adresinin erişilebilir olup olmadığını sınar.
 *
 * Küçük bir Range isteğiyle yalnızca ilk birkaç bayt okunur; böylece canlı
 * yayınlar tamamen indirilmeden hızlıca doğrulanır. RTSP/RTMP adresleri HTTP
 * olmadığı için burada sınanamaz ve "desteklenmeyen adres" olarak işaretlenir.
 */
object LinkChecker {

    data class Result(
        val channel: Channel,
        val ok: Boolean,
        val code: Int?,
        val message: String,
        val elapsedMs: Long,
        val checked: Boolean = true
    ) {
        companion object {
            fun pending(channel: Channel) = Result(channel, false, null, "Bekliyor", 0, false)
        }
    }

    suspend fun check(channel: Channel): Result = withContext(Dispatchers.IO) {
        val started = System.currentTimeMillis()
        val address = channel.url.trim()

        if (!address.startsWith("http://", true) && !address.startsWith("https://", true)) {
            return@withContext Result(channel, false, null, "HTTP olmayan adres", 0)
        }

        var connection: HttpURLConnection? = null
        try {
            connection = (URL(address).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 8_000
                readTimeout = 8_000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "TvListesi/1.9 (Android)")
                setRequestProperty("Accept", "*/*")
                setRequestProperty("Range", "bytes=0-2047")
            }

            val code = connection.responseCode
            if (code !in 200..299) {
                return@withContext Result(
                    channel, false, code, "HTTP $code", System.currentTimeMillis() - started
                )
            }

            // Yalnızca ilk baytları oku: HLS manifesti ya da ilk medya parçası yeterli.
            val delivered = connection.inputStream?.use { stream ->
                stream.read(ByteArray(512)) > 0
            } == true

            Result(
                channel = channel,
                ok = delivered,
                code = code,
                message = if (delivered) "Çalışıyor" else "Veri gelmedi",
                elapsedMs = System.currentTimeMillis() - started
            )
        } catch (error: Exception) {
            Result(
                channel = channel,
                ok = false,
                code = null,
                message = error.message?.take(80)?.ifBlank { "Bağlantı hatası" }
                    ?: "Bağlantı hatası",
                elapsedMs = System.currentTimeMillis() - started
            )
        } finally {
            connection?.disconnect()
        }
    }
}
