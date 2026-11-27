package com.example.tvlistesi.data

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * Yayını cihazdaki başka bir oynatıcıya (VLC, MX Player, TV uygulaması vb.)
 * devreder. Uygun uygulama yoksa false döner.
 */
object ExternalPlayer {

    fun open(context: Context, channel: Channel): Boolean {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(Uri.parse(channel.url), mimeType(channel.url))
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        return try {
            context.startActivity(intent)
            true
        } catch (error: ActivityNotFoundException) {
            false
        } catch (error: SecurityException) {
            false
        }
    }

    private fun mimeType(url: String): String = when {
        url.contains(".m3u8", ignoreCase = true) -> "application/x-mpegURL"
        url.contains(".mpd", ignoreCase = true) -> "application/dash+xml"
        url.contains(".mp4", ignoreCase = true) -> "video/mp4"
        url.contains(".mkv", ignoreCase = true) -> "video/x-matroska"
        url.contains(".webm", ignoreCase = true) -> "video/webm"
        url.contains(".ts", ignoreCase = true) -> "video/mp2t"
        else -> "video/*"
    }
}
