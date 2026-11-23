package com.cartube.ytauto

import android.util.Log

/**
 * Short-lived cache of resolved streams (YouTube URLs expire).
 */
object StreamCache {

    private const val TAG = "CarTube"
    private const val DEFAULT_TTL_MS = 2 * 60 * 60 * 1000L

    private data class Entry(
        val stream: YoutubeStreamResolver.ResolvedStream,
        val expiresAtMs: Long
    )

    private val map = LinkedHashMap<String, Entry>()

    fun get(videoId: String): YoutubeStreamResolver.ResolvedStream? {
        val e = map[videoId] ?: return null
        if (System.currentTimeMillis() > e.expiresAtMs) {
            map.remove(videoId)
            return null
        }
        Log.i(TAG, "[cache] hit $videoId")
        return e.stream
    }

    fun put(videoId: String, stream: YoutubeStreamResolver.ResolvedStream, ttlMs: Long = DEFAULT_TTL_MS) {
        map[videoId] = Entry(stream, System.currentTimeMillis() + ttlMs)
        while (map.size > 24) {
            val oldest = map.keys.first()
            map.remove(oldest)
        }
    }

    fun invalidate(videoId: String) {
        map.remove(videoId)
    }

    /** Background resolve (e.g. on queue-add or search long-press). */
    fun warmResolve(videoId: String) {
        if (videoId.isBlank() || get(videoId) != null) return
        Thread {
            when (val r = YoutubeStreamResolver.resolveVideoId(videoId, applyWebViewCookies = true)) {
                is YoutubeStreamResolver.ResolveResult.Ok -> put(videoId, r.stream)
                else -> Log.d(TAG, "[cache] warm miss $videoId")
            }
        }.start()
    }

    fun peek(videoId: String): YoutubeStreamResolver.ResolvedStream? = get(videoId)
}