package com.example.tvlistesi.data

import android.app.Activity
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import com.example.tvlistesi.R
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import org.json.JSONObject

/** Telegram kanalında bulunan bir APK güncellemesi. */
data class ApkUpdate(
    val id: String,
    val fileName: String,
    val url: String,
    val messageUrl: String,
    val version: String? = null,
    val sizeText: String? = null,
    /** true: doğrudan .apk adresi; false: Telegram dosya eki. */
    val directDownload: Boolean = false
)

/** Sürümü doğrulanmış ve sürümü kurulu olandan daha yeni olan APK. */
data class PreparedUpdate(
    val update: ApkUpdate,
    val file: File,
    val versionName: String?,
    val versionCode: Long
)

/** Kurulu ya da indirilmiş APK'nın sürüm bilgileri. */
data class ApkMeta(
    val versionName: String?,
    val versionCode: Long,
    val signature: String?
)

/** APK güncellemesini uygulama içinde indirme, sürüm kontrolü ve kurma işlemleri. */
object ApkUpdater {

    private const val PREFS = "tv_listesi_guncelleme"
    private const val KEY_LAST_SEEN = "son_gorulen_apk"
    private const val KEY_LAST_CHECK = "son_guncelleme_kontrolu"
    private const val CHECK_INTERVAL_MS = 6 * 60 * 60 * 1000L
    private const val APK_DIR = "apk"
    private const val APK_FILE = "guncelleme.apk"
    private const val MAX_APK_SIZE = 100L * 1024 * 1024
    private const val MEDIA_PROXY = "https://tg.i-c-a.su/media/"
    private const val GITHUB_REPO = "ailantus/TvListesi"
    private const val RELEASES_URL = "https://api.github.com/repos/$GITHUB_REPO/releases/latest"

    /** GitHub'daki son sürümün APK varlığını bulur. */
    suspend fun fixedUpdate(): Result<ApkUpdate> = withContext(Dispatchers.IO) {
        runCatching {
            val root = JSONObject(fetchReleaseText())
            val assets = root.optJSONArray("assets")
            var apkUrl: String? = null
            var fileName = "TvListesi.apk"
            var sizeText: String? = null
            if (assets != null) {
                for (i in 0 until assets.length()) {
                    val asset = assets.optJSONObject(i) ?: continue
                    val name = asset.optString("name", "")
                    if (!name.endsWith(".apk", ignoreCase = true)) continue
                    val url = asset.optString("browser_download_url", "")
                    if (url.isBlank()) continue
                    apkUrl = url
                    fileName = name
                    val size = asset.optLong("size", 0L)
                    if (size > 0) {
                        sizeText = "%.1f MB".format(size / (1024.0 * 1024.0))
                    }
                    break
                }
            }
            val url = apkUrl
                ?: throw IllegalStateException("GitHub sürümünde .apk dosyası bulunamadı")
            ApkUpdate(
                id = "github-guncelleme",
                fileName = fileName,
                url = url,
                messageUrl = url,
                version = root.optString("tag_name", "").ifBlank { null },
                sizeText = sizeText,
                directDownload = true
            )
        }
    }

