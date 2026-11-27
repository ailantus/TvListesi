package com.example.tvlistesi.data

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/**
 * Herkese açık Telegram kanallarının t.me/s/... önizleme sayfasını tarar.
 * Mesaj metnindeki M3U/M3U8 adreslerini ve tek kanal yayınlarını toplar.
 * Telegram oturumu/API anahtarı gerektirmez.
 */
object TelegramDiscovery {

    const val DEFAULT_URL = "https://t.me/s/TvListesi"

    private const val USER_AGENT = "Mozilla/5.0 (Linux; Android) TvListesi/2.0"
    private const val MAX_HTML = 3_000_000
    private const val MAX_PAGES = 50

    private val m3uUrl = Regex(
        """https?://[^\s<>"']+?\.m3u8?(?:\?[^\s<>"']*)?""",
        RegexOption.IGNORE_CASE
    )
    private val relativeM3uUrl = Regex(
        """(?<![:\w])//[^\s<>"']+?\.m3u8?(?:\?[^\s<>"']*)?""",
        RegexOption.IGNORE_CASE
    )
    private val paramM3uUrl = Regex(
        """https?://[^\s<>"']+?[?&](?:type|output|format|ext)=m3u8?(?:_plus)?(?:[&#][^\s<>"']*)?""",
        RegexOption.IGNORE_CASE
    )
    private val relativeParamM3uUrl = Regex(
        """(?<![:\w])//[^\s<>"']+?[?&](?:type|output|format|ext)=m3u8?(?:_plus)?(?:[&#][^\s<>"']*)?""",
        RegexOption.IGNORE_CASE
    )
    /** `?...&type=Film` / `type=Dizi` gibi PHP tabanlı liste adresleri. */
    private val paramMediaUrl = Regex(
        """https?://[^\s<>"']+?[?&]type=(?:film|dizi)(?=&|$)(?:&[^\s<>"']*)?""",
        RegexOption.IGNORE_CASE
    )
    private val relativeParamMediaUrl = Regex(
        """(?<![:\w])//[^\s<>"']+?[?&]type=(?:film|dizi)(?=&|$)(?:&[^\s<>"']*)?""",
        RegexOption.IGNORE_CASE
    )

    private val directStreamPatterns = listOf(
        Regex("""https?://[^\s<>"']+?\.(?:ts|m2ts)(?:\?[^\s<>"']*)?""", RegexOption.IGNORE_CASE),
        Regex(
            """https?://[^\s<>"']+?/(?:live|stream|channel|hls)/[^\s<>"']+""",
            RegexOption.IGNORE_CASE
        ),
        Regex(
            """https?://[^\s<>"']+/[^\s<>"']+/\d+(?:\.ts)?(?:\?[^\s<>"']*)?""",
            RegexOption.IGNORE_CASE
        ),
        // /dizi ve /film ile biten paylaşımlar da tek kanal olarak kontrol edilir.
        Regex(
            """https?://[^\s<>"']+?/(?:dizi|film)(?=[/?#]|$)(?:\?[^\s<>"']*)?""",
            RegexOption.IGNORE_CASE
        )
    )
    private val nonStreamHosts = listOf("t.me", "telegram", "github", "gist", "jsdelivr")

