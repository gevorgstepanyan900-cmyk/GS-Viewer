package com.example.gsviewer

import android.app.Activity
import android.content.Intent

/** Multi-step user journeys that are used from several screens. */
object Flows {

    /** The text that is shared: the title and the short link (just the link when there is no title). */
    fun shareText(title: String?, videoId: String): String {
        val link = "https://youtu.be/" + videoId
        val clean = title?.trim()
        return if (clean.isNullOrEmpty()) link else clean + "\n" + link
    }

    /**
     * The share button: the video's title and link go to any app that accepts text (messengers, mail, notes ...),
     * exactly like in the YouTube app.
     */
    fun shareVideo(activity: Activity, videoId: String, title: String?) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            if (!title.isNullOrBlank()) putExtra(Intent.EXTRA_SUBJECT, title.trim())
            putExtra(Intent.EXTRA_TEXT, shareText(title, videoId))
        }
        val chooser = Intent.createChooser(send, activity.getString(R.string.share_chooser_title)).apply {
            // Opening the share sheet is not "leaving the app": the video must not jump into the mini window.
            addFlags(Intent.FLAG_ACTIVITY_NO_USER_ACTION)
        }
        try {
            activity.startActivity(chooser)
        } catch (e: Exception) {
            // no app can take it: nothing to do
        }
    }

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
