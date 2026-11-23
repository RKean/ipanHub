package com.cartube.ytauto

import java.net.URI

object YouTubeUrlParser {

    private val WATCH_HOSTS = setOf(
        "www.youtube.com",
        "m.youtube.com",
        "youtube.com",
        "youtu.be"
    )

    private val VIDEO_ID_PATTERN = Regex("^[a-zA-Z0-9_-]{8,20}$")

    fun extractVideoId(url: String): String? {
        if (url.isBlank()) return null
        val uri = runCatching { URI(url.trim()) }.getOrNull() ?: return null
        val host = uri.host?.lowercase() ?: return null

        if (host == "youtu.be") {
            val id = uri.path?.trimStart('/')?.substringBefore('/')?.trim()
            return id?.takeIf { VIDEO_ID_PATTERN.matches(it) }
        }
        if (host !in WATCH_HOSTS) return null

        val query = uri.rawQuery.orEmpty()
        query.split('&').forEach { part ->
            if (part.startsWith("v=")) {
                val id = part.removePrefix("v=").substringBefore("&").trim()
                if (VIDEO_ID_PATTERN.matches(id)) return id
            }
        }
        if (uri.path?.contains("/shorts/") == true) {
            val id = uri.path.trimEnd('/').substringAfterLast('/')
            if (VIDEO_ID_PATTERN.matches(id)) return id
        }
        return null
    }

    fun toWatchUrl(videoId: String): String = "https://www.youtube.com/watch?v=$videoId"
}