package com.cartube.ytauto

import androidx.car.app.AppManager
import androidx.car.app.CarContext
import androidx.car.app.Screen
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
 * NavigationTemplate is the Car App Library template that exposes a drawable surface.
 */
class VideoPlayerScreen(carContext: CarContext) : Screen(carContext), SurfaceCallback {

    init {
        carContext.getCarService(AppManager::class.java).setSurfaceCallback(this)
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onDestroy(owner: LifecycleOwner) {
                PlaybackController.setCarSurface(null)
            }
        })
    }

    override fun onSurfaceAvailable(surfaceContainer: SurfaceContainer) {
        PlaybackController.setCarSurface(surfaceContainer.surface)
    }

    override fun onSurfaceDestroyed(surfaceContainer: SurfaceContainer) {
        PlaybackController.setCarSurface(null)
    }

    override fun onGetTemplate(): Template {
        val playing = PlaybackController.player?.isPlaying == true
        val title = PlaybackController.title.ifBlank { carContext.getString(R.string.app_name) }

        val playPause = Action.Builder()
            .setIcon(
                CarIcon.Builder(
                    IconCompat.createWithResource(
                        carContext,
                        if (playing) R.drawable.ic_pause else R.drawable.ic_play
                    )
                ).build()
            )
            .setTitle(if (playing) "Pause" else "Play")
            .setOnClickListener {
                PlaybackController.player?.let { p ->
                    if (p.isPlaying) p.pause() else p.play()
                }
                invalidate()
            }
            .build()

        val seekBack = Action.Builder()
            .setIcon(CarIcon.Builder(IconCompat.createWithResource(carContext, R.drawable.ic_skip_back)).build())
            .setTitle("-10s")
            .setOnClickListener {
                PlaybackController.player?.seekTo(
                    (PlaybackController.player?.currentPosition ?: 0L) - 10_000L
                )
            }
            .build()

        val seekForward = Action.Builder()
            .setIcon(CarIcon.Builder(IconCompat.createWithResource(carContext, R.drawable.ic_skip_forward)).build())
            .setTitle("+10s")
            .setOnClickListener {
                val p = PlaybackController.player ?: return@setOnClickListener
                val end = p.duration.coerceAtLeast(0L)
                p.seekTo((p.currentPosition + 10_000L).coerceAtMost(end))
            }
            .build()

        return NavigationTemplate.Builder()
            .setActionStrip(
                ActionStrip.Builder()
                    .addAction(
                        Action.Builder()
                            .setTitle(title.take(24))
                            .build()
                    )
                    .addAction(seekBack)
                    .addAction(playPause)
                    .addAction(seekForward)
                    .build()
            )
            .build()
    }
}