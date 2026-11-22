package com.example.gsviewer

import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * The floating controls on top of the YouTube page:
 *  - a pill-shaped dock at the bottom (library, save, play/pause, repeat, auto-next),
 *  - a small "mini window" chip at the top right,
 *  - a short message pill.
 * While a video is playing the controls fade away by themselves and come back with a touch.
 * It never blocks touches outside its own buttons.
 */
class PlayerOverlay(context: Context) : FrameLayout(context) {

    interface Listener {
        fun onLibrary()
        fun onHistory()
        fun onSave()
        fun onPlayPause()
        fun onRepeat()
        fun onAutoNext()
        fun onPip()
        fun onPrevious()
        fun onNext()
    }

    var listener: Listener? = null

    private val dock = LinearLayout(context)
    private val btnLibrary: ImageView
    private val btnHistory: ImageView
    private val btnSave: ImageView
    private val btnPrev: ImageView
    private val btnNext: ImageView
    private val btnPlay: ImageView
    private val btnRepeat: ImageView
    private val btnAutoNext: ImageView
    private val btnPip: ImageView
    private val toastView: TextView

    private var videoPage = false
    private var playing = false
    private var suppressed = false
    private var controlsShown = true
    private var autoNextAvailable = false

    private val hideRunnable = Runnable { autoHide() }
    private val hideToastRunnable = Runnable { hideToast() }

    init {
        clipChildren = false
        clipToPadding = false

        dock.orientation = LinearLayout.HORIZONTAL
        dock.gravity = Gravity.CENTER_VERTICAL
        dock.background = context.getDrawable(R.drawable.bg_dock)
        dock.elevation = Ui.dp(context, 10).toFloat()
        dock.setPadding(Ui.dp(context, 8), Ui.dp(context, 6), Ui.dp(context, 8), Ui.dp(context, 6))

        btnLibrary = iconButton(R.drawable.ic_library, R.string.cd_library)
        btnHistory = iconButton(R.drawable.ic_history, R.string.cd_history)
        btnSave = iconButton(R.drawable.ic_star_outline, R.string.cd_save)
        btnPrev = iconButton(R.drawable.ic_skip_previous, R.string.notif_previous)
        btnNext = iconButton(R.drawable.ic_skip_next, R.string.notif_next)
        btnRepeat = iconButton(R.drawable.ic_repeat_one, R.string.cd_repeat)
        btnAutoNext = iconButton(R.drawable.ic_playlist_play, R.string.cd_autonext)

        btnPlay = ImageView(context).apply {
            setImageResource(R.drawable.ic_pause)
            scaleType = ImageView.ScaleType.CENTER
            setColorFilter(context.getColor(R.color.gs_white))
            background = context.getDrawable(R.drawable.bg_play_btn)
            contentDescription = context.getString(R.string.cd_pause)
            elevation = Ui.dp(context, 4).toFloat()
            layoutParams = LinearLayout.LayoutParams(Ui.dp(context, 56), Ui.dp(context, 56)).apply {
                marginStart = Ui.dp(context, 6)
                marginEnd = Ui.dp(context, 6)
            }
        }

        dock.addView(btnLibrary)
        dock.addView(btnHistory)
        dock.addView(btnSave)
        dock.addView(btnPrev)
        dock.addView(btnPlay)
        dock.addView(btnNext)
        dock.addView(btnRepeat)
        dock.addView(btnAutoNext)

        addView(
            dock,
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
                .apply { bottomMargin = Ui.dp(context, 20) }
        )

        btnPip = ImageView(context).apply {
            setImageResource(R.drawable.ic_pip)
            scaleType = ImageView.ScaleType.CENTER
            setColorFilter(context.getColor(R.color.gs_white))
            background = context.getDrawable(R.drawable.bg_chip_dark)
            contentDescription = context.getString(R.string.cd_pip)
            tooltipText = context.getString(R.string.cd_pip)
            elevation = Ui.dp(context, 6).toFloat()
            visibility = View.GONE
        }
        addView(
            btnPip,
            LayoutParams(Ui.dp(context, 44), Ui.dp(context, 44), Gravity.TOP or Gravity.END).apply {
                topMargin = Ui.dp(context, 12)
                marginEnd = Ui.dp(context, 12)
            }
        )

        toastView = TextView(context).apply {
            setTextColor(context.getColor(R.color.gs_text))
            textSize = 13f
            gravity = Gravity.CENTER
            background = context.getDrawable(R.drawable.bg_toast)
            setPadding(Ui.dp(context, 18), Ui.dp(context, 10), Ui.dp(context, 18), Ui.dp(context, 10))
            elevation = Ui.dp(context, 8).toFloat()
            visibility = View.GONE
        }
        addView(
            toastView,
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
                .apply { bottomMargin = Ui.dp(context, 104) }
        )

        press(btnLibrary) { listener?.onLibrary() }
        press(btnHistory) { listener?.onHistory() }
        press(btnSave) { listener?.onSave() }
        press(btnPlay) { listener?.onPlayPause() }
        press(btnPrev) { listener?.onPrevious() }
        press(btnNext) { listener?.onNext() }
        press(btnRepeat) { listener?.onRepeat() }
        press(btnAutoNext) { listener?.onAutoNext() }
        press(btnPip) { listener?.onPip() }

        setActive(btnRepeat, false)
        setActive(btnAutoNext, false)
        renderButtons()
    }

