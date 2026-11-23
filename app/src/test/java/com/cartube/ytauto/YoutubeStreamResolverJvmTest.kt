package com.cartube.ytauto

import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.schabi.newpipe.extractor.NewPipe

/**
 * Live network test against a well-known public video (Me at the zoo).
 * Run with: ./gradlew :app:testDebugUnitTest --tests com.cartube.ytauto.YoutubeStreamResolverJvmTest
 */
class YoutubeStreamResolverJvmTest {

    @Before
    fun initNewPipe() {
        NewPipe.init(NewPipeDownloader.getInstance())
    }

    @Test
    fun resolvesClassicPublicVideo() {
        val result = YoutubeStreamResolver.resolveVideoId("jNQXAC9IVRw", applyWebViewCookies = false)
        if (result is YoutubeStreamResolver.ResolveResult.Err &&
            (result.detail.contains("not a bot", ignoreCase = true) ||
                result.detail.contains("LOGIN_REQUIRED", ignoreCase = true))
        ) {
            assumeTrue("YouTube bot-check blocked CI IP; resolver still returns step=${result.step}", false)
        }
        assertTrue(
            "Expected stream for jNQXAC9IVRw, got $result",
            result is YoutubeStreamResolver.ResolveResult.Ok
        )
        val url = (result as YoutubeStreamResolver.ResolveResult.Ok).stream.primaryUrl
        assertTrue(url.startsWith("http"))
    }
}