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
    private val ALLOWED_HOSTS = setOf("youtu.be", "youtube.com", "www.youtube.com", "m.youtube.com", "music.youtube.com")
    private val ID_PATTERN = Regex("[A-Za-z0-9_-]{11}")
    private val LIST_PATTERN = Regex("[A-Za-z0-9_-]{10,64}")
    private val INDEX_PATTERN = Regex("[0-9]{1,4}")
    private val TIME_PATTERN = Regex("[0-9]{1,7}|([0-9]{1,3}h)?([0-9]{1,3}m)?([0-9]{1,5}s)?")

    /** host, path, query of an http(s) address (host in lower case, without user name / port), or null. */
    private fun split(url: String): Triple<String, String, String>? {
        val text = url.trim()
        val schemeEnd = text.indexOf("://")
        if (schemeEnd < 0) return null
        val scheme = text.substring(0, schemeEnd).lowercase()
        if (scheme != "http" && scheme != "https") return null
        var rest = text.substring(schemeEnd + 3)
        val hash = rest.indexOf('#')
        if (hash >= 0) rest = rest.substring(0, hash)
        val q = rest.indexOf('?')
        val query = if (q >= 0) rest.substring(q + 1) else ""
        val beforeQuery = if (q >= 0) rest.substring(0, q) else rest
        val slash = beforeQuery.indexOf('/')
        val authority = if (slash >= 0) beforeQuery.substring(0, slash) else beforeQuery
        val path = if (slash >= 0) beforeQuery.substring(slash) else "/"
        val host = authority.substringAfterLast('@').substringBefore(':').lowercase()
        return Triple(host, path, query)
    }

    private fun queryParam(query: String, name: String): String? =
        query.split('&').firstOrNull { it.startsWith("$name=") }?.substring(name.length + 1)

    /** Is this an address of YouTube (the only addresses the app opens when another app asks for it)? */
    fun isYoutubeAddress(url: String?): Boolean {
        val parts = split(url ?: return false) ?: return false
        return parts.first in ALLOWED_HOSTS
    }

    /**
     * A YouTube link from another app (youtu.be/..., youtube.com/watch?v=..., /shorts/..., /live/..., /embed/...,
     * a playlist) as the address the app opens: the mobile watch page. Keeps the start time and a YouTube playlist.
     * Returns null for everything that is not a YouTube video / playlist link.
     */
    fun normalizeYoutubeLink(raw: String?): String? {
        val (host, path, query) = split(raw ?: return null) ?: return null
        if (host !in ALLOWED_HOSTS) return null

        var id: String? = null
        if (host == "youtu.be") {
            id = path.trim('/').substringBefore('/')
        } else if (path == "/watch" || path.startsWith("/watch/")) {
            id = queryParam(query, "v")
        } else {
            for (prefix in listOf("/shorts/", "/live/", "/embed/", "/v/")) {
                if (path.startsWith(prefix)) id = path.substring(prefix.length).substringBefore('/')
            }
        }
        val list = queryParam(query, "list")?.takeIf { LIST_PATTERN.matches(it) }

        if (id == null || !ID_PATTERN.matches(id)) {
            return if (path == "/playlist" && list != null) "https://m.youtube.com/playlist?list=" + list else null
        }
        val out = StringBuilder("https://m.youtube.com/watch?v=").append(id)
        if (list != null) {
            out.append("&list=").append(list)
            queryParam(query, "index")?.takeIf { INDEX_PATTERN.matches(it) }?.let { out.append("&index=").append(it) }
        }
        val time = (queryParam(query, "t") ?: queryParam(query, "start") ?: queryParam(query, "time_continue"))
            ?.takeIf { it.isNotEmpty() && TIME_PATTERN.matches(it) }
        if (time != null) out.append("&t=").append(if (time.all { it.isDigit() }) time + "s" else time)
        return out.toString()
    }

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
