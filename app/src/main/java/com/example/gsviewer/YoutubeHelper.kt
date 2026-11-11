package com.example.gsviewer

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

object YoutubeHelper {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val videoIdRegex = Regex("(?:v=|youtu\\.be/|shorts/|embed/)([A-Za-z0-9_-]{11})")

    fun extractVideoId(url: String?): String? {
        if (url == null) return null
        return videoIdRegex.find(url)?.groupValues?.get(1)
    }

    fun fetchOEmbed(videoId: String): SavedVideo {
        val watchUrl = "https://www.youtube.com/watch?v=$videoId"
        val fallbackThumb = "https://img.youtube.com/vi/$videoId/hqdefault.jpg"
        return try {
            val oembedUrl = "https://www.youtube.com/oembed?url=" +
                URLEncoder.encode(watchUrl, "UTF-8") + "&format=json"
            val request = Request.Builder().url(oembedUrl).build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return SavedVideo(videoId, watchUrl, "Տեսանյութ $videoId", fallbackThumb)
                }
                val json = JSONObject(response.body?.string() ?: "{}")
                SavedVideo(
                    videoId = videoId,
                    url = watchUrl,
                    title = json.optString("title", "Տեսանյութ $videoId"),
                    thumbnailUrl = json.optString("thumbnail_url", fallbackThumb)
                )
            }
        } catch (e: Exception) {
            SavedVideo(videoId, watchUrl, "Տեսանյութ $videoId", fallbackThumb)
        }
    }
}