    // ------------------------------------------------------------------ building blocks

    private fun iconButton(iconRes: Int, descRes: Int): ImageView =
        ImageView(context).apply {
            setImageResource(iconRes)
            scaleType = ImageView.ScaleType.CENTER
            setColorFilter(context.getColor(R.color.gs_icon))
            background = context.getDrawable(R.drawable.bg_icon_btn)
            contentDescription = context.getString(descRes)
            tooltipText = context.getString(descRes)
            layoutParams = LinearLayout.LayoutParams(Ui.dp(context, 48), Ui.dp(context, 48)).apply {
                marginStart = Ui.dp(context, 1)
                marginEnd = Ui.dp(context, 1)
            }
        }

    private fun press(view: View, action: () -> Unit) {
        view.setOnClickListener {
            Ui.tick(view)
            showControls()
            action()
        }
    }

    private fun setActive(button: ImageView, active: Boolean) {
        button.setColorFilter(context.getColor(if (active) R.color.gs_accent_light else R.color.gs_icon))
        button.background = context.getDrawable(if (active) R.drawable.bg_icon_btn_active else R.drawable.bg_icon_btn)
        button.isSelected = active
    }

    /** Dock buttons: 48 dp on browse pages, smaller on video pages (more buttons must fit on one line). */
    private fun resizeDockButtons() {
        val screenDp = resources.displayMetrics.widthPixels / resources.displayMetrics.density
        val sizeDp = if (!videoPage) 48 else if (screenDp >= 360f) 42 else 36
        val size = Ui.dp(context, sizeDp)
        listOf(btnLibrary, btnHistory, btnSave, btnPrev, btnNext, btnRepeat, btnAutoNext).forEach { b ->
            val lp = b.layoutParams as LinearLayout.LayoutParams
            if (lp.width != size || lp.height != size) {
                lp.width = size
                lp.height = size
                b.layoutParams = lp
            }
        }
    }

    private fun setEnabledLook(button: ImageView, enabled: Boolean) {
        button.isEnabled = enabled
        button.alpha = if (enabled) 1f else 0.35f
    }

    /** Previous / next video available? (greyed out otherwise) */
    fun setPrevNext(hasPrevious: Boolean, hasNext: Boolean) {
        setEnabledLook(btnPrev, hasPrevious)
        setEnabledLook(btnNext, hasNext)
    }

    private fun renderButtons() {
        resizeDockButtons()
        // On YouTube's browse pages the dock floats above YouTube's own bottom navigation bar.
        (dock.layoutParams as? LayoutParams)?.let { lp ->
            val margin = Ui.dp(context, if (videoPage) 20 else 76)
            if (lp.bottomMargin != margin) {
                lp.bottomMargin = margin
                dock.layoutParams = lp
            }
        }
        val videoVisibility = if (videoPage) View.VISIBLE else View.GONE
        btnHistory.visibility = if (videoPage) View.GONE else View.VISIBLE
        btnSave.visibility = videoVisibility
        btnPrev.visibility = videoVisibility
        btnNext.visibility = videoVisibility
        btnPlay.visibility = videoVisibility
        btnRepeat.visibility = videoVisibility
        btnAutoNext.visibility = if (videoPage && autoNextAvailable) View.VISIBLE else View.GONE
    }

