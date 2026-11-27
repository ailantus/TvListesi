package com.example.tvlistesi.dlna

import com.example.tvlistesi.data.DlnaDevice
import com.example.tvlistesi.data.DlnaEntry
import com.example.tvlistesi.data.PlaylistParser
import org.w3c.dom.Document
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.net.URI
import javax.xml.parsers.DocumentBuilderFactory

/** DLNA sunucusundan dönen tek bir klasör içeriği. */
data class BrowsePage(
    val entries: List<DlnaEntry>,
    val totalMatches: Int
)

/**
 * UPnP cihaz tanımı, SOAP zarfları ve DIDL-Lite içeriklerini
 * Android'e bağlı olmayan saf XML işlemleriyle üretir/ayrıştırır.
 * Böylece birim testlerinde de kullanılabilir.
 */
object DlnaXml {

    const val SERVICE_AV_TRANSPORT = "urn:schemas-upnp-org:service:AVTransport:1"
    const val SERVICE_CONTENT_DIRECTORY = "urn:schemas-upnp-org:service:ContentDirectory:1"

    private const val DIDL_NS = "urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/"
    private const val DC_NS = "http://purl.org/dc/elements/1.1/"
    private const val UPNP_NS = "urn:schemas-upnp-org:metadata-1-0/upnp/"

    // ------------------------------------------------------------ cihaz tanımı

    fun parseDevice(location: String, xml: String): DlnaDevice? {
        val document = parseDocument(xml) ?: return null
        val device = document.getElementsByTagNameNS("*", "device").item(0) as? Element
            ?: return null

        val name = text(device, "friendlyName") ?: return null
        val udn = text(device, "UDN") ?: location
        val urlBase = text(document.documentElement, "URLBase") ?: location

        fun resolve(path: String): String =
            runCatching { URI(urlBase).resolve(path).toString() }.getOrDefault(path)

        var avTransport: String? = null
        var contentDirectory: String? = null

        val services = device.getElementsByTagNameNS("*", "service")
        for (index in 0 until services.length) {
            val service = services.item(index) as? Element ?: continue
            val type = text(service, "serviceType") ?: continue
            val control = text(service, "controlURL") ?: continue

            when {
                type.contains(":service:AVTransport:") && avTransport == null ->
                    avTransport = resolve(control)

                type.contains(":service:ContentDirectory:") && contentDirectory == null ->
                    contentDirectory = resolve(control)
            }
        }

        return DlnaDevice(
            name = name,
            udn = udn,
            location = location,
            avTransportUrl = avTransport,
            contentDirectoryUrl = contentDirectory,
            iconUrl = bestIcon(device, ::resolve)
        )
    }

    private fun bestIcon(device: Element, resolve: (String) -> String): String? {
        val icons = device.getElementsByTagNameNS("*", "icon")
        var bestUrl: String? = null
        var bestArea = -1

        for (index in 0 until icons.length) {
            val icon = icons.item(index) as? Element ?: continue
            val url = text(icon, "url") ?: continue
            val size = text(icon, "size").orEmpty()
            val area = size.split('x')
                .mapNotNull { it.trim().toIntOrNull() }
                .fold(1) { acc, value -> acc * value }
            if (area > bestArea) {
                bestArea = area
                bestUrl = resolve(url)
            }
        }

        return bestUrl
    }

    // ------------------------------------------------------------ SOAP

    fun envelope(serviceType: String, action: String, body: String): String =
        """<?xml version="1.0" encoding="utf-8"?>
<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
<s:Body><u:$action xmlns:u="$serviceType">$body</u:$action></s:Body>
</s:Envelope>"""

    fun setAvTransportUriBody(url: String, title: String, mimeType: String): String {
        val metadata = metadata(title, url, mimeType)
        return "<InstanceID>0</InstanceID>" +
            "<CurrentURI>${escape(url)}</CurrentURI>" +
            "<CurrentURIMetaData>${escape(metadata)}</CurrentURIMetaData>"
    }

    fun playBody(): String = "<InstanceID>0</InstanceID><Speed>1</Speed>"

    fun stopBody(): String = "<InstanceID>0</InstanceID>"

    fun browseBody(objectId: String, start: Int, count: Int): String =
        "<ObjectID>${escape(objectId)}</ObjectID>" +
            "<BrowseFlag>BrowseDirectChildren</BrowseFlag>" +
            "<Filter>*</Filter>" +
            "<StartingIndex>$start</StartingIndex>" +
            "<RequestedCount>$count</RequestedCount>" +
            "<SortCriteria></SortCriteria>"

