package com.example.tvlistesi.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.tvlistesi.R
import com.example.tvlistesi.data.DlnaDevice
import com.example.tvlistesi.databinding.ItemDlnaDeviceBinding

/** DLNA oynatıcı/sunucu cihazlarını listeleyen basit adaptör. */
class DlnaDeviceAdapter(
    private val onClick: (DlnaDevice) -> Unit
) : ListAdapter<DlnaDevice, DlnaDeviceAdapter.DeviceViewHolder>(DIFF) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DeviceViewHolder {
        val binding = ItemDlnaDeviceBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return DeviceViewHolder(binding)
    }

    override fun onBindViewHolder(holder: DeviceViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class DeviceViewHolder(
        private val binding: ItemDlnaDeviceBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(device: DlnaDevice) {
            val context = binding.root.context
            binding.deviceName.text = device.name

            val type = when {
                device.isRenderer && device.isServer -> R.string.dlna_oynatici_ve_sunucu
                device.isRenderer -> R.string.dlna_oynatici_cihaz
                else -> R.string.dlna_sunucu_cihaz
            }
            binding.deviceType.text = "${context.getString(type)} · ${device.location}"

            LogoLoader.load(binding.deviceIcon, device.iconUrl, R.drawable.ic_cast)
            binding.root.setOnClickListener { onClick(device) }
        }
    }

    private companion object {
        val DIFF = object : DiffUtil.ItemCallback<DlnaDevice>() {
            override fun areItemsTheSame(oldItem: DlnaDevice, newItem: DlnaDevice): Boolean =
                oldItem.udn == newItem.udn

            override fun areContentsTheSame(oldItem: DlnaDevice, newItem: DlnaDevice): Boolean =
                oldItem == newItem
        }
    }
}
