package com.example.gsviewer

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

class PlaylistActivity : Activity() {
    private lateinit var recycler: RecyclerView
    private lateinit var emptyView: View
    private lateinit var subtitle: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_playlists)
        recycler = findViewById(R.id.recycler_playlists)
        emptyView = findViewById(R.id.empty_view)
        subtitle = findViewById(R.id.subtitle)
        recycler.layoutManager = LinearLayoutManager(this)

        findViewById<View>(R.id.btn_back).setOnClickListener { finish() }
        findViewById<View>(R.id.btn_add_playlist).setOnClickListener { showCreatePlaylistDialog() }
    }

    override fun onResume() {
        super.onResume()
        refreshList()
    }

    private fun refreshList() {
        val playlists = PlaylistStore.getPlaylists(this)
        val totalVideos = playlists.sumOf { it.videos?.size ?: 0 }
        subtitle.text = "${playlists.size} ցանկ · $totalVideos տեսանյութ"
        emptyView.visibility = if (playlists.isEmpty()) View.VISIBLE else View.GONE
        recycler.visibility = if (playlists.isEmpty()) View.GONE else View.VISIBLE

        recycler.adapter = PlaylistAdapter(
            items = playlists,
            onClick = { playlist ->
                val intent = Intent(this, VideosActivity::class.java)
                intent.putExtra("playlist_id", playlist.id)
                startActivity(intent)
            },
            onMore = { playlist -> showPlaylistOptionsDialog(playlist) }
        )
    }

    private fun showCreatePlaylistDialog() {
        val input = EditText(this)
        input.hint = "Ցանկի անունը"
        input.setSingleLine()
        AlertDialog.Builder(this)
            .setTitle("Նոր ցանկ")
            .setView(input)
            .setPositiveButton("Ստեղծել") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) {
                    val playlists = PlaylistStore.getPlaylists(this)
                    playlists.add(Playlist(name = name))
                    PlaylistStore.savePlaylists(this, playlists)
                    refreshList()
                } else {
                    Toast.makeText(this, "Անունը դատարկ է", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Չեղարկել", null)
            .show()
    }

    private fun showPlaylistOptionsDialog(playlist: Playlist) {
        val options = arrayOf("Վերանվանել", "Ջնջել")
        AlertDialog.Builder(this)
            .setTitle(playlist.name)
            .setItems(options) { _, which ->
                if (which == 0) renamePlaylist(playlist) else deletePlaylist(playlist)
            }
            .show()
    }

    private fun renamePlaylist(playlist: Playlist) {
        val input = EditText(this)
        input.setText(playlist.name)
        input.setSingleLine()
        AlertDialog.Builder(this)
            .setTitle("Վերանվանել")
            .setView(input)
            .setPositiveButton("Պահպանել") { _, _ ->
                val newName = input.text.toString().trim()
                if (newName.isNotEmpty()) {
                    val all = PlaylistStore.getPlaylists(this)
                    all.find { it.id == playlist.id }?.name = newName
                    PlaylistStore.savePlaylists(this, all)
                    refreshList()
                }
            }
            .setNegativeButton("Չեղարկել", null)
            .show()
    }

    private fun deletePlaylist(playlist: Playlist) {
        AlertDialog.Builder(this)
            .setTitle("Ջնջել ցանկը")
            .setMessage("Համոզված՞ եք, որ ուզում եք ջնջել '${playlist.name}' ցանկը:")
            .setPositiveButton("Այո") { _, _ ->
                val all = PlaylistStore.getPlaylists(this)
                all.removeAll { it.id == playlist.id }
                PlaylistStore.savePlaylists(this, all)
                refreshList()
            }
            .setNegativeButton("Ոչ", null)
            .show()
    }
}
