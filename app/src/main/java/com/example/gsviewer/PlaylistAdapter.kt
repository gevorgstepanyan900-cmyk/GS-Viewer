package com.example.gsviewer

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class PlaylistAdapter(
    private val items: List<Playlist>,
    private val onClick: (Playlist) -> Unit,
    private val onLongClick: (Playlist) -> Unit
) : RecyclerView.Adapter<PlaylistAdapter.VH>() {

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val name: TextView = view.findViewById(R.id.playlist_name)
        val count: TextView = view.findViewById(R.id.playlist_count)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_playlist, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val playlist = items[position]
        holder.name.text = playlist.name
        val videoCount = playlist.videos?.size ?: 0
        holder.count.text = "$videoCount տեսանյութ"

        holder.itemView.setOnClickListener { onClick(playlist) }
        holder.itemView.setOnLongClickListener { onLongClick(playlist); true }
    }

    override fun getItemCount() = items.size
}
