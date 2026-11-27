package com.example.tvlistesi

import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.tvlistesi.data.DlnaDevice
import com.example.tvlistesi.data.DlnaEntry
import com.example.tvlistesi.databinding.ActivityDlnaBrowserBinding
import com.example.tvlistesi.dlna.DlnaClient
import com.example.tvlistesi.dlna.DlnaDiscovery
import com.example.tvlistesi.ui.DlnaAddressDialog
import com.example.tvlistesi.ui.DlnaDeviceAdapter
import com.example.tvlistesi.ui.DlnaEntryAdapter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Ağdaki DLNA sunucularını listeler, klasörler arasında gezinir ve
 * seçilen medyayı uygulamanın oynatıcısında açar.
 */
class DlnaBrowserActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDlnaBrowserBinding

    private val deviceAdapter = DlnaDeviceAdapter { device -> openServer(device) }
    private val entryAdapter = DlnaEntryAdapter { entry -> onEntryClick(entry) }

    private val servers = mutableListOf<DlnaDevice>()
    private val manualServers = mutableListOf<DlnaDevice>()
    private val folderStack = ArrayDeque<Pair<String, String>>()

    private var currentServer: DlnaDevice? = null
    private var browsing = false

    private val backCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            navigateUp()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDlnaBrowserBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)

        binding.entryList.layoutManager = LinearLayoutManager(this)
        binding.entryList.adapter = deviceAdapter
        binding.entryList.setHasFixedSize(true)
        binding.toolbar.setNavigationOnClickListener { navigateUp() }
        binding.retryButton.setOnClickListener { reload(force = true) }
        onBackPressedDispatcher.addCallback(this, backCallback)

        loadServers()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_dlna, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.action_dlna_refresh -> {
            reload(force = true)
            true
        }

        R.id.action_dlna_add -> {
            addDeviceByAddress()
            true
        }

        else -> super.onOptionsItemSelected(item)
    }

    // ------------------------------------------------------------ sunucular

    private fun loadServers(force: Boolean = false) {
        browsing = false
        currentServer = null
        folderStack.clear()
        updateToolbar()
        showLoading(true)

        lifecycleScope.launch {
            val found = DlnaDiscovery.discover(this@DlnaBrowserActivity, force)
                .filter { it.isServer }

            if (isFinishing) return@launch

            servers.clear()
            servers += found
            manualServers.forEach { manual ->
                if (servers.none { it.udn == manual.udn }) servers += manual
            }

            showServers()
        }
    }

    private fun showServers() {
        showLoading(false)
        updateToolbar()
        binding.entryList.adapter = deviceAdapter
        deviceAdapter.submitList(servers.toList())

        if (servers.isEmpty()) {
            binding.emptyText.text = getString(R.string.dlna_sunucu_yok)
            binding.emptyView.isVisible = true
        } else {
            binding.emptyView.isVisible = false
        }
    }

    private fun openServer(device: DlnaDevice) {
        currentServer = device
        browsing = true
        folderStack.clear()
        folderStack.addLast("0" to device.name)
        loadFolder("0")
    }

    // ------------------------------------------------------------ klasörler

    private fun loadFolder(objectId: String) {
        val server = currentServer ?: return
        showLoading(true)

        lifecycleScope.launch {
            val page = withContext(Dispatchers.IO) {
                DlnaClient.browse(server, objectId)
            }

            if (isFinishing) return@launch

            showLoading(false)
            updateToolbar()

            val sorted = page.entries.sortedWith(
                compareByDescending<DlnaEntry> { it.isContainer }
                    .thenBy { it.title.lowercase() }
            )

            binding.entryList.adapter = entryAdapter
            entryAdapter.submitList(sorted)

            if (sorted.isEmpty()) {
                binding.emptyText.text = getString(R.string.dlna_bos_klasor)
                binding.emptyView.isVisible = true
            } else {
                binding.emptyView.isVisible = false
            }
        }
    }

    private fun onEntryClick(entry: DlnaEntry) {
        if (entry.isContainer) {
            folderStack.addLast(entry.id to entry.title)
            loadFolder(entry.id)
            return
        }

        val url = entry.url ?: return
        PlayerActivity.startUrl(this, url, entry.title, entry.mimeType)
    }

    private fun navigateUp() {
        if (!browsing) {
            finish()
            return
        }

        if (folderStack.size > 1) {
            folderStack.removeLast()
            loadFolder(folderStack.last().first)
        } else {
            browsing = false
            currentServer = null
            folderStack.clear()
            showServers()
        }
    }

    private fun reload(force: Boolean) {
        if (browsing) {
            folderStack.lastOrNull()?.let { loadFolder(it.first) }
        } else {
            loadServers(force)
        }
    }

    private fun addDeviceByAddress() {
        DlnaAddressDialog.show(
            activity = this,
            requireRenderer = false,
            requireServer = true
        ) { device ->
            manualServers.removeAll { it.udn == device.udn }
            manualServers += device

            if (servers.none { it.udn == device.udn }) servers += device
            if (!browsing) showServers()
        }
    }

    // ------------------------------------------------------------ görünüm

    private fun updateToolbar() {
        val server = currentServer

        if (server == null) {
            supportActionBar?.title = getString(R.string.dlna_sunuculari)
            supportActionBar?.subtitle = getString(R.string.dlna_sunucu_sec)
            backCallback.isEnabled = false
        } else {
            supportActionBar?.title = server.name
            supportActionBar?.subtitle = folderStack.joinToString(" / ") { it.second }
            backCallback.isEnabled = true
        }
    }

    private fun showLoading(loading: Boolean) {
        binding.progress.isVisible = loading
        if (loading) binding.emptyView.isVisible = false
    }
}
