package com.cartube.ytauto

import android.content.Context
import androidx.media3.session.MediaSession
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PlaybackEngineTest {

    @After
    fun tearDown() {
        PlaybackEngine.releaseAll()
        CarTubePlaybackService::class.java // reset listeners flag — cannot reset private static easily
    }

    @Test
    fun ensurePlayerReturnsSingleInstanceForProcess() {
        val ctx: Context = ApplicationProvider.getApplicationContext()
        PlaybackEngine.init(ctx)
        val first = PlaybackEngine.ensurePlayer()
        val second = PlaybackEngine.ensurePlayer()
        assertSame(first, second)
        assertSame(first, PlaybackEngine.player())
    }

    @Test
    fun ensureMediaSessionSingleton() {
        val ctx: Context = ApplicationProvider.getApplicationContext()
        PlaybackEngine.init(ctx)
        val callback = object : MediaSession.Callback {}
        val s1 = PlaybackEngine.ensureMediaSession(callback)
        val s2 = PlaybackEngine.ensureMediaSession(callback)
        assertSame(s1, s2)
    }
}