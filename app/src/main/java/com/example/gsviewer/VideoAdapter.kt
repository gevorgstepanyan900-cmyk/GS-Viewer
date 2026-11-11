package com.example.gsviewer

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import coil.load

class VideoAdapter(
    private val items: List<SavedVideo>,
    private val onClick: (SavedVideo) -> Unit,
    private val onLongClick: (SavedVideo) -> Unit
) : RecyclerView.Adapter<VideoAdapter.VH>() {

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val thumb: ImageView = view.findViewById(R.id.thumb)
        val title: TextView = view.findViewById(R.id.title)
        val url: TextView = view.findViewById(R.id.url)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_video, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val video = items[position]
        holder.title.text = video.title
        holder.url.text = video.url
        holder.thumb.load(video.thumbnailUrl) { crossfade(true) }

        holder.itemView.setOnClickListener { onClick(video) }
        holder.itemView.setOnLongClickListener { onLongClick(video); true }
    }

    override fun getItemCount() = items.size
}