    /** GitHub Releases API'den son sürüm bilgisini okur. */
    private fun fetchReleaseText(): String {
        val connection = (URL(RELEASES_URL).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "TvListesi/2.0")
            setRequestProperty("Accept", "application/vnd.github+json")
        }
        try {
            val code = connection.responseCode
            if (code !in 200..299) throw IllegalStateException("GitHub HTTP $code")
            val bytes = connection.inputStream.use { it.readBytes() }
            if (bytes.size > 1024 * 1024) throw IllegalStateException("Sürüm bilgisi çok büyük")
            return String(bytes, Charsets.UTF_8)
        } finally {
            connection.disconnect()
        }
    }

    /** Aynı sürümün tekrar bildirilmemesi için kullanılan anahtar. */
    fun seenKey(prepared: PreparedUpdate): String = "surum-${prepared.versionCode}"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun lastSeen(context: Context): String? =
        prefs(context).getString(KEY_LAST_SEEN, null)

    fun markSeen(context: Context, id: String) {
        prefs(context).edit().putString(KEY_LAST_SEEN, id).apply()
    }

    /** Otomatik kontrolü açılışta her seferinde yapmamak için kullanılır. */
    fun recentlyChecked(context: Context): Boolean =
        System.currentTimeMillis() - prefs(context).getLong(KEY_LAST_CHECK, 0L) < CHECK_INTERVAL_MS

    fun markChecked(context: Context) {
        prefs(context).edit().putLong(KEY_LAST_CHECK, System.currentTimeMillis()).apply()
    }

    private fun normalizeTag(tag: String?): String =
        tag?.trim()?.removePrefix("v")?.removePrefix("V")?.trim().orEmpty()

    /** Sürüm etiketinden (örn. v2.0.18) tekrar bildirim anahtarı üretir. */
    fun releaseSeenKey(tag: String?): String? =
        normalizeTag(tag).takeIf { it.isNotBlank() }?.let { "surum-$it" }

    /**
     * APK'yı indirmeden önce release etiketini kurulu sürümle karşılaştırır.
     * Etiket okunamazsa eski davranışa dönülür ve APK indirilir.
     */
    fun isReleaseNewer(tag: String?, current: ApkMeta): Boolean {
        val normalized = normalizeTag(tag)
        if (normalized.isEmpty()) return true
        val parts = normalized.split('.')
        if (parts.size == 1) {
            val code = parts[0].toLongOrNull() ?: return true
            return code > current.versionCode
        }
        return isNewer(normalized, current.versionName.orEmpty())
    }

    /** Dosya adındaki sürüm, kurulu sürümden yeni mi? */
    fun isNewer(candidate: String?, current: String): Boolean {
        if (candidate.isNullOrBlank()) return false
        val a = candidate.split('.').mapNotNull { it.toIntOrNull() }
        val b = current.split('.').mapNotNull { it.toIntOrNull() }
        if (a.isEmpty() || b.isEmpty()) return false
        for (i in 0 until maxOf(a.size, b.size)) {
            val av = a.getOrElse(i) { 0 }
            val bv = b.getOrElse(i) { 0 }
            if (av != bv) return av > bv
        }
        return false
    }

    private fun versionCodeOf(info: PackageInfo): Long =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            info.versionCode.toLong()
        }

    private fun signatureOf(info: PackageInfo): String? = runCatching {
        val raw = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val signing = info.signingInfo ?: return@runCatching null
            val certificates = if (signing.hasMultipleSigners()) {
                signing.apkContentsSigners
            } else {
                signing.signingCertificateHistory
            }
            certificates.firstOrNull()?.toCharsString()
        } else {
            @Suppress("DEPRECATION")
            info.signatures?.firstOrNull()?.toCharsString()
        }
        raw?.let { value ->
            MessageDigest.getInstance("SHA-256")
                .digest(value.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        }
    }.getOrNull()

    /** Kurulu uygulamanın sürümü ve imzası. */
    fun currentMeta(context: Context): Result<ApkMeta> = runCatching {
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            @Suppress("DEPRECATION")
            PackageManager.GET_SIGNATURES
        }
        val info = context.packageManager.getPackageInfo(context.packageName, flags)
        ApkMeta(info.versionName, versionCodeOf(info), signatureOf(info))
    }

    /** İndirilen dosyanın gerçekten bu uygulamanın yeni bir APK'sı olup olmadığını okur. */
    fun inspectApk(context: Context, file: File): Result<ApkMeta> = runCatching {
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            @Suppress("DEPRECATION")
            PackageManager.GET_SIGNATURES
        }
        val info = context.packageManager.getPackageArchiveInfo(file.absolutePath, flags)
            ?: throw IllegalStateException(context.getString(R.string.apk_surum_hatasi))
        ApkMeta(info.versionName, versionCodeOf(info), signatureOf(info))
    }

    fun canInstall(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
            context.packageManager.canRequestPackageInstalls()

    fun openUnknownSources(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        runCatching {
            context.startActivity(
                Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:${context.packageName}")
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    private fun telegramPackage(context: Context): String? = listOf(
        "org.telegram.messenger",
        "org.telegram.messenger.web",
        "org.telegram.messenger.xiaomi"
    ).firstOrNull { name ->
        runCatching { context.packageManager.getPackageInfo(name, 0) }.isSuccess
    }

    /**
     * APK'yı Telegram'a (yoksa paylaşım seçicisinden başka bir uygulamaya) gönderir.
     * FileProvider yalnızca filesDir/apk altını paylaşabildiği için dosya kopyalanır.
     */
    fun shareApk(context: Context, file: File, displayName: String? = null) {
        runCatching {
            if (!file.exists()) throw IllegalStateException("APK dosyası bulunamadı")
            val name = (displayName?.substringAfterLast('/') ?: file.name)
                .ifBlank { "TvListesi.apk" }
                .let { if (it.endsWith(".apk", true)) it else "$it.apk" }
            val dir = File(context.filesDir, APK_DIR).apply { mkdirs() }
            val shared = File(dir, name)
            if (file.absolutePath != shared.absolutePath) {
                file.copyTo(shared, overwrite = true)
            }

            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                shared
            )
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "application/vnd.android.package-archive"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, name.removeSuffix(".apk"))
                putExtra(Intent.EXTRA_TEXT, name)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                clipData = ClipData.newRawUri(name, uri)
            }
            telegramPackage(context)?.let(intent::setPackage)
            context.startActivity(
                Intent.createChooser(intent, context.getString(R.string.apk_paylas))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }.onFailure { error ->
            Toast.makeText(
                context,
                context.getString(R.string.apk_paylas_hata, error.message.orEmpty()),
                Toast.LENGTH_LONG
            ).show()
        }
    }

    /** Kurulu uygulamanın APK'sını Telegram'da paylaşır. */
    fun shareInstalledApk(context: Context) {
        val source = File(context.applicationInfo.sourceDir)
        val version = currentMeta(context).getOrNull()?.versionName.orEmpty()
        shareApk(context, source, "TvListesi-${version.ifBlank { "apk" }}.apk")
    }

    private fun telegramParts(update: ApkUpdate): Pair<String?, String?> {
        val uri = runCatching { Uri.parse(update.messageUrl) }.getOrNull()
            ?: return null to update.id
        if (uri.host?.contains("t.me", true) != true) return null to update.id
        val parts = uri.pathSegments.filter { it.isNotBlank() }
        return when {
            parts.size >= 3 && parts[0] == "c" -> parts[1] to parts[2]
            parts.size >= 2 -> parts[0] to parts[1]
            else -> null to update.id
        }
    }

    private fun driveFileId(url: String): String? {
        val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return null
        val host = uri.host.orEmpty()
        if (!host.equals("drive.google.com", true) &&
            !host.equals("drive.usercontent.google.com", true)
        ) return null
        uri.getQueryParameter("id")?.takeIf { it.isNotBlank() }?.let { return it }
        val segments = uri.pathSegments
        val index = segments.indexOfFirst { it.equals("d", true) }
        return if (index >= 0 && segments.size > index + 1) segments[index + 1] else null
    }

    /** Google Drive paylaşım bağlantısını doğrudan indirme adreslerine çevirir. */
    private fun driveDownloadUrls(url: String): List<String> {
        val id = driveFileId(url) ?: return listOf(url)
        return listOf(
            "https://drive.usercontent.google.com/download?id=$id&export=download&confirm=t",
            "https://drive.google.com/uc?export=download&id=$id&confirm=t",
            "https://drive.google.com/uc?export=download&id=$id"
        )
    }

    private fun readDriveConfirm(file: File): String? = runCatching {
        val html = FileInputStream(file).use { input ->
            val bytes = ByteArray(1024 * 1024)
            val read = input.read(bytes)
            if (read > 0) String(bytes, 0, read, Charsets.UTF_8) else ""
        }
        Regex("""name=["']confirm["'][^>]*value=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
            .find(html)?.groupValues?.get(1)
            ?: Regex("""[?&]confirm=([^&"'<>\s]+)""").find(html)?.groupValues?.get(1)
    }.getOrNull()

    /** İndirilecek aday adresler: doğrudan .apk/Drive bağlantısı veya Telegram medya proxy'si. */
    private fun downloadUrls(update: ApkUpdate): List<String> {
        val urls = LinkedHashSet<String>()
        if (update.directDownload) {
            urls += if (driveFileId(update.url) != null) {
                driveDownloadUrls(update.url)
            } else {
                listOf(update.url)
            }
        }
        val (channel, messageId) = telegramParts(update)
        if (!channel.isNullOrBlank() && !messageId.isNullOrBlank()) {
            urls += "$MEDIA_PROXY$channel/$messageId"
        }
        return urls.toList()
    }

    suspend fun download(context: Context, url: String, attempt: Int = 0): Result<File> =
        withContext(Dispatchers.IO) {
            runCatching {
                val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 20_000
                    readTimeout = 30_000
                    instanceFollowRedirects = true
                    setRequestProperty(
                        "User-Agent",
                        "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 " +
                            "(KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36 TvListesi/2.0"
                    )
                    setRequestProperty("Accept", "application/vnd.android.package-archive, application/octet-stream, */*")
                }
                try {
                    val code = connection.responseCode
                    if (code !in 200..299) throw IllegalStateException("HTTP $code")
                    val contentType = connection.contentType.orEmpty()
                    val dir = File(context.filesDir, APK_DIR).apply { mkdirs() }
                    val file = File(dir, APK_FILE)
                    if (file.exists()) file.delete()
                    connection.inputStream.use { input ->
                        FileOutputStream(file).use { output ->
                            val buffer = ByteArray(64 * 1024)
                            var total = 0L
                            while (true) {
                                val read = input.read(buffer)
                                if (read < 0) break
                                total += read
                                if (total > MAX_APK_SIZE) throw IllegalStateException("APK çok büyük")
                                output.write(buffer, 0, read)
                            }
                        }
                    }
                    val magic = runCatching {
                        FileInputStream(file).use { input ->
                            byteArrayOf(input.read().toByte(), input.read().toByte())
                        }
                    }.getOrDefault(byteArrayOf())
                    val isApk = magic.contentEquals(byteArrayOf(0x50, 0x4B))

                    // Drive büyük dosyalarda virüs taraması onayı ister; onay kodunu alıp tekrar dene.
                    if (!isApk && driveFileId(url) != null && attempt < 2) {
                        val confirm = readDriveConfirm(file)
                        if (!confirm.isNullOrBlank()) {
                            file.delete()
                            val retryUrl = if (url.contains("?")) {
                                "$url&confirm=$confirm"
                            } else {
                                "$url?confirm=$confirm"
                            }
                            return@runCatching download(context, retryUrl, attempt + 1).getOrThrow()
                        }
                    }

                    val downloadedSize = file.length()
                    if (downloadedSize < 100_000 || !isApk) {
                        file.delete()
                        throw IllegalStateException(
                            "Geçerli bir APK değil (${contentType.ifBlank { "içerik türü yok" }}, " +
                                "$downloadedSize bayt)"
                        )
                    }
                    file
                } finally {
                    connection.disconnect()
                }
            }
        }

    private suspend fun downloadFirst(context: Context, urls: List<String>): Result<File> {
        var lastError: Throwable = IllegalStateException("İndirme adresi bulunamadı")
        for (url in urls) {
            val result = download(context, url)
            if (result.isSuccess) return Result.success(result.getOrThrow())
            lastError = result.exceptionOrNull() ?: lastError
        }
        return Result.failure(lastError)
    }

    /**
     * APK'yı uygulama içinde indirir, sürümü ve imzayı doğrular.
     * Aynı ya da eski sürümse dosyayı siler ve null döner.
     */
    suspend fun prepare(
        context: Context,
        update: ApkUpdate,
        current: ApkMeta
    ): Result<PreparedUpdate?> = runCatching {
        val file = downloadFirst(context, downloadUrls(update)).getOrThrow()
        val meta = inspectApk(context, file).getOrThrow()

        if (meta.signature != null && current.signature != null && meta.signature != current.signature) {
            file.delete()
            throw IllegalStateException(context.getString(R.string.apk_imza_hatasi))
        }

        val newer = if (meta.versionCode > 0 && current.versionCode > 0) {
            meta.versionCode > current.versionCode
        } else {
            isNewer(meta.versionName, current.versionName.orEmpty())
        }

        if (!newer) {
            file.delete()
            markSeen(context, "surum-${meta.versionCode}")
            null
        } else {
            PreparedUpdate(
                update = update.copy(version = meta.versionName),
                file = file,
                versionName = meta.versionName,
                versionCode = meta.versionCode
            )
        }
    }

    fun installIntent(context: Context, file: File): Intent {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
        return Intent(Intent.ACTION_INSTALL_PACKAGE).apply {
            data = uri
            flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
            putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true)
            putExtra(Intent.EXTRA_RETURN_RESULT, true)
        }
    }

    /** Hazırlanmış APK'yı kurar. */
    fun installPrepared(
        activity: AppCompatActivity,
        prepared: PreparedUpdate,
        launcher: ActivityResultLauncher<Intent>
    ) {
        if (!canInstall(activity)) {
            openUnknownSources(activity)
            Toast.makeText(activity, R.string.bilinmeyen_kaynak_izin, Toast.LENGTH_LONG).show()
            return
        }
        runCatching { launcher.launch(installIntent(activity, prepared.file)) }
            .onFailure { error ->
                Toast.makeText(
                    activity,
                    activity.getString(R.string.kurulum_baslatilamadi, error.message.orEmpty()),
                    Toast.LENGTH_LONG
                ).show()
            }
    }

    /** Güncelleme uyarısını gösterir; istenirse indirilen APK'yı Telegram'da paylaşır. */
    fun prompt(
        activity: AppCompatActivity,
        update: ApkUpdate,
        onUpdate: () -> Unit,
        onShare: (() -> Unit)? = null
    ) {
        markSeen(activity, update.id)
        val detail = listOfNotNull(
            update.version?.let { "v$it" },
            update.sizeText
        ).joinToString(" · ")
        val builder = MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.guncelleme_var)
            .setMessage(
                activity.getString(
                    R.string.guncelleme_mesaj,
                    update.fileName,
                    if (detail.isBlank()) "" else "\n$detail"
                )
            )
            .setPositiveButton(R.string.guncelle) { _, _ -> onUpdate() }
            .setNegativeButton(R.string.sonra, null)
        if (onShare != null) {
            builder.setNeutralButton(R.string.telegramda_paylas) { _, _ -> onShare() }
        }
        builder.show()
    }

    fun installResultMessage(context: Context, resultCode: Int): String = when (resultCode) {
        Activity.RESULT_OK -> context.getString(R.string.guncelleme_kuruldu)
        else -> context.getString(R.string.guncelleme_kurulamadi)
    }
}
