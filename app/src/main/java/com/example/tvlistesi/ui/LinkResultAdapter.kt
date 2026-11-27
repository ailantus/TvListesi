package com.example.tvlistesi.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.example.tvlistesi.R
import com.example.tvlistesi.data.LinkChecker
import com.example.tvlistesi.databinding.ItemLinkCheckBinding

enum class LinkFilter { ALL, OK, FAIL }

/** Yayın adresi kontrol sonuçlarını, canlı filtreyle birlikte gösterir. */
class LinkResultAdapter(
    private val onItemClick: (LinkChecker.Result) -> Unit
) : RecyclerView.Adapter<LinkResultAdapter.ViewHolder>() {

    private val all = mutableListOf<LinkChecker.Result>()
    private val visible = mutableListOf<LinkChecker.Result>()
    private var filter = LinkFilter.ALL

    fun submit(list: List<LinkChecker.Result>) {
        all.clear()
        all.addAll(list)
        applyFilter()
    }

    fun setFilter(value: LinkFilter) {
        filter = value
        applyFilter()
    }

    fun update(index: Int, result: LinkChecker.Result) {
        if (index !in all.indices) return
        all[index] = result

        val position = visible.indexOfFirst { it.channel.url == result.channel.url }
        val shouldShow = matches(result)
        when {
            shouldShow && position < 0 -> {
                visible.add(result)
                notifyItemInserted(visible.size - 1)
            }
            !shouldShow && position >= 0 -> {
                visible.removeAt(position)
                notifyItemRemoved(position)
            }
            position >= 0 -> {
                visible[position] = result
                notifyItemChanged(position)
            }
        }
    }

    private fun applyFilter() {
        visible.clear()
        visible.addAll(all.filter(::matches))
        notifyDataSetChanged()
    }

    private fun matches(result: LinkChecker.Result): Boolean = when (filter) {
        LinkFilter.ALL -> true
        LinkFilter.OK -> result.checked && result.ok
        LinkFilter.FAIL -> result.checked && !result.ok
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemLinkCheckBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) =
        holder.bind(visible[position])

    override fun getItemCount(): Int = visible.size

    inner class ViewHolder(private val binding: ItemLinkCheckBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(result: LinkChecker.Result) {
            binding.root.setOnClickListener {
                bindingAdapterPosition.takeIf { it != RecyclerView.NO_POSITION }
                    ?.let { onItemClick(visible[it]) }
            }
            binding.channelName.text = result.channel.name
            binding.channelUrl.text = result.channel.url

            if (!result.checked) {
                binding.statusIcon.setImageResource(R.drawable.ic_saat)
                binding.statusIcon.setColorFilter(
                    ContextCompat.getColor(binding.root.context, R.color.soluk)
                )
                binding.statusText.text = binding.root.context.getString(R.string.link_bekliyor)
                binding.statusText.setTextColor(
                    ContextCompat.getColor(binding.root.context, R.color.soluk)
                )
                return
            }

            if (result.ok) {
                binding.statusIcon.setImageResource(R.drawable.ic_check_circle)
                binding.statusIcon.setColorFilter(
                    ContextCompat.getColor(binding.root.context, R.color.basari)
                )
                binding.statusText.setTextColor(
                    ContextCompat.getColor(binding.root.context, R.color.basari)
                )
            } else {
                binding.statusIcon.setImageResource(R.drawable.ic_error)
                binding.statusIcon.setColorFilter(
                    ContextCompat.getColor(binding.root.context, R.color.hata)
                )
                binding.statusText.setTextColor(
                    ContextCompat.getColor(binding.root.context, R.color.hata)
                )
            }

            val code = result.code?.let { "$it · " }.orEmpty()
            val time = if (result.elapsedMs > 0) " · ${result.elapsedMs} ms" else ""
            binding.statusText.text = code + result.message + time
        }
    }
}
