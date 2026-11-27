package com.example.tvlistesi.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import android.widget.ImageView
import com.example.tvlistesi.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/**
 * Kanal logolarını küçük bir bellek önbelleğiyle yükler.
 * Harici görsel kütüphanesi gerektirmemesi için bilinçli olarak sade tutuldu.
 */
object LogoLoader {

    private const val MAX_CACHE_KB = 16 * 1024

    private val cache = object : LruCache<String, Bitmap>(MAX_CACHE_KB) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun load(view: ImageView, url: String?, fallback: Int = R.drawable.ic_tv) {
        view.tag = url
        view.setImageResource(fallback)

        if (url.isNullOrBlank()) return

        cache.get(url)?.let {
            view.setImageBitmap(it)
            return
        }

        scope.launch {
            val bitmap = download(url) ?: return@launch
            cache.put(url, bitmap)
            withContext(Dispatchers.Main) {
                if (view.tag == url) view.setImageBitmap(bitmap)
            }
        }
    }

    private fun download(url: String): Bitmap? = runCatching {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8_000
            readTimeout = 8_000
            setRequestProperty("User-Agent", "TvListesi/1.0 (Android)")
        }
        try {
            connection.inputStream.use { BitmapFactory.decodeStream(it) }
        } finally {
            connection.disconnect()
        }
    }.getOrNull()
}
