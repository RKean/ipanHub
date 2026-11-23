package com.cartube.ytauto

import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import androidx.car.app.AppManager
import androidx.car.app.CarContext
import androidx.car.app.SurfaceCallback
import androidx.car.app.SurfaceContainer
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.CarIcon
import androidx.car.app.model.MessageTemplate
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
    private var lastWidth = -1
    private var lastHeight = -1
    private var lastDpi = -1
    private var lastContainer: SurfaceContainer? = null
    private var lastVisibleArea: Rect? = null
    private var lastStableArea: Rect? = null
    private val playbackListener = { invalidate() }
    private val tick = object : Runnable {
        override fun run() {
            if (PlaybackController.hasActivePlayback) invalidate()
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

            override fun onResume(owner: LifecycleOwner) {
                CarTubeLog.i("[car] VideoPlayerScreen RESUMED — reattach surface")
                PlaybackController.reattachCarSurfaceIfNeeded()
                PlaybackEngine.player()?.let { p ->
                    if (!p.isPlaying && p.playbackState == androidx.media3.common.Player.STATE_READY) {
                        p.play()
                    }
                }
                invalidate()
            }

            override fun onPause(owner: LifecycleOwner) {
                CarTubeLog.i("[car] VideoPlayerScreen PAUSED (audio continues)")
            }

            override fun onDestroy(owner: LifecycleOwner) {
                mainHandler.removeCallbacks(tick)
                PlaybackController.removePlaybackListener(playbackListener)
                PlaybackController.releaseCarSurface()
            }
        })
    }

    override fun onSurfaceAvailable(surfaceContainer: SurfaceContainer) {
        lastContainer = surfaceContainer
        bindSurface(surfaceContainer, "onSurfaceAvailable")
    }

    override fun onSurfaceDestroyed(surfaceContainer: SurfaceContainer) {
        logSurfaceEvent("onSurfaceDestroyed", surfaceContainer, null, null)
        if (attachedSurface != null && surfaceContainer.surface !== attachedSurface) {
            CarTubeLog.d("[car] destroy ignored — different surface instance")
            return
        }
        lastContainer = null
        surfaceAttached = false
        attachedSurface = null
        lastWidth = -1
        lastHeight = -1
        lastDpi = -1
        PlaybackController.releaseCarSurface()
        invalidate()
    }

    override fun onVisibleAreaChanged(visibleArea: Rect) {
        lastVisibleArea = Rect(visibleArea)
        val container = lastContainer ?: return
        logSurfaceEvent("onVisibleAreaChanged", container, visibleArea, null)
        bindSurface(container, "onVisibleAreaChanged")
    }

    override fun onStableAreaChanged(stableArea: Rect) {
        lastStableArea = Rect(stableArea)
        val container = lastContainer ?: return
        logSurfaceEvent("onStableAreaChanged", container, null, stableArea)
        bindSurface(container, "onStableAreaChanged")
    }

    private fun bindSurface(surfaceContainer: SurfaceContainer, reason: String) {
        val surface = surfaceContainer.surface ?: run {
            CarTubeLog.w("[car] $reason — null Surface")
            return
        }
        val w = surfaceContainer.width
        val h = surfaceContainer.height
        val dpi = surfaceContainer.dpi
        val sizeChanged = w != lastWidth || h != lastHeight || dpi != lastDpi
        val surfaceChanged = attachedSurface !== surface
        if (surfaceAttached && !surfaceChanged && !sizeChanged) {
            CarTubeLog.d("[car] $reason — no change ${w}x${h}")
            return
        }
        logSurfaceEvent(reason, surfaceContainer, lastVisibleArea, lastStableArea)
        attachedSurface = surface
        lastWidth = w
        lastHeight = h
        lastDpi = dpi
        surfaceAttached = true
        PlaybackController.attachCarSurface(surface, w, h, dpi)
        PlaybackController.reattachCarSurfaceIfNeeded()
        invalidate()
    }

    private fun logSurfaceEvent(
        event: String,
        container: SurfaceContainer,
        visible: Rect?,
        stable: Rect?
    ) {
        val s = container.surface
        CarTubeLog.i(
            "[car] $event id=${CarTubeLog.surfaceIdentity(s)} " +
                "container=${container.width}x${container.height} dpi=${container.dpi} " +
                "visible=${visible ?: lastVisibleArea} stable=${stable ?: lastStableArea} " +
                PlaybackEngine.playerHashLabel()
        )
    }

    override fun buildTemplate(): Template {
        val actionStrip = buildActionStrip()

        if (PlaybackController.hasActivePlayback && !PlaybackController.hasCarSurface) {
            val body = buildString {
                append(carContext.getString(R.string.car_surface_split_hint))
                append("\n\n")
                append(carContext.getString(R.string.parked_only_hint))
            }
            return MessageTemplate.Builder(body)
                .setTitle(carContext.getString(R.string.car_surface_waiting_title))
                .setActionStrip(actionStrip)
                .build()
        }

        return NavigationTemplate.Builder()
            .setActionStrip(actionStrip)
            .build()
    }

    private fun buildActionStrip(): ActionStrip {
        val player = PlaybackController.player
        val playing = player?.isPlaying == true
        val canPrev = QueueController.hasPrevious()
        val canNext = QueueController.hasNext()
        return ActionStrip.Builder()
            .addAction(carSkipAction(R.drawable.ic_car_skip_previous, canPrev) {
                CarTubePlaybackService.requestSkipPrevious(carContext)
            })
            .addAction(
                carSkipAction(if (playing) R.drawable.ic_pause else R.drawable.ic_play, enabled = true) {
                    player?.let { if (it.isPlaying) it.pause() else it.play() }
                    invalidate()
                }
            )
            .addAction(carSkipAction(R.drawable.ic_car_skip_next, canNext) {
                CarTubePlaybackService.requestSkipNext(carContext)
            })
            .build()
    }

    private fun carSkipAction(iconRes: Int, enabled: Boolean, onClick: () -> Unit): Action {
        val icon = IconCompat.createWithResource(carContext, iconRes)
        return Action.Builder()
            .setIcon(CarIcon.Builder(icon).build())
            .setEnabled(enabled)
            .setOnClickListener { if (enabled) onClick() }
            .build()
    }
}