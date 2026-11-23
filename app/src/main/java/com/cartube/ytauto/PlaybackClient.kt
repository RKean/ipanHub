package com.cartube.ytauto

import android.content.ComponentName
import android.content.Context
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

    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var mediaController: MediaController? = null
    private val uiListeners = CopyOnWriteArrayList<() -> Unit>()

    fun connect(context: Context) {
        if (mediaController != null) return
        val token = SessionToken(context, ComponentName(context, CarTubePlaybackService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()
        controllerFuture = future
        future.addListener(
            {
                try {
                    mediaController = future.get()
                    mediaController?.addListener(controllerListener)
                    Log.i(TAG, "[client] MediaController connected")
                    notifyUi()
                } catch (e: Exception) {
                    Log.e(TAG, "[client] MediaController failed", e)
                }
            },
            MoreExecutors.directExecutor()
        )
    }

    @Suppress("UNUSED_PARAMETER")
    fun disconnect(context: Context) {
        mediaController?.removeListener(controllerListener)
        val future = controllerFuture
        mediaController = null
        controllerFuture = null
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