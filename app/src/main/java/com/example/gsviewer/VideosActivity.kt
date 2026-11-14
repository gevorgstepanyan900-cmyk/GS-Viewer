package com.example.gsviewer

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

class VideosActivity : Activity() {
    private var playlistId: String = ""
    private lateinit var recycler: RecyclerView
    private lateinit var emptyView: View
    private lateinit var actionRow: View
    private lateinit var headerTitle: TextView
    private lateinit var subtitle: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_videos)
        playlistId = intent.getStringExtra("playlist_id") ?: run { finish(); return }

        recycler = findViewById(R.id.recycler_videos)
        emptyView = findViewById(R.id.empty_view)
        actionRow = findViewById(R.id.action_row)
        headerTitle = findViewById(R.id.header_title)
        subtitle = findViewById(R.id.subtitle)
        recycler.layoutManager = LinearLayoutManager(this)

        findViewById<View>(R.id.btn_back).setOnClickListener { finish() }
        findViewById<View>(R.id.btn_play_all).setOnClickListener { playAt(0) }
        findViewById<View>(R.id.btn_shuffle).setOnClickListener {
            val size = currentVideos().size
            if (size > 0) playAt((0 until size).random())
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun currentVideos(): MutableList<SavedVideo> =
        PlaylistStore.getPlaylists(this).find { it.id == playlistId }?.videos ?: mutableListOf()

    private fun refresh() {
        val playlist = PlaylistStore.getPlaylists(this).find { it.id == playlistId }
        if (playlist == null) { finish(); return }
        val videos = playlist.videos ?: mutableListOf()
        headerTitle.text = playlist.name
        subtitle.text = "${videos.size} տեսանյութ"

        val empty = videos.isEmpty()
        emptyView.visibility = if (empty) View.VISIBLE else View.GONE
        actionRow.visibility = if (empty) View.GONE else View.VISIBLE
        recycler.visibility = if (empty) View.GONE else View.VISIBLE

        recycler.adapter = VideoAdapter(
            items = videos,
            onClick = { video -> playAt(videos.indexOf(video)) },
            onMore = { video -> removeVideo(video) }
        )
    }

    private fun playAt(index: Int) {
        val videos = currentVideos()
        if (index < 0 || index >= videos.size) return
        val video = videos[index]
        val intent = Intent(this, MainActivity::class.java).apply {
            putExtra("open_url", video.url)
            putExtra("playlist_id", playlistId)
            putExtra("video_index", index)
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        startActivity(intent)
    }

    private fun removeVideo(video: SavedVideo) {
        AlertDialog.Builder(this)
            .setTitle("Հեռացնել տեսանյութը")
            .setMessage(video.title)
            .setPositiveButton("Հեռացնել") { _, _ ->
                val playlists = PlaylistStore.getPlaylists(this)
                playlists.find { it.id == playlistId }?.videos?.removeAll { it.videoId == video.videoId }
                PlaylistStore.savePlaylists(this, playlists)
                refresh()
            }
            .setNegativeButton("Չեղարկել", null)
            .show()
    }
}
