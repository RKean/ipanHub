package com.cartube.ytauto

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * One in-flight resolve per [videoId]; play, prefetch, and warmResolve share the same work.
 */
object ResolveCoordinator {

    @Volatile
  var resolveOverride: (suspend (String) -> YoutubeStreamResolver.ResolveResult)? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val inflight = mutableMapOf<String, Deferred<YoutubeStreamResolver.ResolveResult>>()

    suspend fun resolve(
        videoId: String,
        applyWebViewCookies: Boolean = true,
        preference: YoutubeStreamResolver.ResolvePreference = YoutubeStreamResolver.ResolvePreference.FAST_START,
        skipKinds: Set<YoutubeStreamResolver.StreamKind> = emptySet(),
    ): YoutubeStreamResolver.ResolveResult {
        val (deferred, joined) = mutex.withLock {
            val existing = inflight[videoId]?.takeIf { it.isActive }
            if (existing != null) {
                return@withLock existing to true
            }
            val created = scope.async {
                try {
                    CarTubeLog.i("[resolve] start $videoId pref=$preference skip=$skipKinds")
                    resolveOverride?.invoke(videoId)
                        ?: YoutubeStreamResolver.resolveVideoId(
                            videoId,
                            applyWebViewCookies,
                            preference,
                            skipKinds
                        )
                } finally {
                    mutex.withLock { inflight.remove(videoId) }
                }
            }
            inflight[videoId] = created
            created to false
        }
        if (joined) CarTubeLog.d("[resolve] join in-flight $videoId")
        return deferred.await()
    }

    fun warmResolve(
        videoId: String,
        preference: YoutubeStreamResolver.ResolvePreference = YoutubeStreamResolver.ResolvePreference.FAST_START,
    ) {
        if (videoId.isBlank()) return
        if (StreamCache.peekSilent(videoId) != null) return
        scope.async {
            when (val r = resolve(videoId, applyWebViewCookies = true, preference = preference)) {
                is YoutubeStreamResolver.ResolveResult.Ok -> StreamCache.put(videoId, r.stream)
                else -> CarTubeLog.d("[resolve] warm miss $videoId")
            }
        }
    }

    internal fun resetInflightForTest() {
        inflight.clear()
    }
}