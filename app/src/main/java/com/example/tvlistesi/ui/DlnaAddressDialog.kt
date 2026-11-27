package com.example.tvlistesi.ui

import android.app.AlertDialog
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.tvlistesi.R
import com.example.tvlistesi.data.DlnaDevice
import com.example.tvlistesi.databinding.DialogDlnaAddressBinding
import com.example.tvlistesi.dlna.DlnaClient
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * SSDP ile bulunamayan cihazlar için cihaz tanım adresini elle girmeyi sağlar.
 * Adres indirilip doğrulandıktan sonra [onDevice] çağrılır.
 */
object DlnaAddressDialog {

    fun show(
        activity: AppCompatActivity,
        requireRenderer: Boolean,
        requireServer: Boolean,
        onDevice: (DlnaDevice) -> Unit
    ) {
        val binding = DialogDlnaAddressBinding.inflate(activity.layoutInflater)
        val dialog = MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.dlna_adresle_ekle)
            .setView(binding.root)
            .setPositiveButton(R.string.ekle, null)
            .setNegativeButton(R.string.iptal, null)
            .create()

        dialog.setOnShowListener {
            val positive = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            positive.setOnClickListener {
                val raw = binding.addressInput.text?.toString()?.trim().orEmpty()
                if (raw.isEmpty()) {
                    binding.addressLayout.error = activity.getString(R.string.dlna_gecersiz_adres)
                    return@setOnClickListener
                }

                val url = if (raw.startsWith("http://", true) ||
                    raw.startsWith("https://", true)
                ) {
                    raw
                } else {
                    "http://$raw"
                }

                binding.addressLayout.error = null
                positive.isEnabled = false

                activity.lifecycleScope.launch {
                    val device = withContext(Dispatchers.IO) { DlnaClient.fetchDevice(url) }
                    positive.isEnabled = true

                    if (activity.isFinishing) return@launch

                    val suitable = device != null &&
                        (!requireRenderer || device.isRenderer) &&
                        (!requireServer || device.isServer)

                    if (suitable && device != null) {
                        dialog.dismiss()
                        onDevice(device)
                        Toast.makeText(
                            activity,
                            R.string.dlna_cihaz_eklendi,
                            Toast.LENGTH_SHORT
                        ).show()
                    } else {
                        binding.addressLayout.error =
                            activity.getString(R.string.dlna_cihaz_bulunamadi_adres)
                    }
                }
            }
        }

        dialog.show()
    }
}
