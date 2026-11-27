package com.example.tvlistesi.dlna

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

/**
 * [DlnaClient]'ı gerçek bir HTTP soketi üzerinden sınar.
 * Android birim testlerinde com.sun.net.httpserver bulunmadığı için
 * küçük, tek iş parçacıklı bir HTTP sunucusu kullanılır.
 */
class DlnaClientTest {

    private lateinit var serverSocket: ServerSocket
    private var port = 0

    private val actions = CopyOnWriteArrayList<String>()
    private val bodies = CopyOnWriteArrayList<String>()

    @Before
    fun setUp() {
        serverSocket = ServerSocket(0, 8, java.net.InetAddress.getByName("127.0.0.1"))
        port = serverSocket.localPort

        thread(isDaemon = true, name = "test-http") {
            while (!serverSocket.isClosed) {
                val socket = runCatching { serverSocket.accept() }.getOrNull() ?: break
                runCatching { handle(socket) }
            }
        }
    }

    @After
    fun tearDown() {
        runCatching { serverSocket.close() }
    }

    @Test
    fun `cihaz tanimi indirilir ve servisler bulunur`() {
        val device = DlnaClient.fetchDevice("http://127.0.0.1:$port/desc.xml")

        assertNotNull(device)
        assertEquals("Test TV", device?.name)
        assertEquals("uuid:test-tv", device?.udn)
        assertTrue(device!!.isRenderer)
        assertTrue(device.isServer)
        assertEquals("http://127.0.0.1:$port/avt", device.avTransportUrl)
        assertEquals("http://127.0.0.1:$port/cds", device.contentDirectoryUrl)
    }

    @Test
    fun `yayin gonderilir ve oynatilir`() {
        val device = DlnaClient.fetchDevice("http://127.0.0.1:$port/desc.xml")!!
        val url = "https://ornek/kanal.m3u8?a=1&b=2"

        assertTrue(DlnaClient.setAvTransportUri(device, url, "TRT 1"))
        assertTrue(DlnaClient.play(device))
        assertTrue(DlnaClient.stop(device))

        assertTrue(actions[0].contains("SetAVTransportURI"))
        assertTrue(bodies[0].contains("CurrentURI"))
        assertTrue(bodies[0].contains("a=1&amp;b=2"))
        assertTrue(actions[1].contains("#Play"))
        assertTrue(actions[2].contains("#Stop"))
    }

    @Test
    fun `cihazdan gelen SOAP hatasi okunur`() {
        val device = DlnaClient.fetchDevice("http://127.0.0.1:$port/desc-fail.xml")!!

        assertFalse(DlnaClient.setAvTransportUri(device, "http://ornek/a.mp4", "Test"))
        assertEquals("Invalid CurrentURI", DlnaClient.lastError)
    }

    @Test
    fun `icerik listesi okunur`() {
        val device = DlnaClient.fetchDevice("http://127.0.0.1:$port/desc.xml")!!
        val page = DlnaClient.browse(device, "0")

        assertEquals(2, page.totalMatches)
        assertEquals(2, page.entries.size)
        assertEquals("Filmler", page.entries[0].title)
        assertTrue(page.entries[0].isContainer)
        assertEquals("http://127.0.0.1/film.mp4", page.entries[1].url)
        assertTrue(actions.last().contains("#Browse"))
        assertTrue(bodies.last().contains("<ObjectID>0</ObjectID>"))
    }

    // ------------------------------------------------------------ test sunucusu

    private fun handle(socket: Socket) {
        socket.use { client ->
            val reader = BufferedReader(InputStreamReader(client.getInputStream(), Charsets.UTF_8))

            val requestLine = reader.readLine().orEmpty()
            val path = requestLine.split(' ').getOrNull(1)?.substringBefore('?').orEmpty()

            val headers = mutableMapOf<String, String>()
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) break
                val separator = line.indexOf(':')
                if (separator > 0) {
                    headers[line.substring(0, separator).trim().lowercase()] =
                        line.substring(separator + 1).trim()
                }
            }

