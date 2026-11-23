package com.cartube.ytauto

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.cartube.ytauto.queue.QueueController
import com.cartube.ytauto.queue.QueueItem
import com.google.android.material.card.MaterialCardView
import com.google.android.material.imageview.ShapeableImageView

class QueueListAdapter(
    private val onJump: (Int) -> Unit,
    private val onRemove: (Int) -> Unit,
) : RecyclerView.Adapter<QueueListAdapter.VH>() {

    private val items = mutableListOf<QueueItem>()

    fun setQueue(list: List<QueueItem>, currentIndex: Int) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_queue_row, parent, false)
        return VH(v)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        val playing = position == QueueController.currentIndex()
        holder.index.text = "${position + 1}"
        holder.title.text = item.title
        holder.subtitle.text = item.channel.ifBlank { "YouTube" }
        ThumbnailLoader.loadYouTubeThumbnail(item.videoId, holder.thumb)
        val card = holder.itemView as MaterialCardView
        card.setCardBackgroundColor(
            ContextCompat.getColor(
                holder.itemView.context,
                if (playing) R.color.banner_ok else R.color.md_theme_surface_container
            )
        )
        holder.itemView.setOnClickListener { onJump(position) }
        holder.remove.setOnClickListener { onRemove(position) }
    }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val index: TextView = v.findViewById(R.id.indexLabel)
        val thumb: ShapeableImageView = v.findViewById(R.id.thumb)
        val title: TextView = v.findViewById(R.id.title)
        val subtitle: TextView = v.findViewById(R.id.subtitle)
        val remove: ImageButton = v.findViewById(R.id.removeButton)
    }
}