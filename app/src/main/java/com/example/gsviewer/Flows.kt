package com.example.gsviewer

import android.app.Activity

/** Multi-step user journeys that are used from several screens. */
object Flows {

    /**
     * "Add this video to a playlist": choose a playlist (or make a new one), save, tell the user.
     * [notify] shows the result message, [onChanged] is called when something was saved.
     */
    fun addToPlaylist(
        activity: Activity,
        video: SavedVideo,
        notify: (String) -> Unit,
        onChanged: () -> Unit
    ) {
        val playlists = PlaylistStore.getPlaylists(activity)
        Sheets.playlistPicker(
            activity, video, playlists,
            onPick = { playlist ->
                val added = PlaylistStore.addVideoToPlaylist(activity, playlist.id, video)
                if (added) {
                    notify(activity.getString(R.string.msg_added, playlist.name))
                    onChanged()
                } else {
                    notify(activity.getString(R.string.msg_already))
                }
            },
            onNew = {
                Sheets.input(
                    activity,
                    activity.getString(R.string.new_playlist),
                    activity.getString(R.string.playlist_name_hint),
                    "",
                    activity.getString(R.string.create)
                ) { name ->
                    PlaylistStore.createPlaylist(activity, name, video)
                    notify(activity.getString(R.string.msg_created, name))
                    onChanged()
                }
            }
        )
    }
}