    // ------------------------------------------------------------------ state from the player

    /** true = a video page is open, false = YouTube home / search / channel pages. */
    fun setVideoPage(value: Boolean) {
        if (videoPage == value) return
        videoPage = value
        renderButtons()
        showControls()
    }

    fun setPlaying(value: Boolean) {
        val changed = playing != value
        playing = value
        btnPlay.setImageResource(if (value) R.drawable.ic_pause else R.drawable.ic_play)
        btnPlay.contentDescription = context.getString(if (value) R.string.cd_pause else R.string.cd_play)
        if (changed) {
            if (value) scheduleHide() else showControls()
        }
    }

    fun setRepeat(active: Boolean) {
        setActive(btnRepeat, active)
    }

    fun setAutoNext(available: Boolean, active: Boolean) {
        autoNextAvailable = available
        setActive(btnAutoNext, active)
        renderButtons()
    }

    fun setSaved(saved: Boolean) {
        btnSave.setImageResource(if (saved) R.drawable.ic_star else R.drawable.ic_star_outline)
        setActive(btnSave, saved)
    }

    /** Full-screen video or the mini window: no controls at all. */
    fun setSuppressed(value: Boolean) {
        if (suppressed == value) return
        suppressed = value
        removeCallbacks(hideRunnable)
        if (value) {
            dock.animate().cancel()
            btnPip.animate().cancel()
            dock.visibility = View.GONE
            btnPip.visibility = View.GONE
            toastView.visibility = View.GONE
        } else {
            controlsShown = true
            applyVisibility()
            scheduleHide()
        }
    }

    /** Call on every touch of the page: the controls come back. */
    fun onUserInteraction() {
        showControls()
    }

    /** A short message above the dock (replaces the system Toast). */
    fun toast(message: String) {
        if (suppressed) return
        toastView.text = message
        toastView.animate().cancel()
        removeCallbacks(hideToastRunnable)
        toastView.visibility = View.VISIBLE
        toastView.alpha = 0f
        toastView.translationY = Ui.dp(context, 8).toFloat()
        toastView.animate().alpha(1f).translationY(0f).setDuration(160).start()
        postDelayed(hideToastRunnable, 2000)
    }

    private fun hideToast() {
        toastView.animate().alpha(0f).setDuration(220).withEndAction {
            toastView.visibility = View.GONE
        }.start()
    }

    // ------------------------------------------------------------------ show / hide

    private fun showControls() {
        if (suppressed) return
        controlsShown = true
        applyVisibility()
        scheduleHide()
    }

    private fun scheduleHide() {
        removeCallbacks(hideRunnable)
        if (videoPage && playing && !suppressed) {
            postDelayed(hideRunnable, 3500)
        }
    }

    private fun autoHide() {
        if (videoPage && playing && !suppressed) {
            controlsShown = false
            applyVisibility()
        }
    }

    private fun applyVisibility() {
        val dockVisible = !suppressed && controlsShown
        animateTo(dock, dockVisible, Ui.dp(context, 16).toFloat())
        animateTo(btnPip, dockVisible && videoPage, -Ui.dp(context, 12).toFloat())
    }

    private fun animateTo(view: View, show: Boolean, offset: Float) {
        view.animate().cancel()
        if (show) {
            if (view.visibility != View.VISIBLE) {
                view.alpha = 0f
                view.translationY = offset
                view.visibility = View.VISIBLE
            }
            view.animate().alpha(1f).translationY(0f).setDuration(180).start()
        } else {
            if (view.visibility != View.VISIBLE) return
            view.animate().alpha(0f).translationY(offset).setDuration(160).withEndAction {
                view.visibility = View.GONE
            }.start()
        }
    }
}
