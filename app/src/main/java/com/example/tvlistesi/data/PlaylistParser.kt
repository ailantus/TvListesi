package com.example.tvlistesi.data

import androidx.media3.common.MimeTypes
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.net.URL

/**
 * Sunucudan gelen listeyi iki biçimde de kabul eder:
 *
 * 1) JSON:
 *    { "channels": [ { "id": "...", "name": "...", "url": "...", "logo": "...", "group": "..." } ] }
 *    (kök doğrudan bir dizi de olabilir)
 *
 * 2) M3U / M3U8:
 *    #EXTM3U
 *    #EXTINF:-1 tvg-id="trt1" tvg-logo="..." group-title="Ulusal",TRT 1
 *    https://ornek/trt1.m3u8
 */
object PlaylistParser {

    private val attributeRegex = Regex("([A-Za-z0-9_-]+)=\"([^\"]*)\"")

    /**
     * Doğrudan açılabilen bilinen medya uzantıları. Liste dosyası `.m3u` burada
     * yer almaz; o adres bir liste olarak yüklenir.
     */
    private val directStreamRegex = Regex(
        """\.(m3u8|mpd|ism|manifest|mp4|m4v|mov|3gp|3g2|mkv|mk3d|webm|ts|m2ts|mts|m4s|avi|divx|flv|vob|mpg|mpeg|m1v|m2v|wmv|asf|ogv|rmvb|rm|mp3|m4a|aac|flac|ogg|oga|opus|wav|wma|aif|aiff|amr|mid|midi)(?:\?|#|$)""",
        RegexOption.IGNORE_CASE
    )

    /** Media3'ün kendi extractor'larıyla açabildiği uzantı → MIME eşlemesi. */
    private val mimeByExtension = mapOf(
        "mp4" to MimeTypes.VIDEO_MP4,
        "m4v" to MimeTypes.VIDEO_MP4,
        "mov" to MimeTypes.VIDEO_MP4,
        "3gp" to MimeTypes.VIDEO_MP4,
        "3g2" to MimeTypes.VIDEO_MP4,
        "m4s" to MimeTypes.VIDEO_MP4,
        "mkv" to MimeTypes.VIDEO_MATROSKA,
        "mk3d" to MimeTypes.VIDEO_MATROSKA,
        "webm" to MimeTypes.VIDEO_WEBM,
        "ogv" to MimeTypes.VIDEO_OGG,
        "ts" to MimeTypes.VIDEO_MP2T,
        "m2ts" to MimeTypes.VIDEO_MP2T,
        "mts" to MimeTypes.VIDEO_MP2T,
        "mp3" to MimeTypes.AUDIO_MPEG,
        "m4a" to MimeTypes.AUDIO_MP4,
        "aac" to MimeTypes.AUDIO_AAC,
        "flac" to MimeTypes.AUDIO_FLAC,
        "ogg" to MimeTypes.AUDIO_OGG,
        "oga" to MimeTypes.AUDIO_OGG,
        "opus" to MimeTypes.AUDIO_OPUS,
        "wav" to MimeTypes.AUDIO_WAV,
        "amr" to MimeTypes.AUDIO_AMR
    )

    /** Telegram'da paylaşılan `/dizi` ve `/film` adresleri de tek kanaldır. */
    private val directPathRegex = Regex("""/(?:dizi|film)/?$""", RegexOption.IGNORE_CASE)
    /** `ha.vixolity.com/vs/tt.../` gibi uzantısız HLS film adresleri. */
    private val extensionlessHlsRegex = Regex("""/vs/tt\d+""", RegexOption.IGNORE_CASE)

    /** HLS yayını olduğu bilinen adresler (uzantısız olanlar dahil). */
    fun isHlsAddress(url: String): Boolean =
        url.contains(".m3u8", ignoreCase = true) ||
            url.contains("/hls/", ignoreCase = true) ||
            url.contains("mpegurl", ignoreCase = true) ||
            extensionlessHlsRegex.containsMatchIn(url)

