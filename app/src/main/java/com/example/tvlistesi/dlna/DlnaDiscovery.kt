package com.example.tvlistesi.dlna

import android.annotation.SuppressLint
import android.content.Context
import android.net.wifi.WifiManager
import com.example.tvlistesi.data.DlnaDevice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

/**
 * SSDP aramasını yapar, bulunan cihaz tanımlarını indirir ve
 * kısa süreliğine önbelleğe alır. Tüm ağ işlemleri IO thread'inde çalışır.
 */
object DlnaDiscovery {

    private const val CACHE_MS = 30_000L

    private val cacheLock = Any()
    private var cachedDevices: List<DlnaDevice> = emptyList()
    private var cachedAt = 0L

    suspend fun discover(context: Context, force: Boolean = false): List<DlnaDevice> {
        synchronized(cacheLock) {
            if (!force && System.currentTimeMillis() - cachedAt < CACHE_MS) {
                return cachedDevices
            }
        }

        val devices = withContext(Dispatchers.IO) {
            val lock = acquireMulticastLock(context)
            try {
                val locations = Ssdp.search()
                coroutineScope {
                    locations.map { location ->
                        async { DlnaClient.fetchDevice(location) }
                    }.awaitAll()
                }
            } finally {
                lock?.release()
            }
        }
            .filterNotNull()
            .filter { it.isRenderer || it.isServer }
            .distinctBy { it.udn }
            .sortedBy { it.name.lowercase() }

        synchronized(cacheLock) {
            cachedDevices = devices
            cachedAt = System.currentTimeMillis()
        }

        return devices
    }

    @SuppressLint("MissingPermission")
    private fun acquireMulticastLock(context: Context): WifiManager.MulticastLock? = runCatching {
        val wifi = context.applicationContext
            .getSystemService(Context.WIFI_SERVICE) as WifiManager
        wifi.createMulticastLock("tvlistesi-dlna").apply {
            setReferenceCounted(false)
            acquire()
        }
    }.getOrNull()
}
