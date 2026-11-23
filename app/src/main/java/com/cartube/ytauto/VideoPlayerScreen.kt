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
import com.cartube.ytauto.queue.QueueController

class VideoPlayerScreen(carContext: CarContext) : SafeCarScreen(carContext), SurfaceCallback {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var surfaceAttached = false
    private var attachedSurface: android.view.Surface? = null
    private val playbackListener = { invalidate() }
    private val tick = object : Runnable {
        override fun run() {
            if (surfaceAttached) invalidate()
            mainHandler.postDelayed(this, 2_000L)
        }
    }

    init {
        carContext.getCarService(AppManager::class.java).setSurfaceCallback(this)
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onCreate(owner: LifecycleOwner) {
                PlaybackController.addPlaybackListener(playbackListener)
                mainHandler.post(tick)
            }

            override fun onDestroy(owner: LifecycleOwner) {
                mainHandler.removeCallbacks(tick)
                PlaybackController.removePlaybackListener(playbackListener)
                PlaybackController.releaseCarSurface()
            }
        })
    }

    override fun onSurfaceAvailable(surfaceContainer: SurfaceContainer) {
        val surface = surfaceContainer.surface ?: return
        if (attachedSurface === surface) return
        attachedSurface = surface
        CarTubeLog.i(
            "[car] surface available id=${CarTubeLog.surfaceIdentity(surface)} " +
                PlaybackEngine.playerHashLabel()
        )
        PlaybackController.attachCarSurface(surface)
        surfaceAttached = true
        invalidate()
    }

    override fun onSurfaceDestroyed(surfaceContainer: SurfaceContainer) {
        if (attachedSurface != null && surfaceContainer.surface !== attachedSurface) return
        surfaceAttached = false
        attachedSurface = null
        PlaybackController.releaseCarSurface()
    }

    override fun buildTemplate(): Template {
        val player = PlaybackController.player
        val playing = player?.isPlaying == true
        val canPrev = QueueController.hasPrevious()
        val canNext = QueueController.hasNext()

        return NavigationTemplate.Builder()
            .setActionStrip(
                ActionStrip.Builder()
                    .addAction(carSkipAction(R.drawable.ic_car_skip_previous, canPrev) {
                        CarTubePlaybackService.requestSkipPrevious(carContext)
                    })
                    .addAction(carSkipAction(if (playing) R.drawable.ic_pause else R.drawable.ic_play, enabled = true) {
                        player?.let { if (it.isPlaying) it.pause() else it.play() }
                        invalidate()
                    })
                    .addAction(carSkipAction(R.drawable.ic_car_skip_next, canNext) {
                        CarTubePlaybackService.requestSkipNext(carContext)
                    })
                    .build()
            )
            .build()
    }

    /** Car action-strip icons: pre-tinted white vectors, no runtime tint (AA clips badly). */
    private fun carSkipAction(iconRes: Int, enabled: Boolean, onClick: () -> Unit): Action {
        val icon = IconCompat.createWithResource(carContext, iconRes)
        return Action.Builder()
            .setIcon(CarIcon.Builder(icon).build())
            .setEnabled(enabled)
            .setOnClickListener { if (enabled) onClick() }
            .build()
    }
}