    /**
     * Adresten medya türünü çıkarır. Bilinmeyen ya da Media3'ün doğrudan
     * desteklemediği uzantılarda null döner; oynatıcı içeriği kendi sınar.
     */
    fun mediaMimeType(url: String): String? {
        if (isHlsAddress(url)) return MimeTypes.APPLICATION_M3U8
        val address = url.substringBefore('?').substringBefore('#')
        if (address.endsWith(".mpd", ignoreCase = true) ||
            address.contains("/dash/", ignoreCase = true)
        ) {
            return MimeTypes.APPLICATION_MPD
        }
        if (address.endsWith("/manifest", ignoreCase = true) &&
            address.contains(".ism", ignoreCase = true)
        ) {
            return MimeTypes.APPLICATION_SS
        }
        val extension = address.substringAfterLast('.', "").lowercase()
        return mimeByExtension[extension]
    }

    /**
     * Bir yayın adresinin bizzat kanal olduğu durumlar için tek kanallık liste
     * üretir. Örn: http://57.129.0.61:1990/stream/117/index.m3u8
     */
    fun directChannel(url: String): Channel? {
        val address = url.trim()
        if (!address.startsWith("http://", true) && !address.startsWith("https://", true)) {
            return null
        }
        val path = runCatching { URL(address).path }.getOrDefault(address)
        val isDirect = directStreamRegex.containsMatchIn(address) ||
            isHlsAddress(address) ||
            path.contains("/live/", ignoreCase = true) ||
            directPathRegex.containsMatchIn(path)
        if (!isDirect) return null

        // Doğrudan yayın adresleri için dosya adı ("index" vb.) yerine
        // izleme listesinde sıralı bir kanal adı kullanılır.
        val name = "Kanal 1"
        val mime = mediaMimeType(address)
        val group = when {
            mime == MimeTypes.APPLICATION_M3U8 -> "HLS"
            mime == MimeTypes.APPLICATION_MPD -> "DASH"
            mime == MimeTypes.APPLICATION_SS -> "Smooth Streaming"
            mime?.startsWith("audio/") == true -> "Ses"
            mime?.startsWith("video/") == true -> "Video"
            else -> "Tek kanal"
        }
        return Channel(id = stableId(address), name = name, url = address, group = group)
    }

    /**
     * [baseUrl] verilirse HLS ana listelerindeki göreli adresler çözümlenir.
     */
    fun parse(body: String, baseUrl: String? = null): List<Channel> {
        val text = body.trimStart('\uFEFF', ' ', '\t', '\r', '\n')
        if (text.isEmpty()) return emptyList()
        return if (text.startsWith("#EXTM3U", ignoreCase = true)) {
            // HLS master/media playlist'inde tek bir yayın adresi vardır;
            // EXTINF'li kanal listelerinden farklı ele alınır.
            if (text.contains("#EXT-X-STREAM-INF", true) ||
                text.contains("#EXT-X-MEDIA-SEQUENCE", true)
            ) {
                parseMasterHls(text, baseUrl)
            } else {
                parseM3u(text, baseUrl)
            }
        } else {
            parseJson(text)
        }
    }

    /** Master HLS playlist'ini tek kanallık bir liste olarak döndürür. */
    private fun parseMasterHls(text: String, baseUrl: String?): List<Channel> {
        val master = baseUrl?.takeIf { it.startsWith("http", true) }
        if (master != null) {
            val name = runCatching {
                URL(master).path.substringAfterLast('/').substringBeforeLast('.')
            }.getOrDefault("").ifBlank { "HLS yayını" }
            return listOf(
                Channel(
                    id = stableId(master),
                    name = name,
                    url = master,
                    group = "HLS"
                )
            )
        }

        // Taban adres yoksa ilk mutlak varyantı kullan.
        val firstVariant = text.lineSequence()
            .map { it.trim() }
            .firstOrNull {
                it.isNotEmpty() && !it.startsWith("#") && it.startsWith("http", true)
            } ?: return emptyList()
        return listOf(
            Channel(id = stableId(firstVariant), name = "HLS yayını", url = firstVariant, group = "HLS")
        )
    }

    /** Önbelleğe yazmak için listeyi normalize edilmiş JSON'a çevirir. */
    fun toJson(channels: List<Channel>): String {
        val array = JSONArray()
        channels.forEach { channel ->
            array.put(JSONObject().apply {
                put("id", channel.id)
                put("name", channel.name)
                put("url", channel.url)
                channel.logo?.let { put("logo", it) }
                channel.group?.let { put("group", it) }
            })
        }
        return array.toString()
    }

