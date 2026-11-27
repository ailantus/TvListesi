package com.example.tvlistesi.dlna

import com.example.tvlistesi.data.DlnaDevice
import java.net.HttpURLConnection
import java.net.URL

/**
 * DLNA cihazlarıyla HTTP/SOAP üzerinden konuşur:
 * cihaz tanımını indirir, AVTransport ile yayın gönderir ve
 * ContentDirectory ile klasörleri listeler.
 */
object DlnaClient {

    /** Son istekte cihazdan gelen hata açıklaması (SOAP fault veya HTTP hatası). */
    @Volatile
    var lastError: String? = null
        private set

    fun clearLastError() {
        lastError = null
    }

    private const val USER_AGENT = "TvListesi/1.0 (Android)"
    private const val CONNECT_TIMEOUT = 4_000
    private const val READ_TIMEOUT = 8_000
    private const val BROWSE_COUNT = 300

    fun fetchDevice(location: String): DlnaDevice? = runCatching {
        val xml = httpGet(location) ?: return null
        DlnaXml.parseDevice(location, xml)
    }.getOrNull()

    fun setAvTransportUri(device: DlnaDevice, url: String, title: String): Boolean {
        val control = device.avTransportUrl ?: return false
        val mime = DlnaXml.mimeTypeFor(url)
        val body = DlnaXml.setAvTransportUriBody(url, title, mime)
        return soap(control, DlnaXml.SERVICE_AV_TRANSPORT, "SetAVTransportURI", body) != null
    }

    fun play(device: DlnaDevice): Boolean {
        val control = device.avTransportUrl ?: return false
        return soap(control, DlnaXml.SERVICE_AV_TRANSPORT, "Play", DlnaXml.playBody()) != null
    }

    fun stop(device: DlnaDevice): Boolean {
        val control = device.avTransportUrl ?: return false
        return soap(control, DlnaXml.SERVICE_AV_TRANSPORT, "Stop", DlnaXml.stopBody()) != null
    }

    fun browse(
        device: DlnaDevice,
        objectId: String,
        start: Int = 0,
        count: Int = BROWSE_COUNT
    ): BrowsePage {
        val control = device.contentDirectoryUrl
            ?: return BrowsePage(emptyList(), 0)

        val body = DlnaXml.browseBody(objectId, start, count)
        val response = soap(control, DlnaXml.SERVICE_CONTENT_DIRECTORY, "Browse", body)
            ?: return BrowsePage(emptyList(), 0)

        return DlnaXml.parseBrowseResult(response)
    }

    // ------------------------------------------------------------ HTTP

    private fun httpGet(url: String): String? = request(url, "GET", null, emptyMap())

    private fun soap(
        controlUrl: String,
        serviceType: String,
        action: String,
        body: String
    ): String? {
        val envelope = DlnaXml.envelope(serviceType, action, body)
        return request(
            url = controlUrl,
            method = "POST",
            body = envelope,
            headers = mapOf("SOAPACTION" to "\"$serviceType#$action\"")
        )
    }

    private fun request(
        url: String,
        method: String,
        body: String?,
        headers: Map<String, String>
    ): String? = runCatching {
        lastError = null
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = CONNECT_TIMEOUT
            readTimeout = READ_TIMEOUT
            setRequestProperty("User-Agent", USER_AGENT)
            headers.forEach { (name, value) -> setRequestProperty(name, value) }
        }

        try {
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"")
                connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }

            val code = connection.responseCode
            if (code !in 200..299) {
                val response = runCatching {
                    connection.errorStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                }.getOrNull()
                lastError = response?.let(::faultDescription)
                    ?.takeIf { it.isNotBlank() }
                    ?: "HTTP $code"
                return null
            }
            connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }.onFailure { lastError = it.message?.takeIf { message -> message.isNotBlank() } ?: "Bağlantı hatası" }
        .getOrNull()

    private fun faultDescription(xml: String): String? =
        Regex(
            """<(?:[A-Za-z0-9_-]+:)?errorDescription>\s*([^<]*)\s*</""",
            RegexOption.IGNORE_CASE
        ).find(xml)?.groupValues?.get(1)?.trim()
}
