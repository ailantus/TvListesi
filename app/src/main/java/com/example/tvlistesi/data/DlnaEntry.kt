package com.example.tvlistesi.data

/** DLNA sunucusundaki bir klasör veya oynatılabilir medya öğesi. */
data class DlnaEntry(
    val id: String,
    val title: String,
    val isContainer: Boolean,
    val childCount: Int = 0,
    val url: String? = null,
    val mimeType: String? = null,
    val duration: String? = null,
    val iconUrl: String? = null
)