    /** Kontrol sonrası kalan kanalları yerel M3U olarak kaydetmek için. */
    fun toM3u(channels: List<Channel>): String = buildString {
        append("#EXTM3U\n")
        channels.forEach { channel ->
            append("#EXTINF:-1")
            if (channel.id.isNotBlank()) {
                append(" tvg-id=\"${escape(channel.id)}\"")
            }
            channel.logo?.let { append(" tvg-logo=\"${escape(it)}\"") }
            channel.group?.let { append(" group-title=\"${escape(it)}\"") }
            append(',')
            append(channel.name.replace('\n', ' ').replace('\r', ' '))
            append('\n')
            append(channel.url)
            append('\n')
        }
    }

    private fun escape(value: String): String = value.replace("\"", "")

    private fun parseJson(text: String): List<Channel> {
        val root = runCatching { JSONTokener(text).nextValue() }.getOrNull() ?: return emptyList()
        val array = when (root) {
            is JSONArray -> root
            is JSONObject -> root.optJSONArray("channels")
                ?: root.optJSONArray("items")
                ?: root.optJSONArray("list")
                ?: JSONArray()
            else -> JSONArray()
        }

        val result = ArrayList<Channel>(array.length())
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val url = first(item, "url", "link", "stream", "streamUrl", "adres").trim()
            if (url.isEmpty()) continue

            val name = first(item, "name", "title", "channelName", "kanal")
                .ifBlank { "Kanal ${i + 1}" }
            val id = first(item, "id", "tvg-id", "tvgId").ifBlank { stableId(url) }
            val logo = first(item, "logo", "tvg-logo", "tvgLogo", "icon").ifBlank { null }
            val group = first(item, "group", "group-title", "category", "grup").ifBlank { null }

            result += Channel(id = id, name = name, url = url, logo = logo, group = group)
        }
        return result
    }

    private fun parseM3u(text: String, baseUrl: String? = null): List<Channel> {
        val result = ArrayList<Channel>()
        var name = ""
        var attributes: Map<String, String> = emptyMap()
        var currentGroup: String? = null

        for (rawLine in text.lineSequence()) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue

            when {
                line.startsWith("#EXTINF", ignoreCase = true) -> {
                    val (head, rawName) = splitExtinf(line)
                    name = rawName.trim()
                    attributes = attributeRegex.findAll(head)
                        .associate { it.groupValues[1].lowercase() to it.groupValues[2].trim() }
                }

                line.startsWith("#EXTGRP:", ignoreCase = true) -> {
                    currentGroup = line.substringAfter(':').trim().ifBlank { null }
                }

                line.startsWith("#") -> Unit

                else -> {
                    val url = if (line.startsWith("http", true) ||
                        line.startsWith("rtsp", true) ||
                        line.startsWith("rtmp", true)
                    ) {
                        line
                    } else {
                        runCatching {
                            baseUrl?.let { URL(URL(it), line).toString() }
                        }.getOrNull() ?: continue
                    }

                    result += Channel(
                        id = attributes["tvg-id"].orEmpty().ifBlank { stableId(url) },
                        name = name.ifBlank { "Kanal ${result.size + 1}" },
                        url = url,
                        logo = attributes["tvg-logo"]?.takeIf { it.isNotBlank() },
                        group = attributes["group-title"]?.takeIf { it.isNotBlank() } ?: currentGroup
                    )

                    name = ""
                    attributes = emptyMap()
                }
            }
        }
        return result
    }

    /**
     * EXTINF satırını yalnızca tırnak dışındaki ilk virgülden böler.
     * `tvg-logo` adreslerindeki virgüller film adını bozmaması için gereklidir.
     */
    private fun splitExtinf(line: String): Pair<String, String> {
        var quoted = false
        for (index in line.indices) {
            when (line[index]) {
                '"' -> quoted = !quoted
                ',' -> if (!quoted) {
                    return line.substring(0, index) to line.substring(index + 1)
                }
            }
        }
        return line to ""
    }

    private fun first(json: JSONObject, vararg keys: String): String {
        for (key in keys) {
            val value = json.optString(key, "")
            if (value.isNotBlank() && value != "null") return value
        }
        return ""
    }

    private fun stableId(url: String): String =
        "ch_" + Integer.toHexString(url.hashCode())
}
