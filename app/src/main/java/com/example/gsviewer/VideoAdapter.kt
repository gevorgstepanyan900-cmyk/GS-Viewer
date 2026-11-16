package com.example.gsviewer

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import coil.load

/** The videos of one playlist. */
class VideoAdapter(
    private val items: List<SavedVideo>,
    private val onClick: (Int, SavedVideo) -> Unit,
    private val onMore: (SavedVideo) -> Unit
) : RecyclerView.Adapter<VideoAdapter.VH>() {

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val thumb: ImageView = view.findViewById(R.id.video_thumb)
        val title: TextView = view.findViewById(R.id.video_title)
        val meta: TextView = view.findViewById(R.id.video_meta)
        val more: ImageView = view.findViewById(R.id.video_more)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_video_row, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val video = items[position]
        holder.title.text = video.title
        holder.meta.text = holder.itemView.context.getString(R.string.video_position, position + 1, items.size)
        holder.thumb.load(video.thumbnailUrl) { crossfade(true) }

        holder.itemView.setOnClickListener {
            val index = holder.bindingAdapterPosition
            onClick(if (index >= 0) index else position, video)
        }
        holder.itemView.setOnLongClickListener { onMore(video); true }
        holder.more.setOnClickListener { onMore(video) }
    }

    override fun getItemCount() = items.size
}
