package com.cartube.ytauto

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class SuggestionsAdapter(
    private val onPick: (String) -> Unit,
    private val onFill: (String) -> Unit,
    private val onRemove: ((String) -> Unit)? = null,
) : RecyclerView.Adapter<SuggestionsAdapter.VH>() {

    private val items = mutableListOf<String>()
    var showRecentRemove = false
        set(value) {
            field = value
            notifyDataSetChanged()
        }

    fun setSuggestions(list: List<String>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_suggestion_row, parent, false)
        return VH(v)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val text = items[position]
        holder.text.text = text
        holder.icon.setImageResource(
            if (showRecentRemove) android.R.drawable.ic_menu_recent_history
            else android.R.drawable.ic_menu_search
        )
        holder.remove.visibility = if (showRecentRemove && onRemove != null) View.VISIBLE else View.GONE
        holder.itemView.setOnClickListener { onPick(text) }
        holder.fill.setOnClickListener { onFill(text) }
        holder.remove.setOnClickListener { onRemove?.invoke(text) }
    }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val icon: ImageView = v.findViewById(R.id.suggestIcon)
        val text: TextView = v.findViewById(R.id.suggestText)
        val fill: ImageButton = v.findViewById(R.id.fillButton)
        val remove: ImageButton = v.findViewById(R.id.removeButton)
    }
}