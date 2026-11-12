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
import android.widget.*
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
    private var isVideoPlaying: Boolean = true

    companion object {
        private const val ACTION_MEDIA_CONTROL = "com.example.gsviewer.MEDIA_CONTROL"
        private const val EXTRA_CONTROL_TYPE = "control_type"
        private const val CONTROL_TYPE_PLAY_PAUSE = 1
        private const val CONTROL_TYPE_REWIND = 2
        private const val CONTROL_TYPE_FORWARD = 3
    }

    private val pipActionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent == null || intent.action != ACTION_MEDIA_CONTROL) return
            when (intent.getIntExtra(EXTRA_CONTROL_TYPE, 0)) {
                CONTROL_TYPE_PLAY_PAUSE -> {
                    webView.evaluateJavascript(
                        "(function() { var v = document.querySelector('video'); if (v) { if (v.paused) v.play(); else v.pause(); } })();",
                        null
                    )
                }
                CONTROL_TYPE_REWIND -> {
                    webView.evaluateJavascript(
                        "(function() { var v = document.querySelector('video'); if (v) { v.currentTime = Math.max(0, v.currentTime - 10); } })();",
                        null
                    )
                }
                CONTROL_TYPE_FORWARD -> {
                    webView.evaluateJavascript(
                        "(function() { var v = document.querySelector('video'); if (v) { v.currentTime = Math.min(v.duration || 999999, v.currentTime + 10); } })();",
                        null
                    )
                }
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface", "UnspecifiedRegisterReceiverFlag")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        rootFrame = FrameLayout(this)
        webView = WebView(this)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(pipActionReceiver, IntentFilter(ACTION_MEDIA_CONTROL), RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(pipActionReceiver, IntentFilter(ACTION_MEDIA_CONTROL))
        }

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            loadWithOverviewMode = true
            useWideViewPort = true
        }

        webView.addJavascriptInterface(WebAppInterface(), "AndroidBridge")

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
                saveButton.visibility = View.GONE
                playlistButton.visibility = View.GONE
                repeatButton.visibility = View.GONE
                autoNextButton.visibility = View.GONE
                pipButton.visibility = View.GONE
            }

            override fun onHideCustomView() {
                if (customView == null) return
                rootFrame.removeView(customView)
                customView = null
                customViewCallback?.onCustomViewHidden()
                webView.visibility = View.VISIBLE
                playlistButton.visibility = View.VISIBLE
                repeatButton.visibility = View.VISIBLE
                updateAutoNextButtonVisibility()
                updatePipButtonVisibility()
                updateSaveButton(webView.url)
            }
        }

        webView.webViewClient = object : WebViewClient() {
            override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                super.doUpdateVisitedHistory(view, url, isReload)
                updateSaveButton(url)
                updatePipButtonVisibility()
                injectAudioAndVideoListeners()
            }
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                updateSaveButton(url)
                updatePipButtonVisibility()
                injectAudioAndVideoListeners()
            }
        }

        rootFrame.addView(webView, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))

        playlistButton = makeFab("📁") {
            startActivity(Intent(this@MainActivity, PlaylistActivity::class.java))
        }
        rootFrame.addView(playlistButton, fabParams(Gravity.BOTTOM or Gravity.START, 16, 96))

        saveButton = makeFab("⭐") { showPlaylistSelectionDialog() }
        saveButton.visibility = View.INVISIBLE
        rootFrame.addView(saveButton, fabParams(Gravity.BOTTOM or Gravity.END, 16, 96))

        repeatButton = makeFab("🔂") {
            isRepeatEnabled = !isRepeatEnabled
            updateFabColor(repeatButton, isRepeatEnabled)
            val stateText = if (isRepeatEnabled) "Կրկնությունը ՄԻԱՑՎԱԾ Է" else "Կրկնությունը ԱՆՋԱՏՎԱԾ Է"
            Toast.makeText(this, stateText, Toast.LENGTH_SHORT).show()
            injectLoopScript()
        }
        rootFrame.addView(repeatButton, fabParams(Gravity.BOTTOM or Gravity.END, 80, 96))

        autoNextButton = makeFab("⏭️") {
            isAutoNextEnabled = !isAutoNextEnabled
            updateFabColor(autoNextButton, isAutoNextEnabled)
            val stateText = if (isAutoNextEnabled) "Ավտո-հաջորդը ՄԻԱՑՎԱԾ Է" else "Ավտո-հաջորդը ԱՆՋԱՏՎԱԾ Է"
            Toast.makeText(this, stateText, Toast.LENGTH_SHORT).show()
        }
        autoNextButton.visibility = View.GONE
        rootFrame.addView(autoNextButton, fabParams(Gravity.BOTTOM or Gravity.START, 80, 96))

        pipButton = makeFab("🗗") {
            enterPipMode()
        }
        pipButton.visibility = View.GONE
        rootFrame.addView(pipButton, fabParams(Gravity.TOP or Gravity.END, 16, 16))

        setContentView(rootFrame)

        handleIntent(intent)
        if (savedInstanceState != null && intent.getStringExtra("open_url") == null) {
            webView.restoreState(savedInstanceState)
        }
        updateSaveButton(webView.url)
        updatePipButtonVisibility()
    }

    private fun buildPipParams(): PictureInPictureParams {
        val actions = ArrayList<RemoteAction>()

        // 1. Rewind 10s
        val rewindIntent = PendingIntent.getBroadcast(
            this,
            CONTROL_TYPE_REWIND,
            Intent(ACTION_MEDIA_CONTROL).setPackage(packageName).putExtra(EXTRA_CONTROL_TYPE, CONTROL_TYPE_REWIND),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        actions.add(
            RemoteAction(
                Icon.createWithResource(this, android.R.drawable.ic_media_rew),
                "Հետ 10վ",
                "Հետ տալ 10 վայրկյան",
                rewindIntent
            )
        )

        // 2. Play / Pause
        val playPauseIntent = PendingIntent.getBroadcast(
            this,
            CONTROL_TYPE_PLAY_PAUSE,
            Intent(ACTION_MEDIA_CONTROL).setPackage(packageName).putExtra(EXTRA_CONTROL_TYPE, CONTROL_TYPE_PLAY_PAUSE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val playPauseIcon = if (isVideoPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play
        val playPauseTitle = if (isVideoPlaying) "Դադար" else "Նվագարկել"
        actions.add(
            RemoteAction(
                Icon.createWithResource(this, playPauseIcon),
                playPauseTitle,
                playPauseTitle,
                playPauseIntent
            )
        )

        // 3. Forward 10s
        val forwardIntent = PendingIntent.getBroadcast(
            this,
            CONTROL_TYPE_FORWARD,
            Intent(ACTION_MEDIA_CONTROL).setPackage(packageName).putExtra(EXTRA_CONTROL_TYPE, CONTROL_TYPE_FORWARD),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        actions.add(
            RemoteAction(
                Icon.createWithResource(this, android.R.drawable.ic_media_ff),
                "Առաջ 10վ",
                "Առաջ տալ 10 վայրկյան",
                forwardIntent
            )
        )

        return PictureInPictureParams.Builder()
            .setAspectRatio(Rational(16, 9))
            .setActions(actions)
            .build()
    }

    private fun updatePipParams() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && isInPictureInPictureMode) {
            try {
                setPictureInPictureParams(buildPipParams())
            } catch (e: Exception) {
                // Ignore
            }
        }
    }

    private fun enterPipMode() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            prepareVideoForPip()
            enterPictureInPictureMode(buildPipParams())
        }
    }

    private fun prepareVideoForPip() {
        // Fit the HTML5 video cleanly over the viewport so it fills the PiP window properly and stays playing
        val js = "(function() {" +
                "var v = document.querySelector('video');" +
                "if (v) {" +
                "v.muted = false;" +
                "v.play();" +
                "v.style.position = 'fixed';" +
                "v.style.top = '0';" +
                "v.style.left = '0';" +
                "v.style.width = '100vw';" +
                "v.style.height = '100vh';" +
                "v.style.zIndex = '999999';" +
                "v.style.backgroundColor = 'black';" +
                "v.style.objectFit = 'contain';" +
                "}" +
                "})();"
        webView.evaluateJavascript(js, null)
    }

    private fun restoreVideoFromPip() {
        val js = "(function() {" +
                "var v = document.querySelector('video');" +
                "if (v) {" +
                "v.style.position = '';" +
                "v.style.top = '';" +
                "v.style.left = '';" +
                "v.style.width = '';" +
                "v.style.height = '';" +
                "v.style.zIndex = '';" +
                "v.style.backgroundColor = '';" +
                "v.style.objectFit = '';" +
                "}" +
                "})();"
        webView.evaluateJavascript(js, null)
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        val id = YoutubeHelper.extractVideoId(webView.url)
        if (id != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && customView == null) {
            try {
                prepareVideoForPip()
                enterPictureInPictureMode(buildPipParams())
            } catch (e: Exception) {
                // Ignore
            }
        }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        if (isInPictureInPictureMode) {
            playlistButton.visibility = View.GONE
            saveButton.visibility = View.GONE
            repeatButton.visibility = View.GONE
            autoNextButton.visibility = View.GONE
            pipButton.visibility = View.GONE
            prepareVideoForPip()
            // Force resume playback right after entering PiP
            webView.postDelayed({
                webView.evaluateJavascript("var v = document.querySelector('video'); if (v && v.paused) { v.play(); }", null)
            }, 250)
        } else {
            restoreVideoFromPip()
            playlistButton.visibility = View.VISIBLE
            repeatButton.visibility = View.VISIBLE
            updateAutoNextButtonVisibility()
            updatePipButtonVisibility()
            updateSaveButton(webView.url)
        }
    }

    private fun handleIntent(intent: Intent?) {
        if (intent == null) return
        val openUrl = intent.getStringExtra("open_url")
        currentPlaylistId = intent.getStringExtra("playlist_id")
        currentVideoIndex = intent.getIntExtra("video_index", -1)

        updateAutoNextButtonVisibility()
        updatePipButtonVisibility()

        when {
            openUrl != null -> webView.loadUrl(openUrl)
            else -> if (webView.url == null) webView.loadUrl("https://m.youtube.com")
        }
    }

    private fun updateAutoNextButtonVisibility() {
        if (customView == null && currentPlaylistId != null && currentVideoIndex >= 0) {
            autoNextButton.visibility = View.VISIBLE
        } else {
            autoNextButton.visibility = View.GONE
        }
    }

    private fun updatePipButtonVisibility() {
        val id = YoutubeHelper.extractVideoId(webView.url)
        if (customView == null && id != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            pipButton.visibility = View.VISIBLE
        } else {
            pipButton.visibility = View.GONE
        }
    }

    private fun injectLoopScript() {
        val loopJs = "var videos = document.querySelectorAll('video'); videos.forEach(function(v) { v.loop = " + isRepeatEnabled + "; });"
        webView.evaluateJavascript(loopJs, null)
    }

    private fun injectAudioAndVideoListeners() {
        val js = "(function() {" +
                "function applySettings() {" +
                "var videos = document.querySelectorAll('video');" +
                "videos.forEach(function(video) {" +
                "video.muted = false;" +
                "video.volume = 1.0;" +
                "if (" + isRepeatEnabled + ") { video.loop = true; } else { video.loop = false; }" +
                "if (!video.hasAttribute('data-ended-listener')) {" +
                "video.setAttribute('data-ended-listener', 'true');" +
                "video.addEventListener('ended', function() {" +
                "if (window.AndroidBridge) { window.AndroidBridge.onVideoEnded(); }" +
                "});" +
                "video.addEventListener('play', function() {" +
                "if (window.AndroidBridge) { window.AndroidBridge.onPlaybackStateChanged(true); }" +
                "});" +
                "video.addEventListener('pause', function() {" +
                "if (window.AndroidBridge) { window.AndroidBridge.onPlaybackStateChanged(false); }" +
                "});" +
                "}" +
                "});" +
                "}" +
                "applySettings();" +
                "if (!window.gsIntervalSet) {" +
                "window.gsIntervalSet = true;" +
                "setInterval(applySettings, 500);" +
                "}" +
                "})();"
        webView.evaluateJavascript(js, null)
    }

    private inner class WebAppInterface {
        @JavascriptInterface
        fun onVideoEnded() {
            runOnUiThread {
                if (isRepeatEnabled) {
                    webView.evaluateJavascript("var videos = document.querySelectorAll('video'); videos.forEach(function(v) { v.currentTime = 0; v.play(); });", null)
                } else if (isAutoNextEnabled && currentPlaylistId != null && currentVideoIndex >= 0) {
                    playNextVideoInPlaylist()
                }
            }
        }

        @JavascriptInterface
        fun onPlaybackStateChanged(playing: Boolean) {
            runOnUiThread {
                isVideoPlaying = playing
                updatePipParams()
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
            Toast.makeText(this, "Հաջորդ տեսանյութը: ${nextVideo.title}", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Փլեյլիստի վերջին տեսանյութն ավարտվեց", Toast.LENGTH_SHORT).show()
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

    private fun fabParams(gravity: Int, marginSideDp: Int, marginBottomDp: Int) = FrameLayout.LayoutParams(
        (56 * resources.displayMetrics.density).toInt(),
        (56 * resources.displayMetrics.density).toInt(),
        gravity
    ).apply {
        val m = (marginSideDp * resources.displayMetrics.density).toInt()
        leftMargin = m
        rightMargin = m
        topMargin = m
        bottomMargin = (marginBottomDp * resources.displayMetrics.density).toInt()
    }

    private fun updateSaveButton(url: String?) {
        val id = YoutubeHelper.extractVideoId(url)
        if (customView == null) {
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
        // CRITICAL: Do NOT call webView.onPause() while in Picture-in-Picture mode, otherwise playback freezes!
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && isInPictureInPictureMode) {
            return
        }
        webView.onPause()
    }

    override fun onResume() {
        super.onResume()
        webView.onResume()
    }

    override fun onDestroy() {
        try {
            unregisterReceiver(pipActionReceiver)
        } catch (e: Exception) {
            // Ignore
        }
        webView.destroy()
        super.onDestroy()
    }
}
