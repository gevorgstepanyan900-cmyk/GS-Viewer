package com.example.gsviewer

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

object PlaylistStore {
    private const val PREFS = "gsviewer_playlists_v11"
    private const val KEY = "playlists"
    private val gson = Gson()

    fun getPlaylists(context: Context): MutableList<Playlist> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, null) ?: return mutableListOf()
        val type = object : TypeToken<MutableList<Playlist>>() {}.type
        return try {
            val list = gson.fromJson<MutableList<Playlist>>(raw, type)
            list ?: mutableListOf()
        } catch (e: Exception) {
            mutableListOf()
        }
    }

    fun savePlaylists(context: Context, playlists: List<Playlist>) {
        val json = gson.toJson(playlists)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, json).apply()
    }

    fun getPlaylist(context: Context, playlistId: String): Playlist? =
        getPlaylists(context).find { it.id == playlistId }

    fun createPlaylist(context: Context, name: String, firstVideo: SavedVideo? = null): Playlist {
        val playlists = getPlaylists(context)
        val playlist = Playlist(name = name)
        if (firstVideo != null) playlist.videos?.add(firstVideo)
        playlists.add(playlist)
        savePlaylists(context, playlists)
        return playlist
    }

    fun renamePlaylist(context: Context, playlistId: String, newName: String) {
        val all = getPlaylists(context)
        all.find { it.id == playlistId }?.name = newName
        savePlaylists(context, all)
    }

    fun deletePlaylist(context: Context, playlistId: String) {
        val all = getPlaylists(context)
        all.removeAll { it.id == playlistId }
        savePlaylists(context, all)
    }

    fun removeVideo(context: Context, playlistId: String, videoId: String) {
        val all = getPlaylists(context)
        all.find { it.id == playlistId }?.videos?.removeAll { it.videoId == videoId }
        savePlaylists(context, all)
    }

    /** True when the video is in at least one playlist. */
    fun isVideoSaved(context: Context, videoId: String): Boolean =
        getPlaylists(context).any { playlist -> playlist.videos?.any { it.videoId == videoId } == true }

    fun addVideoToPlaylist(context: Context, playlistId: String, video: SavedVideo): Boolean {
        val playlists = getPlaylists(context)
        val playlist = playlists.find { it.id == playlistId } ?: return false

        val videos = playlist.videos ?: mutableListOf()
        if (videos.any { it.videoId == video.videoId }) return false

        videos.add(0, video)
        val updatedPlaylist = playlist.copy(videos = videos)

        val index = playlists.indexOfFirst { it.id == playlistId }
        if (index != -1) {
            playlists[index] = updatedPlaylist
            savePlaylists(context, playlists)
            return true
        }
        return false
    }
}
