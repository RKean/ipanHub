package com.cartube.ytauto

import android.view.Surface
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.CompletableDeferred
import java.util.concurrent.CopyOnWriteArrayList

object PlaybackController {
    var player: ExoPlayer? = null
    var title: String = ""
    var videoId: String = ""

    private var carSurface: Surface? = null
    private var surfaceReadySignal: CompletableDeferred<Unit>? = null

    private val playbackListeners = CopyOnWriteArrayList<() -> Unit>()

    var playbackListener: (() -> Unit)?
        get() = null
        set(listener) {
            if (listener == null) {
                playbackListeners.clear()
            } else {
                playbackListeners.clear()
                playbackListeners.add(listener)
            }
        }

    fun addPlaybackListener(listener: () -> Unit) {
        playbackListeners.addIfAbsent(listener)
    }

    fun removePlaybackListener(listener: () -> Unit) {
        playbackListeners.remove(listener)
    }

    private fun notifyListeners() {
        playbackListeners.forEach { it.invoke() }
    }

    fun setPlayer(
        exo: ExoPlayer,
        videoTitle: String,
        id: String,
        onSurfaceReady: CompletableDeferred<Unit>? = null
    ) {
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
        notifyListeners()
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
        notifyListeners()
    }

    fun releaseAll() {
        releasePlayerOnly()
    }

    val hasActivePlayback: Boolean
        get() = player != null
}