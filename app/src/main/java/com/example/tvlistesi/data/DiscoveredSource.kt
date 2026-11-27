package com.example.tvlistesi.data

/** Keşfedilen bir liste ya da tek kanal adresi. */
data class DiscoveredSource(
    val repoFullName: String,
    val repoUrl: String,
    val title: String,
    val rawUrl: String,
    val filePath: String,
    val stars: Int = 0,
    val updatedAt: String? = null,
    val description: String? = null,
    val singleStream: Boolean = false,
    val status: SourceStatus = SourceStatus.TEST_EDILMEDI,
    val channelCount: Int? = null,
    val note: String? = null
)

enum class SourceStatus { TEST_EDILMEDI, KONTROL_EDILIYOR, CALISIYOR, HATALI }
