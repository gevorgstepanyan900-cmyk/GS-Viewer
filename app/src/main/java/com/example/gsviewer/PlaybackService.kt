package com.example.gsviewer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.Icon
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.abs

/** What the lock screen / notification shows and can do. */
data class MediaState(
    val title: String,
    val subtitle: String,
    val playing: Boolean,
    val hasPrevious: Boolean,
    val hasNext: Boolean,
    val durationMs: Long,      // 0 = unknown (live stream)
    val positionMs: Long,      // -1 = unknown
    val thumbUrl: String?
) {
    /** Same screen content, ignoring the (constantly changing) position. */
    fun sameAs(other: MediaState): Boolean = copy(positionMs = 0L) == other.copy(positionMs = 0L)

    /** True when the position differs from where the system would expect it after [elapsedMs]. */
    fun positionDrifted(previous: MediaState, elapsedMs: Long): Boolean {
        if (positionMs < 0 || previous.positionMs < 0) return false
        val expected = previous.positionMs + (if (previous.playing) elapsedMs else 0L)
        return abs(positionMs - expected) > 3000L
    }
}

/**
 * Keeps the app (and its WebView) alive while a video plays with the screen off:
 *  - foreground "media playback" service with a media notification:
 *    Previous | Play/Pause | Next | Close,
 *  - a MediaSession: the lock screen shows the title, the picture, previous / next and a seek bar;
 *    Bluetooth / headset buttons work too,
 *  - partial wake lock + Wi-Fi lock while playing. They are renewed every few minutes (never a fixed
 *    hours-long timeout), so even a 30-hour video keeps playing.
 * All playback logic stays in MainActivity / the page: every button only sends a command to it.
 */
class PlaybackService : Service() {

