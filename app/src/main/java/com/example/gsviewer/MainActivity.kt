package com.example.gsviewer

import android.Manifest
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
import android.os.SystemClock
import android.util.Log
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

    // True PiP (mini window) state flag
    private var isPipModeActive: Boolean = false

    // Loop & Playlist state
    private var isRepeatEnabled: Boolean = false
    private var isAutoNextEnabled: Boolean = false
    private var currentPlaylistId: String? = null
    private var currentVideoIndex: Int = -1

    // PiP Control Constants
    companion object {
        private const val TAG = "GSViewer"
        internal const val ACTION_MEDIA_CONTROL = "com.example.gsviewer.MEDIA_CONTROL"
        internal const val EXTRA_CONTROL_TYPE = "control_type"
        internal const val CONTROL_TYPE_PLAY_PAUSE = 1
        internal const val CONTROL_TYPE_REWIND = 2
        internal const val CONTROL_TYPE_FORWARD = 3
        internal const val CONTROL_TYPE_CLOSE = 4

        // How long (ms) after every mode/lifecycle change the page-side "keeper" protects playback.
        private const val GUARD_MS = 5000

        // A video counts as "watched" (goes into the history) after it has played this long.
        private const val HISTORY_DELAY_MS = 5000L

        // ------------------------------------------------------------------------------------
        // Single source of truth for page-side logic. Installed once per document;
        // later injections only refresh state (see injectCoreProtectionScript).
        //
        // THE PLAYBACK KEEPER (why the video no longer stops when switching full <-> mini):
        //  * __gsWant  = what the USER wants (play / pause). It becomes false ONLY when the user
        //    really pauses (touch inside the page, our own pause buttons, headset key).
        //  * While the app is in mini mode (__gsPip) or right after any mode/lifecycle change
        //    (__gsGuardUntil), every pause that was NOT caused by the user is undone:
        //      1) pause() calls coming from the page (YouTube) are blocked at the source,
        //      2) native / event based pauses are resumed immediately,
        //      3) a watchdog keeps checking every 300 ms, so even a second or third pause
        //         (the "I start it and it stops again" case) is repaired.
        //  * The user's own pause/play (PiP button, full-mode button, YouTube's own buttons)
        //    always works and is never fought.
        // ------------------------------------------------------------------------------------
        private const val CORE_JS = """
(function() {
    if (window.__gsCoreInstalled) { if (window.__gsScan) window.__gsScan(); return; }
    window.__gsCoreInstalled = true;

    function now() { return Date.now(); }

    window.__gsLog = function(msg) {
        try { if (window.AndroidBridge && window.AndroidBridge.onDebug) window.AndroidBridge.onDebug(String(msg)); } catch (e) {}
    };

    function shortStack() {
        try { return String(new Error().stack || '').split('\n').slice(2, 5).join(' | ').substring(0, 400); } catch (e) { return '?'; }
    }

    // ---------------- shared state ----------------
    window.__gsWant = false;            // the user's intent: should the video be playing?
    window.__gsWantKey = null;          // which video (watch id / path) the intent belongs to
    window.__gsWantVideo = null;        // which <video> element the intent belongs to
    window.__gsInputAt = 0;             // last real touch / key / headset input inside the page
    window.__gsOurPauseAt = 0;          // last pause that the app itself (our buttons) requested
    window.__gsGuardUntil = window.__gsGuardUntil || 0;
    window.__gsPip = !!window.__gsPip;  // true while the mini window is shown
    window.__gsBg = !!window.__gsBg;    // true while the app is in the background / screen is off
    window.__gsKicks = 0;
    window.__gsResumeBusy = false;

    function currentKey() {
        try {
            var u = new URL(location.href);
            return u.searchParams.get('v') || u.pathname;
        } catch (e) { return location.href; }
    }

    function isProtected() { return !!window.__gsPip || !!window.__gsBg || now() < window.__gsGuardUntil; }

    function userActed() {
        var t = now();
        return (t - window.__gsInputAt < 700) || (t - window.__gsOurPauseAt < 1200);
    }

    function markInput() { window.__gsInputAt = now(); }
    window.__gsMarkInput = markInput;

    window.__gsArmGuard = function(ms) {
        var t = now() + (ms || 5000);
        if (t > window.__gsGuardUntil) window.__gsGuardUntil = t;
    };

    // ---------------- make the page believe it is always visible ----------------
    function def(obj, name, value) {
        try { Object.defineProperty(obj, name, { get: function() { return value; }, configurable: true }); } catch (e) {}
    }
    def(document, 'hidden', false);
    def(document, 'webkitHidden', false);
    def(document, 'visibilityState', 'visible');
    def(document, 'webkitVisibilityState', 'visible');
    try { document.hasFocus = function() { return true; }; } catch (e) {}
    ['visibilitychange', 'webkitvisibilitychange', 'pagehide', 'freeze'].forEach(function(t) {
        var stop = function(e) { e.stopImmediatePropagation(); };
        try { window.addEventListener(t, stop, true); } catch (e) {}
        try { document.addEventListener(t, stop, true); } catch (e) {}
    });

    // ---------------- real user input (so we never fight a genuine pause) ----------------
    ['touchstart', 'touchend', 'pointerdown', 'pointerup', 'mousedown', 'mouseup', 'click', 'keydown'].forEach(function(t) {
        try {
            window.addEventListener(t, function(e) { if (e.isTrusted) markInput(); }, { capture: true, passive: true });
        } catch (e) {}
    });

    // Headset / notification buttons reach the page through the Media Session API.
    try {
        var ms = navigator.mediaSession;
        if (ms && ms.setActionHandler) {
            var origSet = ms.setActionHandler.bind(ms);
            ms.setActionHandler = function(action, handler) {
                if (typeof handler === 'function') {
                    var h = handler;
                    handler = function() { markInput(); return h.apply(this, arguments); };
                }
                return origSet(action, handler);
            };
        }
    } catch (e) {}

    // The main video: the wanted one if still alive, otherwise playing one first, otherwise the biggest.
    window.__gsVideo = function() {
        var vs = document.querySelectorAll('video');
        var best = null, bestScore = -1;
        for (var i = 0; i < vs.length; i++) {
            var v = vs[i];
            var r = v.getBoundingClientRect();
            var score = r.width * r.height;
            if (!v.paused) score += 1e9;
            if (v === window.__gsWantVideo) score += 1e8;
            if (score > bestScore) { bestScore = score; best = v; }
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

    // ---------------- block pause() calls that the page makes on its own ----------------
    window.__gsShouldHold = function(v) {
        if (!window.__gsWant) return false;
        if (v !== window.__gsWantVideo) return false;
        if (v.ended) return false;
        if (!isProtected()) return false;
        if (userActed()) return false;
        if (currentKey() !== window.__gsWantKey) return false;
        return true;
    };

    var origPause = HTMLMediaElement.prototype.pause;
    HTMLMediaElement.prototype.pause = function() {
        var hold = false;
        try { hold = window.__gsShouldHold(this); } catch (e) {}
        if (hold) {
            window.__gsLog('pause() from the page BLOCKED: ' + shortStack());
            return;
        }
        try {
            if (this === window.__gsWantVideo && isProtected()) {
                window.__gsLog('pause() allowed (want=' + window.__gsWant + ', userActed=' + userActed() + '): ' + shortStack());
            }
        } catch (e) {}
        return origPause.apply(this, arguments);
    };

    // ---------------- resume: react to EVERY unwanted pause immediately ----------------
    function tryPlayNow() {
        var v = window.__gsWantVideo;
        if (!v || !v.isConnected) v = window.__gsVideo();
        if (!window.__gsWant || !v || v.ended || !v.paused) return false;
        if (currentKey() !== window.__gsWantKey) return false;   // the user already left this video
        window.__gsWantVideo = v;
        try { var p = ytPlayer(); if (p) p.playVideo(); } catch (e) {}
        try {
            var pr = v.play();
            if (pr && pr.catch) pr.catch(function(err) { window.__gsLog('play() rejected: ' + err); });
        } catch (e) {}
        window.__gsReport(true);
        return true;
    }

    // Plays right now, and keeps re-checking a few times (one retry chain at a time).
    // Every call (pause event, watchdog tick) still gets its own immediate attempt.
    window.__gsResume = function() {
        tryPlayNow();
        if (window.__gsResumeBusy) return;
        window.__gsResumeBusy = true;
        var tries = 0;
        (function retry() {
            setTimeout(function() {
                tries++;
                var v = window.__gsWantVideo;
                if (!window.__gsWant || !v || v.ended || currentKey() !== window.__gsWantKey) { window.__gsResumeBusy = false; return; }
                if (v.paused) tryPlayNow();
                if (tries < 6) retry(); else window.__gsResumeBusy = false;
            }, 250);
        })();
    };

    // Play/pause requested by the app (PiP button, full-mode button). Always honoured.
    window.__gsPlay = function() {
        var v = window.__gsVideo();
        if (v) { window.__gsWant = true; window.__gsWantKey = currentKey(); window.__gsWantVideo = v; }
        // A fresh "play" cancels any earlier "user paused" marker, otherwise an unwanted pause
        // right after it would be mistaken for a real user pause.
        window.__gsOurPauseAt = 0;
        window.__gsInputAt = 0;
        window.__gsKicks = 0;
        window.__gsResume();
        window.__gsReport(true);
    };

    window.__gsPause = function() {
        window.__gsOurPauseAt = now();
        window.__gsWant = false;
        var v = window.__gsVideo();
        var p = ytPlayer();
        try { if (p) p.pauseVideo(); } catch (e) {}
        if (v && !v.paused) v.pause();
        window.__gsReport(true);
        setTimeout(function() {
            var vv = window.__gsVideo();
            if (vv && !vv.paused && !window.__gsWant) vv.pause();
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
        window.__gsWant = true; window.__gsWantKey = currentKey(); window.__gsWantVideo = v;
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

    // A pause event arrived. Decide: real user pause (respect it) or unwanted pause (undo it).
    function onPaused(video) {
        if (video.ended) return;
        if (video !== window.__gsWantVideo) return;
        if (userActed()) { window.__gsWant = false; return; }
        if (!window.__gsWant) return;
        if (isProtected() && currentKey() === window.__gsWantKey) {
            window.__gsLog('unexpected pause event -> resuming');
            window.__gsResume();
        } else if (!isProtected()) {
            window.__gsWant = false;
        }
    }

    function bind(video) {
        video.setAttribute('data-gs-bound', 'true');
        video.__gsNextSent = false;
        video.addEventListener('ended', function() {
            if (video === window.__gsWantVideo) window.__gsWant = false;
            notifyEnded(video);
            window.__gsReport(false);
        });
        video.addEventListener('play', function() {
            window.__gsWant = true;
            window.__gsWantKey = currentKey();
            window.__gsWantVideo = video;
            window.__gsKicks = 0;
            window.__gsReport(false);
        });
        video.addEventListener('playing', function() { window.__gsReport(false); });
        video.addEventListener('pause', function() { onPaused(video); window.__gsReport(false); });
        ['emptied', 'abort'].forEach(function(ev) {
            video.addEventListener(ev, function() { window.__gsReport(false); });
        });
        // A video that is already playing when we bind to it counts as wanted.
        if (!video.paused && !video.ended) {
            window.__gsWant = true;
            window.__gsWantKey = currentKey();
            window.__gsWantVideo = video;
        }
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

        // WATCHDOG: while protected and the user wants playback, never stay paused.
        var wv = window.__gsWantVideo;
        if (window.__gsWant && (!wv || !wv.isConnected)) {
            var cand = window.__gsVideo();
            if (cand && currentKey() === window.__gsWantKey) { window.__gsWantVideo = cand; wv = cand; }
        }
        if (wv && !wv.paused) window.__gsKicks = 0;
        if (window.__gsWant && wv && wv.isConnected && wv.paused && !wv.ended &&
            isProtected() && !userActed() && currentKey() === window.__gsWantKey) {
            if (window.__gsKicks < 30) {
                window.__gsKicks++;
                window.__gsLog('watchdog: video is paused but the user wants it playing -> resume');
                window.__gsResume();
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

    // Screen-off / background playback state
    private var isActivityResumed: Boolean = false
    private var inBackground: Boolean = false
    private var playbackReported: Boolean = false
    private var keepAliveRunning: Boolean = false
    private var lastKeepPlaying: Boolean? = null
    private var lastKeepTitle: String? = null

    // Watch history
    private var historyRecordedId: String? = null
    private var historyPendingId: String? = null

    // While the system is still switching to the mini window, isInPictureInPictureMode can be
    // false for a moment. During this window we must not treat the activity as "back in full mode".
    private var pipTransitionUntil: Long = 0L

    private val pipReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != ACTION_MEDIA_CONTROL) return
            when (intent.getIntExtra(EXTRA_CONTROL_TYPE, 0)) {
                CONTROL_TYPE_PLAY_PAUSE -> {
                    armGuard()
                    ensureWebViewRunning()
                    webView.evaluateJavascript("(window.__gsToggle || function(){})();", null)
                }
                CONTROL_TYPE_CLOSE -> {
                    stopKeepAlive(true)
                    finishAndRemoveTask()
                }
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
        webView = KeepAliveWebView(this)

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
                // Never open a (new) full-screen layer while the mini window is shown or being
                // opened: the page would re-parent the <video> and stop it. Just decline it.
                if (customView != null || isPipModeActive || SystemClock.uptimeMillis() < pipTransitionUntil) {
                    dbg("fullscreen request declined (pip=$isPipModeActive)")
                    callback?.onCustomViewHidden()
                    return
                }
                armGuard()
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
                armGuard()
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
            override fun onPageCommitVisible(view: WebView?, url: String?) {
                super.onPageCommitVisible(view, url)
                injectCoreProtectionScript()
            }
            override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                super.doUpdateVisitedHistory(view, url, isReload)
                updateSaveButton(url)
                syncPlaylistPositionWithUrl(url)
                injectCoreProtectionScript()
                if (isVideoPlaying) scheduleHistoryRecord(url)
            }
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                updateSaveButton(url)
                injectCoreProtectionScript()
                syncKeepAlive()
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
        playPauseButton = makeFab("⏸") {
            webView.evaluateJavascript("(window.__gsToggle || function(){})();", null)
        }
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

        if (savedInstanceState == null &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1001)
        }
    }

    private fun dbg(msg: String) {
        Log.d(TAG, msg)
    }

    /** Tells the page: "a mode/lifecycle change is happening, protect playback for a few seconds". */
    private fun armGuard(ms: Int = GUARD_MS) {
        if (!::webView.isInitialized) return
        webView.evaluateJavascript("(window.__gsArmGuard || function(){})($ms);", null)
    }

    /** Tells the page whether the mini window is currently shown. */
    private fun pushPipState() {
        if (!::webView.isInitialized) return
        webView.evaluateJavascript("window.__gsPip = $isPipModeActive;", null)
    }

    /** Tells the page whether the app is in the background (screen off, other app on top). */
    private fun pushBackgroundState() {
        if (!::webView.isInitialized) return
        webView.evaluateJavascript("window.__gsBg = $inBackground;", null)
    }

    private fun ensureWebViewRunning() {
        webView.onResume()
        webView.resumeTimers()
    }

    private fun notificationTitle(): String {
        val t = webView.title?.removeSuffix(" - YouTube")?.trim()
        return if (t.isNullOrEmpty()) "GSViewer" else t
    }

    /**
     * Screen-off playback: while a video is playing, a foreground service (with a notification and
     * wake locks) keeps the app alive, so playback, repeat and auto-next continue with the screen off.
     */
    private fun syncKeepAlive() {
        if (!::webView.isInitialized || !playbackReported) return
        if (isVideoPlaying) {
            if (!isActivityResumed) ensureWebViewRunning()
            sendKeepAlive(true)
        } else if (isActivityResumed || isPipModeActive) {
            stopKeepAlive()
        } else if (keepAliveRunning) {
            sendKeepAlive(false)
        }
    }

    private fun sendKeepAlive(playing: Boolean) {
        val title = notificationTitle()
        if (keepAliveRunning && lastKeepPlaying == playing && lastKeepTitle == title) return
        try {
            val i = Intent(this, PlaybackService::class.java)
                .putExtra(PlaybackService.EXTRA_PLAYING, playing)
                .putExtra(PlaybackService.EXTRA_TITLE, title)
            startForegroundService(i)
            keepAliveRunning = true
            lastKeepPlaying = playing
            lastKeepTitle = title
        } catch (e: Exception) {
            dbg("keep-alive service start failed: $e")
        }
    }

    private fun stopKeepAlive(force: Boolean = false) {
        if (!keepAliveRunning && !force) return
        keepAliveRunning = false
        lastKeepPlaying = null
        lastKeepTitle = null
        try {
            stopService(Intent(this, PlaybackService::class.java))
        } catch (e: Exception) {
            dbg("keep-alive service stop failed: $e")
        }
    }

    /**
     * Watch history: a video is added after it has really been playing for a few seconds
     * (so videos that are only opened or skipped are not recorded).
     */
    private fun scheduleHistoryRecord(url: String?) {
        if (!::webView.isInitialized) return
        val id = YoutubeHelper.extractVideoId(url) ?: return
        if (id == historyRecordedId || id == historyPendingId) return
        historyPendingId = id
        webView.postDelayed({
            if (historyPendingId == id) historyPendingId = null
            val current = YoutubeHelper.extractVideoId(webView.url)
            if (current == id && isVideoPlaying && id != historyRecordedId) {
                historyRecordedId = id
                recordHistory(id)
            }
        }, HISTORY_DELAY_MS)
    }

    private fun recordHistory(id: String) {
        val pageTitle = webView.title?.removeSuffix(" - YouTube")?.trim()
        scope.launch {
            var video = withContext(Dispatchers.IO) { YoutubeHelper.fetchOEmbed(id) }
            // No internet for the title lookup: use the title of the open page instead.
            if (video.title.startsWith("Տեսանյութ ") && !pageTitle.isNullOrEmpty()) {
                video = video.copy(title = pageTitle)
            }
            HistoryStore.add(this@MainActivity, video)
        }
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
        dbg("enterPipMode")
        pipTransitionUntil = SystemClock.uptimeMillis() + 2500L
        isPipModeActive = true
        pushPipState()
        armGuard()
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
            dbg("enterPictureInPictureMode failed")
            pipTransitionUntil = 0L
            isPipModeActive = false
            pushPipState()
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
                    window.dispatchEvent(new Event('resize'));
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
        dbg("onUserLeaveHint")
        armGuard()
        if (YoutubeHelper.extractVideoId(webView.url) != null || customView != null) {
            enterPipMode()
        }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration?) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        dbg("onPictureInPictureModeChanged -> $isInPictureInPictureMode")
        isPipModeActive = isInPictureInPictureMode
        pushPipState()
        armGuard()
        if (isInPictureInPictureMode) {
            wasInPip = true
            hideAllFabs()
            applyPipLayout(true)
            webView.onResume()
            webView.resumeTimers()
            syncPlaybackStateFromPage()
        } else {
            pipTransitionUntil = 0L
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

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        armGuard()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        armGuard()
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
            "window.__gsHasPlaylist = $hasPlaylist; " +
            "window.__gsPip = $isPipModeActive; " +
            "window.__gsBg = $inBackground;\n"
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

        @JavascriptInterface
        fun onDebug(message: String) {
            Log.d(TAG, "JS: $message")
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

    /** Single entry point for any playback-state change coming from the page (UI only). */
    private fun applyPlaybackState(playing: Boolean) {
        playbackReported = true
        if (playing) scheduleHistoryRecord(webView.url)
        if (isVideoPlaying == playing) {
            // Nothing changed for the user; still make sure the icons are correct.
            updatePlayPauseButton()
            syncKeepAlive()
            return
        }
        isVideoPlaying = playing
        updatePlayPauseButton()
        updatePipActions()
        syncKeepAlive()
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

    /** Headset / media keys: tell the page they are real user input, so they are never undone. */
    override fun dispatchKeyEvent(event: KeyEvent?): Boolean {
        when (event?.keyCode) {
            KeyEvent.KEYCODE_MEDIA_PLAY,
            KeyEvent.KEYCODE_MEDIA_PAUSE,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            KeyEvent.KEYCODE_MEDIA_STOP,
            KeyEvent.KEYCODE_HEADSETHOOK ->
                webView.evaluateJavascript("(window.__gsMarkInput || function(){})();", null)
        }
        return super.dispatchKeyEvent(event)
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
        dbg("onPause pip=$isPipModeActive playing=$isVideoPlaying")
        isActivityResumed = false
        inBackground = true
        pushBackgroundState()
        armGuard()
        syncKeepAlive()
        // Keep the page running while a video plays (screen off / other app), in the mini window,
        // and during the switch to the mini window. Only an idle page is paused.
        val keepRunning = isPipModeActive || isVideoPlaying ||
            SystemClock.uptimeMillis() < pipTransitionUntil
        if (!keepRunning) {
            webView.onPause()
        } else {
            webView.onResume()
            webView.resumeTimers()
        }
    }

    override fun onStop() {
        super.onStop()
        dbg("onStop pip=$isPipModeActive inPip=$isInPictureInPictureMode")
        if (wasInPip && !isInPictureInPictureMode) {
            // The mini window was closed: only reset the flags (never touch playback here,
            // some devices call onStop while the window is being expanded).
            isPipModeActive = false
            wasInPip = false
            pushPipState()
        } else if (isPipModeActive) {
            webView.onResume()
            webView.resumeTimers()
        }
    }

    override fun onResume() {
        super.onResume()
        dbg("onResume inPip=$isInPictureInPictureMode")
        isActivityResumed = true
        inBackground = false
        pushBackgroundState()
        armGuard()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !isInPictureInPictureMode &&
            SystemClock.uptimeMillis() >= pipTransitionUntil
        ) {
            isPipModeActive = false
            wasInPip = false
            pushPipState()
            applyPipLayout(false)
            if (customView == null) showDefaultFabs()
        }
        webView.onResume()
        webView.resumeTimers()
        syncKeepAlive()
    }

    override fun onDestroy() {
        try {
            unregisterReceiver(pipReceiver)
        } catch (e: Exception) {}
        stopKeepAlive(true)
        scope.cancel()
        webView.destroy()
        super.onDestroy()
    }
}

/** A WebView that is not hidden when the screen turns off, so audio and video keep playing. */
private class KeepAliveWebView(context: Context) : WebView(context) {
    override fun onWindowVisibilityChanged(visibility: Int) {
        if (visibility != View.GONE) super.onWindowVisibilityChanged(View.VISIBLE)
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        if (visibility != View.GONE) super.onVisibilityChanged(changedView, View.VISIBLE)
        else super.onVisibilityChanged(changedView, visibility)
    }
}
