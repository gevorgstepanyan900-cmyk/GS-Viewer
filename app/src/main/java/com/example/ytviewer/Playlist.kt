package com.example.gsviewer

data class Playlist(
    val id: String = System.currentTimeMillis().toString(),
    var name: String = "",
    val videos: MutableList<SavedVideo>? = mutableListOf()
)