    /** Bir yayın için DLNA cihazlarına gönderilecek DIDL-Lite üst verisi. */
    fun metadata(title: String, url: String, mimeType: String): String {
        val itemClass = when {
            mimeType.startsWith("audio/") -> "object.item.audioItem"
            mimeType.startsWith("image/") -> "object.item.imageItem"
            else -> "object.item.videoItem"
        }

        return """<DIDL-Lite xmlns="$DIDL_NS" xmlns:dc="$DC_NS" xmlns:upnp="$UPNP_NS">""" +
            """<item id="0" parentID="-1" restricted="1">""" +
            "<dc:title>${escape(title)}</dc:title>" +
            "<upnp:class>$itemClass</upnp:class>" +
            """<res protocolInfo="http-get:*:$mimeType:*">${escape(url)}</res>""" +
            "</item></DIDL-Lite>"
    }

    // ------------------------------------------------------------ içerik listesi

    fun parseBrowseResult(xml: String): BrowsePage {
        val document = parseDocument(xml)
            ?: return BrowsePage(emptyList(), 0)

        val resultElement = document.getElementsByTagNameNS("*", "Result").item(0) as? Element
        val total = text(document.documentElement, "TotalMatches")?.toIntOrNull() ?: 0

        // Bazı sunucular SOAP zarfı yerine doğrudan DIDL-Lite döndürür.
        val didl = resultElement?.textContent
            ?: document.documentElement?.textContent
            ?: return BrowsePage(emptyList(), total)

        val didlDocument = parseDocument(didl) ?: return BrowsePage(emptyList(), total)
        val entries = mutableListOf<DlnaEntry>()

        val containers = didlDocument.getElementsByTagNameNS("*", "container")
        for (index in 0 until containers.length) {
            val element = containers.item(index) as? Element ?: continue
            val id = element.getAttribute("id").ifBlank { "container-$index" }
            entries += DlnaEntry(
                id = id,
                title = text(element, "title") ?: id,
                isContainer = true,
                childCount = element.getAttribute("childCount").toIntOrNull() ?: 0,
                iconUrl = text(element, "icon")
            )
        }

        val items = didlDocument.getElementsByTagNameNS("*", "item")
        for (index in 0 until items.length) {
            val element = items.item(index) as? Element ?: continue
            val itemClass = text(element, "class").orEmpty()
            if (itemClass.contains("imageItem", ignoreCase = true)) continue

            val media = bestResource(element) ?: continue
            val id = element.getAttribute("id").ifBlank { "item-$index" }
            entries += DlnaEntry(
                id = id,
                title = text(element, "title") ?: id,
                isContainer = false,
                url = media.url,
                mimeType = media.mimeType,
                duration = media.duration,
                iconUrl = text(element, "icon")
            )
        }

        return BrowsePage(entries, total)
    }

    private data class Resource(
        val url: String,
        val mimeType: String?,
        val duration: String?
    )

    private fun bestResource(item: Element): Resource? {
        val resources = item.getElementsByTagNameNS("*", "res")
        var fallback: Resource? = null

        for (index in 0 until resources.length) {
            val element = resources.item(index) as? Element ?: continue
            val url = element.textContent?.trim().orEmpty()
            if (!url.startsWith("http://", true) && !url.startsWith("https://", true)) continue

            val protocol = element.getAttribute("protocolInfo")
            val mime = protocol.split(':').getOrNull(2)
                ?.trim()
                ?.takeIf { it.isNotEmpty() && it != "*" }

            if (mime != null && mime.startsWith("image/")) continue

            val resource = Resource(url, mime, element.getAttribute("duration").ifBlank { null })
            if (mime != null && (mime.startsWith("video/") || mime.startsWith("audio/"))) {
                return resource
            }
            if (fallback == null) fallback = resource
        }

        return fallback
    }

    // ------------------------------------------------------------ yardımcılar

    fun mimeTypeFor(url: String): String =
        PlaylistParser.mediaMimeType(url) ?: "video/mp4"

    fun escape(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")

    private fun parseDocument(xml: String): Document? = runCatching {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            runCatching {
                setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            }
            runCatching {
                setFeature("http://xml.org/sax/features/external-general-entities", false)
            }
            runCatching {
                setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            }
        }
        factory.newDocumentBuilder()
            .parse(ByteArrayInputStream(xml.trim().toByteArray(Charsets.UTF_8)))
    }.getOrNull()

    private fun text(parent: Element?, tag: String): String? {
        if (parent == null) return null
        val nodes = parent.getElementsByTagNameNS("*", tag)
        val value = (nodes.item(0) as? Element)?.textContent?.trim()
        return value?.takeIf { it.isNotEmpty() }
    }
}
