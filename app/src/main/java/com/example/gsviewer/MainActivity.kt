package com.example.gsviewer

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.NetworkCapabilities
import android.net.Network
import android.net.ConnectivityManager
import android.media.AudioManager
import android.content.res.Configuration
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Looper
import android.os.Handler
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.util.Rational
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceResponse
import android.webkit.WebResourceRequest
import android.webkit.WebResourceError
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : Activity() {
    private lateinit var webView: WebView
    private lateinit var overlay: PlayerOverlay

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
        internal const val CONTROL_TYPE_PREVIOUS = 5
        internal const val CONTROL_TYPE_NEXT = 6
        internal const val CONTROL_TYPE_PLAY = 7
        internal const val CONTROL_TYPE_PAUSE = 8
        internal const val CONTROL_TYPE_SEEK_TO = 9
        internal const val EXTRA_POSITION_MS = "position_ms"

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
            if (window.__gsProgress) window.__gsProgress(true);
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

    // ---------------- ADS & CLUTTER DURING THE VIDEO ----------------
    // 1) the ad data is removed from the player data YouTube sends (so most ads are never created),
    // 2) an ad that still appears is skipped: Skip button pressed / the ad is fast-forwarded to its end,
    // 3) overlays, info cards, end-screen suggestions, promo banners and ad slots are hidden.
    // Nothing here touches the real video: it only acts while YouTube says "an ad is showing".
    function stripAdData(o) {
        if (!o || typeof o !== 'object') return;
        if (o.adPlacements) delete o.adPlacements;
        if (o.playerAds) delete o.playerAds;
        if (o.adSlots) delete o.adSlots;
        if (o.adBreakHeartbeatParams) delete o.adBreakHeartbeatParams;
        if (o.playerResponse && typeof o.playerResponse === 'object') stripAdData(o.playerResponse);
        if (Array.isArray(o)) {
            for (var i = 0; i < o.length && i < 4; i++) {
                if (o[i] && typeof o[i] === 'object' && o[i].playerResponse) stripAdData(o[i].playerResponse);
            }
        }
    }
    var origJsonParse = JSON.parse;
    JSON.parse = function() {
        var r = origJsonParse.apply(this, arguments);
        try { stripAdData(r); } catch (e) {}
        return r;
    };
    try {
        var origResponseJson = Response.prototype.json;
        Response.prototype.json = function() {
            return origResponseJson.apply(this, arguments).then(function(r) {
                try { stripAdData(r); } catch (e) {}
                return r;
            });
        };
    } catch (e) {}

    var AD_CSS =
        '.ytp-ad-overlay-container, .ytp-ad-text-overlay, .ytp-ad-image-overlay, .ytp-ad-overlay-slot, ' +
        '.ytp-ce-element, .ytp-cards-teaser, .ytp-cards-button, .ytp-watermark, .ytp-paid-content-overlay, ' +
        '.ytp-endscreen-content, .ytp-suggestion-set, .ytp-videowall-still, .ytp-pause-overlay, ' +
        'ytm-promoted-sparkles-web-renderer, ytm-promoted-video-renderer, ytm-companion-slot, ytm-companion-ad-renderer, ' +
        'ytm-display-ad-renderer, ytm-ad-slot-renderer, ytm-in-feed-ad-layout-renderer, ytm-statement-banner-renderer, ' +
        'ytm-brand-video-singleton-renderer, ytm-mealbar-promo-renderer, #player-ads { display: none !important; } ' +
        'html[data-gs-playing="1"] .ytp-spinner, html[data-gs-playing="1"] #movie_player [class*="spinner"] { display: none !important; }';

    function ensureAdStyle() {
        try {
            if (document.getElementById('gs-ad-style')) return;
            var st = document.createElement('style');
            st.id = 'gs-ad-style';
            st.textContent = AD_CSS;
            (document.head || document.documentElement).appendChild(st);
        } catch (e) {}
    }

    // Strong signal: the player itself says that an ad is playing.
    function adClassShowing() {
        try {
            var p = document.getElementById('movie_player');
            return !!(p && p.classList && (p.classList.contains('ad-showing') || p.classList.contains('ad-interrupting')));
        } catch (e) { return false; }
    }

    // Weaker signal: an ad control that is really visible on the screen.
    function adOverlayVisible() {
        try {
            var els = document.querySelectorAll('.ytp-ad-player-overlay, .ytp-ad-player-overlay-layout, .ytp-ad-preview-container, .ytp-ad-skip-button-container');
            for (var i = 0; i < els.length; i++) {
                if (els[i].getClientRects && els[i].getClientRects().length > 0) return true;
            }
        } catch (e) {}
        return false;
    }

    function isAdShowing() { return adClassShowing() || adOverlayVisible(); }

    // True while an ad is showing and for 1.5 s after it (an ad's "ended" must never count as the video's end).
    function adJustEnded() {
        if (isAdShowing()) { window.__gsLastAdAt = now(); return true; }
        return now() - (window.__gsLastAdAt || 0) < 1500;
    }

    var SKIP_SELECTOR = '.ytp-ad-skip-button, .ytp-ad-skip-button-modern, .ytp-skip-ad-button, ' +
        '.ytp-ad-skip-button-container button, .ytp-ad-skip-button-slot button';
    window.__gsAdTicks = 0;

    function skipAds() {
        try {
            if (!isAdShowing()) {
                if (window.__gsAdTicks > 0) {
                    window.__gsAdTicks = 0;
                    var vv = window.__gsVideo();
                    if (vv && window.__gsRateBeforeAd !== undefined) { try { vv.playbackRate = window.__gsRateBeforeAd; } catch (e) {} }
                    window.__gsRateBeforeAd = undefined;
                }
                return;
            }
            window.__gsLastAdAt = now();
            window.__gsAdTicks++;
            var btn = document.querySelector(SKIP_SELECTOR);
            if (btn) { btn.click(); window.__gsLog('ad: Skip button pressed'); }
            var v = window.__gsVideo();
            // Only a short video, and only after the ad has been seen twice in a row (never the real video).
            if (v && adClassShowing() && window.__gsAdTicks >= 2 && isFinite(v.duration) && v.duration > 0 && v.duration <= 120) {
                if (window.__gsRateBeforeAd === undefined) window.__gsRateBeforeAd = v.playbackRate || 1;
                try { v.playbackRate = 16; } catch (e) {}
                if (v.currentTime < v.duration - 0.2) {
                    v.currentTime = v.duration - 0.1;
                    window.__gsLog('ad: fast-forwarded to its end');
                }
            }
        } catch (e) {}
    }

    // ---------------- LONG PLAYBACK (many hours) ----------------
    // Position / length of the video for the lock-screen seek bar (sent at most every 15 s, and on changes).
    window.__gsLastProgressAt = 0;
    window.__gsProgress = function(force) {
        try {
            var v = window.__gsVideo();
            if (!v || !window.AndroidBridge || !window.AndroidBridge.onProgress) return;
            var t = now();
            if (!force && t - window.__gsLastProgressAt < 15000) return;
            window.__gsLastProgressAt = t;
            var d = v.duration;
            window.AndroidBridge.onProgress(v.currentTime || 0, (isFinite(d) && d > 0) ? d : 0);
        } catch (e) {}
    };

    // Jump to an absolute position (lock screen / notification seek bar).
    window.__gsSeekTo = function(sec) {
        try {
            var v = window.__gsVideo();
            if (!v) return;
            var t = Math.max(0, Number(sec) || 0);
            if (isFinite(v.duration) && v.duration > 0 && t > v.duration) t = v.duration;
            v.currentTime = t;
            window.__gsProgress(true);
        } catch (e) {}
    };

    // A live stream has no real "end": never treat its live edge as the end of a video.
    function isLiveStream() {
        try {
            var p = ytPlayer();
            if (p && typeof p.getVideoData === 'function') {
                var d = p.getVideoData();
                if (d && (d.isLive || d.isLowLatencyLiveStream)) return true;
            }
            var el = document.getElementById('movie_player');
            if (el && el.classList && el.classList.contains('ytp-live')) return true;
        } catch (e) {}
        return false;
    }

    // While the video really plays (enough data to continue) YouTube's loading circle must not show.
    // (It sometimes stays on top of a playing video, most visibly in the small mini window.)
    function updatePlayingFlag() {
        try {
            var pv = window.__gsVideo();
            var ok = !!pv && !pv.paused && !pv.ended && pv.readyState >= 3;
            var de = document.documentElement;
            if (de) {
                var want = ok ? '1' : '0';
                if (de.getAttribute('data-gs-playing') !== want) de.setAttribute('data-gs-playing', want);
            }
        } catch (e) {}
    }

    // After hours of playback YouTube asks "Video paused. Continue watching?". While nobody is looking at
    // the screen (mini window / background) the question is answered "yes" so playback goes on.
    // The innermost <button> is pressed (a click on its wrapper would not reach the button's own handler).
    var CONFIRM_BUTTON = 'yt-confirm-dialog-renderer #confirm-button button, ytm-confirm-dialog-renderer button';
    var CONFIRM_WRAPPER = 'yt-confirm-dialog-renderer #confirm-button';
    function dismissStillWatching() {
        try {
            if (!window.__gsWant || !(window.__gsBg || window.__gsPip)) return;
            var btn = document.querySelector(CONFIRM_BUTTON) || document.querySelector(CONFIRM_WRAPPER);
            if (btn && btn.getClientRects && btn.getClientRects().length > 0) {
                var t = now();
                if (t - (window.__gsLastConfirmAt || 0) < 5000) return;
                window.__gsLastConfirmAt = t;
                btn.click();
                window.__gsLog('dialog: "still watching" answered');
            }
        } catch (e) {}
    }

    // After the app itself opened a video (next / previous / playlist / history) the video must start,
    // even if YouTube does not start it by itself (typical with the screen off). Stops as soon as the video
    // plays, after 25 s, or when the user pauses it.
    window.__gsAutoStartUntil = window.__gsAutoStartUntil || 0;
    function autoStart() {
        try {
            var until = window.__gsAutoStartUntil || 0;
            if (!until) return;
            if (now() > until || userActed()) { window.__gsAutoStartUntil = 0; return; }
            var v = window.__gsVideo();
            if (!v) return;
            if (!v.paused) { window.__gsAutoStartUntil = 0; return; }
            if (!v.ended && v.readyState >= 2 && !isAdShowing()) {
                window.__gsLog('auto start after opening a video');
                window.__gsPlay();
            }
        } catch (e) {}
    }

    // Tells the app when playback is in trouble, so that it can open the video again (or skip it in a playlist):
    //  - YouTube shows an error ("video unavailable", "an error occurred"),
    //  - the media element reports an error,
    //  - the video wants to play but has had no data for 30 s (lost connection).
    window.__gsLastProblemAt = 0;
    window.__gsStallSince = 0;
    function checkPlaybackProblem() {
        try {
            if (!window.AndroidBridge || !window.AndroidBridge.onPlaybackProblem) return;
            var t = now();
            var expecting = !!window.__gsWant || (window.__gsAutoStartUntil || 0) > t || !!window.__gsHasPlaylist;
            if (!expecting) { window.__gsStallSince = 0; return; }
            var v = window.__gsVideo();
            var el = document.getElementById('movie_player');
            var kind = '';
            if (el && el.classList && el.classList.contains('ytp-error')) {
                kind = 'error';
            } else if (v && v.error) {
                kind = 'media-error';
            } else if (window.__gsWant && v && !v.paused && !v.ended && v.readyState < 3 && !isAdShowing()) {
                if (!window.__gsStallSince) window.__gsStallSince = t;
                if (t - window.__gsStallSince > 30000) kind = 'stall';
            } else {
                window.__gsStallSince = 0;
            }
            if (kind && t - window.__gsLastProblemAt >= 20000) {
                window.__gsLastProblemAt = t;
                window.__gsStallSince = 0;
                window.__gsLog('problem: ' + kind);
                window.AndroidBridge.onPlaybackProblem(kind);
            }
        } catch (e) {}
    }

    // End of the video seen from the playback position (events keep coming with the screen off, timers are slow).
    function endCheck(video) {
        try {
            if (window.__gsRepeatEnabled || video.paused) return;
            var d = video.duration;
            if (!(d > 1) || !isFinite(d)) return;
            var remaining = d - video.currentTime;
            if (remaining <= 0.5 && remaining >= 0 && !adJustEnded() && !isLiveStream()) notifyEnded(video);
        } catch (e) {}
    }

    // For diagnostics only: JS memory use, written to the log every 5 minutes.
    window.__gsLastMemLogAt = 0;
    function logMemory() {
        try {
            var t = now();
            if (t - window.__gsLastMemLogAt < 300000) return;
            window.__gsLastMemLogAt = t;
            var m = window.performance && window.performance.memory;
            if (m) window.__gsLog('memory: JS heap ' + Math.round(m.usedJSHeapSize / 1048576) + ' MB of ' + Math.round(m.jsHeapSizeLimit / 1048576) + ' MB');
        } catch (e) {}
    }

    // ---------------- REPEAT: the video must stay THE video ----------------
    // While repeat is on, YouTube is never told that the video ended (it would start its own "next video"
    // after a few rounds). The same video simply starts again from the beginning.
    window.addEventListener('ended', function(e) {
        try {
            var v0 = e.target;
            if (!v0 || v0.tagName !== 'VIDEO') return;
            // PLAYLIST: when the app itself goes on with the next video, YouTube must not start another one.
            if (!window.__gsRepeatEnabled && window.__gsAppAdvances && window.__gsAutoNextEnabled && window.__gsHasPlaylist) {
                if (adJustEnded()) return;
                e.stopImmediatePropagation();
                if (!v0.__gsNextSent) {
                    v0.__gsNextSent = true;
                    window.__gsLog('playlist: the app moves on to the next video');
                    if (window.AndroidBridge) window.AndroidBridge.onVideoEnded();
                }
                return;
            }
            if (!window.__gsRepeatEnabled) return;
            var v = v0;
            if (adJustEnded()) return;                 // the end of an ad: leave it to YouTube
            e.stopImmediatePropagation();
            window.__gsWant = true;
            window.__gsWantKey = currentKey();
            window.__gsWantVideo = v;
            window.__gsLog('repeat: end handled here, the same video starts again');
            v.currentTime = 0;
            var pr = v.play();
            if (pr && pr.catch) pr.catch(function() { window.__gsResume(); });
            window.__gsReport(false);
        } catch (err) {}
    }, true);

    // YouTube's own suggestion for "next video" (the first other video linked on the page).
    // Returns its id, or '' when there is none.
    window.__gsUpNext = function() {
        try {
            var current = currentKey();
            var links = document.querySelectorAll('a[href*="/watch?v="]');
            for (var i = 0; i < links.length; i++) {
                var m = (links[i].getAttribute('href') || '').match(/[?&]v=([A-Za-z0-9_-]{11})/);
                if (m && m[1] !== current) return m[1];
            }
        } catch (e) {}
        return '';
    };

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
            if (adJustEnded()) { window.__gsReport(false); return; }
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
        video.addEventListener('timeupdate', function() { endCheck(video); });
        ['seeked', 'loadedmetadata', 'durationchange'].forEach(function(ev) {
            video.addEventListener(ev, function() { window.__gsProgress(true); });
        });
        ['waiting', 'stalled'].forEach(function(ev) {
            video.addEventListener(ev, function() {
                var t = now();
                if (t - (window.__gsLastStallLogAt || 0) < 10000) return;
                window.__gsLastStallLogAt = t;
                window.__gsLog('video ' + ev + ' (readyState=' + video.readyState + ', pip=' + window.__gsPip + ')');
            });
        });
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
        ensureAdStyle();
        skipAds();
        updatePlayingFlag();
        autoStart();
        checkPlaybackProblem();
        dismissStillWatching();
        logMemory();
        var adNow = adJustEnded();
        var liveNow = isLiveStream();
        var videos = document.querySelectorAll('video');
        for (var i = 0; i < videos.length; i++) {
            var video = videos[i];
            if (!video.hasAttribute('data-gs-bound')) bind(video);
            if (video.muted) video.muted = false;
            if (video.volume < 1.0) video.volume = 1.0;
            video.loop = !!window.__gsRepeatEnabled;

            // Fallback for the end of the video (YouTube sometimes skips 'ended').
            if (!video.paused) window.__gsProgress(false);
            if (!adNow && !liveNow && video.duration > 1 && !isNaN(video.duration) && !video.paused) {
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

    // Full speed while the app is on screen; while it plays in the background (screen off, hours long)
    // the page is checked only once per second: much less work for the processor and the battery.
    function scanLoop() {
        try { window.__gsScan(); } catch (e) {}
        window.__gsScanner = setTimeout(scanLoop, window.__gsBg ? 1000 : 300);
    }
    function adLoop() {
        skipAds();
        window.__gsAdScanner = setTimeout(adLoop, window.__gsBg ? 1000 : 250);
    }
    window.__gsScanner = setTimeout(scanLoop, 300);
    window.__gsAdScanner = setTimeout(adLoop, 250);
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
    private var lastMediaState: MediaState? = null
    private var lastMediaSentElapsed: Long = 0L

    // Where the video is (reported by the page): lock-screen seek bar and recovery after a WebView crash
    private var lastKnownUrl: String? = null
    private var lastPositionSec: Double = 0.0
    private var lastDurationSec: Double = 0.0
    private var lastProgressElapsed: Long = 0L

    // Videos opened during this session, so that "previous / next" works outside playlists too
    private val sessionVideos = ArrayList<String>()
    private var sessionIndex: Int = -1

    // Repeat: the looped video must never be left by YouTube's own "next video"
    private var repeatVideoId: String? = null
    private var lastExplicitNavAt: Long = 0L   // last time the user / the app chose a video
    private var lastTouchAt: Long = 0L         // last touch on the page
    private var autoStartUntilElapsed: Long = 0L   // the page must start the video until this moment

    // A video watched before the ones of this session (from the history): "previous" can go there
    private var historyPreviousId: String? = null

    // Never stuck: broken loads / playback are tried again (or skipped in a playlist); a stopped video is restarted
    private val recoveryHandler = Handler(Looper.getMainLooper())
    private var recoveryRunnable: Runnable? = null
    private var recoveryUrl: String? = null
    private var loadAttempt: Int = 0
    private var problemVideoId: String? = null
    private var problemCount: Int = 0
    private var lastProblemElapsed: Long = 0L
    private var notPlayingSince: Long = 0L
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private val resumeCheckRunnable = object : Runnable {
        override fun run() {
            resumeCheck()
            if (!isActivityResumed || isPipModeActive) recoveryHandler.postDelayed(this, 10_000L)
        }
    }

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
                    autoStartUntilElapsed = 0L
                    armGuard()
                    ensureWebViewRunning()
                    webView.evaluateJavascript("(window.__gsToggle || function(){})();", null)
                }
                CONTROL_TYPE_PLAY -> {
                    armGuard()
                    ensureWebViewRunning()
                    webView.evaluateJavascript("(window.__gsPlay || function(){})();", null)
                }
                CONTROL_TYPE_PAUSE -> {
                    autoStartUntilElapsed = 0L
                    armGuard()
                    ensureWebViewRunning()
                    webView.evaluateJavascript("(window.__gsPause || function(){})();", null)
                }
                CONTROL_TYPE_PREVIOUS -> {
                    armGuard()
                    ensureWebViewRunning()
                    playPrevious()
                }
                CONTROL_TYPE_NEXT -> {
                    armGuard()
                    ensureWebViewRunning()
                    playNext()
                }
                CONTROL_TYPE_SEEK_TO -> {
                    val positionMs = intent.getLongExtra(EXTRA_POSITION_MS, -1L)
                    if (positionMs >= 0) {
                        ensureWebViewRunning()
                        webView.evaluateJavascript(
                            "(window.__gsSeekTo || function(){})(" + (positionMs / 1000.0) + ");", null
                        )
                    }
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

        configureWebView(webView)

        registerNetworkCallback()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(pipReceiver, IntentFilter(ACTION_MEDIA_CONTROL), RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(pipReceiver, IntentFilter(ACTION_MEDIA_CONTROL))
        }

        rootFrame.addView(webView, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))

        // The floating controls (dock + mini-window chip + message pill).
        overlay = PlayerOverlay(this)
        overlay.listener = object : PlayerOverlay.Listener {
            override fun onLibrary() {
                startActivity(Intent(this@MainActivity, LibraryActivity::class.java))
            }

            override fun onHistory() {
                startActivity(
                    Intent(this@MainActivity, LibraryActivity::class.java)
                        .putExtra(LibraryActivity.EXTRA_TAB, LibraryActivity.TAB_HISTORY)
                )
            }

            override fun onSave() {
                showPlaylistSelectionDialog()
            }

            override fun onPlayPause() {
                autoStartUntilElapsed = 0L
                webView.evaluateJavascript("(window.__gsToggle || function(){})();", null)
            }

            override fun onRepeat() {
                isRepeatEnabled = !isRepeatEnabled
                repeatVideoId = if (isRepeatEnabled) YoutubeHelper.extractVideoId(webView.url) else null
                overlay.setRepeat(isRepeatEnabled)
                showMessage(getString(if (isRepeatEnabled) R.string.msg_repeat_on else R.string.msg_repeat_off))
                syncRepeatStateToJs()
                syncAutoNextStateToJs()
            }

            override fun onAutoNext() {
                isAutoNextEnabled = !isAutoNextEnabled
                updateAutoNextButtonVisibility()
                showMessage(getString(if (isAutoNextEnabled) R.string.msg_autonext_on else R.string.msg_autonext_off))
                syncAutoNextStateToJs()
            }

            override fun onPip() {
                enterPipMode()
            }

            override fun onPrevious() {
                playPrevious()
            }

            override fun onNext() {
                playNext()
            }
        }
        rootFrame.addView(overlay, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))

        setContentView(rootFrame)

        if (savedInstanceState != null) {
            currentPlaylistId = savedInstanceState.getString("gs_playlist_id")
            currentVideoIndex = savedInstanceState.getInt("gs_video_index", -1)
            isRepeatEnabled = savedInstanceState.getBoolean("gs_repeat", false)
            isAutoNextEnabled = savedInstanceState.getBoolean("gs_autonext", false)
            overlay.setRepeat(isRepeatEnabled)
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

    /** All settings, bridges and callbacks of a WebView (also used again when a new one has to be made). */
    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
    private fun configureWebView(wv: WebView) {
        wv.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            loadWithOverviewMode = true
            useWideViewPort = true
        }

        wv.addJavascriptInterface(WebAppInterface(), "AndroidBridge")

        wv.webChromeClient = object : WebChromeClient() {
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

            override fun onReceivedTitle(view: WebView?, title: String?) {
                super.onReceivedTitle(view, title)
                // YouTube changes pages without a reload: keep the lock-screen title correct.
                syncKeepAlive()
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

        wv.webViewClient = object : WebViewClient() {
            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                super.onReceivedError(view, request, error)
                if (request == null || !request.isForMainFrame) return
                val description = error?.description?.toString() ?: ""
                if (description.contains("ERR_ABORTED")) return
                val failed = request.url.toString()
                if (!failed.contains("youtube.com") && !failed.contains("youtu.be")) return
                dbg("page could not be loaded (" + (error?.errorCode ?: 0) + " " + description + "): " + failed)
                runOnUiThread { handleLoadFailure(failed, "load error") }
            }

            override fun onReceivedHttpError(view: WebView?, request: WebResourceRequest?, errorResponse: WebResourceResponse?) {
                super.onReceivedHttpError(view, request, errorResponse)
                if (request == null || !request.isForMainFrame) return
                val code = errorResponse?.statusCode ?: return
                if (code < 400) return
                val failed = request.url.toString()
                if (!failed.contains("youtube.com") && !failed.contains("youtu.be")) return
                dbg("page answered with error " + code + ": " + failed)
                runOnUiThread { handleLoadFailure(failed, "http " + code) }
            }

            override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
                // Android took the page's process away (usually: not enough memory after hours of playback).
                // Do not crash: build a new WebView and go on from the same position.
                dbg("render process gone (crashed=" + (detail?.didCrash() ?: false) + ")")
                runOnUiThread { recoverWebView() }
                return true
            }
            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                super.onPageStarted(view, url, favicon)
                // A planned retry must not override a page the user (or the app) opened in the meantime.
                val planned = recoveryUrl
                if (recoveryRunnable != null && planned != null && url != null && url.startsWith("http") &&
                    YoutubeHelper.extractVideoId(url) != YoutubeHelper.extractVideoId(planned)
                ) {
                    dbg("another page was opened: the planned retry is cancelled")
                    cancelRecovery()
                }
                injectCoreProtectionScript()
            }
            override fun onPageCommitVisible(view: WebView?, url: String?) {
                super.onPageCommitVisible(view, url)
                injectCoreProtectionScript()
            }
            override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                super.doUpdateVisitedHistory(view, url, isReload)
                if (guardRepeatNavigation(url)) return
                if (url != null && url.startsWith("http")) lastKnownUrl = url
                updateSaveButton(url)
                syncPlaylistPositionWithUrl(url)
                trackSessionVideo(url)
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

        // Any touch on the page brings the controls back (the touch itself is not consumed).
        wv.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                lastTouchAt = SystemClock.elapsedRealtime()
                if (::overlay.isInitialized) overlay.onUserInteraction()
            }
            false
        }
    }

    /**
     * Android can end the WebView's process after many hours of playback (memory pressure). Instead of
     * crashing the whole app, a fresh WebView is made and the video continues from the same position.
     */
    private fun recoverWebView() {
        val url = lastKnownUrl ?: "https://m.youtube.com/"
        val position = lastPositionSec
        val resumeUrl =
            if (YoutubeHelper.extractVideoId(url) != null && position.isFinite() && position > 5.0) {
                UrlTools.withStartTime(url, position.toLong())
            } else {
                url
            }
        dbg("recovering the WebView, continuing with: " + resumeUrl)
        customView?.let { rootFrame.removeView(it) }
        customView = null
        customViewCallback = null
        try { rootFrame.removeView(webView) } catch (e: Exception) { }
        try { webView.destroy() } catch (e: Exception) { }
        webView = KeepAliveWebView(this)
        configureWebView(webView)
        rootFrame.addView(webView, 0, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))
        lastProgressElapsed = 0L
        markExplicitNavigation()
        ensureWebViewRunning()
        refreshOverlay()
        webView.loadUrl(resumeUrl)
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

    /** What the notification / lock screen should show right now. */
    private fun buildMediaState(playing: Boolean): MediaState {
        val id = YoutubeHelper.extractVideoId(webView.url)
        val durationMs =
            if (lastDurationSec.isFinite() && lastDurationSec > 0.0) (lastDurationSec * 1000.0).toLong() else 0L
        var positionMs = if (lastPositionSec.isFinite() && lastPositionSec > 0.0) (lastPositionSec * 1000.0).toLong() else 0L
        if (playing && lastProgressElapsed > 0L) positionMs += SystemClock.elapsedRealtime() - lastProgressElapsed
        if (durationMs > 0L && positionMs > durationMs) positionMs = durationMs
        val subtitle = currentPlaylistId?.let { PlaylistStore.getPlaylist(this, it)?.name } ?: "YouTube"
        return MediaState(
            title = notificationTitle(),
            subtitle = subtitle,
            playing = playing,
            hasPrevious = hasPrevious(),
            hasNext = hasNext(),
            durationMs = durationMs,
            positionMs = if (durationMs > 0L) positionMs else -1L,
            thumbUrl = if (id != null) "https://img.youtube.com/vi/" + id + "/hqdefault.jpg" else null,
            keepAwake = recoveryRunnable != null || autoStartUntilElapsed > SystemClock.elapsedRealtime()
        )
    }

    /**
     * Screen-off playback: while a video is playing, a foreground service (media notification, lock-screen
     * controls, wake locks) keeps the app alive, so playback, repeat and auto-next continue with the screen off.
     * A paused video keeps its lock-screen controls while the app is in the background.
     */
    private fun syncKeepAlive() {
        if (!::webView.isInitialized || !playbackReported) return
        if (isVideoPlaying) {
            if (!isActivityResumed) ensureWebViewRunning()
            sendKeepAlive(true)
        } else if (isActivityResumed || isPipModeActive) {
            stopKeepAlive()
        } else if (keepAliveRunning || YoutubeHelper.extractVideoId(webView.url) != null) {
            sendKeepAlive(false)
        }
    }

    private fun sendKeepAlive(playing: Boolean) {
        val state = buildMediaState(playing)
        val previous = lastMediaState
        val now = SystemClock.elapsedRealtime()
        if (keepAliveRunning && previous != null && state.sameAs(previous) &&
            !state.positionDrifted(previous, now - lastMediaSentElapsed)
        ) {
            return
        }
        PlaybackService.update(this, state)
        keepAliveRunning = true
        lastMediaState = state
        lastMediaSentElapsed = now
    }

    private fun stopKeepAlive(force: Boolean = false) {
        if (!keepAliveRunning && !force) return
        keepAliveRunning = false
        lastMediaState = null
        PlaybackService.stop(this)
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
            refreshHistoryPrevious()
            if (::overlay.isInitialized) overlay.setPrevNext(hasPrevious(), hasNext())
        }
    }

    /** Full-screen video or the mini window: the controls disappear completely. */
    private fun hideAllFabs() {
        overlay.setSuppressed(true)
    }

    private fun showDefaultFabs() {
        refreshOverlay()
    }

    /** Brings the controls in line with the current page, playback and settings. */
    private fun refreshOverlay(url: String? = null) {
        if (!::overlay.isInitialized) return
        val suppressed = customView != null || isPipModeActive
        overlay.setSuppressed(suppressed)
        if (suppressed) return
        val id = YoutubeHelper.extractVideoId(url ?: webView.url)
        overlay.setVideoPage(id != null)
        overlay.setPlaying(isVideoPlaying)
        overlay.setRepeat(isRepeatEnabled)
        overlay.setAutoNext(currentPlaylistId != null && currentVideoIndex >= 0, isAutoNextEnabled)
        overlay.setSaved(id != null && PlaylistStore.isVideoSaved(this, id))
        overlay.setPrevNext(hasPrevious(), hasNext())
    }

    /**
     * A short message: on the overlay while the app is in front, otherwise a system Toast.
     * In the mini window nothing is shown, unless [force] is set (answer to a button the user pressed).
     */
    private fun showMessage(message: String, force: Boolean = false) {
        if (isPipModeActive && !force) return
        if (isActivityResumed && customView == null && !isPipModeActive) {
            overlay.toast(message)
        } else {
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        }
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

    private fun pipAction(type: Int, iconRes: Int, label: String, enabled: Boolean = true): RemoteAction {
        val intent = PendingIntent.getBroadcast(
            this, type,
            Intent(ACTION_MEDIA_CONTROL).setPackage(packageName).putExtra(EXTRA_CONTROL_TYPE, type),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val action = RemoteAction(Icon.createWithResource(this, iconRes), label, label, intent)
        action.isEnabled = enabled
        return action
    }

    /**
     * The buttons of the mini window. Most phones allow 3: Previous | Play/Pause | Next.
     * Phones that allow 5 also get "back 10 s" and "forward 10 s".
     */
    private fun buildPipActions(): List<RemoteAction> {
        val maxActions = try { maxNumPictureInPictureActions } catch (e: Exception) { 3 }
        val actions = mutableListOf<RemoteAction>()
        actions.add(pipAction(
            CONTROL_TYPE_PREVIOUS, android.R.drawable.ic_media_previous,
            getString(R.string.notif_previous), hasPrevious()
        ))
        if (maxActions >= 5) {
            actions.add(pipAction(
                CONTROL_TYPE_REWIND, android.R.drawable.ic_media_rew, getString(R.string.pip_rewind)
            ))
        }
        actions.add(pipAction(
            CONTROL_TYPE_PLAY_PAUSE,
            if (isVideoPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
            getString(if (isVideoPlaying) R.string.cd_pause else R.string.cd_play)
        ))
        if (maxActions >= 5) {
            actions.add(pipAction(
                CONTROL_TYPE_FORWARD, android.R.drawable.ic_media_ff, getString(R.string.pip_forward)
            ))
        }
        actions.add(pipAction(
            CONTROL_TYPE_NEXT, android.R.drawable.ic_media_next,
            getString(R.string.notif_next), hasNext()
        ))
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
        markExplicitNavigation()
        val openUrl = intent.getStringExtra("open_url")
        if (intent.hasExtra("playlist_id")) {
            currentPlaylistId = intent.getStringExtra("playlist_id")
            currentVideoIndex = intent.getIntExtra("video_index", -1)
            if (currentPlaylistId != null && currentVideoIndex >= 0 && !isRepeatEnabled) {
                isAutoNextEnabled = true
            }
        } else if (openUrl != null) {
            // Opened a plain URL: it no longer belongs to any playlist.
            currentPlaylistId = null
            currentVideoIndex = -1
        }

        updateAutoNextButtonVisibility()
        syncAutoNextStateToJs()
        onNavigationChanged()

        when {
            openUrl != null -> webView.loadUrl(openUrl)
            else -> if (webView.url == null) webView.loadUrl("https://m.youtube.com")
        }
    }

    private fun updateAutoNextButtonVisibility() {
        if (!::overlay.isInitialized) return
        overlay.setAutoNext(currentPlaylistId != null && currentVideoIndex >= 0, isAutoNextEnabled)
    }

    private fun syncRepeatStateToJs() {
        val js = "window.__gsRepeatEnabled = $isRepeatEnabled; " +
            "document.querySelectorAll('video').forEach(function(v){ v.loop = $isRepeatEnabled; });"
        webView.evaluateJavascript(js, null)
    }

    /** True when the app will itself open the next playlist video at the end of this one. */
    private fun autoAdvanceReady(): Boolean =
        isAutoNextEnabled && !isRepeatEnabled && playlistActive() && hasNext()

    private fun syncAutoNextStateToJs() {
        val hasPlaylist = (currentPlaylistId != null && currentVideoIndex >= 0)
        val js = "window.__gsAutoNextEnabled = $isAutoNextEnabled; window.__gsHasPlaylist = $hasPlaylist; " +
            "window.__gsAppAdvances = " + autoAdvanceReady() + ";"
        webView.evaluateJavascript(js, null)
    }

    private fun injectCoreProtectionScript() {
        val hasPlaylist = (currentPlaylistId != null && currentVideoIndex >= 0)
        val stateJs = "window.__gsRepeatEnabled = $isRepeatEnabled; " +
            "window.__gsAutoNextEnabled = $isAutoNextEnabled; " +
            "window.__gsHasPlaylist = $hasPlaylist; " +
            "window.__gsPip = $isPipModeActive; " +
            "window.__gsBg = $inBackground; " +
            "window.__gsAppAdvances = " + autoAdvanceReady() + "; " +
            "window.__gsAutoStartUntil = " + autoStartScript() + ";\n"
        // A new page (e.g. the next video loaded while the mini window is shown) starts without the
        // mini-window layout: put it back.
        if (isPipModeActive) applyPipLayout(true)
        // The big script is sent only once per page; later calls just refresh the state (less work, less memory).
        webView.evaluateJavascript("(window.__gsCoreInstalled === true)") { installed ->
            if (installed == "true") {
                webView.evaluateJavascript(stateJs + "(window.__gsScan || function(){})();", null)
            } else {
                webView.evaluateJavascript(stateJs + CORE_JS, null)
            }
        }
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
            onNavigationChanged()
        }
    }

    private inner class WebAppInterface {
        @JavascriptInterface
        fun onVideoEnded() {
            runOnUiThread {
                if (!isActivityResumed) ensureWebViewRunning()
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
        fun onPlaybackProblem(kind: String) {
            runOnUiThread { handlePlaybackProblem(kind) }
        }

        @JavascriptInterface
        fun onProgress(positionSeconds: Double, durationSeconds: Double) {
            runOnUiThread {
                // The video really plays: everything that was planned to repair it is no longer needed.
                if (isVideoPlaying && positionSeconds > 3.0) {
                    loadAttempt = 0
                    problemCount = 0
                    if (recoveryRunnable != null) cancelRecovery()
                }
                lastPositionSec = positionSeconds
                lastDurationSec = durationSeconds
                lastProgressElapsed = SystemClock.elapsedRealtime()
                if (keepAliveRunning) sendKeepAlive(isVideoPlaying)
            }
        }

        @JavascriptInterface
        fun onDebug(message: String) {
            Log.d(TAG, "JS: $message")
        }
    }

    /** JS expression: the moment (page clock) until which the page must start the video, or 0. */
    private fun autoStartScript(): String {
        val remaining = autoStartUntilElapsed - SystemClock.elapsedRealtime()
        return if (remaining > 0L) "(Date.now() + " + remaining + ")" else "0"
    }

    private fun playlistActive(): Boolean = currentPlaylistId != null && currentVideoIndex >= 0

    private fun playlistVideos(): List<SavedVideo>? {
        val pid = currentPlaylistId ?: return null
        return PlaylistStore.getPlaylist(this, pid)?.videos
    }

    // ------------------------------------------------------------------ never stuck

    private fun isNetworkAvailable(): Boolean = try {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
        caps != null &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    } catch (e: Exception) {
        false
    }

    private fun cancelRecovery() {
        recoveryRunnable?.let { recoveryHandler.removeCallbacks(it) }
        recoveryRunnable = null
    }

    /** Opens [url] again after [delayMs] (only one such retry is planned at a time). */
    private fun scheduleReload(url: String, delayMs: Long, why: String) {
        cancelRecovery()
        recoveryUrl = url
        dbg("opening again in " + delayMs + " ms (" + why + "): " + url)
        val task = Runnable {
            recoveryRunnable = null
            markExplicitNavigation()
            ensureWebViewRunning()
            webView.loadUrl(url)
        }
        recoveryRunnable = task
        recoveryHandler.postDelayed(task, delayMs)
        syncKeepAlive()      // the processor must stay awake until the retry has been done
    }

    /** In a playlist: gives up on a video that cannot be opened and goes on with the next one. */
    private fun skipBrokenVideo() {
        loadAttempt = 0
        problemCount = 0
        cancelRecovery()
        showMessage(getString(R.string.msg_video_skipped), force = true)
        playNextVideoInPlaylist(manual = false)
        onNavigationChanged()
    }

    /** A page could not be loaded (no connection ...): try again with growing pauses, or skip it in a playlist. */
    private fun handleLoadFailure(url: String, why: String) {
        loadAttempt++
        if (loadAttempt > RecoveryPolicy.MAX_ATTEMPTS) {
            dbg("giving up on " + url)
            return
        }
        if (RecoveryPolicy.shouldSkip(loadAttempt, isNetworkAvailable(), playlistActive(), hasNext())) {
            skipBrokenVideo()
            return
        }
        scheduleReload(url, RecoveryPolicy.retryDelayMs(loadAttempt), why)
    }

    /** The page reported that the video shows an error, has an error or got no data for a long time. */
    private fun handlePlaybackProblem(kind: String) {
        val id = YoutubeHelper.extractVideoId(webView.url) ?: return
        val now = SystemClock.elapsedRealtime()
        if (id == problemVideoId && now - lastProblemElapsed < 180_000L) {
            problemCount++
        } else {
            problemVideoId = id
            problemCount = 1
        }
        lastProblemElapsed = now
        val online = isNetworkAvailable()
        dbg("playback problem: " + kind + " (#" + problemCount + ", internet=" + online + ")")
        if (RecoveryPolicy.shouldSkip(problemCount, online, playlistActive(), hasNext())) {
            skipBrokenVideo()
            return
        }
        // A video that YouTube itself refuses although the internet works: outside playlists try once more only.
        if (kind == "error" && online && !playlistActive() && problemCount >= 2) return
        if (problemCount > RecoveryPolicy.MAX_ATTEMPTS) return
        val base = lastKnownUrl ?: webView.url ?: return
        val position = lastPositionSec
        val resume = if (kind != "error" && position.isFinite() && position > 5.0) {
            UrlTools.withStartTime(base, position.toLong())
        } else {
            base
        }
        scheduleReload(resume, RecoveryPolicy.retryDelayMs(problemCount), "playback problem " + kind)
    }

    /** When the connection comes back, a planned retry is done at once. */
    private fun registerNetworkCallback() {
        try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    runOnUiThread {
                        val url = recoveryUrl
                        if (url != null && recoveryRunnable != null) scheduleReload(url, 1500L, "connection is back")
                    }
                }
            }
            cm.registerDefaultNetworkCallback(callback)
            networkCallback = callback
        } catch (e: Exception) {
            dbg("network callback not available: " + e)
        }
    }

    private fun unregisterNetworkCallback() {
        val callback = networkCallback ?: return
        networkCallback = null
        try {
            (getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager).unregisterNetworkCallback(callback)
        } catch (e: Exception) {
        }
    }

    /** Can playback be started again without disturbing a call or another app's music? */
    private fun canResumeNow(): Boolean {
        val audio = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        return audio.mode == AudioManager.MODE_NORMAL && !audio.isMusicActive
    }

    /**
     * While the app is in the background / mini window: if the video stopped by itself (a call, a short
     * loss of audio focus ...) although the user did not pause it, it is started again after ~10 s.
     */
    private fun resumeCheck() {
        if (isActivityResumed && !isPipModeActive) return
        if (isVideoPlaying) {
            notPlayingSince = 0L
            return
        }
        val now = SystemClock.elapsedRealtime()
        if (notPlayingSince == 0L) {
            notPlayingSince = now
            return
        }
        if (now - notPlayingSince < 10_000L) return
        if (recoveryRunnable != null) return      // a reload is already planned
        webView.evaluateJavascript("(window.__gsWant === true)") { answer ->
            if (answer == "true" && canResumeNow()) {
                dbg("watchdog: playback stopped by itself, starting it again")
                ensureWebViewRunning()
                webView.evaluateJavascript("(window.__gsPlay || function(){})();", null)
            }
        }
    }

    private fun startResumeWatchdog() {
        recoveryHandler.removeCallbacks(resumeCheckRunnable)
        notPlayingSince = 0L
        recoveryHandler.postDelayed(resumeCheckRunnable, 10_000L)
    }

    private fun stopResumeWatchdog() {
        recoveryHandler.removeCallbacks(resumeCheckRunnable)
        notPlayingSince = 0L
    }

    private fun markExplicitNavigation() {
        lastExplicitNavAt = SystemClock.elapsedRealtime()
        autoStartUntilElapsed = lastExplicitNavAt + 25000L
    }

    private fun onVideoPage(): Boolean = YoutubeHelper.extractVideoId(webView.url) != null

    /**
     * Is there a video before the current one? In a playlist: the one before it. Otherwise: a video opened
     * earlier in this session, or (if there is none) a video from the watch history.
     */
    private fun hasPrevious(): Boolean =
        if (playlistActive()) currentVideoIndex > 0 else sessionIndex > 0 || historyPreviousId != null

    /**
     * Is there a next video? In a playlist: the one after it. Otherwise: a video that was left by "previous",
     * or YouTube's own suggestion (any open video page has one).
     */
    private fun hasNext(): Boolean {
        if (playlistActive()) {
            val size = playlistVideos()?.size ?: 0
            return currentVideoIndex < size - 1
        }
        return (sessionIndex >= 0 && sessionIndex < sessionVideos.size - 1) || onVideoPage()
    }

    /** The newest history video that was not opened in this session. */
    private fun refreshHistoryPrevious() {
        val seen = HashSet<String>(sessionVideos)
        historyPreviousId = HistoryStore.getHistory(this).firstOrNull { it.videoId !in seen }?.videoId
    }

    /** Remembers the videos opened in this session (like browser history) for "previous / next". */
    private fun trackSessionVideo(url: String?) {
        val id = YoutubeHelper.extractVideoId(url) ?: return
        if (sessionIndex in sessionVideos.indices && sessionVideos[sessionIndex] == id) return
        while (sessionVideos.size > sessionIndex + 1) sessionVideos.removeAt(sessionVideos.size - 1)
        sessionVideos.add(id)
        sessionIndex = sessionVideos.size - 1
        if (sessionVideos.size > 200) {
            sessionVideos.removeAt(0)
            sessionIndex--
        }
        onNavigationChanged()
    }

    /** Previous / next may have become (un)available: update the app, mini-window and lock-screen buttons. */
    private fun onNavigationChanged() {
        refreshHistoryPrevious()
        if (::webView.isInitialized) syncAutoNextStateToJs()
        if (::overlay.isInitialized) overlay.setPrevNext(hasPrevious(), hasNext())
        updatePipActions()
        syncKeepAlive()
    }

    private fun loadWatchId(id: String) {
        markExplicitNavigation()
        webView.loadUrl("https://m.youtube.com/watch?v=" + id)
    }

    /**
     * While repeat is on the looped video must stay: if YouTube moves on by itself (its own "next video"),
     * without the user or the app having chosen another video, the looped video is opened again.
     * Returns true when that happened.
     */
    private fun guardRepeatNavigation(url: String?): Boolean {
        if (!isRepeatEnabled) return false
        val id = YoutubeHelper.extractVideoId(url) ?: return false
        val looped = repeatVideoId
        if (looped == null || id == looped) {
            repeatVideoId = id
            return false
        }
        val now = SystemClock.elapsedRealtime()
        val chosen = now - lastExplicitNavAt < 15000L || now - lastTouchAt < 15000L
        if (chosen) {
            repeatVideoId = id            // the user picked another video: repeat that one
            return false
        }
        dbg("repeat: YouTube moved to another video by itself, going back to " + looped)
        loadWatchId(looped)
        return true
    }

    /** Previous video: in a playlist the one before it, otherwise an earlier video of this session / the history. */
    private fun playPrevious() {
        markExplicitNavigation()
        if (!hasPrevious()) {
            showMessage(getString(R.string.msg_no_previous), force = true)
            return
        }
        if (playlistActive()) {
            val videos = playlistVideos() ?: return
            val index = currentVideoIndex - 1
            val video = videos.getOrNull(index) ?: return
            currentVideoIndex = index
            webView.loadUrl(video.url)
            showMessage(getString(R.string.msg_previous_video, video.title), force = true)
        } else if (sessionIndex > 0) {
            sessionIndex--
            loadWatchId(sessionVideos[sessionIndex])
        } else {
            val id = historyPreviousId ?: return
            sessionVideos.add(0, id)
            sessionIndex = 0
            loadWatchId(id)
        }
        onNavigationChanged()
    }

    /** Next video: in a playlist the one after it, otherwise one left by "previous", otherwise YouTube's suggestion. */
    private fun playNext() {
        markExplicitNavigation()
        if (playlistActive()) {
            if (hasNext()) {
                playNextVideoInPlaylist(manual = true)
                onNavigationChanged()
            } else {
                showMessage(getString(R.string.msg_no_next), force = true)
            }
            return
        }
        if (sessionIndex >= 0 && sessionIndex < sessionVideos.size - 1) {
            sessionIndex++
            loadWatchId(sessionVideos[sessionIndex])
            onNavigationChanged()
            return
        }
        webView.evaluateJavascript("(window.__gsUpNext ? window.__gsUpNext() : '')") { raw ->
            val id = (raw ?: "").trim().removeSurrounding("\"")
            if (id.length == 11) {
                loadWatchId(id)
            } else {
                showMessage(getString(R.string.msg_no_next), force = true)
            }
        }
    }

    private fun playNextVideoInPlaylist(manual: Boolean = false) {
        markExplicitNavigation()
        val videos = playlistVideos() ?: return

        val nextIndex = currentVideoIndex + 1
        if (nextIndex < videos.size) {
            currentVideoIndex = nextIndex
            val nextVideo = videos[nextIndex]
            webView.loadUrl(nextVideo.url)
            if (manual || !isPipModeActive) {
                showMessage(getString(R.string.msg_next_video, nextVideo.title), force = manual)
            }
        } else {
            if (!isPipModeActive) {
                showMessage(getString(R.string.msg_playlist_finished))
            }
        }
    }

    private fun updateSaveButton(url: String?) {
        refreshOverlay(url)
    }

    private fun updatePlayPauseButton() {
        if (!::overlay.isInitialized) return
        overlay.setPlaying(isVideoPlaying)
    }

    /** Single entry point for any playback-state change coming from the page (UI only). */
    private fun applyPlaybackState(playing: Boolean) {
        playbackReported = true
        if (playing) {
            autoStartUntilElapsed = 0L
            loadAttempt = 0
        }
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

    /** The star button: choose a playlist (or create one) for the open video. */
    private fun showPlaylistSelectionDialog() {
        val id = YoutubeHelper.extractVideoId(webView.url) ?: return
        scope.launch {
            val video = withContext(Dispatchers.IO) { YoutubeHelper.fetchOEmbed(id) }
            Flows.addToPlaylist(
                this@MainActivity, video,
                notify = { message -> showMessage(message) },
                onChanged = { refreshOverlay() }
            )
        }
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
        startResumeWatchdog()
        // Keep the page running while a video plays (screen off / other app), in the mini window and during the
        // switch to the mini window. A page that is paused cannot start the next video (that was one reason why a
        // playlist could stop with the screen off), so on a video page the page is never paused. Only an idle,
        // non-video page is.
        val keepRunning = isPipModeActive || isVideoPlaying || onVideoPage() ||
            autoStartUntilElapsed > SystemClock.elapsedRealtime() ||
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
        if (!isPipModeActive) stopResumeWatchdog()
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
        cancelRecovery()
        stopResumeWatchdog()
        unregisterNetworkCallback()
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
