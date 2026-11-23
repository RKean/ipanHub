package com.cartube.ytauto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class YouTubeUrlParserTest {

    @Test
    fun extractVideoId_fromMobileWatchUrl() {
        assertEquals(
            "dQw4w9WgXcQ",
            YouTubeUrlParser.extractVideoId("https://m.youtube.com/watch?v=dQw4w9WgXcQ&feature=share")
        )
    }

    @Test
    fun extractVideoId_fromShortLink() {
        assertEquals("dQw4w9WgXcQ", YouTubeUrlParser.extractVideoId("https://youtu.be/dQw4w9WgXcQ"))
    }

    @Test
    fun extractVideoId_ignoresSearch() {
        assertNull(YouTubeUrlParser.extractVideoId("https://m.youtube.com/results?search_query=test"))
    }
}