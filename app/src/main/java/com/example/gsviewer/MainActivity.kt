package com.example.gsviewer

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
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

    private val scope = CoroutineScope(Dispatchers.Main)
    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null
    private lateinit var rootFrame: FrameLayout

    // Loop & Playlist state
    private var isRepeatEnabled: Boolean = false
    private var isAutoNextEnabled: Boolean = false
    private var currentPlaylistId: String? = null
    private var currentVideoIndex: Int = -1

    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
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
                updateSaveButton(webView.url)
            }
        }

        webView.webViewClient = object : WebViewClient() {
            override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                super.doUpdateVisitedHistory(view, url, isReload)
                updateSaveButton(url)
                injectAudioAndVideoListeners()
            }
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                updateSaveButton(url)
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
        rootFrame.addView(playlistButton, fabParams(Gravity.BOTTOM or Gravity.START, 16))

        saveButton = makeFab("⭐") { showPlaylistSelectionDialog() }
        saveButton.visibility = View.INVISIBLE
        rootFrame.addView(saveButton, fabParams(Gravity.BOTTOM or Gravity.END, 16))

        repeatButton = makeFab("🔂") {
            isRepeatEnabled = !isRepeatEnabled
            updateFabColor(repeatButton, isRepeatEnabled)
            val stateText = if (isRepeatEnabled) "Կրկնությունը ՄԻԱՑՎԱԾ Է" else "Կրկնությունը ԱՆՋԱՏՎԱԾ Է"
            Toast.makeText(this, stateText, Toast.LENGTH_SHORT).show()
            injectLoopScript()
        }
        rootFrame.addView(repeatButton, fabParams(Gravity.BOTTOM or Gravity.END, 80))

        autoNextButton = makeFab("⏭️") {
            isAutoNextEnabled = !isAutoNextEnabled
            updateFabColor(autoNextButton, isAutoNextEnabled)
            val stateText = if (isAutoNextEnabled) "Ավտո-հաջորդը ՄԻԱՑՎԱԾ Է" else "Ավտո-հաջորդը ԱՆՋԱՏՎԱԾ Է"
            Toast.makeText(this, stateText, Toast.LENGTH_SHORT).show()
        }
        autoNextButton.visibility = View.GONE
        rootFrame.addView(autoNextButton, fabParams(Gravity.BOTTOM or Gravity.START, 80))

        setContentView(rootFrame)

        handleIntent(intent)
        if (savedInstanceState != null && intent.getStringExtra("open_url") == null) {
            webView.restoreState(savedInstanceState)
        }
        updateSaveButton(webView.url)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent == null) return
        val openUrl = intent.getStringExtra("open_url")
        currentPlaylistId = intent.getStringExtra("playlist_id")
        currentVideoIndex = intent.getIntExtra("video_index", -1)

        updateAutoNextButtonVisibility()

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

    override fun onPause() { super.onPause(); webView.onPause() }
    override fun onResume() { super.onResume(); webView.onResume() }
    override fun onDestroy() { webView.destroy(); super.onDestroy() }
}
