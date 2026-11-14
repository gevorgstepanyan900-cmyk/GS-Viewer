package com.example.gsviewer

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import coil.load

class PlaylistAdapter(
    private val items: List<Playlist>,
    private val onClick: (Playlist) -> Unit,
    private val onMore: (Playlist) -> Unit
) : RecyclerView.Adapter<PlaylistAdapter.VH>() {

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val cover: ImageView = view.findViewById(R.id.cover)
        val name: TextView = view.findViewById(R.id.playlist_name)
        val count: TextView = view.findViewById(R.id.playlist_count)
        val more: View = view.findViewById(R.id.btn_more)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_playlist, parent, false)
        val vh = VH(v)
        vh.cover.outlineProvider = ViewOutlineProvider.BACKGROUND
        vh.cover.clipToOutline = true
        return vh
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val playlist = items[position]
        holder.name.text = playlist.name
        val videos = playlist.videos ?: emptyList<SavedVideo>()
        holder.count.text = "${videos.size} տեսանյութ"

        val thumb = videos.firstOrNull()?.thumbnailUrl
        if (thumb != null) {
            holder.cover.scaleType = ImageView.ScaleType.CENTER_CROP
            holder.cover.setPadding(0, 0, 0, 0)
            holder.cover.load(thumb) { crossfade(true) }
        } else {
            val pad = (24 * holder.cover.resources.displayMetrics.density).toInt()
            holder.cover.scaleType = ImageView.ScaleType.FIT_CENTER
            holder.cover.setPadding(pad, pad, pad, pad)
            holder.cover.setImageResource(R.drawable.ic_music_note)
        }

        holder.itemView.setOnClickListener { onClick(playlist) }
        holder.itemView.setOnLongClickListener { onMore(playlist); true }
        holder.more.setOnClickListener { onMore(playlist) }
    }

    override fun getItemCount() = items.size
}