            val length = headers["content-length"]?.toIntOrNull() ?: 0
            val body = if (length > 0) {
                val buffer = CharArray(length)
                var read = 0
                while (read < length) {
                    val count = reader.read(buffer, read, length - read)
                    if (count < 0) break
                    read += count
                }
                String(buffer, 0, read)
            } else {
                ""
            }

            if (path == "/fail") {
                actions += headers["soapaction"].orEmpty()
                writeResponse(client, soapFault(), 500)
                return
            }

            val response = when (path) {
                "/desc.xml" -> deviceDescription()
                "/desc-fail.xml" -> deviceDescription(controlPath = "/fail")
                "/avt" -> {
                    actions += headers["soapaction"].orEmpty()
                    bodies += body
                    soapResponse("SetAVTransportURI")
                }

                "/cds" -> {
                    actions += headers["soapaction"].orEmpty()
                    bodies += body
                    browseResponse()
                }

                else -> ""
            }

            writeResponse(client, response)
        }
    }

    private fun deviceDescription(
        controlPath: String = "/avt",
        browsePath: String = "/cds"
    ): String =
        """<?xml version="1.0"?>
        <root xmlns="urn:schemas-upnp-org:device-1-0">
          <device>
            <deviceType>urn:schemas-upnp-org:device:MediaRenderer:1</deviceType>
            <friendlyName>Test TV</friendlyName>
            <UDN>uuid:test-tv</UDN>
            <serviceList>
              <service>
                <serviceType>urn:schemas-upnp-org:service:AVTransport:1</serviceType>
                <controlURL>http://127.0.0.1:$port$controlPath</controlURL>
              </service>
              <service>
                <serviceType>urn:schemas-upnp-org:service:ContentDirectory:1</serviceType>
                <controlURL>http://127.0.0.1:$port$browsePath</controlURL>
              </service>
            </serviceList>
          </device>
        </root>"""

    private fun browseResponse(): String {
        val didl = """<DIDL-Lite xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/" xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:upnp="urn:schemas-upnp-org:metadata-1-0/upnp/">
            <container id="1" parentID="0" childCount="2"><dc:title>Filmler</dc:title></container>
            <item id="2" parentID="1"><dc:title>Demo</dc:title><upnp:class>object.item.videoItem</upnp:class><res protocolInfo="http-get:*:video/mp4:*">http://127.0.0.1/film.mp4</res></item>
        </DIDL-Lite>"""

        return """<?xml version="1.0"?>
        <s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/">
          <s:Body>
            <u:BrowseResponse xmlns:u="urn:schemas-upnp-org:service:ContentDirectory:1">
              <Result>${DlnaXml.escape(didl)}</Result>
              <TotalMatches>2</TotalMatches>
            </u:BrowseResponse>
          </s:Body>
        </s:Envelope>"""
    }

    private fun soapResponse(action: String): String =
        """<?xml version="1.0"?>
        <s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/">
          <s:Body>
            <u:${action}Response xmlns:u="urn:schemas-upnp-org:service:AVTransport:1" />
          </s:Body>
        </s:Envelope>"""

    private fun soapFault(): String = """<?xml version="1.0"?>
        <s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/">
          <s:Body><s:Fault><faultstring>UPnPError</faultstring><detail>
            <UPnPError xmlns="urn:schemas-upnp-org:control-1-0">
              <errorCode>402</errorCode>
              <errorDescription>Invalid CurrentURI</errorDescription>
            </UPnPError>
          </detail></s:Fault></s:Body>
        </s:Envelope>"""

    private fun writeResponse(socket: Socket, text: String, status: Int = 200) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        val header = buildString {
            append("HTTP/1.1 $status ${if (status == 200) "OK" else "Internal Server Error"}\r\n")
            append("Content-Type: text/xml; charset=\"utf-8\"\r\n")
            append("Content-Length: ${bytes.size}\r\n")
            append("Connection: close\r\n")
            append("\r\n")
        }.toByteArray(Charsets.UTF_8)

        socket.getOutputStream().apply {
            write(header)
            write(bytes)
            flush()
        }
    }
}
