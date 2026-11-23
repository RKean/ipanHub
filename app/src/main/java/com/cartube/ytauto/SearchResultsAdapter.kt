package com.cartube.ytauto

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.PopupMenu
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.imageview.ShapeableImageView

class SearchResultsAdapter(
    private val onPlayNow: (YoutubeSearchResult) -> Unit,
    private val onPlayNext: (YoutubeSearchResult) -> Unit,
    private val onAddQueue: (YoutubeSearchResult) -> Unit,
) : RecyclerView.Adapter<SearchResultsAdapter.VH>() {

    private val items = mutableListOf<YoutubeSearchResult>()

    fun setResults(list: List<YoutubeSearchResult>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_search_result, parent, false)
        return VH(v)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        holder.title.text = item.title
        holder.subtitle.text = formatMeta(item)
        ThumbnailLoader.loadYouTubeThumbnail(item.videoId, holder.thumb)
        holder.itemView.setOnClickListener { onPlayNow(item) }
        holder.itemView.setOnLongClickListener {
            showMenu(holder.itemView, item)
            true
        }
    }

    private fun showMenu(anchor: View, item: YoutubeSearchResult) {
        PopupMenu(anchor.context, anchor).apply {
            menu.add(0, 1, 0, anchor.context.getString(R.string.play_now))
            menu.add(0, 2, 1, anchor.context.getString(R.string.play_next))
            menu.add(0, 3, 2, anchor.context.getString(R.string.add_to_queue))
            setOnMenuItemClickListener {
                when (it.itemId) {
                    1 -> onPlayNow(item)
                    2 -> onPlayNext(item)
                    3 -> onAddQueue(item)
                }
                true
            }
            show()
        }
    }

    private fun formatMeta(item: YoutubeSearchResult): String {
        val dur = formatDuration(item.durationSeconds)
        return listOf(item.channel, dur).filter { it.isNotBlank() }.joinToString(" · ")
    }

    private fun formatDuration(sec: Long): String {
        if (sec <= 0) return ""
        val m = sec / 60
        val s = sec % 60
        return if (m >= 60) {
            val h = m / 60
            String.format("%d:%02d:%02d", h, m % 60, s)
        } else {
            String.format("%d:%02d", m, s)
        }
    }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val thumb: ShapeableImageView = v.findViewById(R.id.thumb)
        val title: TextView = v.findViewById(R.id.title)
        val subtitle: TextView = v.findViewById(R.id.subtitle)
    }
}