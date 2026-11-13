package com.example.gsviewer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager

/**
 * Keeps the app (and its WebView) alive while a video is playing and the screen is off.
 *
 *  - runs as a foreground "media playback" service (notification with Play/Pause and Close),
 *  - holds a partial wake lock + Wi-Fi lock while the video is playing, so CPU and network
 *    keep working with the screen off.
 * All playback logic (repeat, auto-next, playlists) stays in MainActivity / the page.
 */
class PlaybackService : Service() {

    companion object {
        const val CHANNEL_ID = "gsviewer_playback"
        const val NOTIFICATION_ID = 4242
        const val EXTRA_PLAYING = "playing"
        const val EXTRA_TITLE = "title"

        // After a video stops (e.g. between two playlist videos) the locks are kept this long.
        private const val IDLE_RELEASE_MS = 90_000L

        fun ensureChannel(context: Context) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                val channel = NotificationChannel(
                    CHANNEL_ID, "GSViewer", NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Նվագարկում ֆոնում"
                    setShowBadge(false)
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

        fun buildNotification(context: Context, title: String, playing: Boolean): Notification {
            ensureChannel(context)
            val open = PendingIntent.getActivity(
                context, 0,
                Intent(context, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val toggleIcon =
                if (playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play
            val toggleTitle = if (playing) "Դադար" else "Նվագարկել"

            return Notification.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle(title)
                .setContentText(if (playing) "Նվագարկվում է" else "Դադար")
                .setContentIntent(open)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .addAction(
                    Notification.Action.Builder(
                        Icon.createWithResource(context, toggleIcon),
                        toggleTitle,
                        controlIntent(context, MainActivity.CONTROL_TYPE_PLAY_PAUSE)
                    ).build()
                )
                .addAction(
                    Notification.Action.Builder(
                        Icon.createWithResource(context, android.R.drawable.ic_menu_close_clear_cancel),
                        "Փակել",
                        controlIntent(context, MainActivity.CONTROL_TYPE_CLOSE)
                    ).build()
                )
                .setStyle(Notification.MediaStyle().setShowActionsInCompactView(0, 1))
                .build()
        }
    }

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private val handler = Handler(Looper.getMainLooper())
    private val releaseRunnable = Runnable { releaseLocks() }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        // Must call startForeground right away after startForegroundService().
        enterForeground(buildNotification(this, "GSViewer", true))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val playing = intent?.getBooleanExtra(EXTRA_PLAYING, true) ?: true
        val title = intent?.getStringExtra(EXTRA_TITLE) ?: "GSViewer"
        enterForeground(buildNotification(this, title, playing))

        handler.removeCallbacks(releaseRunnable)
        if (playing) {
            acquireLocks()
        } else {
            handler.postDelayed(releaseRunnable, IDLE_RELEASE_MS)
        }
        return START_NOT_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // The app was swiped away from the recent apps: stop completely.
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        handler.removeCallbacks(releaseRunnable)
        releaseLocks()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
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

    private fun acquireLocks() {
        try {
            if (wakeLock == null) {
                val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "GSViewer:playback").apply {
                    setReferenceCounted(false)
                }
            }
            if (wakeLock?.isHeld != true) wakeLock?.acquire(8 * 60 * 60 * 1000L)
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