    private val redirectParam = Regex("""(?:url|redirect_url)=([^&#"']+)""", RegexOption.IGNORE_CASE)
    private val userNameParam = Regex("""(?:username|user)=([^&#"']+)""", RegexOption.IGNORE_CASE)
    private val messageBlock = Regex("""<div class="tgme_widget_message_wrap""")
    private val postRegex = Regex("""data-post="([^"]+)"""")
    private val dateRegex = Regex("""<time datetime="([^"]+)"""")
    private val textRegex = Regex(
        """<div class="tgme_widget_message_text[^"]*"[^>]*>(.*?)</div>""",
        RegexOption.DOT_MATCHES_ALL
    )
    private val hrefRegex = Regex("""href="([^"]+)"""")
    private val brRegex = Regex("""<br\s*/?>""", RegexOption.IGNORE_CASE)
    private val tagRegex = Regex("""<[^>]+>""")

    private val apkUrl = Regex(
        """https?://[^\s<>"']+?\.apk(?:\?[^\s<>"']*)?""",
        RegexOption.IGNORE_CASE
    )
    private val driveUrl = Regex(
        """https?://(?:drive\.google\.com|drive\.usercontent\.google\.com)/[^\s<>"']+""",
        RegexOption.IGNORE_CASE
    )
    private val driveApkName = Regex(
        """TvListesi[^\s<>"']*\.apk""",
        RegexOption.IGNORE_CASE
    )
    private val documentTitleRegex = Regex(
        """tgme_widget_message_document_title[^>]*>(.*?)</div>""",
        RegexOption.DOT_MATCHES_ALL
    )
    private val documentSizeRegex = Regex(
        """tgme_widget_message_document_extra[^>]*>(.*?)</div>""",
        RegexOption.DOT_MATCHES_ALL
    )
    private val documentHrefRegex = Regex(
        """tgme_widget_message_document_wrap[^>]*href="([^"]+)"""",
        RegexOption.IGNORE_CASE
    )
    private val versionInName = Regex("""(\d+(?:\.\d+){1,3})""")
    private val updatePrefix = Regex("""^TvListesi""", RegexOption.IGNORE_CASE)

    private data class Message(val id: String, val block: String)

    /** Telegram önizlemesindeki tüm mesaj bloklarını en yeniden eskiye doğru okur. */
    private suspend fun readMessages(channelUrl: String): Pair<String, List<Message>> =
        withContext(Dispatchers.IO) {
            val channel = normalize(channelUrl)
            val base = "https://t.me/s/$channel"
            val messages = LinkedHashMap<String, Message>()
            var before: String? = null
            var page = 0

            while (page < MAX_PAGES) {
                val address = if (before == null) base else "$base?before=$before"
                val blocks = messageBlock.split(get(address)).drop(1)
                if (blocks.isEmpty()) break

                var minId = Long.MAX_VALUE
                var newMessages = 0
                blocks.forEach { block ->
                    val id = postRegex.find(block)?.groupValues?.get(1)
                        ?.substringAfterLast('/') ?: return@forEach
                    val numericId = id.toLongOrNull() ?: return@forEach
                    if (messages.containsKey(id)) return@forEach
                    newMessages++
                    minId = minOf(minId, numericId)
                    messages[id] = Message(id, block)
                }

                if (newMessages == 0 || minId <= 1L) break
                before = minId.toString()
                page++
            }
            channel to messages.values.toList()
        }

    suspend fun search(
        context: Context,
        channelUrl: String
    ): Result<List<DiscoveredSource>> = runCatching {
        val (channel, messages) = readMessages(channelUrl)
        val found = LinkedHashMap<String, DiscoveredSource>()
        messages.forEach { message -> parseBlock(context, channel, message.block, found) }

        if (found.isEmpty()) {
            throw IllegalStateException(
                "Bu kanalda M3U linki bulunamadı. Kanalın herkese açık olduğundan ve " +
                    "mesajlarda .m3u/.m3u8 adresi ya da type=m3u bağlantısı paylaşıldığından emin olun."
            )
        }
        found.values.toList()
    }

    /** Kanalda paylaşılan ve adı TvListesi ile başlayan APK güncellemelerini bulur. */
    suspend fun findApkUpdates(channelUrl: String): Result<List<ApkUpdate>> = runCatching {
        val (channel, messages) = readMessages(channelUrl)
        val found = LinkedHashMap<String, ApkUpdate>()
        messages.forEach { message ->
            parseApkBlock(channel, message, found)
        }
        found.values.sortedByDescending { it.id.toLongOrNull() ?: 0L }
    }

    private fun parseApkBlock(
        channel: String,
        message: Message,
        found: MutableMap<String, ApkUpdate>
    ) {
        val block = message.block
        val candidates = LinkedHashMap<String, ApkUpdate>()

        // Mesaj metnindeki ya da bağlantılardaki doğrudan .apk adresleri.
        val text = textRegex.findAll(block)
            .map { stripTags(it.groupValues[1]) }
            .joinToString("\n")
        val values = buildList {
            add(text)
            hrefRegex.findAll(block).forEach { add(unescapeHtml(it.groupValues[1])) }
        }
        values.forEach { value ->
            apkUrl.findAll(value).forEach { match ->
                val url = clean(match.value)
                val fileName = runCatching { Uri.parse(url).lastPathSegment }.getOrNull().orEmpty()
                if (!updatePrefix.containsMatchIn(fileName)) return@forEach
                val host = runCatching { Uri.parse(url).host }.getOrNull().orEmpty()
                val isTelegram = host.contains("t.me", true) || host.contains("telegram", true)
                candidates[url] = ApkUpdate(
                    id = message.id,
                    fileName = fileName,
                    url = url,
                    messageUrl = if (isTelegram) url else "https://t.me/$channel/${message.id}",
                    version = versionInName.find(fileName)?.groupValues?.get(1),
                    directDownload = !isTelegram
                )
            }
        }

        // Google Drive paylaşım bağlantıları mesaj metninde .apk uzantısı olmadan paylaşılabilir.
        values.forEach { value ->
            driveUrl.findAll(value).forEach { match ->
                val url = clean(match.value)
                val name = (driveApkName.find(text)?.value ?: driveApkName.find(value)?.value
                    ?: "TvListesi-guncelleme.apk").substringAfterLast('/')
                candidates[url] = ApkUpdate(
                    id = message.id,
                    fileName = name,
                    url = url,
                    messageUrl = "https://t.me/$channel/${message.id}",
                    version = versionInName.find(name)?.groupValues?.get(1),
                    directDownload = true
                )
            }
        }

        // Telegram dosya ekleri önizlemede yalnızca dosya adı ve mesaj bağlantısı olarak görünür.
        documentTitleRegex.findAll(block).forEach { titleMatch ->
            val fileName = stripTags(titleMatch.groupValues[1]).trim()
            if (!fileName.endsWith(".apk", true) || !updatePrefix.containsMatchIn(fileName)) {
                return@forEach
            }
            val messageUrl = documentHrefRegex.find(block)?.groupValues?.get(1)
                ?.let { unescapeHtml(it) }
                ?.takeIf { it.startsWith("https://t.me/", true) || it.startsWith("http://t.me/", true) }
                ?: "https://t.me/$channel/${message.id}"
            val size = documentSizeRegex.find(block)?.groupValues?.get(1)
                ?.let { stripTags(it).trim() }?.takeIf { it.isNotBlank() }
            candidates[messageUrl] = ApkUpdate(
                id = message.id,
                fileName = fileName,
                url = messageUrl,
                messageUrl = messageUrl,
                version = versionInName.find(fileName)?.groupValues?.get(1),
                sizeText = size,
                directDownload = false
            )
        }

        candidates.values.forEach { update -> found.putIfAbsent(update.url, update) }
    }

    private fun parseBlock(
        context: Context,
        channel: String,
        block: String,
        found: MutableMap<String, DiscoveredSource>
    ) {
        val post = postRegex.find(block)?.groupValues?.get(1) ?: return
        val id = post.substringAfterLast('/')

        val date = dateRegex.find(block)?.groupValues?.get(1)
        val text = textRegex.findAll(block)
            .map { stripTags(it.groupValues[1]) }
            .joinToString("\n")
            .trim()

        if (text.startsWith("#EXTM3U", true) || text.contains("#EXTINF", true)) {
            if (PlaylistRepository.saveTelegramList(context, id, text)) {
                val localUrl = PlaylistRepository.telegramLocalUrl(id)
                val localKey = urlKey(localUrl)
                if (localKey !in found) {
                    found[localKey] = DiscoveredSource(
                        repoFullName = "t.me/$channel · mesaj #$id",
                        repoUrl = "https://t.me/$channel/$id",
                        title = "mesaj_$id.m3u",
                        rawUrl = localUrl,
                        filePath = "Mesaj #$id · M3U metni",
                        description = text.take(140).replace('\n', ' ')
                    )
                }
            }
        }

        val urls = LinkedHashSet<String>()
        val direct = LinkedHashSet<String>()
        collectUrls(text, urls)
        collectDirect(text, direct)
        hrefRegex.findAll(block).forEach { match ->
            val href = unescapeHtml(match.groupValues[1])
            collectUrls(href, urls)
            collectDirect(href, direct)
            redirectParam.findAll(href).forEach { param ->
                val decoded = runCatching { Uri.decode(param.groupValues[1]) }.getOrDefault("")
                collectUrls(decoded, urls)
                collectDirect(decoded, direct)
            }
        }
        val listMap = LinkedHashMap<String, String>()
        val singleMap = LinkedHashMap<String, String>()

        urls.forEach { url ->
            val key = urlKey(url)
            // Doğrudan oynatılabilen yayın adresleri (.m3u8, /stream/ vb.)
            // liste kaynağı değil tek kanal olarak eklenir.
            if (PlaylistParser.directChannel(url) != null) {
                singleMap.putIfAbsent(key, url)
            } else {
                listMap.putIfAbsent(key, url)
            }
        }
        direct.forEach { url ->
            val key = urlKey(url)
            if (key !in listMap) singleMap.putIfAbsent(key, url)
        }

        listMap.forEach { (key, url) ->
            if (key !in found) {
                val userName = userNameParam.find(url)?.groupValues?.get(1)
                    ?.takeIf { it.isNotBlank() }
                val fileName = runCatching { Uri.parse(url).lastPathSegment }.getOrNull()
                    ?.takeIf { it.isNotBlank() } ?: "liste.m3u"
                val label = if (userName == null) fileName else "$fileName · $userName"
                found[key] = DiscoveredSource(
                    repoFullName = "t.me/$channel · mesaj #$id",
                    repoUrl = "https://t.me/$channel/$id",
                    title = label,
                    rawUrl = url,
                    filePath = "Mesaj #$id · $label",
                    description = text.take(140).ifBlank { null }?.replace('\n', ' ')
                )
            }
        }

        singleMap.forEach { (key, url) ->
            val uri = runCatching { Uri.parse(url) }.getOrNull()
            val host = uri?.host.orEmpty()
            if (host.isEmpty() || nonStreamHosts.any { host.contains(it, true) }) return@forEach
            if (key in found) return@forEach

            val lastPart = uri?.lastPathSegment?.takeIf { it.isNotBlank() } ?: host
            found[key] = DiscoveredSource(
                repoFullName = "t.me/$channel · mesaj #$id",
                repoUrl = "https://t.me/$channel/$id",
                title = "$lastPart · $host",
                rawUrl = url,
                filePath = "Mesaj #$id · Tek kanal",
                description = text.take(140).ifBlank { "Tek kanal yayın adresi" }
                    ?.replace('\n', ' '),
                singleStream = true
            )
        }
    }

    private fun collectDirect(value: String, target: MutableSet<String>) {
        directStreamPatterns.forEach { pattern ->
            pattern.findAll(value).forEach { target += clean(it.value) }
        }
    }

    internal fun collectUrls(value: String, target: MutableSet<String>) {
        m3uUrl.findAll(value).forEach { target += clean(it.value) }
        relativeM3uUrl.findAll(value).forEach { target += "https:" + clean(it.value) }
        paramM3uUrl.findAll(value).forEach { target += clean(it.value) }
        relativeParamM3uUrl.findAll(value).forEach { target += "https:" + clean(it.value) }
        paramMediaUrl.findAll(value).forEach { target += clean(it.value) }
        relativeParamMediaUrl.findAll(value).forEach { target += "https:" + clean(it.value) }
    }

    private fun clean(url: String): String = url
        .trimEnd('.', ',', ';', ')', ']', '}', '!', '»', '"')
        .replace("&amp;", "&")

    /** Aynı adresin farklı biçimlerini tek bir kayıt olarak saymak için karşılaştırma anahtarı. */
    private fun urlKey(url: String): String = clean(url)
        .lowercase()
        .trimEnd('/')

    private fun stripTags(value: String): String = unescapeHtml(
        brRegex.replace(tagRegex.replace(value, " "), "\n")
    ).replace(Regex("[ \\t]{2,}"), " ").trim()

    private fun unescapeHtml(value: String): String = value
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&apos;", "'")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&nbsp;", " ")
        .replace(Regex("""&#(\d+);""")) {
            it.groupValues[1].toIntOrNull()?.toChar()?.toString().orEmpty()
        }
        .replace("&amp;", "&")

    private fun normalize(input: String): String {
        var value = input.trim()
        if (value.startsWith("@")) value = value.substring(1)
        value = value.removePrefix("https://").removePrefix("http://").removePrefix("www.")
        if (value.startsWith("t.me/")) value = value.removePrefix("t.me/")
        if (value.startsWith("s/")) value = value.removePrefix("s/")
        if (value.startsWith("@")) value = value.substring(1)
        value = value.substringBefore('/').substringBefore('?').trim().trimEnd('/')

        require(value.matches(Regex("[A-Za-z0-9_]{4,32}"))) {
            "Geçerli bir Telegram kanalı adresi girin (örn: https://t.me/s/TvListesi)"
        }
        return value
    }

    private fun get(address: String): String {
        val connection = (URL(address).openConnection() as HttpURLConnection).apply {
            connectTimeout = 12_000
            readTimeout = 15_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Accept", "text/html,application/xhtml+xml")
        }
        try {
            val code = connection.responseCode
            if (code !in 200..299) {
                throw IllegalStateException("Telegram sayfası açılamadı: HTTP $code")
            }
            val reader = connection.inputStream?.bufferedReader(Charsets.UTF_8)
                ?: throw IllegalStateException("Telegram sayfası boş döndü")
            val result = StringBuilder()
            val buffer = CharArray(8192)
            var total = 0
            while (true) {
                val read = reader.read(buffer)
                if (read < 0) break
                total += read
                if (total > MAX_HTML) throw IllegalStateException("Telegram sayfası çok büyük")
                result.append(buffer, 0, read)
            }
            return result.toString()
        } finally {
            connection.disconnect()
        }
    }
}
