package com.example.gsviewer

import android.content.Context
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.View
import android.widget.FrameLayout

/** Small helpers shared by all screens. */
object Ui {
    fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density + 0.5f).toInt()

    /** A short, light vibration for button presses. */
    fun tick(view: View) {
        view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
    }
}

/** A frame whose height is always 9/16 of its width (video thumbnails). */
class RatioFrameLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    var ratio: Float = 9f / 16f

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = (width * ratio).toInt()
        super.onMeasure(
            MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY)
        )
    }
}

/** Small pure helpers for addresses. */
object UrlTools {
    /** The same video address, starting at [seconds] (an old t= / start= is replaced). */
    fun withStartTime(url: String, seconds: Long): String {
        val hash = url.indexOf('#')
        val base = if (hash >= 0) url.substring(0, hash) else url
        val q = base.indexOf('?')
        if (q < 0) return base + "?t=" + seconds + "s"
        val params = base.substring(q + 1).split('&')
            .filter { it.isNotEmpty() && !it.startsWith("t=") && !it.startsWith("start=") }
        return base.substring(0, q) + "?" + (params + ("t=" + seconds + "s")).joinToString("&")
    }
}

/** When a video cannot be opened / played: how long to wait before trying again and when to give up on it. */
object RecoveryPolicy {
    const val MAX_ATTEMPTS = 40

    /** 3 s, 6 s, 12 s, 24 s, 48 s, then every 60 s. */
    fun retryDelayMs(attempt: Int): Long {
        val step = (attempt - 1).coerceIn(0, 5)
        return minOf(60_000L, 3_000L shl step)
    }

    /**
     * In a playlist a video that still fails after 3 tries although the internet works is skipped.
     * Without internet nothing is skipped: the app simply waits for the connection to come back.
     */
    fun shouldSkip(attempt: Int, online: Boolean, inPlaylist: Boolean, hasNext: Boolean): Boolean =
        online && inPlaylist && hasNext && attempt >= 3
}
