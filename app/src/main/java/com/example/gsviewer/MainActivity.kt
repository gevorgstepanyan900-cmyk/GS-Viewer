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

    private val scope = CoroutineScope(Dispatchers.Main)
    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null
    private lateinit var rootFrame: FrameLayout

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
    }

    private var isVideoPlaying: Boolean = true

    private val pipReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != ACTION_MEDIA_CONTROL) return
            when (intent.getIntExtra(EXTRA_CONTROL_TYPE, 0)) {
                CONTROL_TYPE_PLAY_PAUSE -> {
                    webView.evaluateJavascript(
                        """
                        (function() {
                            var v = document.querySelector('video');
                            if (!v) return;
                            if (v.paused) {
                                window.__gsUserPaused = false;
                                v.play();
                            } else {
                                window.__gsUserPaused = true;
                                v.pause();
                            }
                        })();
                        """.trimIndent(), null
                    )
                }
                CONTROL_TYPE_REWIND -> {
                    webView.evaluateJavascript("var v = document.querySelector('video'); if(v) v.currentTime = Math.max(0, v.currentTime - 10);", null)
                }
                CONTROL_TYPE_FORWARD -> {
                    webView.evaluateJavascript("var v = document.querySelector('video'); if(v) v.currentTime += 10;", null)
                }
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
                if (!isInPictureInPictureMode) {
                    showDefaultFabs()
                }
            }
        }

        webView.webViewClient = object : WebViewClient() {
            override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                super.doUpdateVisitedHistory(view, url, isReload)
                updateSaveButton(url)
                injectCoreProtectionScript()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && isInPictureInPictureMode) {
                    applyPipLayout(true)
                    webView.evaluateJavascript("window.__gsPipActive = true; window.__gsUserPaused = false;", null)
                }
            }
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                updateSaveButton(url)
                injectCoreProtectionScript()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && isInPictureInPictureMode) {
                    applyPipLayout(true)
                    webView.evaluateJavascript("window.__gsPipActive = true; window.__gsUserPaused = false; var v = document.querySelector('video'); if(v && v.paused) v.play();", null)
                }
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

        setContentView(rootFrame)

        handleIntent(intent)
        if (savedInstanceState != null && intent.getStringExtra("open_url") == null) {
            webView.restoreState(savedInstanceState)
        }
        updateSaveButton(webView.url)
    }

    private fun hideAllFabs() {
        saveButton.visibility = View.GONE
        playlistButton.visibility = View.GONE
        repeatButton.visibility = View.GONE
        autoNextButton.visibility = View.GONE
        pipButton.visibility = View.GONE
    }

    private fun showDefaultFabs() {
        playlistButton.visibility = View.VISIBLE
        repeatButton.visibility = View.VISIBLE
        pipButton.visibility = View.VISIBLE
        updateAutoNextButtonVisibility()
        updateSaveButton(webView.url)
    }

    private fun enterPipMode() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // Preserve current play/pause state when entering PiP instead of forcing play if user paused it
            val syncJs = """
                (function() {
                    var v = document.querySelector('video');
                    window.__gsPipActive = true;
                    if (v) {
                        window.__gsUserPaused = v.paused;
                    }
                })();
            """.trimIndent()
            webView.evaluateJavascript(syncJs, null)
            applyPipLayout(true)

            val params = PictureInPictureParams.Builder()
                .setAspectRatio(Rational(16, 9))
                .setActions(buildPipActions())
                .build()
            enterPictureInPictureMode(params)
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && isInPictureInPictureMode) {
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

                    // Restore interactive controls and layout cleanly after exiting PiP
                    var v = document.querySelector('video');
                    if (v) {
                        v.style.pointerEvents = 'auto';
                        // Sync YouTube's internal player UI state with the actual <video> paused/playing state
                        var player = document.getElementById('movie_player');
                        if (player) {
                            if (v.paused && typeof player.pauseVideo === 'function') {
                                window.__gsUserPaused = true;
                                player.pauseVideo();
                            } else if (!v.paused && typeof player.playVideo === 'function') {
                                window.__gsUserPaused = false;
                                player.playVideo();
                            }
                        }
                    }

                    // Trigger layout & player UI recalculation so YouTube fullscreen controls (play/pause, seekbar) respond immediately
                    window.dispatchEvent(new Event('resize'));
                    setTimeout(function() {
                        window.dispatchEvent(new Event('resize'));
                    }, 150);
                    setTimeout(function() {
                        window.dispatchEvent(new Event('resize'));
                    }, 400);
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
        if (isInPictureInPictureMode) {
            hideAllFabs()
            applyPipLayout(true)
            webView.evaluateJavascript("window.__gsPipActive = true;", null)
            webView.onResume()
            webView.resumeTimers()
        } else {
            // Reset PiP flags FIRST so normal fullscreen/inline YouTube buttons can call pause()/play() freely
            webView.evaluateJavascript("window.__gsPipActive = false; window.__gsUserPaused = false;", null)
            applyPipLayout(false)
            webView.onResume()
            webView.resumeTimers()
            webView.requestLayout()
            customView?.requestLayout()
            if (customView == null) {
                showDefaultFabs()
            }
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
        }

        updateAutoNextButtonVisibility()
        syncAutoNextStateToJs()

        when {
            openUrl != null -> webView.loadUrl(openUrl)
            else -> if (webView.url == null) webView.loadUrl("https://m.youtube.com")
        }
    }

    private fun updateAutoNextButtonVisibility() {
        if (customView == null && !isInPictureInPictureMode && currentPlaylistId != null && currentVideoIndex >= 0) {
            autoNextButton.visibility = View.VISIBLE
        } else {
            autoNextButton.visibility = View.GONE
        }
    }

    private fun syncRepeatStateToJs() {
        val js = """
            window.__gsRepeatEnabled = $isRepeatEnabled;
            document.querySelectorAll('video').forEach(function(v) {
                v.loop = $isRepeatEnabled;
            });
        """.trimIndent()
        webView.evaluateJavascript(js, null)
    }

    private fun syncAutoNextStateToJs() {
        val hasPlaylist = (currentPlaylistId != null && currentVideoIndex >= 0)
        val js = """
            window.__gsAutoNextEnabled = $isAutoNextEnabled;
            window.__gsHasPlaylist = $hasPlaylist;
        """.trimIndent()
        webView.evaluateJavascript(js, null)
    }

    private fun injectCoreProtectionScript() {
        val hasPlaylist = (currentPlaylistId != null && currentVideoIndex >= 0)
        val isPipNow = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) isInPictureInPictureMode else false
        val js = """
            (function() {
                window.__gsRepeatEnabled = $isRepeatEnabled;
                window.__gsAutoNextEnabled = $isAutoNextEnabled;
                window.__gsHasPlaylist = $hasPlaylist;
                window.__gsPipActive = $isPipNow;

                if (!window.__gsHookInstalled) {
                    window.__gsHookInstalled = true;
                    window.__gsUserPaused = false;

                    try {
                        Object.defineProperty(document, 'hidden', { get: function() { return false; }, configurable: true });
                        Object.defineProperty(document, 'visibilityState', { get: function() { return 'visible'; }, configurable: true });
                    } catch(e) {}

                    const origPause = HTMLMediaElement.prototype.pause;
                    HTMLMediaElement.prototype.pause = function() {
                        // Only block automatic background pauses when actually in PiP mode and not requested by user
                        if (window.__gsPipActive === true && window.__gsUserPaused !== true) {
                            return;
                        }
                        return origPause.apply(this, arguments);
                    };

                    const origPlay = HTMLMediaElement.prototype.play;
                    HTMLMediaElement.prototype.play = function() {
                        window.__gsUserPaused = false;
                        return origPlay.apply(this, arguments);
                    };
                }

                function handleVideoCompletion(video) {
                    if (window.__gsRepeatEnabled) {
                        video.currentTime = 0;
                        window.__gsUserPaused = false;
                        video.play().catch(function(){});
                    } else if (window.__gsAutoNextEnabled && window.__gsHasPlaylist) {
                        if (window.AndroidBridge) {
                            window.AndroidBridge.onVideoEnded();
                        }
                    }
                }

                function setupVideos() {
                    var videos = document.querySelectorAll('video');
                    videos.forEach(function(video) {
                        if (video.muted) video.muted = false;
                        if (video.volume < 1.0) video.volume = 1.0;

                        video.loop = !!window.__gsRepeatEnabled;

                        if (video.duration > 1 && !isNaN(video.duration)) {
                            var remaining = video.duration - video.currentTime;
                            if (remaining <= 0.35 && remaining >= 0) {
                                if (window.__gsRepeatEnabled) {
                                    video.currentTime = 0;
                                    window.__gsUserPaused = false;
                                    video.play().catch(function(){});
                                } else if (window.__gsAutoNextEnabled && window.__gsHasPlaylist && !video.__gsNextSent) {
                                    video.__gsNextSent = true;
                                    if (window.AndroidBridge) window.AndroidBridge.onVideoEnded();
                                }
                            } else if (remaining > 2.0) {
                                video.__gsNextSent = false;
                            }
                        }

                        if (!video.hasAttribute('data-gs-bound')) {
                            video.setAttribute('data-gs-bound', 'true');
                            video.__gsNextSent = false;

                            video.addEventListener('ended', function() {
                                if (window.__gsRepeatEnabled) {
                                    video.currentTime = 0;
                                    window.__gsUserPaused = false;
                                    video.play().catch(function(){});
                                } else if (window.__gsAutoNextEnabled && window.__gsHasPlaylist && !video.__gsNextSent) {
                                    video.__gsNextSent = true;
                                    if (window.AndroidBridge) window.AndroidBridge.onVideoEnded();
                                }
                            });

                            video.addEventListener('timeupdate', function() {
                                if (window.__gsRepeatEnabled && video.duration > 1 && (video.duration - video.currentTime) <= 0.3) {
                                    video.currentTime = 0;
                                    window.__gsUserPaused = false;
                                    video.play().catch(function(){});
                                }
                            });

                            video.addEventListener('play', function() {
                                window.__gsUserPaused = false;
                                if (window.AndroidBridge) window.AndroidBridge.onPlaybackStateChanged(true);
                            });

                            video.addEventListener('pause', function() {
                                if (video.duration > 1 && (video.duration - video.currentTime) <= 0.5) {
                                    handleVideoCompletion(video);
                                    return;
                                }
                                if (window.AndroidBridge) window.AndroidBridge.onPlaybackStateChanged(false);
                            });
                        }
                    });
                }

                setupVideos();
                if (!window.__gsScanner) {
                    window.__gsScanner = setInterval(setupVideos, 300);
                }
            })();
        """.trimIndent()
        webView.evaluateJavascript(js, null)
    }

    private inner class WebAppInterface {
        @JavascriptInterface
        fun onVideoEnded() {
            runOnUiThread {
                if (isRepeatEnabled) {
                    webView.evaluateJavascript("var v = document.querySelector('video'); if(v){ v.currentTime = 0; window.__gsUserPaused = false; v.play(); }", null)
                } else if (isAutoNextEnabled && currentPlaylistId != null && currentVideoIndex >= 0) {
                    playNextVideoInPlaylist()
                }
            }
        }

        @JavascriptInterface
        fun onPlaybackStateChanged(playing: Boolean) {
            runOnUiThread {
                if (isVideoPlaying != playing) {
                    isVideoPlaying = playing
                    updatePipActions()
                }
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
            if (!isInPictureInPictureMode) {
                Toast.makeText(this, "Հաջորդ տեսանյութը: ${nextVideo.title}", Toast.LENGTH_SHORT).show()
            }
        } else {
            if (!isInPictureInPictureMode) {
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
        if (customView == null && !isInPictureInPictureMode) {
            saveButton.visibility = if (id != null) View.VISIBLE else View.INVISIBLE
        }
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
        if (!isInPictureInPictureMode) {
            webView.onPause()
        } else {
            webView.onResume()
            webView.resumeTimers()
        }
    }

    override fun onStop() {
        super.onStop()
        if (isInPictureInPictureMode) {
            webView.onResume()
            webView.resumeTimers()
        }
    }

    override fun onResume() {
        super.onResume()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !isInPictureInPictureMode) {
            webView.evaluateJavascript("window.__gsPipActive = false; window.__gsUserPaused = false;", null)
        }
        webView.onResume()
        webView.resumeTimers()
    }

    override fun onDestroy() {
        try {
            unregisterReceiver(pipReceiver)
        } catch (e: Exception) {}
        webView.destroy()
        super.onDestroy()
    }
}
