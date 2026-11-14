package com.example.gsviewer

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import coil.load
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class HistoryAdapter(
    private val items: List<HistoryEntry>,
    private val onClick: (HistoryEntry) -> Unit,
    private val onLongClick: (HistoryEntry) -> Unit
) : RecyclerView.Adapter<HistoryAdapter.VH>() {

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val thumb: ImageView = view.findViewById(R.id.history_thumb)
        val title: TextView = view.findViewById(R.id.history_title)
        val time: TextView = view.findViewById(R.id.history_time)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_history, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val entry = items[position]
        holder.title.text = entry.title
        holder.time.text = formatWatchedAt(entry.watchedAt, System.currentTimeMillis())
        holder.thumb.load(entry.thumbnailUrl) { crossfade(true) }

        holder.itemView.setOnClickListener { onClick(entry) }
        holder.itemView.setOnLongClickListener { onLongClick(entry); true }
    }

    override fun getItemCount() = items.size

    companion object {
        private fun startOfDay(now: Long, daysAgo: Int): Long {
            val c = Calendar.getInstance()
            c.timeInMillis = now
            c.set(Calendar.HOUR_OF_DAY, 0)
            c.set(Calendar.MINUTE, 0)
            c.set(Calendar.SECOND, 0)
            c.set(Calendar.MILLISECOND, 0)
            c.add(Calendar.DAY_OF_YEAR, -daysAgo)
            return c.timeInMillis
        }

        /** "Այսօր, 18:30" / "Երեկ, 09:15" / "12.11.2026, 21:40" */
        fun formatWatchedAt(watchedAt: Long, now: Long): String {
            val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(watchedAt))
            return when {
                watchedAt >= startOfDay(now, 0) -> "Այսօր, $time"
                watchedAt >= startOfDay(now, 1) -> "Երեկ, $time"
                else -> SimpleDateFormat("dd.MM.yyyy, HH:mm", Locale.getDefault()).format(Date(watchedAt))
            }
        }
    }
}
