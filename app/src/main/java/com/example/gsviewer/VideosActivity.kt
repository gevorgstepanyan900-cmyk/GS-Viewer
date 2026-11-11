package com.example.gsviewer

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

class VideosActivity : Activity() {
    private lateinit var playlistId: String
    private lateinit var adapter: VideoAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_videos)
        playlistId = intent.getStringExtra("playlist_id") ?: return

        val recycler = findViewById<RecyclerView>(R.id.recycler_videos)
        recycler.layoutManager = LinearLayoutManager(this)

        val playlists = PlaylistStore.getPlaylists(this)
        val playlist = playlists.find { it.id == playlistId }
        title = playlist?.name ?: "Ցանկ"

        val videos = playlist?.videos ?: mutableListOf()
        adapter = VideoAdapter(
            items = videos,
            onClick = { video ->
                val intent = Intent(this, MainActivity::class.java).apply {
                    putExtra("open_url", video.url)
                    flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                }
                startActivity(intent)
            },
            onLongClick = { video -> removeVideo(video) }
        )
        recycler.adapter = adapter
    }

    private fun removeVideo(video: SavedVideo) {
        AlertDialog.Builder(this)
            .setTitle("Հեռացնել տեսանյութը")
            .setMessage(video.title)
            .setPositiveButton("Հեռացնել") { _, _ ->
                val playlists = PlaylistStore.getPlaylists(this)
                val playlist = playlists.find { it.id == playlistId }
                playlist?.videos?.removeAll { it.videoId == video.videoId }
                PlaylistStore.savePlaylists(this, playlists)
                recreate()
            }
            .setNegativeButton("Չեղարկել", null)
            .show()
    }
}
