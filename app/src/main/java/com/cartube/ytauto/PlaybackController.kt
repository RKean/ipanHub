package com.cartube.ytauto

import android.view.Surface
import androidx.media3.common.C
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
    private var carSurfaceWidth: Int = 0
    private var carSurfaceHeight: Int = 0
    private var carSurfaceDpi: Int = 0
    /** Surface currently passed to ExoPlayer — avoid redundant [ExoPlayer.setVideoSurface]. */
    private var surfaceBoundToPlayer: Surface? = null
    private var surfaceBoundWidth: Int = -1
    private var surfaceBoundHeight: Int = -1
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

    val hasCarSurface: Boolean
        get() = synchronized(surfaceLock) { carSurface?.isValid == true }

    /**
     * Wire UI/car shell to the process player. Does **not** own or release ExoPlayer ([PlaybackEngine] does).
     */
    fun attachPlayer(
        exo: ExoPlayer,
        videoTitle: String,
        id: String,
        mode: PlaybackMode,
        onSurfaceReady: CompletableDeferred<Unit>? = null
    ) {
        val previous = player
        if (previous !== exo && previous != null) {
            fanOutListener?.let { previous.removeListener(it) }
        }
        playbackMode = mode
        player = exo
        title = videoTitle
        videoId = id
        surfaceReadySignal = onSurfaceReady
        exo.setVideoScalingMode(C.VIDEO_SCALING_MODE_SCALE_TO_FIT)

        if (fanOutListener == null) {
            fanOutListener = object : Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) = notifyListeners()
                override fun onPlaybackStateChanged(playbackState: Int) = notifyListeners()
                override fun onPositionDiscontinuity(
                    oldPosition: Player.PositionInfo,
                    newPosition: Player.PositionInfo,
                    reason: Int
                ) = notifyListeners()
            }.also { exo.addListener(it) }
        } else if (previous !== exo) {
            fanOutListener?.let { listener ->
                previous?.removeListener(listener)
                exo.addListener(listener)
            }
        }

        synchronized(surfaceLock) {
            when (mode) {
                PlaybackMode.PHONE_BACKGROUND -> {
                    if (surfaceBoundToPlayer != null) {
                        CarTubeLog.i("[surface] clear video (phone bg) ${CarTubeLog.surfaceIdentity(surfaceBoundToPlayer)}")
                        exo.clearVideoSurface()
                        surfaceBoundToPlayer = null
                        surfaceBoundWidth = -1
                        surfaceBoundHeight = -1
                    }
                }
                PlaybackMode.CAR -> applyCarSurfaceIfNeeded(exo, force = false)
            }
        }
        notifyListeners()
    }

    private fun applyCarSurfaceIfNeeded(exo: ExoPlayer, force: Boolean) {
        val surface = carSurface ?: return
        if (!surface.isValid) return
        val sameSurface = surfaceBoundToPlayer === surface
        val sameSize = surfaceBoundWidth == carSurfaceWidth && surfaceBoundHeight == carSurfaceHeight
        if (!force && sameSurface && sameSize) {
            surfaceReadySignal?.complete(Unit)
            surfaceReadySignal = null
            return
        }
        CarTubeLog.i(
            "[surface] attach id=${CarTubeLog.surfaceIdentity(surface)} " +
                "was=${CarTubeLog.surfaceIdentity(surfaceBoundToPlayer)} " +
                "size=${carSurfaceWidth}x${carSurfaceHeight} dpi=$carSurfaceDpi force=$force"
        )
        exo.setVideoScalingMode(C.VIDEO_SCALING_MODE_SCALE_TO_FIT)
        exo.setVideoSurface(surface)
        surfaceBoundToPlayer = surface
        surfaceBoundWidth = carSurfaceWidth
        surfaceBoundHeight = carSurfaceHeight
        surfaceReadySignal?.complete(Unit)
        surfaceReadySignal = null
    }

    fun attachCarSurface(surface: Surface, width: Int, height: Int, dpi: Int) {
        synchronized(surfaceLock) {
            carSurface = surface
            carSurfaceWidth = width
            carSurfaceHeight = height
            carSurfaceDpi = dpi
            if (playbackMode != PlaybackMode.CAR) return
            player?.let { applyCarSurfaceIfNeeded(it, force = false) }
        }
    }

    /** After resize / visible-area change / resume — bind video output again (audio keeps playing). */
    fun reattachCarSurfaceIfNeeded() {
        synchronized(surfaceLock) {
            if (playbackMode != PlaybackMode.CAR) return
            player?.let { applyCarSurfaceIfNeeded(it, force = true) }
        }
    }

    fun releaseCarSurface() {
        synchronized(surfaceLock) {
            if (surfaceBoundToPlayer != null) {
                player?.clearVideoSurface()
                CarTubeLog.i(
                    "[surface] release ${CarTubeLog.surfaceIdentity(surfaceBoundToPlayer)} " +
                        "was=${surfaceBoundWidth}x${surfaceBoundHeight}"
                )
                surfaceBoundToPlayer = null
                surfaceBoundWidth = -1
                surfaceBoundHeight = -1
            }
            carSurface = null
            carSurfaceWidth = 0
            carSurfaceHeight = 0
            carSurfaceDpi = 0
            surfaceReadySignal = null
        }
    }

    @Deprecated("Use attachCarSurface / releaseCarSurface", ReplaceWith("releaseCarSurface()"))
    fun setCarSurface(surface: Surface?) {
        if (surface == null) releaseCarSurface() else attachCarSurface(surface, 0, 0, 0)
    }

    /** UI detach only — never release the process [ExoPlayer]. */
    fun detachUiOnly() {
        synchronized(surfaceLock) {
            fanOutListener?.let { player?.removeListener(it) }
            fanOutListener = null
            if (surfaceBoundToPlayer != null) {
                player?.clearVideoSurface()
                surfaceBoundToPlayer = null
                surfaceBoundWidth = -1
                surfaceBoundHeight = -1
            }
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

    @Deprecated("Use detachUiOnly", ReplaceWith("detachUiOnly()"))
    fun detachAndRelease() = detachUiOnly()

    val hasActivePlayback: Boolean
        get() = player != null

    val isPhoneBackgroundPlayback: Boolean
        get() = hasActivePlayback && playbackMode == PlaybackMode.PHONE_BACKGROUND
}