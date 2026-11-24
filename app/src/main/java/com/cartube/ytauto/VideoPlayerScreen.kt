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
import androidx.car.app.model.CarColor
import androidx.car.app.model.CarIcon
import androidx.car.app.model.CarText
import androidx.car.app.model.Distance
import androidx.car.app.model.Template
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.car.app.navigation.model.TravelEstimate
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.cartube.ytauto.queue.QueueController
import java.time.ZonedDateTime

/**
 * Sole car-app entry: [NavigationTemplate] + [SurfaceCallback]. Map strip = **icon-only** actions.
 */
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
  private var uiNavClaimed = false
  private val playbackListener = {
    if (CarNavigationClaim.isNavigationSessionActive) {
      CarNavigationClaim.refreshTripTitle(PlaybackController.title)
    }
    invalidate()
  }
  private val tick = object : Runnable {
    override fun run() {
      invalidate()
      mainHandler.postDelayed(this, 5_000L)
    }
  }

  init {
    carContext.getCarService(AppManager::class.java).setSurfaceCallback(this)
    lifecycle.addObserver(object : DefaultLifecycleObserver {
      override fun onCreate(owner: LifecycleOwner) {
        CarNavigationClaim.attachCarContext(carContext)
        PlaybackController.addPlaybackListener(playbackListener)
        mainHandler.post(tick)
        claimNavigationOnceForOpen()
        if (PlaybackController.hasActivePlayback) {
          CarNavigationClaim.onPlaybackActive(PlaybackController.title)
        }
      }

      override fun onResume(owner: LifecycleOwner) {
        CarTubeLog.i("[car] NavigationPlayer RESUMED — reattach surface")
        PlaybackController.reattachCarSurfaceIfNeeded()
        if (CarNavigationClaim.isNavigationSessionActive) {
          CarNavigationClaim.refreshTripTitle(PlaybackController.title)
        }
        PlaybackEngine.player()?.let { p ->
          if (PlaybackController.hasActivePlayback &&
            !p.isPlaying &&
            p.playbackState == androidx.media3.common.Player.STATE_READY &&
            PlaybackController.playbackErrorMessage == null
          ) {
            p.play()
          }
        }
        invalidate()
      }

      override fun onPause(owner: LifecycleOwner) {
        CarTubeLog.i("[car] NavigationPlayer PAUSED (playback continues)")
      }

      override fun onDestroy(owner: LifecycleOwner) {
        mainHandler.removeCallbacks(tick)
        PlaybackController.removePlaybackListener(playbackListener)
        CarNavigationClaim.detachCarContext()
        PlaybackController.releaseCarSurface()
      }
    })
  }

  private fun claimNavigationOnceForOpen() {
    if (uiNavClaimed) return
    uiNavClaimed = true
    if (!PlaybackController.hasActivePlayback && !CarNavigationClaim.isNavigationSessionActive) {
      CarNavigationClaim.claimWhileUiVisible("", "ui-open-once")
    }
  }

  override fun onSurfaceAvailable(surfaceContainer: SurfaceContainer) {
    lastContainer = surfaceContainer
    bindSurface(surfaceContainer, "onSurfaceAvailable")
  }

  override fun onSurfaceDestroyed(surfaceContainer: SurfaceContainer) {
    logSurfaceEvent("onSurfaceDestroyed", surfaceContainer, null, null)
    val destroyed = surfaceContainer.surface
    if (destroyed != null && attachedSurface != null && destroyed !== attachedSurface) {
      CarTubeLog.d(
        "[car] destroy ignored — different surface instance " +
          "destroy=${CarTubeLog.surfaceIdentity(destroyed)} " +
          "ours=${CarTubeLog.surfaceIdentity(attachedSurface)}"
      )
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
      CarTubeLog.w("[car] $reason — null Surface; release player binding")
      PlaybackController.releaseCarSurface()
      surfaceAttached = false
      attachedSurface = null
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
    if (PlaybackController.hasActivePlayback && PlaybackController.playbackErrorMessage == null) {
      PlaybackController.reattachCarSurfaceIfNeeded()
    }
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
    val builder = NavigationTemplate.Builder()
      .setActionStrip(buildMainActionStrip())
      .setDestinationTravelEstimate(buildStatusEstimate())
    buildMapActionStrip()?.let { builder.setMapActionStrip(it) }
    return builder.build()
  }

  /** Map action strip: icon-only (no custom titles). */
  private fun buildMapActionStrip(): ActionStrip? {
    return ActionStrip.Builder()
      .addAction(
        carIconAction(R.drawable.ic_car_take_map) {
          CarNavigationClaim.forceReclaim(PlaybackController.title)
          PlaybackController.reattachCarSurfaceIfNeeded()
          invalidate()
        }
      )
      .addAction(
        carIconAction(R.drawable.ic_car_help) {
          screenManager.push(CarSplitHelpScreen(carContext))
        }
      )
      .build()
  }

  private fun buildStatusEstimate(): TravelEstimate {
    val status = statusLine()
    val distance = Distance.create(0.0, Distance.UNIT_METERS)
    return TravelEstimate.Builder(distance, ZonedDateTime.now())
      .setTripText(CarText.create(status))
      .setRemainingTimeSeconds(0)
      .setRemainingTimeColor(CarColor.DEFAULT)
      .build()
  }

  private fun statusLine(): String {
    PlaybackController.playbackErrorMessage?.let { return it }
    if (PlaybackController.isLoading) {
      return carContext.getString(R.string.loading_subtitle)
    }
    if (PlaybackController.hasActivePlayback && PlaybackController.title.isNotBlank()) {
      return PlaybackController.title
    }
    val q = QueueController.allItems().size
    return if (q > 0) {
      carContext.getString(R.string.car_queue_count, q)
    } else {
      carContext.getString(R.string.car_pick_on_phone)
    }
  }

  private fun buildMainActionStrip(): ActionStrip {
    val player = PlaybackController.player
    val playing = player?.isPlaying == true
    val canPrev = QueueController.hasPrevious()
    val canNext = QueueController.hasNext()
    val error = PlaybackController.playbackErrorMessage != null
    val builder = ActionStrip.Builder()
      .addAction(carSkipAction(R.drawable.ic_car_skip_previous, canPrev && !error) {
        CarTubePlaybackService.requestSkipPrevious(carContext)
      })
    if (error) {
      builder.addAction(
        carSkipAction(R.drawable.ic_car_retry, enabled = true) {
          PlaybackController.clearPlaybackError()
          CarTubePlaybackService.requestPlayCurrent(carContext, forceRestart = true)
          invalidate()
        }
      )
    } else {
      builder.addAction(
        carSkipAction(if (playing) R.drawable.ic_pause else R.drawable.ic_play, enabled = player != null) {
          player?.let { if (it.isPlaying) it.pause() else it.play() }
          invalidate()
        }
      )
    }
    return builder
      .addAction(carSkipAction(R.drawable.ic_car_skip_next, canNext && !error) {
        CarTubePlaybackService.requestSkipNext(carContext)
      })
      .build()
  }

  private fun carIconAction(iconRes: Int, onClick: () -> Unit): Action {
    val icon = IconCompat.createWithResource(carContext, iconRes)
    return Action.Builder()
      .setIcon(CarIcon.Builder(icon).build())
      .setOnClickListener { onClick() }
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