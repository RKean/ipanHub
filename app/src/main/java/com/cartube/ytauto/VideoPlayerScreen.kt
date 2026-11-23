package com.cartube.ytauto

import android.os.Handler
import android.os.Looper
import androidx.car.app.AppManager
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.SurfaceCallback
import androidx.car.app.SurfaceContainer
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.CarIcon
import androidx.car.app.model.CarText
import androidx.car.app.model.Template
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Full-screen video on the head unit via [NavigationTemplate] + [SurfaceCallback].
 */
class VideoPlayerScreen(carContext: CarContext) : Screen(carContext), SurfaceCallback {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var overlayVisibleUntilMs = 0L
    private val playbackTick = object : Runnable {
        override fun run() {
            if (PlaybackController.player?.isPlaying == true) {
                invalidate()
            }
            mainHandler.postDelayed(this, 1_000L)
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

            override fun onDestroy(owner: LifecycleOwner) {
                mainHandler.removeCallbacks(playbackTick)
                PlaybackController.removePlaybackListener(playbackListener)
                PlaybackController.setCarSurface(null)
            }
        })
    }

    override fun onSurfaceAvailable(surfaceContainer: SurfaceContainer) {
        PlaybackController.setCarSurface(surfaceContainer.surface)
        bumpOverlay()
    }

    override fun onSurfaceDestroyed(surfaceContainer: SurfaceContainer) {
        PlaybackController.setCarSurface(null)
    }

    private fun bumpOverlay() {
        overlayVisibleUntilMs = System.currentTimeMillis() + 6_000L
        invalidate()
    }

    override fun onGetTemplate(): Template {
        val player = PlaybackController.player
        val playing = player?.isPlaying == true
        val title = PlaybackController.title.ifBlank { carContext.getString(R.string.app_name) }
        val position = player?.currentPosition ?: 0L
        val duration = player?.duration?.coerceAtLeast(0L) ?: 0L
        val showOverlay = System.currentTimeMillis() < overlayVisibleUntilMs

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
                player?.let { p -> if (p.isPlaying) p.pause() else p.play() }
                bumpOverlay()
            }
            .build()

        val seekBack = Action.Builder()
            .setIcon(CarIcon.Builder(IconCompat.createWithResource(carContext, R.drawable.ic_skip_back)).build())
            .setTitle("-10")
            .setOnClickListener {
                player?.seekTo((position - 10_000L).coerceAtLeast(0L))
                bumpOverlay()
            }
            .build()

        val seekForward = Action.Builder()
            .setIcon(CarIcon.Builder(IconCompat.createWithResource(carContext, R.drawable.ic_skip_forward)).build())
            .setTitle("+10")
            .setOnClickListener {
                val end = duration.coerceAtLeast(0L)
                player?.seekTo((position + 10_000L).coerceAtMost(end))
                bumpOverlay()
            }
            .build()

        val builder = NavigationTemplate.Builder()
            .setActionStrip(
                ActionStrip.Builder()
                    .addAction(seekBack)
                    .addAction(playPause)
                    .addAction(seekForward)
                    .build()
            )

        if (showOverlay) {
            val progress = if (duration > 0) {
                "${formatTime(position)} / ${formatTime(duration)}"
            } else {
                formatTime(position)
            }
            builder.setMapActionStrip(
                ActionStrip.Builder()
                    .addAction(
                        Action.Builder()
                            .setTitle(CarText.create(title.take(40)))
                            .build()
                    )
                    .addAction(
                        Action.Builder()
                            .setTitle(progress)
                            .setOnClickListener { bumpOverlay() }
                            .build()
                    )
                    .build()
            )
        }

        return builder.build()
    }

    private fun formatTime(ms: Long): String {
        if (ms <= 0) return "0:00"
        val totalSec = TimeUnit.MILLISECONDS.toSeconds(ms)
        val min = totalSec / 60
        val sec = totalSec % 60
        return String.format(Locale.US, "%d:%02d", min, sec)
    }
}