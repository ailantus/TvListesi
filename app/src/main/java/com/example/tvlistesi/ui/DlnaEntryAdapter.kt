package com.example.tvlistesi.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.tvlistesi.R
import com.example.tvlistesi.data.DlnaEntry
import com.example.tvlistesi.databinding.ItemDlnaEntryBinding

/** DLNA sunucusundaki klasör ve medya öğelerini listeler. */
class DlnaEntryAdapter(
    private val onClick: (DlnaEntry) -> Unit
) : ListAdapter<DlnaEntry, DlnaEntryAdapter.EntryViewHolder>(DIFF) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): EntryViewHolder {
        val binding = ItemDlnaEntryBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return EntryViewHolder(binding)
    }

    override fun onBindViewHolder(holder: EntryViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class EntryViewHolder(
        private val binding: ItemDlnaEntryBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(entry: DlnaEntry) {
            val context = binding.root.context
            binding.entryTitle.text = entry.title

            if (entry.isContainer) {
                binding.entryIcon.setImageResource(R.drawable.ic_folder)
                binding.entrySubtitle.text = if (entry.childCount > 0) {
                    context.getString(R.string.dlna_klasor_oge_sayisi, entry.childCount)
                } else {
                    context.getString(R.string.dlna_klasor)
                }
            } else {
                binding.entryIcon.setImageResource(R.drawable.ic_tv)
                binding.entrySubtitle.text = entry.duration
                    ?: entry.mimeType
                    ?: context.getString(R.string.dlna_oge)
            }

            binding.root.setOnClickListener { onClick(entry) }
        }
    }

    private companion object {
        val DIFF = object : DiffUtil.ItemCallback<DlnaEntry>() {
            override fun areItemsTheSame(oldItem: DlnaEntry, newItem: DlnaEntry): Boolean =
                oldItem.id == newItem.id

            override fun areContentsTheSame(oldItem: DlnaEntry, newItem: DlnaEntry): Boolean =
                oldItem == newItem
        }
    }
}
