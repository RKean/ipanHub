package com.cartube.ytauto

import android.content.ComponentName
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Phone-side bridge to the single ExoPlayer in [CarTubePlaybackService].
 */
object PlaybackClient {

    const val TAG = "CarTube"

    private val mainHandler = Handler(Looper.getMainLooper())
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var mediaController: MediaController? = null
    private var connectAttempts = 0
    private val uiListeners = CopyOnWriteArrayList<() -> Unit>()

    fun connect(context: Context) {
        connectAttempts = 0
        attemptConnect(context.applicationContext)
    }

    fun ensureConnected(context: Context) {
        if (mediaController != null) return
        attemptConnect(context.applicationContext)
    }

    private fun attemptConnect(context: Context) {
        if (mediaController != null) return
        val appContext = context.applicationContext
        val token = SessionToken(appContext, ComponentName(appContext, CarTubePlaybackService::class.java))
        val future = MediaController.Builder(appContext, token).buildAsync()
        controllerFuture = future
        future.addListener(
            {
                try {
                    mediaController = future.get()
                    mediaController?.addListener(controllerListener)
                    connectAttempts = 0
                    Log.i(TAG, "[client] MediaController connected")
                    notifyUi()
                } catch (e: Exception) {
                    Log.w(TAG, "[client] MediaController failed (attempt=${connectAttempts + 1})", e)
                    controllerFuture = null
                    if (connectAttempts < 12) {
                        connectAttempts++
                        val delay = (300L * connectAttempts).coerceAtMost(3_000L)
                        mainHandler.postDelayed({ attemptConnect(appContext) }, delay)
                    }
                }
            },
            MoreExecutors.directExecutor()
        )
    }

    @Suppress("UNUSED_PARAMETER")
    fun disconnect(context: Context) {
        mainHandler.removeCallbacksAndMessages(null)
        mediaController?.removeListener(controllerListener)
        val future = controllerFuture
        mediaController = null
        controllerFuture = null
        connectAttempts = 0
        if (future != null) {
            MediaController.releaseFuture(future)
        }
    }

    fun addUiListener(listener: () -> Unit) {
        uiListeners.addIfAbsent(listener)
    }

    fun removeUiListener(listener: () -> Unit) {
        uiListeners.remove(listener)
    }

    fun play() {
        activePlayer()?.play()
    }

    fun pause() {
        activePlayer()?.pause()
    }

    fun seekTo(positionMs: Long) {
        activePlayer()?.seekTo(positionMs)
    }

    fun seekBy(deltaMs: Long) {
        val p = activePlayer() ?: return
        p.seekTo((p.currentPosition + deltaMs).coerceIn(0L, p.duration.coerceAtLeast(0L)))
    }

    fun isPlaying(): Boolean = activePlayer()?.isPlaying == true

    fun positionMs(): Long = activePlayer()?.currentPosition ?: 0L

    fun durationMs(): Long = activePlayer()?.duration?.coerceAtLeast(0L) ?: 0L

    fun activePlayer(): Player? = mediaController ?: PlaybackController.player

    private fun notifyUi() {
        uiListeners.forEach { it.invoke() }
    }

    private val controllerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) = notifyUi()
        override fun onPlaybackStateChanged(playbackState: Int) = notifyUi()
        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int
        ) = notifyUi()
    }
}