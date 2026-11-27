package com.example.tvlistesi.data

import android.content.Context
import com.example.tvlistesi.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Sunucudan yönetilen kanal listesinin tek kaynağı.
 *
 * - Liste sunucudan indirilir, normalize edilip önbelleğe yazılır.
 * - Ağ yoksa son başarılı liste kullanılır.
 * - Aynı liste MainActivity ve PlayerActivity tarafından paylaşılır.
 */
object PlaylistRepository {

    private const val PREFS = "tv_listesi_ayarlar"
    private const val KEY_SERVER = "sunucu_adresi"
    private const val KEY_AUTO = "otomatik_guncelle"
    private const val KEY_UPDATED = "son_guncelleme"
    private const val KEY_SAVED_LISTS = "kayitli_listeler"
    private const val KEY_EXTERNAL_PLAYER = "harici_oynatici"
    private const val KEY_SCREEN_MODE = "goruntu_modu"
    private const val CACHE_FILE = "kanal_listesi_onbellek.json"
    private const val FILTRELI_LISTE_DOSYASI = "filtrelenmis_liste.m3u"

    /** Link kontrolü sonrası oluşturulan, cihazda saklanan listenin adresi. */
    const val FILTRELI_LISTE_URL = "local://filtrelenmis"

    /** Telegram mesajına gömülü M3U metninin yerel adres öneki. */
    const val TELEGRAM_LISTE_ON_EK = "local://telegram/"
    private const val TELEGRAM_LISTE_DOSYASI = "telegram_liste_%s.m3u"

    /** Telegram'da bulunan tek kanal yayın adresinin yerel liste adresi. */
    const val TEK_KANAL_ON_EK = "local://tek/"
    private const val TEK_KANAL_DOSYASI = "tek_kanal_%s.m3u"

    /** Kaydedilebilecek en fazla liste sayısı. */
    const val MAX_SAVED_LISTS = 10

    /** Uygulamayla gelen sabit listenin güncel kaynağı. */
    const val YERLESIK_LISTE_URL =
        "https://raw.githubusercontent.com/ailantus/TeVe/refs/heads/main/data/playlist.m3u"

    /** Önceki sürümlerin yerleşik liste adresi; yeni adrese geçişte kullanılır. */
    private const val ESKI_YERLESIK_LISTE_URL =
        "https://raw.githubusercontent.com/YosemiteSam6/turkiye-iptv-m3u-m3u8/main/top50.m3u"

    /** APK içine gömülü listenin yedeği (ağ yokken de çalışır). */
    private const val YERLESIK_LISTE_ASSET = "yerlesik_liste.m3u"

    /** Emülatörden bilgisayardaki örnek sunucuya erişim adresi. */
    const val VARSAYILAN_SUNUCU = "http://10.0.2.2:8000/channels.json"

    @Volatile
    var channels: List<Channel> = emptyList()
        private set

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun serverUrl(context: Context): String =
        prefs(context).getString(KEY_SERVER, YERLESIK_LISTE_URL) ?: YERLESIK_LISTE_URL

    fun setServerUrl(context: Context, url: String) {
        prefs(context).edit().putString(KEY_SERVER, url).apply()
    }

    /** Uygulamayla gelen, listeden silinemeyen yerleşik liste. */
    fun builtInList(context: Context): SavedList =
        SavedList(context.getString(R.string.yerlesik_liste), YERLESIK_LISTE_URL)

    fun isBuiltIn(url: String): Boolean = url == YERLESIK_LISTE_URL

    fun isFiltered(url: String): Boolean = url == FILTRELI_LISTE_URL

    fun telegramLocalUrl(messageId: String): String = TELEGRAM_LISTE_ON_EK + messageId

    private fun telegramFileName(messageId: String): String =
        TELEGRAM_LISTE_DOSYASI.format(messageId.filter { it.isDigit() }.take(18))

    /** Telegram mesajındaki M3U metnini cihaza yazar; başarılıysa true döner. */
    fun saveTelegramList(context: Context, messageId: String, body: String): Boolean = runCatching {
        val safeId = messageId.filter { it.isDigit() }
        if (safeId.isEmpty() || body.isBlank()) return@runCatching false
        val parsed = PlaylistParser.parse(body)
        if (parsed.isEmpty()) return@runCatching false
        File(context.filesDir, telegramFileName(safeId)).writeText(body)
        true
    }.getOrDefault(false)

