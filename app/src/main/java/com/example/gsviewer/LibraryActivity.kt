package com.example.gsviewer

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.os.Bundle
import android.text.format.DateUtils
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

/** Your library: playlists (cards) and the watch history of the last 30 days. */
class LibraryActivity : Activity() {

    companion object {
        const val EXTRA_TAB = "tab"
        const val TAB_PLAYLISTS = 0
        const val TAB_HISTORY = 1
    }

    private var tab = TAB_PLAYLISTS

    private lateinit var tabPlaylists: TextView
    private lateinit var tabHistory: TextView
    private lateinit var historyHint: TextView
    private lateinit var content: View
    private lateinit var recyclerPlaylists: RecyclerView
    private lateinit var recyclerHistory: RecyclerView
    private lateinit var emptyState: View
    private lateinit var emptyIcon: ImageView
    private lateinit var emptyTitle: TextView
    private lateinit var emptyHint: TextView
    private lateinit var fabNew: View
    private lateinit var btnClear: View

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_library)

        tab = savedInstanceState?.getInt(EXTRA_TAB, TAB_PLAYLISTS)
            ?: intent.getIntExtra(EXTRA_TAB, TAB_PLAYLISTS)

        tabPlaylists = findViewById(R.id.tab_playlists)
        tabHistory = findViewById(R.id.tab_history)
        historyHint = findViewById(R.id.history_hint)
        content = findViewById(R.id.content)
        recyclerPlaylists = findViewById(R.id.recycler_playlists)
        recyclerHistory = findViewById(R.id.recycler_history)
        emptyState = findViewById(R.id.empty_state)
        emptyIcon = findViewById(R.id.empty_icon)
        emptyTitle = findViewById(R.id.empty_title)
        emptyHint = findViewById(R.id.empty_hint)
        fabNew = findViewById(R.id.fab_new)
        btnClear = findViewById(R.id.btn_clear_history)

        val metrics = resources.displayMetrics
        val spans = (metrics.widthPixels / (metrics.density * 180f)).toInt().coerceAtLeast(2)
        recyclerPlaylists.layoutManager = GridLayoutManager(this, spans)
        recyclerHistory.layoutManager = LinearLayoutManager(this)

        findViewById<View>(R.id.btn_back).setOnClickListener { finish() }
        tabPlaylists.setOnClickListener { selectTab(TAB_PLAYLISTS, true) }
        tabHistory.setOnClickListener { selectTab(TAB_HISTORY, true) }
        fabNew.setOnClickListener { showCreatePlaylist() }
        btnClear.setOnClickListener { confirmClearHistory() }
        findViewById<View>(R.id.btn_default_links).setOnClickListener { showDefaultLinksSheet() }

        selectTab(tab, false)
    }

    override fun onResume() {
        super.onResume()
        refreshAll()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(EXTRA_TAB, tab)
    }

    // ------------------------------------------------------------------ tabs

    private fun selectTab(newTab: Int, animate: Boolean) {
        tab = newTab
        styleTab(tabPlaylists, tab == TAB_PLAYLISTS)
        styleTab(tabHistory, tab == TAB_HISTORY)
        if (animate) {
            content.alpha = 0f
            content.animate().alpha(1f).setDuration(180).start()
        }
        refreshAll()
    }

    private fun styleTab(view: TextView, selected: Boolean) {
        view.setBackgroundResource(if (selected) R.drawable.bg_segment_selected else 0)
        view.setTextColor(getColor(if (selected) R.color.gs_white else R.color.gs_text_secondary))
    }

    // ------------------------------------------------------------------ data

    private fun refreshAll() {
        val onPlaylists = tab == TAB_PLAYLISTS
        recyclerPlaylists.visibility = if (onPlaylists) View.VISIBLE else View.GONE
        recyclerHistory.visibility = if (onPlaylists) View.GONE else View.VISIBLE
        historyHint.visibility = if (onPlaylists) View.GONE else View.VISIBLE
        fabNew.visibility = if (onPlaylists) View.VISIBLE else View.GONE

        if (onPlaylists) refreshPlaylists() else refreshHistory()
    }

    private fun refreshPlaylists() {
        val playlists = PlaylistStore.getPlaylists(this)
        recyclerPlaylists.adapter = PlaylistsAdapter(
            items = playlists,
            onClick = { playlist ->
                startActivity(Intent(this, VideosActivity::class.java).putExtra("playlist_id", playlist.id))
            },
            onMore = { playlist -> showPlaylistActions(playlist) }
        )
        btnClear.visibility = View.GONE
        showEmpty(
            playlists.isEmpty(), R.drawable.ic_library,
            R.string.empty_playlists_title, R.string.empty_playlists_hint
        )
    }

    private fun refreshHistory() {
        val entries = HistoryStore.getHistory(this)
        val rows = HistoryRows.build(
            entries, System.currentTimeMillis(),
            getString(R.string.today), getString(R.string.yesterday)
        ) { dayStart ->
            DateUtils.formatDateTime(
                this, dayStart,
                DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_YEAR or DateUtils.FORMAT_SHOW_WEEKDAY
            )
        }
        recyclerHistory.adapter = HistoryAdapter(
            rows = rows,
            onClick = { entry -> openVideo(entry) },
            onMore = { entry -> showHistoryActions(entry) }
        )
        btnClear.visibility = if (entries.isEmpty()) View.GONE else View.VISIBLE
        showEmpty(
            entries.isEmpty(), R.drawable.ic_history,
            R.string.empty_history_title, R.string.empty_history_hint
        )
    }

    private fun showEmpty(empty: Boolean, iconRes: Int, titleRes: Int, hintRes: Int) {
        emptyState.visibility = if (empty) View.VISIBLE else View.GONE
        if (empty) {
            emptyIcon.setImageResource(iconRes)
            emptyTitle.setText(titleRes)
            emptyHint.setText(hintRes)
        }
    }

    // ------------------------------------------------------------------ actions

    private fun openVideo(entry: HistoryEntry) {
        val intent = Intent(this, MainActivity::class.java).apply {
            putExtra("open_url", entry.url)
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        startActivity(intent)
    }

    private fun showCreatePlaylist() {
        Sheets.input(
            this, getString(R.string.new_playlist), getString(R.string.playlist_name_hint),
            "", getString(R.string.create)
        ) { name ->
            PlaylistStore.createPlaylist(this, name)
            refreshAll()
        }
    }

    private fun showPlaylistActions(playlist: Playlist) {
        val count = playlist.videos?.size ?: 0
        Sheets.actions(
            this, playlist.name, resources.getQuantityString(R.plurals.n_videos, count, count),
            listOf(
                Sheets.Action(getString(R.string.rename), R.drawable.ic_edit) { renamePlaylist(playlist) },
                Sheets.Action(getString(R.string.delete_playlist_title), R.drawable.ic_delete, destructive = true) {
                    deletePlaylist(playlist)
                }
            )
        )
    }

    private fun renamePlaylist(playlist: Playlist) {
        Sheets.input(
            this, getString(R.string.rename), getString(R.string.playlist_name_hint),
            playlist.name, getString(R.string.save)
        ) { newName ->
            PlaylistStore.renamePlaylist(this, playlist.id, newName)
            refreshAll()
        }
    }

    private fun deletePlaylist(playlist: Playlist) {
        Sheets.confirm(
            this, getString(R.string.delete_playlist_title),
            getString(R.string.delete_playlist_message, playlist.name),
            getString(R.string.delete), destructive = true
        ) {
            PlaylistStore.deletePlaylist(this, playlist.id)
            refreshAll()
        }
    }

    private fun showHistoryActions(entry: HistoryEntry) {
        Sheets.actions(
            this, entry.title, null,
            listOf(
                Sheets.Action(getString(R.string.share), R.drawable.ic_share) {
                    Flows.shareVideo(this, entry.videoId, entry.title)
                },
                Sheets.Action(getString(R.string.add_to_playlist), R.drawable.ic_playlist_add) {
                    Flows.addToPlaylist(
                        this, entry.toSavedVideo(),
                        notify = { message -> Toast.makeText(this, message, Toast.LENGTH_SHORT).show() },
                        onChanged = { }
                    )
                },
                Sheets.Action(getString(R.string.remove_from_history), R.drawable.ic_delete, destructive = true) {
                    HistoryStore.remove(this, entry.videoId)
                    refreshAll()
                }
            )
        )
    }

    /** Explains how to make the app the one that opens YouTube links, then opens the right Android setting. */
    private fun showDefaultLinksSheet() {
        val message = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) R.string.links_message_new else R.string.links_message_old
        Sheets.confirm(
            this, getString(R.string.links_title), getString(message),
            getString(R.string.links_open_settings), destructive = false
        ) { openLinkSettings() }
    }

    private fun openLinkSettings() {
        val app = Uri.parse("package:" + packageName)
        val screens = mutableListOf<Intent>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            screens.add(Intent(Settings.ACTION_APP_OPEN_BY_DEFAULT_SETTINGS, app))   // "Open by default"
        }
        screens.add(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, app))       // the app's settings page
        for (screen in screens) {
            try {
                startActivity(screen)
                return
            } catch (e: Exception) {
                // try the next one
            }
        }
    }

    private fun confirmClearHistory() {
        Sheets.confirm(
            this, getString(R.string.clear_history_title), getString(R.string.clear_history_message),
            getString(R.string.clear), destructive = true
        ) {
            HistoryStore.clear(this)
            refreshAll()
        }
    }
}
