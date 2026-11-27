package com.example.tvlistesi.data

/**
 * Ağda bulunan bir DLNA/UPnP cihazı.
 *
 * [avTransportUrl] doluysa cihaz yayın alabilir (MediaRenderer),
 * [contentDirectoryUrl] doluysa cihaz içerik sunabilir (MediaServer).
 */
data class DlnaDevice(
    val name: String,
    val udn: String,
    val location: String,
    val avTransportUrl: String? = null,
    val contentDirectoryUrl: String? = null,
    val iconUrl: String? = null
) {
    val isRenderer: Boolean get() = !avTransportUrl.isNullOrBlank()
    val isServer: Boolean get() = !contentDirectoryUrl.isNullOrBlank()
}
