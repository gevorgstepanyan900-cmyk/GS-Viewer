package com.example.gsviewer

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import coil.load

/** Playlist cards (cover = thumbnail of the newest video). */
class PlaylistsAdapter(
    private val items: List<Playlist>,
    private val onClick: (Playlist) -> Unit,
    private val onMore: (Playlist) -> Unit
) : RecyclerView.Adapter<PlaylistsAdapter.VH>() {

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val coverImage: ImageView = view.findViewById(R.id.cover_image)
        val initial: TextView = view.findViewById(R.id.cover_initial)
        val more: ImageView = view.findViewById(R.id.btn_more)
        val name: TextView = view.findViewById(R.id.name)
        val meta: TextView = view.findViewById(R.id.meta)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_playlist_card, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val playlist = items[position]
        val context = holder.itemView.context
        val count = playlist.videos?.size ?: 0

        holder.name.text = playlist.name
        holder.meta.text = context.resources.getQuantityString(R.plurals.n_videos, count, count)

        val cover = playlist.videos?.firstOrNull()?.thumbnailUrl
        if (cover.isNullOrEmpty()) {
            holder.coverImage.setImageDrawable(null)
            holder.initial.visibility = View.VISIBLE
            holder.initial.text = playlist.name.trim().take(1).uppercase()
        } else {
            holder.initial.visibility = View.GONE
            holder.coverImage.load(cover) { crossfade(true) }
        }

        holder.itemView.setOnClickListener { onClick(playlist) }
        holder.itemView.setOnLongClickListener { onMore(playlist); true }
        holder.more.setOnClickListener { onMore(playlist) }
    }

    override fun getItemCount() = items.size
}
