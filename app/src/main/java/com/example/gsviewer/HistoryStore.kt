package com.example.gsviewer

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/** One watched video in the history. */
data class HistoryEntry(
    val videoId: String,
    val url: String,
    val title: String,
    val thumbnailUrl: String,
    val watchedAt: Long
)

/**
 * Watch history: every video that was really watched (played for a few seconds) is kept for the
 * last 30 days. One entry per video; watching it again moves it to the top with the new time.
 */
object HistoryStore {
    private const val PREFS = "gsviewer_history_v1"
    private const val KEY = "history"
    const val KEEP_DAYS = 30
    private const val MAX_ENTRIES = 1000
    private val gson = Gson()

    private fun cutoff(now: Long): Long = now - KEEP_DAYS * 24L * 60L * 60L * 1000L

    /** Drops everything older than 30 days and sorts newest first. */
    fun purge(list: List<HistoryEntry>, now: Long): MutableList<HistoryEntry> =
        list.filter { it.watchedAt >= cutoff(now) }
            .sortedByDescending { it.watchedAt }
            .toMutableList()

    /** The list after "entry was just watched": no duplicates, newest first, old ones removed. */
    fun withEntry(list: List<HistoryEntry>, entry: HistoryEntry, now: Long): MutableList<HistoryEntry> {
        val result = list.filter { it.videoId != entry.videoId }.toMutableList()
        result.add(entry)
        return purge(result, now).take(MAX_ENTRIES).toMutableList()
    }

    fun getHistory(context: Context): MutableList<HistoryEntry> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, null) ?: return mutableListOf()
        val type = object : TypeToken<MutableList<HistoryEntry>>() {}.type
        val list: MutableList<HistoryEntry> = try {
            gson.fromJson<MutableList<HistoryEntry>>(raw, type) ?: mutableListOf()
        } catch (e: Exception) {
            mutableListOf()
        }
        return purge(list, System.currentTimeMillis())
    }

    private fun save(context: Context, list: List<HistoryEntry>) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, gson.toJson(list)).apply()
    }

    fun add(context: Context, video: SavedVideo) {
        val now = System.currentTimeMillis()
        val entry = HistoryEntry(video.videoId, video.url, video.title, video.thumbnailUrl, now)
        save(context, withEntry(getHistory(context), entry, now))
    }

    fun remove(context: Context, videoId: String) {
        save(context, getHistory(context).filter { it.videoId != videoId })
    }

    fun clear(context: Context) {
        save(context, emptyList())
    }
}