    /**
     * Tek kanal yayın adresini, tek kanallık yerel bir M3U listesine dönüştürür.
     * Böylece canlı `.ts` akışı doğrudan liste adresi olarak kullanılmaz.
     */
    fun saveSingleStream(context: Context, name: String, url: String): String {
        val id = url.hashCode().toUInt().toString()
        val channel = Channel(
            id = id,
            name = name.ifBlank { "Tek kanal" },
            url = url,
            group = "Telegram"
        )
        File(context.filesDir, TEK_KANAL_DOSYASI.format(id))
            .writeText(PlaylistParser.toM3u(listOf(channel)))
        return TEK_KANAL_ON_EK + id
    }

    /**
     * Doğrudan yayın adresini (örn. .m3u8) hiç değiştirmeden mevcut kanallara
     * ekler. Adres aynen saklanır; yerel M3U'ya çevirme yapılmaz.
     */
    fun addStream(context: Context, url: String, title: String? = null): Channel? {
        val base = PlaylistParser.directChannel(url) ?: return null
        val name = title?.ifBlank { null } ?: nextDirectChannelName(context)
        val channel = base.copy(name = name)
        val updated = if (channels.none { it.url == channel.url }) {
            channels + channel
        } else {
            // Aynı adres daha önce "index" gibi bir adla eklenmişse adını düzelt.
            channels.map { if (it.url == channel.url) channel else it }
        }
        if (updated != channels) {
            channels = updated
            writeCache(context, channels)
        }
        return channel
    }

    /** Eklenen doğrudan yayınlar için kullanılmamış ilk "Kanal N" adını üretir. */
    private fun nextDirectChannelName(context: Context): String {
        val used = channels.map { it.name.trim().lowercase() }.toHashSet()
        var index = 1
        while (context.getString(R.string.kanal_adi, index).lowercase() in used) index++
        return context.getString(R.string.kanal_adi, index)
    }

    /**
     * Tek kanal yayınını mevcut listenin yerine yükler.
     * Önceki kanallar önbellekten silinir; yalnızca bu adres kalır.
     */
    fun replaceWithStream(context: Context, url: String): Channel? {
        val base = PlaylistParser.directChannel(url) ?: return null
        val channel = base.copy(name = context.getString(R.string.kanal_adi, 1))
        channels = listOf(channel)
        writeCache(context, channels)
        return channel
    }

    /** Kanal listesini önbelleğe atomik olarak yazar. */
    private fun writeCache(context: Context, list: List<Channel>) {
        val cacheFile = File(context.filesDir, CACHE_FILE)
        val tempFile = File(context.filesDir, "$CACHE_FILE.tmp")
        val json = PlaylistParser.toJson(list)
        tempFile.writeText(json)
        if (!tempFile.renameTo(cacheFile)) {
            cacheFile.writeText(json)
            tempFile.delete()
        }
        prefs(context).edit().putLong(KEY_UPDATED, System.currentTimeMillis()).apply()
    }

    /** Cihazda saklanan yerel listeleri okur; HTTP adresleri için null döner. */
    fun readLocalList(context: Context, address: String): String? {
        val file = when {
            address == FILTRELI_LISTE_URL ->
                File(context.filesDir, FILTRELI_LISTE_DOSYASI)

            address.startsWith(TELEGRAM_LISTE_ON_EK) ->
                File(context.filesDir, telegramFileName(address.removePrefix(TELEGRAM_LISTE_ON_EK)))

            address.startsWith(TEK_KANAL_ON_EK) ->
                File(context.filesDir, TEK_KANAL_DOSYASI.format(address.removePrefix(TEK_KANAL_ON_EK)))

            else -> return null
        }
        if (!file.exists()) {
            throw IllegalStateException(
                when {
                    address == FILTRELI_LISTE_URL -> "Filtrelenmiş liste bulunamadı"
                    address.startsWith(TEK_KANAL_ON_EK) -> "Tek kanal listesi bulunamadı"
                    else -> "Telegram listesi bulunamadı"
                }
            )
        }
        return file.readText()
    }

