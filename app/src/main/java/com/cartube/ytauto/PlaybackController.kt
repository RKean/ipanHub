package com.cartube.ytauto

import android.view.Surface
import androidx.media3.common.Player
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

    @Volatile
    var isLoading: Boolean = false
        private set

    var loadingTitle: String = ""
        private set

    private val surfaceLock = Any()
    private var carSurface: Surface? = null
    private var surfaceReadySignal: CompletableDeferred<Unit>? = null
    private var fanOutListener: Player.Listener? = null

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

    fun setLoading(title: String) {
        isLoading = true
        loadingTitle = title
        notifyListeners()
    }

    fun clearLoading() {
        isLoading = false
        loadingTitle = ""
        notifyListeners()
    }

    fun attachPlayer(
        exo: ExoPlayer,
        videoTitle: String,
        id: String,
        mode: PlaybackMode,
        onSurfaceReady: CompletableDeferred<Unit>? = null
    ) {
        val previous = player
        if (previous === exo) {
            playbackMode = mode
            title = videoTitle
            videoId = id
            surfaceReadySignal = onSurfaceReady
            synchronized(surfaceLock) {
                when (mode) {
                    PlaybackMode.PHONE_BACKGROUND -> exo.clearVideoSurface()
                    PlaybackMode.CAR -> {
                        val surface = carSurface
                        if (surface != null && surface.isValid) {
                            exo.setVideoSurface(surface)
                            surfaceReadySignal?.complete(Unit)
                            surfaceReadySignal = null
                        }
                    }
                }
            }
            notifyListeners()
            return
        }

        fanOutListener?.let { listener -> previous?.removeListener(listener) }
        if (previous != null) {
            synchronized(surfaceLock) {
                previous.clearVideoSurface()
            }
            previous.release()
        }
        playbackMode = mode
        player = exo
        title = videoTitle
        videoId = id
        surfaceReadySignal = onSurfaceReady
        fanOutListener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) = notifyListeners()
            override fun onPlaybackStateChanged(playbackState: Int) = notifyListeners()
            override fun onPositionDiscontinuity(
                oldPosition: Player.PositionInfo,
                newPosition: Player.PositionInfo,
                reason: Int
            ) = notifyListeners()
        }.also { exo.addListener(it) }

        synchronized(surfaceLock) {
            when (mode) {
                PlaybackMode.PHONE_BACKGROUND -> exo.clearVideoSurface()
                PlaybackMode.CAR -> {
                    val surface = carSurface
                    if (surface != null && surface.isValid) {
                        exo.setVideoSurface(surface)
                        surfaceReadySignal?.complete(Unit)
                        surfaceReadySignal = null
                    }
                }
            }
        }
        notifyListeners()
    }

    /** Host surface became available — ExoPlayer is the sole producer (no canvas overlay). */
    fun attachCarSurface(surface: Surface) {
        synchronized(surfaceLock) {
            if (carSurface === surface && surface.isValid) {
                if (playbackMode == PlaybackMode.CAR) {
                    player?.setVideoSurface(surface)
                    surfaceReadySignal?.complete(Unit)
                    surfaceReadySignal = null
                }
                return
            }
            player?.clearVideoSurface()
            carSurface = surface
            if (playbackMode != PlaybackMode.CAR) return
            if (surface.isValid) {
                player?.setVideoSurface(surface)
                surfaceReadySignal?.complete(Unit)
                surfaceReadySignal = null
            }
        }
    }

    /** Detach before the host releases the surface (onSurfaceDestroyed / screen teardown). */
    fun releaseCarSurface() {
        synchronized(surfaceLock) {
            player?.clearVideoSurface()
            carSurface = null
            surfaceReadySignal = null
        }
    }

    @Deprecated("Use attachCarSurface / releaseCarSurface", ReplaceWith("releaseCarSurface()"))
    fun setCarSurface(surface: Surface?) {
        if (surface == null) releaseCarSurface() else attachCarSurface(surface)
    }

    fun detachAndRelease() {
        synchronized(surfaceLock) {
            fanOutListener?.let { player?.removeListener(it) }
            fanOutListener = null
            player?.clearVideoSurface()
            player?.release()
            player = null
            carSurface = null
            title = ""
            videoId = ""
            isLoading = false
            loadingTitle = ""
            playbackMode = PlaybackMode.CAR
            surfaceReadySignal = null
        }
        notifyListeners()
    }

    val hasActivePlayback: Boolean
        get() = player != null

    val isPhoneBackgroundPlayback: Boolean
        get() = hasActivePlayback && playbackMode == PlaybackMode.PHONE_BACKGROUND
}