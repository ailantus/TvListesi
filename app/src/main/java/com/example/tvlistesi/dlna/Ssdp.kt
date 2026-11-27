package com.example.tvlistesi.dlna

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException

/**
 * SSDP (Simple Service Discovery Protocol) ile ağdaki UPnP cihazlarını arar.
 * Yalnızca cihaz tanım adreslerini (LOCATION) döndürür; ayrıştırma [DlnaClient]'ta yapılır.
 */
object Ssdp {

    private const val SSDP_ADDRESS = "239.255.255.250"
    private const val SSDP_PORT = 1900

    private val SEARCH_TARGETS = listOf(
        "upnp:rootdevice",
        "urn:schemas-upnp-org:device:MediaRenderer:1",
        "urn:schemas-upnp-org:device:MediaServer:1"
    )

    /**
     * Ağa M-SEARCH yayınlar ve [timeoutMs] boyunca gelen yanıtları toplar.
     * Aynı adres birden fazla kez dönerse tekilleştirilir.
     */
    fun search(timeoutMs: Long = 3_500): List<String> {
        val locations = LinkedHashSet<String>()

        runCatching {
            DatagramSocket().use { socket ->
                socket.soTimeout = 250
                val group = InetAddress.getByName(SSDP_ADDRESS)

                SEARCH_TARGETS.forEach { target ->
                    val request = buildString {
                        append("M-SEARCH * HTTP/1.1\r\n")
                        append("HOST: $SSDP_ADDRESS:$SSDP_PORT\r\n")
                        append("MAN: \"ssdp:discover\"\r\n")
                        append("MX: 2\r\n")
                        append("ST: $target\r\n")
                        append("\r\n")
                    }
                    val bytes = request.toByteArray(Charsets.UTF_8)
                    runCatching {
                        socket.send(DatagramPacket(bytes, bytes.size, group, SSDP_PORT))
                    }
                }

                val deadline = System.currentTimeMillis() + timeoutMs
                val buffer = ByteArray(8 * 1024)

                while (System.currentTimeMillis() < deadline) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    try {
                        socket.receive(packet)
                    } catch (timeout: SocketTimeoutException) {
                        continue
                    }

                    val response = String(packet.data, 0, packet.length, Charsets.UTF_8)
                    parseLocation(response)?.let { locations += it }
                }
            }
        }

        return locations.toList()
    }

    /** SSDP yanıtındaki `LOCATION` başlığını (büyük/küçük harf duyarsız) okur. */
    fun parseLocation(response: String): String? = response.lineSequence()
        .map { it.trim() }
        .firstOrNull { it.startsWith("LOCATION:", ignoreCase = true) }
        ?.substringAfter(':')
        ?.trim()
        ?.takeIf { it.startsWith("http://", true) || it.startsWith("https://", true) }
}
