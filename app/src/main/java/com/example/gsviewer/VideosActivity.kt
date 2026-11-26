package com.example.gsviewer

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

/** One playlist: play all, play a video, rename / delete the playlist, remove videos. */
class VideosActivity : Activity() {
    private lateinit var playlistId: String
    private lateinit var recycler: RecyclerView
    private lateinit var titleView: TextView
    private lateinit var metaView: TextView
    private lateinit var playAll: View
    private lateinit var emptyState: View

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_videos)
        playlistId = intent.getStringExtra("playlist_id") ?: run {
            finish()
            return
        }

        recycler = findViewById(R.id.recycler_videos)
        recycler.layoutManager = LinearLayoutManager(this)
        titleView = findViewById(R.id.playlist_title)
        metaView = findViewById(R.id.playlist_meta)
        playAll = findViewById(R.id.btn_play_all)
        emptyState = findViewById(R.id.empty_state)
        findViewById<android.widget.ImageView>(R.id.empty_icon).setImageResource(R.drawable.ic_library)
        findViewById<TextView>(R.id.empty_title).setText(R.string.empty_videos_title)
        findViewById<TextView>(R.id.empty_hint).setText(R.string.empty_videos_hint)

        findViewById<View>(R.id.btn_back).setOnClickListener { finish() }
        findViewById<View>(R.id.btn_playlist_more).setOnClickListener { showPlaylistActions() }
        playAll.setOnClickListener { play(0) }
    }

    override fun onResume() {
        super.onResume()
        if (::playlistId.isInitialized) refresh()
    }

    private fun refresh() {
        val playlist = PlaylistStore.getPlaylist(this, playlistId)
        if (playlist == null) {
            finish()
            return
        }
        val videos = playlist.videos ?: mutableListOf()
        titleView.text = playlist.name
        metaView.text = resources.getQuantityString(R.plurals.n_videos, videos.size, videos.size)
        playAll.visibility = if (videos.isEmpty()) View.GONE else View.VISIBLE
        emptyState.visibility = if (videos.isEmpty()) View.VISIBLE else View.GONE

        recycler.adapter = VideoAdapter(
            items = videos,
            onClick = { index, _ -> play(index) },
            onMore = { video -> showVideoActions(video) }
        )
    }

    /** Opens the player on video [index] of this playlist (auto-next continues from there). */
    private fun play(index: Int) {
        val videos = PlaylistStore.getPlaylist(this, playlistId)?.videos ?: return
        val video = videos.getOrNull(index) ?: return
        val intent = Intent(this, MainActivity::class.java).apply {
            putExtra("open_url", video.url)
            putExtra("playlist_id", playlistId)
            putExtra("video_index", index)
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        startActivity(intent)
    }

    private fun showVideoActions(video: SavedVideo) {
        val index = PlaylistStore.getPlaylist(this, playlistId)?.videos
            ?.indexOfFirst { it.videoId == video.videoId } ?: -1
        Sheets.actions(
            this, video.title, null,
            listOf(
                Sheets.Action(getString(R.string.play), R.drawable.ic_play) { play(index) },
                Sheets.Action(getString(R.string.share), R.drawable.ic_share) {
                    Flows.shareVideo(this, video.videoId, video.title)
                },
                Sheets.Action(getString(R.string.remove_video_title), R.drawable.ic_delete, destructive = true) {
                    Sheets.confirm(
                        this, getString(R.string.remove_video_title), video.title,
                        getString(R.string.remove), destructive = true
                    ) {
                        PlaylistStore.removeVideo(this, playlistId, video.videoId)
                        refresh()
                    }
                }
            )
        )
    }

    private fun showPlaylistActions() {
        val playlist = PlaylistStore.getPlaylist(this, playlistId) ?: return
        Sheets.actions(
            this, playlist.name, null,
            listOf(
                Sheets.Action(getString(R.string.rename), R.drawable.ic_edit) {
                    Sheets.input(
                        this, getString(R.string.rename), getString(R.string.playlist_name_hint),
                        playlist.name, getString(R.string.save)
                    ) { newName ->
                        PlaylistStore.renamePlaylist(this, playlistId, newName)
                        refresh()
                    }
                },
                Sheets.Action(getString(R.string.delete_playlist_title), R.drawable.ic_delete, destructive = true) {
                    Sheets.confirm(
                        this, getString(R.string.delete_playlist_title),
                        getString(R.string.delete_playlist_message, playlist.name),
                        getString(R.string.delete), destructive = true
                    ) {
                        PlaylistStore.deletePlaylist(this, playlistId)
                        finish()
                    }
                }
            )
        )
    }
}
