package com.example.gsviewer

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import coil.load

class VideoAdapter(
    private val items: List<SavedVideo>,
    private val onClick: (SavedVideo) -> Unit,
    private val onMore: (SavedVideo) -> Unit
) : RecyclerView.Adapter<VideoAdapter.VH>() {

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val thumb: ImageView = view.findViewById(R.id.thumb)
        val index: TextView = view.findViewById(R.id.index)
        val title: TextView = view.findViewById(R.id.title)
        val url: TextView = view.findViewById(R.id.url)
        val more: View = view.findViewById(R.id.btn_more)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_video, parent, false)
        val vh = VH(v)
        vh.thumb.outlineProvider = ViewOutlineProvider.BACKGROUND
        vh.thumb.clipToOutline = true
        return vh
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val video = items[position]
        holder.title.text = video.title
        holder.url.text = "youtube.com"
        holder.index.text = (position + 1).toString()
        holder.thumb.load(video.thumbnailUrl) { crossfade(true) }

        holder.itemView.setOnClickListener { onClick(video) }
        holder.itemView.setOnLongClickListener { onMore(video); true }
        holder.more.setOnClickListener { onMore(video) }
    }

    override fun getItemCount() = items.size
}
