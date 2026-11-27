package com.example.tvlistesi

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.MenuItem
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.tvlistesi.data.Channel
import com.example.tvlistesi.data.LinkChecker
import com.example.tvlistesi.data.PlaylistRepository
import com.example.tvlistesi.databinding.ActivityLinkCheckBinding
import com.example.tvlistesi.ui.LinkFilter
import com.example.tvlistesi.ui.LinkResultAdapter
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * Etkin listedeki yayın adreslerini toplu olarak sınar, çalışan/çalışmayan
 * sonuçlarını gösterir ve çalışmayanları ayıklayıp yeni bir yerel liste oluşturur.
 */
class LinkCheckActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLinkCheckBinding
    private lateinit var adapter: LinkResultAdapter

    private var channels: List<Channel> = emptyList()
    private var results = mutableListOf<LinkChecker.Result>()
    private var job: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLinkCheckBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        adapter = LinkResultAdapter(onItemClick = { copyUrl(it.channel.url) })
        binding.resultList.layoutManager = LinearLayoutManager(this)
        binding.resultList.adapter = adapter
        binding.resultList.setHasFixedSize(true)

        binding.filterGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            val filter = when (checkedId) {
                R.id.filterOk -> LinkFilter.OK
                R.id.filterFail -> LinkFilter.FAIL
                else -> LinkFilter.ALL
            }
            adapter.setFilter(filter)
        }

        binding.startButton.setOnClickListener { startCheck() }
        binding.stopButton.setOnClickListener { stopCheck() }
        binding.removeDeadButton.setOnClickListener { confirmRemoveDead() }

        channels = PlaylistRepository.channels
        lifecycleScope.launch {
            if (channels.isEmpty()) {
                channels = PlaylistRepository.loadCache(this@LinkCheckActivity)
            }
            binding.channelCount.text = getString(R.string.link_kanal_sayisi, channels.size)
            binding.emptyText.isVisible = channels.isEmpty()
            if (channels.isNotEmpty() && savedInstanceState == null) startCheck()
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        android.R.id.home -> {
            finish()
            true
        }
        else -> super.onOptionsItemSelected(item)
    }

    private fun startCheck() {
        if (job?.isActive == true || channels.isEmpty()) return

        job = lifecycleScope.launch {
            binding.startButton.isEnabled = false
            binding.stopButton.isEnabled = true
            binding.removeDeadButton.isEnabled = false
            binding.emptyText.isVisible = false
            binding.progress.max = channels.size
            binding.progress.progress = 0

            results = channels.map { LinkChecker.Result.pending(it) }.toMutableList()
            adapter.submit(results)

            val semaphore = Semaphore(8)
            var done = 0

            val jobs = channels.mapIndexed { index, channel ->
                launch {
                    semaphore.withPermit {
                        val result = LinkChecker.check(channel)
                        results[index] = result
                        adapter.update(index, result)
                        done++
                        binding.progress.progress = done
                        updateHeader(done)
                    }
                }
            }
            jobs.forEach { it.join() }

            binding.startButton.isEnabled = true
            binding.stopButton.isEnabled = false
            binding.removeDeadButton.isEnabled = results.any { it.ok.not() && it.checked }
            updateHeader(done)
            Toast.makeText(this@LinkCheckActivity, R.string.link_tamamlandi, Toast.LENGTH_SHORT)
                .show()
        }
    }

    private fun stopCheck() {
        job?.cancel()
        job = null
        binding.startButton.isEnabled = true
        binding.stopButton.isEnabled = false
        binding.removeDeadButton.isEnabled = results.any { !it.ok && it.checked }
        updateHeader(results.count { it.checked })
        Toast.makeText(this, R.string.link_durduruldu, Toast.LENGTH_SHORT).show()
    }

    private fun updateHeader(done: Int) {
        val ok = results.count { it.checked && it.ok }
        val failed = results.count { it.checked && !it.ok }
        binding.headerText.text = getString(R.string.link_durum, done, channels.size, ok, failed)
    }

    private fun confirmRemoveDead() {
        val failed = results.count { it.checked && !it.ok }
        if (failed == 0) return
        val pending = results.count { !it.checked }

        val message = if (pending > 0) {
            getString(R.string.link_kaldirma_uyari, failed, pending)
        } else {
            getString(R.string.link_kaldirma_onay, failed)
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.link_kaldirma_baslik)
            .setMessage(message)
            .setPositiveButton(R.string.link_kaldirma_button) { _, _ ->
                val keep = results.filter { it.checked && it.ok }.map { it.channel }
                lifecycleScope.launch {
                    PlaylistRepository.saveFilteredList(this@LinkCheckActivity, keep)
                        .onSuccess { count ->
                            setResult(Activity.RESULT_OK)
                            Toast.makeText(
                                this@LinkCheckActivity,
                                getString(R.string.link_yeni_liste, count),
                                Toast.LENGTH_SHORT
                            ).show()
                            finish()
                        }
                        .onFailure { error ->
                            Toast.makeText(
                                this@LinkCheckActivity,
                                error.message ?: getString(R.string.bilinmeyen_hata),
                                Toast.LENGTH_LONG
                            ).show()
                        }
                }
            }
            .setNegativeButton(R.string.iptal, null)
            .show()
    }

    private fun copyUrl(url: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(getString(R.string.sunucu_adresi), url))
        Toast.makeText(this, R.string.adres_kopyalandi, Toast.LENGTH_SHORT).show()
    }
}
