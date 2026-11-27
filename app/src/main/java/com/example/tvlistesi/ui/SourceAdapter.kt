package com.example.tvlistesi.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.example.tvlistesi.R
import com.example.tvlistesi.data.DiscoveredSource
import com.example.tvlistesi.data.SourceStatus
import com.example.tvlistesi.databinding.ItemSourceBinding

class SourceAdapter(
    private val onCheck: (DiscoveredSource) -> Unit,
    private val onUse: (DiscoveredSource) -> Unit
) : RecyclerView.Adapter<SourceAdapter.ViewHolder>() {

    private val items = mutableListOf<DiscoveredSource>()

    fun submit(list: List<DiscoveredSource>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    fun update(index: Int, source: DiscoveredSource) {
        if (index in items.indices) {
            items[index] = source
            notifyItemChanged(index)
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder =
        ViewHolder(ItemSourceBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: ViewHolder, position: Int) = holder.bind(items[position])

    override fun getItemCount(): Int = items.size

    inner class ViewHolder(private val binding: ItemSourceBinding) : RecyclerView.ViewHolder(binding.root) {

        fun bind(source: DiscoveredSource) {
            binding.titleText.text = source.title
            binding.descriptionText.text = source.description.orEmpty()
            binding.statusText.text = when (source.status) {
                SourceStatus.TEST_EDILMEDI ->
                    binding.root.context.getString(R.string.github_test_edilmedi)

                SourceStatus.KONTROL_EDILIYOR ->
                    binding.root.context.getString(R.string.github_kontrol_ediliyor)

                SourceStatus.CALISIYOR -> source.note
                    ?: binding.root.context.getString(R.string.github_calisiyor)

                SourceStatus.HATALI -> source.note
                    ?: binding.root.context.getString(R.string.github_erisme_hatasi)
            }
            binding.checkButton.setOnClickListener { onCheck(source) }
            binding.useButton.setOnClickListener { onUse(source) }
            binding.root.setOnClickListener { onUse(source) }
        }
    }
}
