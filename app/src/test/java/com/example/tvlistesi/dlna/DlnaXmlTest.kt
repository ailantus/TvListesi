package com.example.tvlistesi.dlna

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DlnaXmlTest {

    @Test
    fun `cihaz tanimi goreli adresleri cozer`() {
        val xml = """
            <?xml version="1.0"?>
            <root xmlns="urn:schemas-upnp-org:device-1-0">
              <device>
                <deviceType>urn:schemas-upnp-org:device:MediaRenderer:1</deviceType>
                <friendlyName>Salon TV</friendlyName>
                <UDN>uuid:test-1</UDN>
                <iconList>
                  <icon><size>48x48</size><url>/icon48.png</url></icon>
                  <icon><size>120x120</size><url>/icon120.png</url></icon>
                </iconList>
                <serviceList>
                  <service>
                    <serviceType>urn:schemas-upnp-org:service:AVTransport:1</serviceType>
                    <controlURL>avt</controlURL>
                  </service>
                  <service>
                    <serviceType>urn:schemas-upnp-org:service:ContentDirectory:1</serviceType>
                    <controlURL>/cds</controlURL>
                  </service>
                </serviceList>
              </device>
            </root>
        """.trimIndent()

        val device = DlnaXml.parseDevice("http://192.168.1.20:9197/desc.xml", xml)

        assertEquals("Salon TV", device?.name)
        assertEquals("uuid:test-1", device?.udn)
        assertEquals("http://192.168.1.20:9197/avt", device?.avTransportUrl)
        assertEquals("http://192.168.1.20:9197/cds", device?.contentDirectoryUrl)
        assertEquals("http://192.168.1.20:9197/icon120.png", device?.iconUrl)
        assertTrue(device!!.isRenderer)
        assertTrue(device.isServer)
    }

    @Test
    fun `gecersiz cihaz tanimi null doner`() {
        assertNull(DlnaXml.parseDevice("http://x/desc.xml", "bozuk xml"))
        assertNull(DlnaXml.parseDevice("http://x/desc.xml", "<root/>"))
    }

    @Test
    fun `browse yaniti klasor ve medya ogelerini ayristirir`() {
        val didl = """<DIDL-Lite xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/" xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:upnp="urn:schemas-upnp-org:metadata-1-0/upnp/">
            <container id="1" parentID="0" childCount="3"><dc:title>Filmler</dc:title><upnp:class>object.container.storageFolder</upnp:class></container>
            <item id="2" parentID="1"><dc:title>Demo Film</dc:title><upnp:class>object.item.videoItem</upnp:class><res protocolInfo="http-get:*:video/mp4:*" duration="00:01:30">http://192.168.1.20/film.mp4</res></item>
            <item id="3" parentID="1"><dc:title>Kapak</dc:title><upnp:class>object.item.imageItem</upnp:class><res protocolInfo="http-get:*:image/jpeg:*">http://192.168.1.20/kapak.jpg</res></item>
        </DIDL-Lite>"""

        val response = """<?xml version="1.0"?>
            <s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/">
              <s:Body>
                <u:BrowseResponse xmlns:u="urn:schemas-upnp-org:service:ContentDirectory:1">
                  <Result>${DlnaXml.escape(didl)}</Result>
                  <NumberReturned>3</NumberReturned>
                  <TotalMatches>3</TotalMatches>
                </u:BrowseResponse>
              </s:Body>
            </s:Envelope>"""

        val page = DlnaXml.parseBrowseResult(response)

        assertEquals(3, page.totalMatches)
        assertEquals(2, page.entries.size)

        val folder = page.entries[0]
        assertEquals("1", folder.id)
        assertEquals("Filmler", folder.title)
        assertTrue(folder.isContainer)
        assertEquals(3, folder.childCount)

        val item = page.entries[1]
        assertEquals("Demo Film", item.title)
        assertEquals("http://192.168.1.20/film.mp4", item.url)
        assertEquals("video/mp4", item.mimeType)
        assertEquals("00:01:30", item.duration)
    }

    @Test
    fun `dlna ust verisi kacis yapar ve dogru sinifi secer`() {
        val metadata = DlnaXml.metadata(
            title = "TRT & 1",
            url = "https://ornek/kanal.m3u8?a=1&b=2",
            mimeType = "application/vnd.apple.mpegurl"
        )

        assertTrue(metadata.contains("<dc:title>TRT &amp; 1</dc:title>"))
        assertTrue(metadata.contains("a=1&amp;b=2"))
        assertTrue(metadata.contains("object.item.videoItem"))
        assertTrue(
            metadata.contains(
                "protocolInfo=\"http-get:*:application/vnd.apple.mpegurl:*\""
            )
        )
    }

    @Test
    fun `soap zarfi eylem ve servis adini icerir`() {
        val envelope = DlnaXml.envelope(
            DlnaXml.SERVICE_AV_TRANSPORT,
            "Play",
            DlnaXml.playBody()
        )

        assertTrue(envelope.contains("<u:Play"))
        assertTrue(envelope.contains(DlnaXml.SERVICE_AV_TRANSPORT))
        assertTrue(envelope.contains("<Speed>1</Speed>"))
    }

    @Test
    fun `ssdp location basligi okunur`() {
        val response = "HTTP/1.1 200 OK\r\n" +
            "CACHE-CONTROL: max-age=1800\r\n" +
            "location: http://192.168.1.30:9197/dmr\r\n" +
            "ST: urn:schemas-upnp-org:device:MediaRenderer:1\r\n\r\n"

        assertEquals("http://192.168.1.30:9197/dmr", Ssdp.parseLocation(response))
        assertNull(Ssdp.parseLocation("HTTP/1.1 200 OK\r\nST: test\r\n"))
        assertNull(Ssdp.parseLocation("LOCATION: ftp://192.168.1.30/x"))
    }
}