    /**
     * Eski sürümlerin yalnızca emülatörde çalışan örnek adresini ve eski
     * yerleşik liste adresini yeni yerleşik listeye geçirir. Kullanıcının
     * kendi girdiği adres korunur.
     */
    fun migrateDemoServer(context: Context) {
        val stored = prefs(context).getString(KEY_SERVER, null)
        if (stored == null || stored == VARSAYILAN_SUNUCU || stored == ESKI_YERLESIK_LISTE_URL) {
            prefs(context).edit().putString(KEY_SERVER, YERLESIK_LISTE_URL).apply()
            // Eski listenin önbelleği yeni listenin önüne geçmesin.
            runCatching { File(context.filesDir, CACHE_FILE).delete() }
        }
    }

    /** APK içine gömülü listenin ham metni. */
    private fun readBuiltIn(context: Context): String? = runCatching {
        context.assets.open(YERLESIK_LISTE_ASSET)
            .bufferedReader(Charsets.UTF_8)
            .use { it.readText() }
    }.getOrNull()

    fun autoRefresh(context: Context): Boolean =
        prefs(context).getBoolean(KEY_AUTO, true)

    fun setAutoRefresh(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_AUTO, enabled).apply()
    }

    fun lastUpdated(context: Context): Long =
        prefs(context).getLong(KEY_UPDATED, 0L)

    // ------------------------------------------------------------ görüntü modu

    /**
     * Oynatıcıdaki görüntü modu (AspectRatioFrameLayout sabiti).
     * Varsayılan: sığdır (0).
     */
    fun screenMode(context: Context): Int =
        prefs(context).getInt(KEY_SCREEN_MODE, 0)

    fun setScreenMode(context: Context, mode: Int) {
        prefs(context).edit().putInt(KEY_SCREEN_MODE, mode).apply()
    }

    // ------------------------------------------------------------ kayıtlı listeler

    /** Kaydedilmiş listeleri (en fazla [MAX_SAVED_LISTS] adet) okur. */
    fun savedLists(context: Context): List<SavedList> {
        val raw = prefs(context).getString(KEY_SAVED_LISTS, null) ?: return emptyList()

        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { index ->
                val item = array.optJSONObject(index) ?: return@mapNotNull null
                val url = item.optString("url").trim()
                if (url.isEmpty()) {
                    null
                } else {
                    SavedList(
                        name = item.optString("name").trim().ifEmpty { url },
                        url = url
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    /**
     * Listeyi kaydeder. Aynı adres zaten kayıtlıysa adı güncellenir.
     * 10 liste sınırı doluysa ve adres yeni ise false döner.
     */
    fun saveList(context: Context, name: String, url: String): Boolean {
        val lists = savedLists(context).toMutableList()
        val existing = lists.indexOfFirst { it.url == url }

        if (existing >= 0) {
            lists[existing] = SavedList(name, url)
        } else {
            if (lists.size >= MAX_SAVED_LISTS) return false
            lists += SavedList(name, url)
        }

        persistSavedLists(context, lists)
        return true
    }

    fun deleteList(context: Context, url: String) {
        persistSavedLists(context, savedLists(context).filterNot { it.url == url })
    }

    private fun persistSavedLists(context: Context, lists: List<SavedList>) {
        val array = JSONArray()
        lists.forEach { saved ->
            array.put(
                JSONObject()
                    .put("name", saved.name)
                    .put("url", saved.url)
            )
        }
        prefs(context).edit().putString(KEY_SAVED_LISTS, array.toString()).apply()
    }

    // ------------------------------------------------------------ harici oynatıcı

    fun externalPlayer(context: Context): Boolean =
        prefs(context).getBoolean(KEY_EXTERNAL_PLAYER, false)

    fun setExternalPlayer(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_EXTERNAL_PLAYER, enabled).apply()
    }

    /** Disk önbelleğini okur; ağ isteği yapmaz. */
    suspend fun loadCache(context: Context): List<Channel> = withContext(Dispatchers.IO) {
        if (channels.isNotEmpty()) return@withContext channels

        val file = File(context.filesDir, CACHE_FILE)
        val cachedText = if (file.exists()) {
            runCatching { file.readText() }.getOrNull()
        } else {
            null
        }

        var parsed = cachedText
            ?.let { runCatching { PlaylistParser.parse(it) }.getOrDefault(emptyList()) }
            .orEmpty()

        // Yerleşik liste için önbellek yoksa veya bozuksa APK içindeki kopyayı kullan.
        if (parsed.isEmpty() && isBuiltIn(serverUrl(context))) {
            parsed = readBuiltIn(context)
                ?.let { runCatching { PlaylistParser.parse(it) }.getOrDefault(emptyList()) }
                .orEmpty()
        }

        if (parsed.isNotEmpty()) channels = parsed
        parsed
    }

    /**
     * Listeyi verilen adresten indirir.
     * Başarılıysa kanal sayısını döner.
     */
    suspend fun refresh(
        context: Context,
        url: String = serverUrl(context)
    ): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            val (body, finalUrl) = runCatching { download(context, url) }.getOrElse { error ->
                // Yerleşik liste her koşulda çalışsın: ağ yoksa APK içindeki kopya.
                if (isBuiltIn(url)) {
                    (readBuiltIn(context) ?: throw error) to url
                } else {
                    throw error
                }
            }
            // HLS master listelerindeki göreli adresler bu URL'e göre çözümlenir.
            // Adres bizzat bir yayın (ör. .../stream/117/index.m3u8) ise ve
            // sunucu playlist metni döndürmediyse tek kanal olarak aç.
            val parsed = PlaylistParser.parse(body, finalUrl).ifEmpty {
                PlaylistParser.directChannel(finalUrl)?.let(::listOf).orEmpty()
            }
            if (parsed.isEmpty()) {
                throw IllegalStateException("Liste boş veya biçimi tanınmadı")
            }

            channels = parsed
            writeCache(context, parsed)
            parsed.size
        }
    }

    /**
     * Çalışmayan kanallar ayıklanmış listeyi cihaza yazar ve etkin liste yapar.
     * Ardından normal yenileme akışıyla önbellek ve zaman damgası güncellenir.
     */
    suspend fun saveFilteredList(
        context: Context,
        channelsToKeep: List<Channel>
    ): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            if (channelsToKeep.isEmpty()) {
                throw IllegalStateException("Kaydedilecek kanal kalmadı")
            }
            val text = PlaylistParser.toM3u(channelsToKeep)
            val file = File(context.filesDir, FILTRELI_LISTE_DOSYASI)
            val temp = File(context.filesDir, "$FILTRELI_LISTE_DOSYASI.tmp")
            temp.writeText(text)
            if (!temp.renameTo(file)) {
                file.writeText(text)
                temp.delete()
            }
            prefs(context).edit().putString(KEY_SERVER, FILTRELI_LISTE_URL).apply()
            refresh(context, FILTRELI_LISTE_URL).getOrThrow()
        }
    }

    /** İçeriği ve yönlendirmeler sonrası son adresi döndürür. */
    private fun download(context: Context, address: String): Pair<String, String> {
        // Cihaz üzerinde saklanan listeler bir HTTP adresi değildir.
        readLocalList(context, address)?.let { return it to address }

        var current = address

        repeat(5) {
            val connection = (URL(current).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 20_000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "TvListesi/1.0 (Android)")
                setRequestProperty(
                    "Accept",
                    "application/json, application/x-mpegURL, audio/mpegurl, text/plain, */*"
                )
                setRequestProperty("Cache-Control", "no-cache")
            }

            try {
                val code = connection.responseCode
                if (code in 300..399) {
                    val location = connection.getHeaderField("Location")
                        ?: throw IllegalStateException("Yönlendirme adresi yok")
                    current = URL(URL(current), location).toString()
                    return@repeat
                }
                if (code !in 200..299) {
                    throw IllegalStateException("Sunucu yanıtı: HTTP $code")
                }
                val text = connection.inputStream
                    .bufferedReader(Charsets.UTF_8)
                    .use { it.readText() }
                return text to current
            } finally {
                connection.disconnect()
            }
        }

        throw IllegalStateException("Çok fazla yönlendirme")
    }
}
