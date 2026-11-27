package com.example.tvlistesi.ui

import android.content.Context
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.tvlistesi.R
import com.example.tvlistesi.data.Channel
import com.example.tvlistesi.databinding.ItemPlayerChannelBinding

/**
 * Oynatıcının sağ tarafındaki kanal panelini doldurur.
 * Geçerli kanal vurgulanır; D-pad odağı ayrıca belirginleştirilir.
 */
class PlayerChannelAdapter(
    private val onClick: (Int) -> Unit
) : ListAdapter<Channel, PlayerChannelAdapter.ChannelViewHolder>(DIFF) {

    /** Oynatılmakta olan kanalın listedeki konumu. */
    var selectedIndex: Int = 0
        set(value) {
            if (field == value) return
            val previous = field
            field = value
            if (previous in 0 until itemCount) notifyItemChanged(previous)
            if (value in 0 until itemCount) notifyItemChanged(value)
        }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ChannelViewHolder {
        val binding = ItemPlayerChannelBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ChannelViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ChannelViewHolder, position: Int) {
        holder.bind(getItem(position), position)
    }

    inner class ChannelViewHolder(
        private val binding: ItemPlayerChannelBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        private var position = 0

        fun bind(channel: Channel, position: Int) {
            this.position = position

            binding.channelName.text = channel.name
            binding.channelGroup.text = channel.group
            binding.channelGroup.isVisible = !channel.group.isNullOrBlank()

            LogoLoader.load(binding.channelLogo, channel.logo)

            binding.root.setOnClickListener { onClick(this.position) }
            binding.root.setOnFocusChangeListener { _, hasFocus -> applyState(hasFocus) }
            applyState(binding.root.isFocused)
        }

        private fun applyState(focused: Boolean) {
            val context = binding.root.context
            val selected = position == selectedIndex

            binding.root.setCardBackgroundColor(
                ContextCompat.getColor(
                    context,
                    if (selected) R.color.vurgu_kap else R.color.yuzey
                )
            )
            binding.root.strokeColor = ContextCompat.getColor(
                context,
                if (focused || selected) R.color.vurgu else R.color.kanal_cizgi
            )
            binding.root.strokeWidth = dp(context, if (focused) 2f else 1f)
            binding.channelName.setTextColor(
                ContextCompat.getColor(context, if (selected) R.color.vurgu else R.color.metin)
            )
        }
    }

    private fun dp(context: Context, value: Float): Int =
        (value * context.resources.displayMetrics.density).toInt()

    private companion object {
        val DIFF = object : DiffUtil.ItemCallback<Channel>() {
            override fun areItemsTheSame(oldItem: Channel, newItem: Channel): Boolean =
                oldItem.id == newItem.id && oldItem.url == newItem.url

            override fun areContentsTheSame(oldItem: Channel, newItem: Channel): Boolean =
                oldItem == newItem
        }
    }
}
