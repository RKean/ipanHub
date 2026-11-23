package com.cartube.ytauto

import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class ResolveCoordinatorDedupeTest {

    @After
    fun tearDown() {
        ResolveCoordinator.resolveOverride = null
        ResolveCoordinator.resetInflightForTest()
    }

    @Test
    fun concurrentResolveSameVideoIdRunsOnce() = runBlocking {
        val starts = AtomicInteger(0)
        val ok = YoutubeStreamResolver.ResolveResult.Ok(
            YoutubeStreamResolver.ResolvedStream(
                kind = YoutubeStreamResolver.StreamKind.HLS,
                primaryUrl = "https://example.test/stream.m3u8",
                mimeType = null,
                via = "test",
            )
        )
        ResolveCoordinator.resolveOverride = {
            starts.incrementAndGet()
            delay(150)
            ok
        }
        val jobs = (1..6).map {
            async { ResolveCoordinator.resolve("dedupeA") }
        }
        jobs.forEach { it.await() }
        assertEquals(1, starts.get())
    }

    @Test
    fun rapidSwitchKeepsSeparateResolvesPerId() = runBlocking {
        val counts = mutableMapOf<String, AtomicInteger>()
        ResolveCoordinator.resolveOverride = { id ->
            counts.getOrPut(id) { AtomicInteger(0) }.incrementAndGet()
            delay(50)
            YoutubeStreamResolver.ResolveResult.Ok(
                YoutubeStreamResolver.ResolvedStream(
                    kind = YoutubeStreamResolver.StreamKind.HLS,
                    primaryUrl = "https://example.test/$id.m3u8",
                    mimeType = null,
                    via = "test",
                )
            )
        }
        ResolveCoordinator.resolve("vidA")
        ResolveCoordinator.resolve("vidB")
        ResolveCoordinator.resolve("vidC")
        assertEquals(1, counts["vidA"]?.get())
        assertEquals(1, counts["vidB"]?.get())
        assertEquals(1, counts["vidC"]?.get())
    }
}