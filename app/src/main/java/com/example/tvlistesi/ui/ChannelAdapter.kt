package com.example.tvlistesi.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.tvlistesi.R
import com.example.tvlistesi.data.Channel
import com.example.tvlistesi.databinding.ItemChannelBinding

class ChannelAdapter(
    private val onClick: (Channel) -> Unit,
    private val onLongClick: (Channel) -> Unit
) : ListAdapter<Channel, ChannelAdapter.ChannelViewHolder>(DIFF) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ChannelViewHolder {
        val binding = ItemChannelBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ChannelViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ChannelViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ChannelViewHolder(
        private val binding: ItemChannelBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(channel: Channel) {
            binding.channelName.text = channel.name
            binding.channelGroup.text = channel.group
            binding.channelGroup.isVisible = !channel.group.isNullOrBlank()

            LogoLoader.load(binding.channelLogo, channel.logo)

            binding.root.setOnClickListener { onClick(channel) }
            binding.root.setOnLongClickListener {
                onLongClick(channel)
                true
            }
        }
    }

    private companion object {
        val DIFF = object : DiffUtil.ItemCallback<Channel>() {
            override fun areItemsTheSame(oldItem: Channel, newItem: Channel): Boolean =
                oldItem.id == newItem.id && oldItem.url == newItem.url

            override fun areContentsTheSame(oldItem: Channel, newItem: Channel): Boolean =
                oldItem == newItem
        }
    }
}
