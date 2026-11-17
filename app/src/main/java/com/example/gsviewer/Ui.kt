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
