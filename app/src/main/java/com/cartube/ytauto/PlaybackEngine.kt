package com.cartube.ytauto

import android.content.Context
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.Player
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.session.MediaSession
import java.io.IOException

/**
 * Process-lifetime single [ExoPlayer] + [MediaSession]. Survives [CarTubePlaybackService] restarts.
 */
object PlaybackEngine {

    private val lock = Any()
    private var appContext: Context? = null
    private var exoPlayer: ExoPlayer? = null
    private var mediaSession: MediaSession? = null

    fun init(context: Context) {
        synchronized(lock) {
            if (appContext == null) {
                appContext = context.applicationContext
            }
        }
    }

    fun playerHashLabel(): String =
        synchronized(lock) {
            exoPlayer?.let { "p@${Integer.toHexString(System.identityHashCode(it))}" } ?: "p@none"
        }

    fun player(): ExoPlayer? = synchronized(lock) { exoPlayer }

    fun mediaSession(): MediaSession? = synchronized(lock) { mediaSession }

    fun ensurePlayer(): ExoPlayer {
        synchronized(lock) {
            val existing = exoPlayer
            if (existing != null) return existing
            val ctx = requireNotNull(appContext) { "PlaybackEngine.init() first" }
            val trackSelector = DefaultTrackSelector(ctx).apply {
                setParameters(
                    buildUponParameters()
                        .setMaxVideoSize(1280, 720)
                        .setMaxVideoBitrate(5_000_000)
                        .build()
                )
            }
            val loadControl = DefaultLoadControl.Builder()
                .setBufferDurationsMs(
                    /* minBufferMs = */ 15_000,
                    /* maxBufferMs = */ 50_000,
                    /* bufferForPlaybackMs = */ 1_500,
                    /* bufferForPlaybackAfterRebufferMs = */ 3_000,
                )
                .build()
            val exo = ExoPlayer.Builder(ctx)
                .setTrackSelector(trackSelector)
                .setLoadControl(loadControl)
                .setWakeMode(C.WAKE_MODE_NETWORK)
                .build()
            exo.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(),
                true
            )
            @OptIn(UnstableApi::class)
            run { exo.setVideoScalingMode(C.VIDEO_SCALING_MODE_SCALE_TO_FIT) }
            exoPlayer = exo
            CarTubeLog.i("[engine] ExoPlayer created ${playerHashLabel()}")
            return exo
        }
    }

    fun ensureMediaSession(callback: MediaSession.Callback): MediaSession {
        synchronized(lock) {
            val existing = mediaSession
            if (existing != null) return existing
            val ctx = requireNotNull(appContext) { "PlaybackEngine.init() first" }
            val player = ensurePlayer()
            val session = MediaSession.Builder(ctx, player)
                .setId(CarTubePlaybackService.SESSION_ID)
                .setCallback(callback)
                .build()
            mediaSession = session
            CarTubeLog.i("[engine] MediaSession ready ${playerHashLabel()}")
            return session
        }
    }

    fun addPlayerListenerOnce(listener: Player.Listener) {
        ensurePlayer().addListener(listener)
    }

    fun addAnalyticsListenerOnce(listener: AnalyticsListener) {
        ensurePlayer().addAnalyticsListener(listener)
    }

    /**
     * Only for instrumentation / process teardown — not used on normal service [android.app.Service.onDestroy].
     */
    fun releaseAll() {
        synchronized(lock) {
            mediaSession?.release()
            mediaSession = null
            exoPlayer?.release()
            exoPlayer = null
            CarTubeLog.i("[engine] released all players")
        }
    }
}