    companion object {
        const val CHANNEL_ID = "gsviewer_playback"
        const val NOTIFICATION_ID = 4242

        // After a video stops (e.g. between two playlist videos) the locks are kept this long.
        private const val IDLE_RELEASE_MS = 90_000L

        // The "Close" button of the lock-screen / media card (Android 13+ shows it next to the controls).
        private const val CUSTOM_ACTION_CLOSE = "com.example.gsviewer.CLOSE"

        // Locks are taken for a short time and renewed while playing: no fixed multi-hour limit.
        private const val LOCK_TIMEOUT_MS = 15L * 60L * 1000L
        private const val LOCK_RENEW_MS = 5L * 60L * 1000L

        @Volatile
        private var instance: PlaybackService? = null

        @Volatile
        private var pending: MediaState? = null

        /** Shows / updates the media notification and the lock-screen session (starts the service if needed). */
        fun update(context: Context, state: MediaState) {
            pending = state
            try {
                val service = instance
                if (service != null) {
                    service.render(state)
                } else {
                    context.startForegroundService(Intent(context, PlaybackService::class.java))
                }
            } catch (e: Exception) {
                // Starting a foreground service can be refused by the system in rare situations.
            }
        }

        fun stop(context: Context) {
            pending = null
            try {
                context.stopService(Intent(context, PlaybackService::class.java))
            } catch (e: Exception) {
            }
        }

        fun ensureChannel(context: Context) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                val channel = NotificationChannel(
                    CHANNEL_ID, "GSViewer", NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = context.getString(R.string.notif_channel_desc)
                    setShowBadge(false)
                    lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                }
                nm.createNotificationChannel(channel)
            }
        }

        private fun controlIntent(context: Context, type: Int): PendingIntent =
            PendingIntent.getBroadcast(
                context, 100 + type,
                Intent(MainActivity.ACTION_MEDIA_CONTROL)
                    .setPackage(context.packageName)
                    .putExtra(MainActivity.EXTRA_CONTROL_TYPE, type),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )

        private fun action(context: Context, iconRes: Int, label: String, type: Int): Notification.Action =
            Notification.Action.Builder(
                Icon.createWithResource(context, iconRes), label, controlIntent(context, type)
            ).build()

        fun buildNotification(
            context: Context,
            state: MediaState,
            token: MediaSession.Token?,
            artwork: Bitmap?
        ): Notification {
            ensureChannel(context)
            val open = PendingIntent.getActivity(
                context, 0,
                Intent(context, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val toggleIcon =
                if (state.playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play
            val toggleLabel = context.getString(if (state.playing) R.string.cd_pause else R.string.cd_play)

            val builder = Notification.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle(state.title)
                .setContentText(state.subtitle)
                .setContentIntent(open)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .addAction(
                    action(context, android.R.drawable.ic_media_previous,
                        context.getString(R.string.notif_previous), MainActivity.CONTROL_TYPE_PREVIOUS)
                )
                .addAction(action(context, toggleIcon, toggleLabel, MainActivity.CONTROL_TYPE_PLAY_PAUSE))
                .addAction(
                    action(context, android.R.drawable.ic_media_next,
                        context.getString(R.string.notif_next), MainActivity.CONTROL_TYPE_NEXT)
                )
                .addAction(
                    action(context, android.R.drawable.ic_menu_close_clear_cancel,
                        context.getString(R.string.notif_close), MainActivity.CONTROL_TYPE_CLOSE)
                )
            if (artwork != null) builder.setLargeIcon(artwork)

            val style = Notification.MediaStyle().setShowActionsInCompactView(0, 1, 2)
            if (token != null) style.setMediaSession(token)
            builder.setStyle(style)
            return builder.build()
        }
    }

    private var session: MediaSession? = null
    private var lastState: MediaState? = null
    private var artwork: Bitmap? = null
    private var artworkUrl: String? = null

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private val handler = Handler(Looper.getMainLooper())
    private val releaseRunnable = Runnable { releaseLocks() }
    private val renewRunnable = object : Runnable {
        override fun run() {
            val state = lastState
            if (state != null && state.playing) {
                acquireLocks()
                handler.postDelayed(this, LOCK_RENEW_MS)
            }
        }
    }

    /** Every lock-screen / headset / notification command goes to the player as a broadcast. */
    private val sessionCallback = object : MediaSession.Callback() {
        override fun onPlay() = sendControl(MainActivity.CONTROL_TYPE_PLAY)
        override fun onPause() = sendControl(MainActivity.CONTROL_TYPE_PAUSE)
        override fun onStop() = sendControl(MainActivity.CONTROL_TYPE_PAUSE)
        override fun onSkipToNext() = sendControl(MainActivity.CONTROL_TYPE_NEXT)
        override fun onSkipToPrevious() = sendControl(MainActivity.CONTROL_TYPE_PREVIOUS)
        override fun onSeekTo(pos: Long) = sendControl(MainActivity.CONTROL_TYPE_SEEK_TO, pos)
        override fun onCustomAction(action: String, extras: Bundle?) {
            if (action == CUSTOM_ACTION_CLOSE) sendControl(MainActivity.CONTROL_TYPE_CLOSE)
        }
    }

    private fun sendControl(type: Int, positionMs: Long = -1L) {
        sendBroadcast(
            Intent(MainActivity.ACTION_MEDIA_CONTROL)
                .setPackage(packageName)
                .putExtra(MainActivity.EXTRA_CONTROL_TYPE, type)
                .putExtra(MainActivity.EXTRA_POSITION_MS, positionMs)
        )
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        session = MediaSession(this, "GSViewer").apply {
            setCallback(sessionCallback)
            isActive = true
        }
        // startForeground must be called right away after startForegroundService().
        render(pending ?: MediaState("GSViewer", "YouTube", true, false, false, 0L, -1L, null))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        pending?.let { render(it) }
        return START_NOT_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // The app was swiped away from the recent apps: stop completely.
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        instance = null
        handler.removeCallbacksAndMessages(null)
        releaseLocks()
        try {
            session?.isActive = false
            session?.release()
        } catch (e: Exception) {
        }
        session = null
        artwork = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    // ------------------------------------------------------------------ drawing the state

    private fun render(state: MediaState) {
        lastState = state

        // locks: kept while playing (renewed regularly), released shortly after the video stops
        handler.removeCallbacks(releaseRunnable)
        handler.removeCallbacks(renewRunnable)
        if (state.playing) {
            acquireLocks()
            handler.postDelayed(renewRunnable, LOCK_RENEW_MS)
        } else {
            handler.postDelayed(releaseRunnable, IDLE_RELEASE_MS)
        }

        if (state.thumbUrl != artworkUrl) {
            artwork = null   // never show the picture of the previous video
        }
        updateSession(state)
        enterForeground(buildNotification(this, state, session?.sessionToken, artwork))
        loadArtwork(state.thumbUrl)
    }

    private fun updateSession(state: MediaState) {
        val s = session ?: return
        try {
            val meta = MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, state.title)
                .putString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE, state.title)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, state.subtitle)
            if (state.durationMs > 0) meta.putLong(MediaMetadata.METADATA_KEY_DURATION, state.durationMs)
            artwork?.let {
                meta.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, it)
                meta.putBitmap(MediaMetadata.METADATA_KEY_ART, it)
            }
            s.setMetadata(meta.build())

            var actions = PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or
                PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_STOP
            if (state.hasPrevious) actions = actions or PlaybackState.ACTION_SKIP_TO_PREVIOUS
            if (state.hasNext) actions = actions or PlaybackState.ACTION_SKIP_TO_NEXT
            val seekable = state.durationMs > 0 && state.positionMs >= 0
            if (seekable) actions = actions or PlaybackState.ACTION_SEEK_TO

            s.setPlaybackState(
                PlaybackState.Builder()
                    .setActions(actions)
                    .addCustomAction(
                        PlaybackState.CustomAction.Builder(
                            CUSTOM_ACTION_CLOSE, getString(R.string.notif_close), R.drawable.ic_close
                        ).build()
                    )
                    .setState(
                        if (state.playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED,
                        if (seekable) state.positionMs else PlaybackState.PLAYBACK_POSITION_UNKNOWN,
                        if (state.playing) 1f else 0f,
                        SystemClock.elapsedRealtime()
                    )
                    .build()
            )
            s.isActive = true
        } catch (e: Exception) {
            // never let a lock-screen detail stop the playback
        }
    }

    private fun enterForeground(notification: Notification) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            stopSelf()
        }
    }

    /** The video picture for the lock screen (one small bitmap, loaded once per video). */
    private fun loadArtwork(url: String?) {
        if (url == null || url == artworkUrl) return
        artworkUrl = url
        Thread {
            var bitmap: Bitmap? = null
            try {
                val connection = URL(url).openConnection() as HttpURLConnection
                connection.connectTimeout = 8000
                connection.readTimeout = 8000
                connection.inputStream.use { bitmap = BitmapFactory.decodeStream(it) }
            } catch (e: Exception) {
            }
            val result = bitmap
            if (result != null) {
                handler.post {
                    if (artworkUrl == url && instance === this) {
                        artwork = result
                        lastState?.let { render(it) }
                    }
                }
            }
        }.start()
    }

    // ------------------------------------------------------------------ locks

    private fun acquireLocks() {
        try {
            if (wakeLock == null) {
                val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "GSViewer:playback").apply {
                    setReferenceCounted(false)
                }
            }
            // not reference counted: this simply extends the lock by another LOCK_TIMEOUT_MS
            wakeLock?.acquire(LOCK_TIMEOUT_MS)
        } catch (e: Exception) {
        }
        try {
            if (wifiLock == null) {
                val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                @Suppress("DEPRECATION")
                wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "GSViewer:wifi").apply {
                    setReferenceCounted(false)
                }
            }
            if (wifiLock?.isHeld != true) wifiLock?.acquire()
        } catch (e: Exception) {
        }
    }

    private fun releaseLocks() {
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        } catch (e: Exception) {
        }
        try {
            if (wifiLock?.isHeld == true) wifiLock?.release()
        } catch (e: Exception) {
        }
    }
}
