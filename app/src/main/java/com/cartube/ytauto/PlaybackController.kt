package com.cartube.ytauto

import android.view.Surface
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.CompletableDeferred

object PlaybackController {
    var player: ExoPlayer? = null
    var title: String = ""
    var videoId: String = ""

    private var carSurface: Surface? = null
    private var surfaceReadySignal: CompletableDeferred<Unit>? = null

    var playbackListener: (() -> Unit)? = null

    fun setPlayer(exo: ExoPlayer, videoTitle: String, id: String, onSurfaceReady: CompletableDeferred<Unit>? = null) {
        releasePlayerOnly()
        player = exo
        title = videoTitle
        videoId = id
        surfaceReadySignal = onSurfaceReady
        carSurface?.let {
            exo.setVideoSurface(it)
            surfaceReadySignal?.complete(Unit)
            surfaceReadySignal = null
        }
        playbackListener?.invoke()
    }

    fun setCarSurface(surface: Surface?) {
        carSurface = surface
        if (surface != null) {
            player?.setVideoSurface(surface)
            surfaceReadySignal?.complete(Unit)
            surfaceReadySignal = null
        } else {
            player?.clearVideoSurface()
        }
    }

    fun releasePlayerOnly() {
        player?.clearVideoSurface()
        player?.release()
        player = null
        title = ""
        videoId = ""
        surfaceReadySignal = null
        playbackListener?.invoke()
    }

    fun releaseAll() {
        releasePlayerOnly()
    }

    val hasActivePlayback: Boolean
        get() = player != null
}