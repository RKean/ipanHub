package com.cartube.ytauto

/**
 * Short-lived cache of resolved streams (YouTube URLs expire).
 */
object StreamCache {

    private const val DEFAULT_TTL_MS = 2 * 60 * 60 * 1000L

    private data class Entry(
        val stream: YoutubeStreamResolver.ResolvedStream,
        val expiresAtMs: Long
    )

    private val map = LinkedHashMap<String, Entry>()

    fun get(videoId: String): YoutubeStreamResolver.ResolvedStream? = getInternal(videoId, logHit = true)

    fun peekSilent(videoId: String): YoutubeStreamResolver.ResolvedStream? =
        getInternal(videoId, logHit = false)

    private fun getInternal(videoId: String, logHit: Boolean): YoutubeStreamResolver.ResolvedStream? {
        val e = map[videoId] ?: return null
        if (System.currentTimeMillis() > e.expiresAtMs) {
            map.remove(videoId)
            return null
        }
        if (logHit) CarTubeLog.d("[cache] hit $videoId")
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

    /** Background resolve (e.g. on queue-add); shares in-flight work with playback. */
    fun warmResolve(videoId: String) {
        ResolveCoordinator.warmResolve(videoId)
    }

    fun peek(videoId: String): YoutubeStreamResolver.ResolvedStream? = peekSilent(videoId)
}