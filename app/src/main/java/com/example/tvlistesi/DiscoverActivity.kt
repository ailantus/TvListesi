package com.example.tvlistesi

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.MenuItem
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.tvlistesi.data.Channel
import com.example.tvlistesi.data.DiscoveredSource
import com.example.tvlistesi.data.LinkChecker
import com.example.tvlistesi.data.PlaylistParser
import com.example.tvlistesi.data.PlaylistRepository
import com.example.tvlistesi.data.SourceStatus
import com.example.tvlistesi.data.TelegramDiscovery
import com.example.tvlistesi.databinding.ActivityDiscoverBinding
import com.example.tvlistesi.ui.SourceAdapter
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/** Herkese açık Telegram kanallarında paylaşılan liste/ yayın adreslerini bulur. */
class DiscoverActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDiscoverBinding
    private lateinit var adapter: SourceAdapter
    private var sources = mutableListOf<DiscoveredSource>()
    private var searchJob: Job? = null
    private var checkJob: Job? = null
    private var checkGeneration = 0
    private var checking = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDiscoverBinding.inflate(layoutInflater)
        setContentView(binding.root)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        title = getString(R.string.telegram_kaynak)

        adapter = SourceAdapter(onCheck = { checkOne(it) }, onUse = { useSource(it) })
        binding.sourceList.layoutManager = LinearLayoutManager(this)
        binding.sourceList.adapter = adapter

        binding.queryInput.setText(TelegramDiscovery.DEFAULT_URL)
        binding.searchButton.setOnClickListener { search() }
        binding.checkAllButton.setOnClickListener { checkAll() }
        search()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        android.R.id.home -> {
            finish()
            true
        }

        else -> super.onOptionsItemSelected(item)
    }

    private fun search() {
        val query = binding.queryInput.text?.toString()?.trim().orEmpty()
            .ifBlank { TelegramDiscovery.DEFAULT_URL }
        // Önceki aramanın otomatik kontrol işleri yeni sonuçların durumlarını bozmasın.
        checkJob?.cancel()
        checkGeneration++
        checking = false
        searchJob?.cancel()
        searchJob = lifecycleScope.launch {
            binding.progress.isVisible = true
            val result = TelegramDiscovery.search(this@DiscoverActivity, query)
            binding.progress.isVisible = false
            result.onSuccess { found ->
                sources = found.toMutableList()
                adapter.submit(sources)
                updateSummary()
                // Bulunan tüm kaynaklar aramadan hemen sonra otomatik kontrol edilir.
                checkAll()
            }.onFailure { error ->
                Toast.makeText(
                    this@DiscoverActivity,
                    error.message ?: getString(R.string.bilinmeyen_hata),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun checkOne(source: DiscoveredSource) {
        lifecycleScope.launch {
            setStatus(source, SourceStatus.KONTROL_EDILIYOR, null)
            val result = checkSource(source)
            val status = if (result.ok) SourceStatus.CALISIYOR else SourceStatus.HATALI
            setStatus(source, status, result.note)
        }
    }

    private fun checkAll() {
        checkJob?.cancel()
        val generation = ++checkGeneration
        // İptal edilen bir kontrol turunda "Kontrol ediliyor" kalan kaynaklar
        // yeni turda atlanmasın diye test edilmemiş durumuna döndürülür.
        sources.forEachIndexed { index, source ->
            if (source.status == SourceStatus.KONTROL_EDILIYOR) {
                val updated = source.copy(status = SourceStatus.TEST_EDILMEDI, note = null)
                sources[index] = updated
                adapter.update(index, updated)
            }
        }
        checking = true
        updateSummary()

        val snapshot = sources.toList()
        checkJob = lifecycleScope.launch {
            val permit = Semaphore(3)
            snapshot.forEach { source ->
                launch {
                    permit.withPermit {
                        if (!isActive || generation != checkGeneration) return@withPermit
                        setStatus(source, SourceStatus.KONTROL_EDILIYOR, null)
                        val result = checkSource(source)
                        if (!isActive || generation != checkGeneration) return@withPermit
                        val status = if (result.ok) SourceStatus.CALISIYOR else SourceStatus.HATALI
                        setStatus(source, status, result.note)
                    }
                }
            }
        }
        checkJob?.invokeOnCompletion {
            if (generation == checkGeneration) {
                checking = false
                updateSummary()
            }
        }
    }

    private fun setStatus(source: DiscoveredSource, status: SourceStatus, note: String?) {
        val index = sources.indexOfFirst { it.rawUrl == source.rawUrl }
        if (index !in sources.indices) return
        val updated = sources[index].copy(status = status, note = note)
        sources[index] = updated
        adapter.update(index, updated)
        updateSummary()
    }

    private fun updateSummary() {
        val working = sources.count { it.status == SourceStatus.CALISIYOR }
        val failed = sources.count { it.status == SourceStatus.HATALI }
        binding.summaryText.text = getString(R.string.github_ozet, sources.size, working, failed)
        // "Tümünü kontrol et" sonuçlar çıksa da yeniden sınamak için açık kalır.
        binding.checkAllButton.isEnabled = sources.isNotEmpty() && !checking
    }

    private fun useSource(source: DiscoveredSource) {
        fun apply() {
            if (source.singleStream) {
                // Mevcut listeyi sil, adresi değiştirmeden tek kanalı yükle.
                PlaylistRepository.replaceWithStream(this, source.rawUrl)
                setResult(
                    Activity.RESULT_OK,
                    Intent().putExtra(MainActivity.EXTRA_STREAM_ADDED, true)
                )
            } else {
                PlaylistRepository.setServerUrl(this, source.rawUrl)
                setResult(Activity.RESULT_OK)
            }
            finish()
        }

        if (source.status != SourceStatus.CALISIYOR) {
            MaterialAlertDialogBuilder(this)
                .setTitle(source.title)
                .setMessage(R.string.github_erisme_hatasi)
                .setPositiveButton(R.string.github_kullan) { _, _ -> apply() }
                .setNegativeButton(R.string.iptal, null)
                .show()
        } else {
            apply()
        }
    }

    private data class SourceCheck(val ok: Boolean, val note: String)

    private suspend fun checkSource(source: DiscoveredSource): SourceCheck = withContext(Dispatchers.IO) {
        if (source.singleStream) {
            val result = LinkChecker.check(
                Channel(id = source.rawUrl, name = source.title, url = source.rawUrl)
            )
            return@withContext SourceCheck(
                result.ok,
                if (result.ok) "Tek kanal · HTTP ${result.code}" else result.message
            )
        }

        runCatching {
            // Telegram mesajına gömülü M3U metni cihazda saklanır; HTTP ile açılamaz.
            val text = PlaylistRepository.readLocalList(this@DiscoverActivity, source.rawUrl)
                ?: downloadList(source.rawUrl)
            val channels = PlaylistParser.parse(text, source.rawUrl)
            if (channels.isEmpty()) {
                SourceCheck(false, "Liste boş veya biçimi tanınmadı")
            } else {
                SourceCheck(true, "${channels.size} kanal · ${text.length / 1024} KB")
            }
        }.getOrElse { error ->
            SourceCheck(false, error.message?.take(80) ?: "Kontrol edilemedi")
        }
    }

    /** HTTP(S) adresindeki liste metnini küçük bir Range isteğiyle okur. */
    private fun downloadList(address: String): String {
        val connection = (URL(address).openConnection() as HttpURLConnection).apply {
            connectTimeout = 12_000
            readTimeout = 15_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "TvListesi/2.0 (Android)")
            setRequestProperty("Range", "bytes=0-524287")
        }
        try {
            val code = connection.responseCode
            if (code !in 200..299) throw IllegalStateException("HTTP $code")
            return connection.inputStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                .orEmpty()
        } finally {
            connection.disconnect()
        }
    }
}
