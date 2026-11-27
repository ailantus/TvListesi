package com.example.tvlistesi.data

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * Sunucudan gelen tek bir kanal kaydı.
 *
 * [url] doğrudan oynatılabilen bir yayın adresi olmalıdır
 * (HLS .m3u8, DASH veya progressive MP4).
 */
@Parcelize
data class Channel(
    val id: String,
    val name: String,
    val url: String,
    val logo: String? = null,
    val group: String? = null
) : Parcelable
