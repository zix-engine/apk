package com.roziq.zixtheserver

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.roziq.zixtheserver.databinding.ItemToolBinding

/** Adapter sederhana untuk layar daftar (monitor, jadwal, broadcast). */
class SimpleToolAdapter(
    private val onClick: (Int) -> Unit
) : RecyclerView.Adapter<SimpleToolAdapter.VH>() {

    class VH(val v: ItemToolBinding) : RecyclerView.ViewHolder(v.root)

    private var items: List<Tools.Item> = emptyList()

    fun set(list: List<Tools.Item>) {
        items = list
        notifyDataSetChanged()
    }

    override fun getItemCount() = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(ItemToolBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        val ctx = holder.itemView.context
        holder.v.tvTitle.text = item.title
        holder.v.tvSub.text = item.sub
        holder.v.tvSub.visibility = if (item.sub.isEmpty()) View.GONE else View.VISIBLE
        holder.v.tvTag.text = item.tag
        holder.v.tvTag.setTextColor(
            when (item.level) {
                1 -> ctx.zColor(R.attr.zOk)
                2 -> ctx.zColor(R.attr.zWarn)
                3 -> ctx.zColor(R.attr.zBad)
                else -> ctx.zColor(R.attr.zTextDim)
            }
        )
        holder.v.root.setOnClickListener { onClick(holder.bindingAdapterPosition) }
    }
}
