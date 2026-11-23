package com.cartube.ytauto

import android.os.Handler
import android.os.Looper
import android.util.Log
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
 * Only ExoPlayer writes to the surface — no canvas/HWUI overlay (single producer).
 */
class VideoPlayerScreen(carContext: CarContext) : SafeCarScreen(carContext), SurfaceCallback {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var surfaceAttached = false
    private val playbackTick = object : Runnable {
        override fun run() {
            if (surfaceAttached && PlaybackController.player?.isPlaying == true) {
                invalidate()
            }
            mainHandler.postDelayed(this, 2_000L)
        }
    }
    private val playbackListener = { invalidate() }

    init {
        carContext.getCarService(AppManager::class.java).setSurfaceCallback(this)
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onCreate(owner: LifecycleOwner) {
                PlaybackController.addPlaybackListener(playbackListener)
                mainHandler.post(playbackTick)
            }

            override fun onStop(owner: LifecycleOwner) {
                PlaybackController.releaseCarSurface()
                surfaceAttached = false
            }

            override fun onDestroy(owner: LifecycleOwner) {
                mainHandler.removeCallbacks(playbackTick)
                PlaybackController.removePlaybackListener(playbackListener)
                PlaybackController.releaseCarSurface()
                surfaceAttached = false
            }
        })
    }

    override fun onSurfaceAvailable(surfaceContainer: SurfaceContainer) {
        val surface = surfaceContainer.surface ?: return
        Log.i(CarTubePlaybackService.TAG, "[car] surface available")
        PlaybackController.attachCarSurface(surface)
        surfaceAttached = true
        invalidate()
    }

    override fun onSurfaceDestroyed(surfaceContainer: SurfaceContainer) {
        Log.i(CarTubePlaybackService.TAG, "[car] surface destroyed — detaching player")
        surfaceAttached = false
        PlaybackController.releaseCarSurface()
    }

    override fun buildTemplate(): Template {
        val player = PlaybackController.player
        val playing = player?.isPlaying == true

        val playPause = iconAction(
            if (playing) R.drawable.ic_pause else R.drawable.ic_play
        ) {
            player?.let { p -> if (p.isPlaying) p.pause() else p.play() }
            invalidate()
        }

        val seekBack = iconAction(R.drawable.ic_skip_back) {
            val pos = player?.currentPosition ?: 0L
            player?.seekTo((pos - 10_000L).coerceAtLeast(0L))
            invalidate()
        }

        val seekForward = iconAction(R.drawable.ic_skip_forward) {
            val pos = player?.currentPosition ?: 0L
            val end = player?.duration?.coerceAtLeast(0L) ?: 0L
            player?.seekTo((pos + 10_000L).coerceAtMost(end))
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