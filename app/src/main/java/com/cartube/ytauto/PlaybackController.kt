package com.cartube.ytauto

import android.view.Surface
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.CompletableDeferred
import java.util.concurrent.CopyOnWriteArrayList

object PlaybackController {

    enum class PlaybackMode {
        CAR,
        PHONE_BACKGROUND,
    }

    var playbackMode: PlaybackMode = PlaybackMode.CAR
        private set

    var player: ExoPlayer? = null
        private set
    var title: String = ""
        private set
    var videoId: String = ""
        private set

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

    fun attachPlayer(
        exo: ExoPlayer,
        videoTitle: String,
        id: String,
        mode: PlaybackMode,
        onSurfaceReady: CompletableDeferred<Unit>? = null
    ) {
        val previous = player
        if (previous != null && previous !== exo) {
            previous.clearVideoSurface()
            previous.release()
        }
        playbackMode = mode
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
        if (playbackMode == PlaybackMode.PHONE_BACKGROUND) return
        carSurface = surface
        if (surface != null) {
            player?.setVideoSurface(surface)
            surfaceReadySignal?.complete(Unit)
            surfaceReadySignal = null
        } else {
            player?.clearVideoSurface()
        }
    }

    fun detachAndRelease() {
        player?.clearVideoSurface()
        player?.release()
        player = null
        title = ""
        videoId = ""
        playbackMode = PlaybackMode.CAR
        surfaceReadySignal = null
        notifyListeners()
    }

    val hasActivePlayback: Boolean
        get() = player != null

    val isPhoneBackgroundPlayback: Boolean
        get() = hasActivePlayback && playbackMode == PlaybackMode.PHONE_BACKGROUND
}