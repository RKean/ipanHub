package com.cartube.ytauto

import android.os.Handler
import android.os.Looper
import androidx.car.app.AppManager
import androidx.car.app.CarContext
import androidx.car.app.SurfaceCallback
import androidx.car.app.SurfaceContainer
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.CarIcon
import androidx.car.app.model.Template
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner

/**
 * Full-screen video on the head unit via [NavigationTemplate] + [SurfaceCallback].
 * Action strips use **icon-only** actions (Car App Library constraints).
 */
class VideoPlayerScreen(carContext: CarContext) : SafeCarScreen(carContext), SurfaceCallback {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val playbackTick = object : Runnable {
        override fun run() {
            if (PlaybackController.player?.isPlaying == true) {
                CarSurfaceOverlay.bump()
            }
            mainHandler.postDelayed(this, 1_000L)
        }
    }
    private val playbackListener = {
        invalidate()
        CarSurfaceOverlay.bump()
    }

    init {
        carContext.getCarService(AppManager::class.java).setSurfaceCallback(this)
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onCreate(owner: LifecycleOwner) {
                PlaybackController.addPlaybackListener(playbackListener)
                mainHandler.post(playbackTick)
            }

            override fun onDestroy(owner: LifecycleOwner) {
                mainHandler.removeCallbacks(playbackTick)
                PlaybackController.removePlaybackListener(playbackListener)
                PlaybackController.setCarSurface(null)
                CarSurfaceOverlay.attachSurface(null)
            }
        })
    }

    override fun onSurfaceAvailable(surfaceContainer: SurfaceContainer) {
        PlaybackController.setCarSurface(surfaceContainer.surface)
        CarSurfaceOverlay.attachSurface(surfaceContainer.surface)
        CarSurfaceOverlay.bump()
    }

    override fun onSurfaceDestroyed(surfaceContainer: SurfaceContainer) {
        PlaybackController.setCarSurface(null)
        CarSurfaceOverlay.attachSurface(null)
    }

    override fun buildTemplate(): Template {
        val player = PlaybackController.player
        val playing = player?.isPlaying == true

        val playPause = iconAction(
            if (playing) R.drawable.ic_pause else R.drawable.ic_play
        ) {
            player?.let { p -> if (p.isPlaying) p.pause() else p.play() }
            CarSurfaceOverlay.bump()
            invalidate()
        }

        val seekBack = iconAction(R.drawable.ic_skip_back) {
            val pos = player?.currentPosition ?: 0L
            player?.seekTo((pos - 10_000L).coerceAtLeast(0L))
            CarSurfaceOverlay.bump()
            invalidate()
        }

        val seekForward = iconAction(R.drawable.ic_skip_forward) {
            val pos = player?.currentPosition ?: 0L
            val end = player?.duration?.coerceAtLeast(0L) ?: 0L
            player?.seekTo((pos + 10_000L).coerceAtMost(end))
            CarSurfaceOverlay.bump()
            invalidate()
        }

        return NavigationTemplate.Builder()
            .setActionStrip(
                ActionStrip.Builder()
                    .addAction(seekBack)
                    .addAction(playPause)
                    .addAction(seekForward)
                    .build()
            )
            .build()
    }

    private fun iconAction(iconRes: Int, onClick: () -> Unit): Action {
        return Action.Builder()
            .setIcon(
                CarIcon.Builder(IconCompat.createWithResource(carContext, iconRes)).build()
            )
            .setOnClickListener(onClick)
            .build()
    }
}