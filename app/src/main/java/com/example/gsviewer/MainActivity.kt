package com.example.gsviewer

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import android.util.Rational
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : Activity() {
    private lateinit var webView: WebView
    private lateinit var saveButton: TextView
    private lateinit var playlistButton: TextView
    private lateinit var repeatButton: TextView
    private lateinit var autoNextButton: TextView
    private lateinit var pipButton: TextView
    private lateinit var playPauseButton: TextView

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null
    private lateinit var rootFrame: FrameLayout

    // True PiP state flag
    private var isPipModeActive: Boolean = false

    // Loop & Playlist state
    private var isRepeatEnabled: Boolean = false
    private var isAutoNextEnabled: Boolean = false
    private var currentPlaylistId: String? = null
    private var currentVideoIndex: Int = -1

    // PiP Control Constants
    companion object {
        private const val ACTION_MEDIA_CONTROL = "com.example.gsviewer.MEDIA_CONTROL"
        private const val EXTRA_CONTROL_TYPE = "control_type"
        private const val CONTROL_TYPE_PLAY_PAUSE = 1
        private const val CONTROL_TYPE_REWIND = 2
        private const val CONTROL_TYPE_FORWARD = 3

        // Single source of truth for page-side logic. Installed once per document;
        // later injections only refresh state (see injectCoreProtectionScript).
        private const val CORE_JS = """
(function() {
    if (window.__gsCoreInstalled) { if (window.__gsScan) window.__gsScan(); return; }
    window.__gsCoreInstalled = true;

    try {
        Object.defineProperty(document, 'hidden', { get: function() { return false; }, configurable: true });
        Object.defineProperty(document, 'visibilityState', { get: function() { return 'visible'; }, configurable: true });
    } catch (e) {}

    // The main video: playing one first, otherwise the biggest one.
    window.__gsVideo = function() {
        var vs = document.querySelectorAll('video');
        var best = null, bestScore = -1;
        for (var i = 0; i < vs.length; i++) {
            var r = vs[i].getBoundingClientRect();
            var score = r.width * r.height + (vs[i].paused ? 0 : 1e9);
            if (score > bestScore) { bestScore = score; best = vs[i]; }
        }
        return best;
    };

    window.__gsIsPlaying = function(v) { return !!v && !v.paused && !v.ended; };

    // Push the REAL playback state to Android (only when it changed, or when forced).
    window.__gsReport = function(force) {
        var p = window.__gsIsPlaying(window.__gsVideo());
        if (force || window.__gsLastPlaying !== p) {
            window.__gsLastPlaying = p;
            if (window.AndroidBridge) window.AndroidBridge.onPlaybackStateChanged(p);
        }
    };

    function ytPlayer() {
        var p = document.getElementById('movie_player');
        return (p && typeof p.playVideo === 'function' && typeof p.pauseVideo === 'function') ? p : null;
    }

    // Play/pause through YouTube's own player first (keeps its UI/state machine consistent),
    // then verify on the <video> element and force it if YouTube did not react.
    window.__gsPlay = function() {
        var v = window.__gsVideo();
        var p = ytPlayer();
        try { if (p) p.playVideo(); } catch (e) {}
        if (v) {
            var pr = v.play();
            if (pr && pr.catch) pr.catch(function() {});
        }
        window.__gsReport(true);
        setTimeout(function() {
            var vv = window.__gsVideo();
            if (vv && vv.paused) { var q = vv.play(); if (q && q.catch) q.catch(function() {}); }
            window.__gsReport(true);
        }, 250);
    };

    window.__gsPause = function() {
        var v = window.__gsVideo();
        var p = ytPlayer();
        try { if (p) p.pauseVideo(); } catch (e) {}
        if (v && !v.paused) v.pause();
        window.__gsReport(true);
        setTimeout(function() {
            var vv = window.__gsVideo();
            if (vv && !vv.paused) vv.pause();
            window.__gsReport(true);
        }, 250);
    };

    window.__gsToggle = function() {
        var v = window.__gsVideo();
        if (!v) return;
        if (v.paused || v.ended) window.__gsPlay(); else window.__gsPause();
    };

    window.__gsSeek = function(delta) {
        var v = window.__gsVideo();
        if (!v) return;
        var t = v.currentTime + delta;
        if (t < 0) t = 0;
        if (v.duration && t > v.duration) t = v.duration;
        v.currentTime = t;
    };

    window.__gsRestart = function() {
        var v = window.__gsVideo();
        if (!v) return;
        v.currentTime = 0;
        var pr = v.play();
        if (pr && pr.catch) pr.catch(function() {});
    };

    function notifyEnded(video) {
        if (window.__gsRepeatEnabled) {
            window.__gsRestart();
        } else if (window.__gsAutoNextEnabled && window.__gsHasPlaylist && !video.__gsNextSent) {
            video.__gsNextSent = true;
            if (window.AndroidBridge) window.AndroidBridge.onVideoEnded();
        }
    }

    function bind(video) {
        video.setAttribute('data-gs-bound', 'true');
        video.__gsNextSent = false;
        video.addEventListener('ended', function() { notifyEnded(video); window.__gsReport(false); });
        ['play', 'playing', 'pause', 'emptied', 'abort'].forEach(function(ev) {
            video.addEventListener(ev, function() { window.__gsReport(false); });
        });
    }

    window.__gsScan = function() {
        var videos = document.querySelectorAll('video');
        for (var i = 0; i < videos.length; i++) {
            var video = videos[i];
            if (!video.hasAttribute('data-gs-bound')) bind(video);
            if (video.muted) video.muted = false;
            if (video.volume < 1.0) video.volume = 1.0;
            video.loop = !!window.__gsRepeatEnabled;

            // Fallback for the end of the video (YouTube sometimes skips 'ended').
            if (video.duration > 1 && !isNaN(video.duration) && !video.paused) {
                var remaining = video.duration - video.currentTime;
                if (remaining <= 0.35 && remaining >= 0) {
                    if (!window.__gsRepeatEnabled) notifyEnded(video);
                } else if (remaining > 2.0) {
                    video.__gsNextSent = false;
                }
            }
        }
        window.__gsReport(false);
    };

    window.__gsScanner = setInterval(window.__gsScan, 300);
    window.__gsScan();
})();
"""
    }

    private var isVideoPlaying: Boolean = true
    private var wasInPip: Boolean = false

    private val pipReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != ACTION_MEDIA_CONTROL) return
            when (intent.getIntExtra(EXTRA_CONTROL_TYPE, 0)) {
                CONTROL_TYPE_PLAY_PAUSE ->
                    webView.evaluateJavascript("(window.__gsToggle || function(){})();", null)
                CONTROL_TYPE_REWIND ->
                    webView.evaluateJavascript("(window.__gsSeek || function(){})(-10);", null)
                CONTROL_TYPE_FORWARD ->
                    webView.evaluateJavascript("(window.__gsSeek || function(){})(10);", null)
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface", "UnspecifiedRegisterReceiverFlag")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        rootFrame = FrameLayout(this)
        webView = WebView(this)

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            loadWithOverviewMode = true
            useWideViewPort = true
        }

        webView.addJavascriptInterface(WebAppInterface(), "AndroidBridge")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(pipReceiver, IntentFilter(ACTION_MEDIA_CONTROL), RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(pipReceiver, IntentFilter(ACTION_MEDIA_CONTROL))
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                if (customView != null) {
                    callback?.onCustomViewHidden()
                    return
                }
                customView = view
                customViewCallback = callback
                rootFrame.addView(customView, FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                ))
                webView.visibility = View.GONE
                hideAllFabs()
            }

            override fun onHideCustomView() {
                if (customView == null) return
                rootFrame.removeView(customView)
                customView = null
                customViewCallback?.onCustomViewHidden()
                webView.visibility = View.VISIBLE
                if (!isPipModeActive) {
                    showDefaultFabs()
                }
            }
        }

        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                super.onPageStarted(view, url, favicon)
                injectCoreProtectionScript()
            }
            override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                super.doUpdateVisitedHistory(view, url, isReload)
                updateSaveButton(url)
                syncPlaylistPositionWithUrl(url)
                injectCoreProtectionScript()
            }
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                updateSaveButton(url)
                injectCoreProtectionScript()
            }
        }

        rootFrame.addView(webView, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))

        playlistButton = makeFab("📁") {
            startActivity(Intent(this@MainActivity, PlaylistActivity::class.java))
        }
        rootFrame.addView(playlistButton, fabParams(Gravity.BOTTOM or Gravity.START, 16))

        saveButton = makeFab("⭐") { showPlaylistSelectionDialog() }
        saveButton.visibility = View.INVISIBLE
        rootFrame.addView(saveButton, fabParams(Gravity.BOTTOM or Gravity.END, 16))

        repeatButton = makeFab("🔂") {
            isRepeatEnabled = !isRepeatEnabled
            updateFabColor(repeatButton, isRepeatEnabled)
            val stateText = if (isRepeatEnabled) "Կրկնությունը ՄԻԱՑՎԱԾ Է" else "Կրկնությունը ԱՆՋԱՏՎԱԾ Է"
            Toast.makeText(this, stateText, Toast.LENGTH_SHORT).show()
            syncRepeatStateToJs()
        }
        rootFrame.addView(repeatButton, fabParams(Gravity.BOTTOM or Gravity.END, 80))

        autoNextButton = makeFab("⏭️") {
            isAutoNextEnabled = !isAutoNextEnabled
            updateFabColor(autoNextButton, isAutoNextEnabled)
            val stateText = if (isAutoNextEnabled) "Ավտո-հաջորդը ՄԻԱՑՎԱԾ Է" else "Ավտո-հաջորդը ԱՆՋԱՏՎԱԾ Է"
            Toast.makeText(this, stateText, Toast.LENGTH_SHORT).show()
            syncAutoNextStateToJs()
        }
        autoNextButton.visibility = View.GONE
        rootFrame.addView(autoNextButton, fabParams(Gravity.BOTTOM or Gravity.START, 80))

        pipButton = makeFab("🗗") { enterPipMode() }
        rootFrame.addView(pipButton, FrameLayout.LayoutParams(
            (48 * resources.displayMetrics.density).toInt(),
            (48 * resources.displayMetrics.density).toInt(),
            Gravity.TOP or Gravity.END
        ).apply {
            val m = (16 * resources.displayMetrics.density).toInt()
            topMargin = m
            rightMargin = m
        })

        // Play/pause in full mode: same toggle and same state as the PiP window's button.
        playPauseButton = makeFab("⏸") { webView.evaluateJavascript("(window.__gsToggle || function(){})();", null) }
        rootFrame.addView(playPauseButton, FrameLayout.LayoutParams(
            (48 * resources.displayMetrics.density).toInt(),
            (48 * resources.displayMetrics.density).toInt(),
            Gravity.TOP or Gravity.START
        ).apply {
            val m = (16 * resources.displayMetrics.density).toInt()
            topMargin = m
            leftMargin = m
        })

        setContentView(rootFrame)

        if (savedInstanceState != null) {
            currentPlaylistId = savedInstanceState.getString("gs_playlist_id")
            currentVideoIndex = savedInstanceState.getInt("gs_video_index", -1)
            isRepeatEnabled = savedInstanceState.getBoolean("gs_repeat", false)
            isAutoNextEnabled = savedInstanceState.getBoolean("gs_autonext", false)
            updateFabColor(repeatButton, isRepeatEnabled)
            updateFabColor(autoNextButton, isAutoNextEnabled)
            webView.restoreState(savedInstanceState)
            updateAutoNextButtonVisibility()
            if (webView.url == null) handleIntent(intent)
        } else {
            handleIntent(intent)
        }
        updateSaveButton(webView.url)
    }

    private fun hideAllFabs() {
        saveButton.visibility = View.GONE
        playlistButton.visibility = View.GONE
        repeatButton.visibility = View.GONE
        autoNextButton.visibility = View.GONE
        pipButton.visibility = View.GONE
        playPauseButton.visibility = View.GONE
    }

    private fun showDefaultFabs() {
        playlistButton.visibility = View.VISIBLE
        repeatButton.visibility = View.VISIBLE
        pipButton.visibility = View.VISIBLE
        updatePlayPauseButton()
        updateAutoNextButtonVisibility()
        updateSaveButton(webView.url)
    }

    private fun enterPipMode() {
        if (isInPictureInPictureMode) return
        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)) return
        isPipModeActive = true
        applyPipLayout(true)

        val params = PictureInPictureParams.Builder()
            .setAspectRatio(Rational(16, 9))
            .setActions(buildPipActions())
            .build()
        val entered = try {
            enterPictureInPictureMode(params)
        } catch (e: Exception) {
            false
        }
        if (entered) {
            // The cached flag may be stale: ask the page for the real state and refresh the buttons.
            syncPlaybackStateFromPage()
        } else {
            isPipModeActive = false
            applyPipLayout(false)
        }
    }

    /** Reads the real play/pause state from the page and refreshes the PiP play/pause button. */
    private fun syncPlaybackStateFromPage() {
        webView.evaluateJavascript(
            "(function(){try{var v=window.__gsVideo&&window.__gsVideo();return !!(v&&!v.paused&&!v.ended);}catch(e){return false;}})()"
        ) { result ->
            applyPlaybackState(result == "true")
        }
    }

    private fun buildPipActions(): List<RemoteAction> {
        val actions = mutableListOf<RemoteAction>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val rewIntent = PendingIntent.getBroadcast(
                this, CONTROL_TYPE_REWIND,
                Intent(ACTION_MEDIA_CONTROL).setPackage(packageName).putExtra(EXTRA_CONTROL_TYPE, CONTROL_TYPE_REWIND),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            actions.add(RemoteAction(
                Icon.createWithResource(this, android.R.drawable.ic_media_rew),
                "Հետ 10վ", "Հետ 10վ", rewIntent
            ))

            val playPauseIntent = PendingIntent.getBroadcast(
                this, CONTROL_TYPE_PLAY_PAUSE,
                Intent(ACTION_MEDIA_CONTROL).setPackage(packageName).putExtra(EXTRA_CONTROL_TYPE, CONTROL_TYPE_PLAY_PAUSE),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val iconRes = if (isVideoPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play
            val title = if (isVideoPlaying) "Դադար" else "Նվագարկել"
            actions.add(RemoteAction(
                Icon.createWithResource(this, iconRes),
                title, title, playPauseIntent
            ))

            val ffIntent = PendingIntent.getBroadcast(
                this, CONTROL_TYPE_FORWARD,
                Intent(ACTION_MEDIA_CONTROL).setPackage(packageName).putExtra(EXTRA_CONTROL_TYPE, CONTROL_TYPE_FORWARD),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            actions.add(RemoteAction(
                Icon.createWithResource(this, android.R.drawable.ic_media_ff),
                "Առաջ 10վ", "Առաջ 10վ", ffIntent
            ))
        }
        return actions
    }

    private fun updatePipActions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && isPipModeActive) {
            try {
                val params = PictureInPictureParams.Builder()
                    .setAspectRatio(Rational(16, 9))
                    .setActions(buildPipActions())
                    .build()
                setPictureInPictureParams(params)
            } catch (e: Exception) {}
        }
    }

    private fun applyPipLayout(enable: Boolean) {
        if (enable) {
            val js = """
                (function() {
                    var style = document.getElementById('gs-pip-style');
                    if (!style) {
                        style = document.createElement('style');
                        style.id = 'gs-pip-style';
                        style.innerHTML = `
                            body, html { overflow: hidden !important; background: #000 !important; margin: 0 !important; padding: 0 !important; }
                            ytm-mobile-topbar-renderer, header, ytm-pivot-bar-renderer, .related-chips-slot-renderer, 
                            ytm-item-section-renderer, ytm-single-column-watch-next-results-renderer > :not(.player-size),
                            .ytp-chrome-top, .ytp-chrome-bottom, .player-controls-content, .player-controls-top, .player-controls-bottom {
                                display: none !important;
                                opacity: 0 !important;
                                pointer-events: none !important;
                            }
                            #player-container-id, .player-container, #movie_player, .html5-video-player {
                                position: fixed !important;
                                top: 0 !important;
                                left: 0 !important;
                                width: 100vw !important;
                                height: 100vh !important;
                                z-index: 999999 !important;
                                background: #000 !important;
                            }
                            video {
                                position: fixed !important;
                                width: 100vw !important;
                                height: 100vh !important;
                                object-fit: contain !important;
                                top: 0 !important;
                                left: 0 !important;
                                background: #000 !important;
                            }
                        `;
                        document.head.appendChild(style);
                    }
                })();
            """.trimIndent()
            webView.evaluateJavascript(js, null)
        } else {
            val js = """
                (function() {
                    var style = document.getElementById('gs-pip-style');
                    if (style) style.remove();
                    window.dispatchEvent(new Event('resize'));
                })();
            """.trimIndent()
            webView.evaluateJavascript(js, null)
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (YoutubeHelper.extractVideoId(webView.url) != null || customView != null) {
            enterPipMode()
        }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration?) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        isPipModeActive = isInPictureInPictureMode
        if (isInPictureInPictureMode) {
            wasInPip = true
            hideAllFabs()
            applyPipLayout(true)
            webView.onResume()
            webView.resumeTimers()
            syncPlaybackStateFromPage()
        } else {
            applyPipLayout(false)
            webView.onResume()
            webView.resumeTimers()
            webView.requestFocus()
            if (customView == null) {
                showDefaultFabs()
            }
            // Keep the full-mode UI in sync with whatever happened in the mini window.
            webView.evaluateJavascript("(window.__gsReport || function(){})(true);", null)
            webView.postDelayed({ syncPlaybackStateFromPage() }, 400)
        }
    }

    private fun handleIntent(intent: Intent?) {
        if (intent == null) return
        val openUrl = intent.getStringExtra("open_url")
        if (intent.hasExtra("playlist_id")) {
            currentPlaylistId = intent.getStringExtra("playlist_id")
            currentVideoIndex = intent.getIntExtra("video_index", -1)
            if (currentPlaylistId != null && currentVideoIndex >= 0 && !isRepeatEnabled) {
                isAutoNextEnabled = true
                updateFabColor(autoNextButton, true)
            }
        } else if (openUrl != null) {
            // Opened a plain URL: it no longer belongs to any playlist.
            currentPlaylistId = null
            currentVideoIndex = -1
        }

        updateAutoNextButtonVisibility()
        syncAutoNextStateToJs()

        when {
            openUrl != null -> webView.loadUrl(openUrl)
            else -> if (webView.url == null) webView.loadUrl("https://m.youtube.com")
        }
    }

    private fun updateAutoNextButtonVisibility() {
        if (customView == null && !isPipModeActive && currentPlaylistId != null && currentVideoIndex >= 0) {
            autoNextButton.visibility = View.VISIBLE
        } else {
            autoNextButton.visibility = View.GONE
        }
    }

    private fun syncRepeatStateToJs() {
        val js = "window.__gsRepeatEnabled = $isRepeatEnabled; " +
            "document.querySelectorAll('video').forEach(function(v){ v.loop = $isRepeatEnabled; });"
        webView.evaluateJavascript(js, null)
    }

    private fun syncAutoNextStateToJs() {
        val hasPlaylist = (currentPlaylistId != null && currentVideoIndex >= 0)
        val js = "window.__gsAutoNextEnabled = $isAutoNextEnabled; window.__gsHasPlaylist = $hasPlaylist;"
        webView.evaluateJavascript(js, null)
    }

    private fun injectCoreProtectionScript() {
        val hasPlaylist = (currentPlaylistId != null && currentVideoIndex >= 0)
        val stateJs = "window.__gsRepeatEnabled = $isRepeatEnabled; " +
            "window.__gsAutoNextEnabled = $isAutoNextEnabled; " +
            "window.__gsHasPlaylist = $hasPlaylist;\n"
        webView.evaluateJavascript(stateJs + CORE_JS, null)
    }

    /** Keeps the playlist index correct when the user opens another video by hand. */
    private fun syncPlaylistPositionWithUrl(url: String?) {
        val pid = currentPlaylistId ?: return
        val id = YoutubeHelper.extractVideoId(url) ?: return
        val videos = PlaylistStore.getPlaylists(this).find { it.id == pid }?.videos
        val idx = videos?.indexOfFirst { it.videoId == id } ?: -1
        if (idx != currentVideoIndex) {
            currentVideoIndex = idx
            updateAutoNextButtonVisibility()
            syncAutoNextStateToJs()
        }
    }

    private inner class WebAppInterface {
        @JavascriptInterface
        fun onVideoEnded() {
            runOnUiThread {
                if (isRepeatEnabled) {
                    webView.evaluateJavascript("(window.__gsRestart || function(){})();", null)
                } else if (isAutoNextEnabled && currentPlaylistId != null && currentVideoIndex >= 0) {
                    playNextVideoInPlaylist()
                }
            }
        }

        @JavascriptInterface
        fun onPlaybackStateChanged(playing: Boolean) {
            runOnUiThread {
                applyPlaybackState(playing)
            }
        }
    }

    private fun playNextVideoInPlaylist() {
        val playlists = PlaylistStore.getPlaylists(this)
        val playlist = playlists.find { it.id == currentPlaylistId } ?: return
        val videos = playlist.videos ?: return

        val nextIndex = currentVideoIndex + 1
        if (nextIndex < videos.size) {
            currentVideoIndex = nextIndex
            val nextVideo = videos[nextIndex]
            webView.loadUrl(nextVideo.url)
            if (!isPipModeActive) {
                Toast.makeText(this, "Հաջորդ տեսանյութը: ${nextVideo.title}", Toast.LENGTH_SHORT).show()
            }
        } else {
            if (!isPipModeActive) {
                Toast.makeText(this, "Փլեյլիստի վերջին տեսանյութն ավարտվեց", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun updateFabColor(button: TextView, active: Boolean) {
        val color = if (active) Color.argb(255, 0x4C, 0xAF, 0x50) else Color.argb(230, 0x21, 0x96, 0xF3)
        button.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
        }
    }

    private fun makeFab(text: String, onClick: () -> Unit): TextView {
        val sizePx = (56 * resources.displayMetrics.density).toInt()
        return TextView(this).apply {
            this.text = text
            textSize = 22f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.argb(230, 0x21, 0x96, 0xF3))
            }
            layoutParams = FrameLayout.LayoutParams(sizePx, sizePx)
            setOnClickListener { onClick() }
        }
    }

    private fun fabParams(gravity: Int, marginSideDp: Int) = FrameLayout.LayoutParams(
        (56 * resources.displayMetrics.density).toInt(),
        (56 * resources.displayMetrics.density).toInt(),
        gravity
    ).apply {
        val m = (marginSideDp * resources.displayMetrics.density).toInt()
        leftMargin = m
        rightMargin = m
        bottomMargin = (96 * resources.displayMetrics.density).toInt()
    }

    private fun updateSaveButton(url: String?) {
        val id = YoutubeHelper.extractVideoId(url)
        if (customView == null && !isPipModeActive) {
            saveButton.visibility = if (id != null) View.VISIBLE else View.INVISIBLE
            playPauseButton.visibility = if (id != null) View.VISIBLE else View.GONE
        }
    }

    private fun updatePlayPauseButton() {
        playPauseButton.text = if (isVideoPlaying) "⏸" else "▶"
        if (customView == null && !isPipModeActive) {
            playPauseButton.visibility =
                if (YoutubeHelper.extractVideoId(webView.url) != null) View.VISIBLE else View.GONE
        }
    }

    /** Single entry point for any playback-state change coming from the page. */
    private fun applyPlaybackState(playing: Boolean) {
        isVideoPlaying = playing
        updatePlayPauseButton()
        updatePipActions()
    }

    private fun showPlaylistSelectionDialog() {
        val id = YoutubeHelper.extractVideoId(webView.url)
        if (id == null) return

        scope.launch {
            val video = withContext(Dispatchers.IO) { YoutubeHelper.fetchOEmbed(id) }
            val playlists = PlaylistStore.getPlaylists(this@MainActivity)

            val items = mutableListOf<String>()
            items.add("+ Նոր ցանկ")
            playlists.forEach { items.add(it.name) }

            AlertDialog.Builder(this@MainActivity)
                .setTitle("Ավելացնել ցանկում")
                .setItems(items.toTypedArray()) { _, which ->
                    if (which == 0) {
                        showCreatePlaylistDialog(video)
                    } else {
                        val selectedPlaylist = playlists[which - 1]
                        val added = PlaylistStore.addVideoToPlaylist(this@MainActivity, selectedPlaylist.id, video)
                        if (added) {
                            Toast.makeText(this@MainActivity, "Ավելացվեց՝ ${selectedPlaylist.name}", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(this@MainActivity, "Տեսանյութն արդեն կա ցանկում", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
                .show()
        }
    }

    private fun showCreatePlaylistDialog(video: SavedVideo) {
        val input = EditText(this)
        input.hint = "Ցանկի անունը"
        AlertDialog.Builder(this)
            .setTitle("Նոր նվագարկանկ")
            .setView(input)
            .setPositiveButton("Ստեղծել") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) {
                    val newPlaylist = Playlist(name = name)
                    newPlaylist.videos?.add(video)
                    val playlists = PlaylistStore.getPlaylists(this)
                    playlists.add(newPlaylist)
                    PlaylistStore.savePlaylists(this, playlists)
                    Toast.makeText(this, "Ստեղծվեց և ավելացվեց՝ '$name'", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Չեղարկել", null)
            .show()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView.saveState(outState)
        outState.putString("gs_playlist_id", currentPlaylistId)
        outState.putInt("gs_video_index", currentVideoIndex)
        outState.putBoolean("gs_repeat", isRepeatEnabled)
        outState.putBoolean("gs_autonext", isAutoNextEnabled)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (customView != null) {
                webView.webChromeClient?.onHideCustomView()
                return true
            }
            if (webView.canGoBack()) {
                webView.goBack()
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onPause() {
        super.onPause()
        if (!isPipModeActive) {
            webView.onPause()
        } else {
            webView.onResume()
            webView.resumeTimers()
        }
    }

    override fun onStop() {
        super.onStop()
        if (wasInPip && !isInPictureInPictureMode) {
            // The mini window was closed: only reset the flags (never touch playback here,
            // some devices call onStop while the window is being expanded).
            isPipModeActive = false
            wasInPip = false
        } else if (isPipModeActive) {
            webView.onResume()
            webView.resumeTimers()
        }
    }

    override fun onResume() {
        super.onResume()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !isInPictureInPictureMode) {
            isPipModeActive = false
            wasInPip = false
            applyPipLayout(false)
            if (customView == null) showDefaultFabs()
        }
        webView.onResume()
        webView.resumeTimers()
    }

    override fun onDestroy() {
        try {
            unregisterReceiver(pipReceiver)
        } catch (e: Exception) {}
        scope.cancel()
        webView.destroy()
        super.onDestroy()
    }
}
