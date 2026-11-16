package com.example.gsviewer

import android.text.format.DateFormat
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import coil.load
import java.util.Calendar
import java.util.Date

/** A row of the history list: a day heading or a watched video. */
sealed class HistoryRow {
    data class Header(val title: String) : HistoryRow()
    data class Item(val entry: HistoryEntry) : HistoryRow()
}

object HistoryRows {
    fun startOfDay(millis: Long, daysAgo: Int): Long {
        val c = Calendar.getInstance()
        c.timeInMillis = millis
        c.set(Calendar.HOUR_OF_DAY, 0)
        c.set(Calendar.MINUTE, 0)
        c.set(Calendar.SECOND, 0)
        c.set(Calendar.MILLISECOND, 0)
        c.add(Calendar.DAY_OF_YEAR, -daysAgo)
        return c.timeInMillis
    }

    /** Groups the (newest first) entries under "Today", "Yesterday" and older dates. */
    fun build(
        entries: List<HistoryEntry>,
        now: Long,
        todayLabel: String,
        yesterdayLabel: String,
        dateLabel: (Long) -> String
    ): List<HistoryRow> {
        val rows = mutableListOf<HistoryRow>()
        val today = startOfDay(now, 0)
        val yesterday = startOfDay(now, 1)
        var lastDay: Long? = null
        for (entry in entries) {
            val day = startOfDay(entry.watchedAt, 0)
            if (day != lastDay) {
                lastDay = day
                val label = when (day) {
                    today -> todayLabel
                    yesterday -> yesterdayLabel
                    else -> dateLabel(day)
                }
                rows.add(HistoryRow.Header(label))
            }
            rows.add(HistoryRow.Item(entry))
        }
        return rows
    }
}

class HistoryAdapter(
    private val rows: List<HistoryRow>,
    private val onClick: (HistoryEntry) -> Unit,
    private val onMore: (HistoryEntry) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private class HeaderVH(view: View) : RecyclerView.ViewHolder(view) {
        val text: TextView = view.findViewById(R.id.history_header_text)
    }

    private class ItemVH(view: View) : RecyclerView.ViewHolder(view) {
        val thumb: ImageView = view.findViewById(R.id.history_thumb)
        val title: TextView = view.findViewById(R.id.history_title)
        val time: TextView = view.findViewById(R.id.history_meta)
        val more: ImageView = view.findViewById(R.id.history_more)
    }

    override fun getItemViewType(position: Int): Int =
        if (rows[position] is HistoryRow.Header) TYPE_HEADER else TYPE_ITEM

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_HEADER) {
            HeaderVH(inflater.inflate(R.layout.item_history_header, parent, false))
        } else {
            ItemVH(inflater.inflate(R.layout.item_history_row, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = rows[position]) {
            is HistoryRow.Header -> (holder as HeaderVH).text.text = row.title
            is HistoryRow.Item -> {
                val h = holder as ItemVH
                val entry = row.entry
                h.title.text = entry.title
                h.time.text = DateFormat.getTimeFormat(h.itemView.context).format(Date(entry.watchedAt))
                h.thumb.load(entry.thumbnailUrl) { crossfade(true) }
                h.itemView.setOnClickListener { onClick(entry) }
                h.itemView.setOnLongClickListener { onMore(entry); true }
                h.more.setOnClickListener { onMore(entry) }
            }
        }
    }

    override fun getItemCount() = rows.size

    companion object {
        private const val TYPE_HEADER = 0
        private const val TYPE_ITEM = 1
    }
}
