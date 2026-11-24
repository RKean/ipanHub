package com.cartube.ytauto

import android.os.Handler
import android.os.Looper
import androidx.car.app.CarContext
import androidx.car.app.model.CarColor
import androidx.car.app.model.CarText
import androidx.car.app.model.Distance
import androidx.car.app.navigation.NavigationManager
import androidx.car.app.navigation.NavigationManagerCallback
import androidx.car.app.navigation.model.Destination
import androidx.car.app.navigation.model.TravelEstimate
import androidx.car.app.navigation.model.Trip
import java.lang.ref.WeakReference
import java.time.ZonedDateTime

object CarNavigationClaim {

  private var contextRef = WeakReference<CarContext>(null)
  @Volatile private var sessionActive = false
  @Volatile private var uiOpenClaimed = false
  @Volatile var hostRevokedWhilePlaying: Boolean = false
    private set

  private val mainHandler = Handler(Looper.getMainLooper())
  private var lastTitle: String = ""
  private val tripPulse = object : Runnable {
    override fun run() {
      if (!sessionActive) return
      val ctx = contextRef.get() ?: return
      putActiveTrip(ctx.getCarService(NavigationManager::class.java), lastTitle, pulse = true)
      mainHandler.postDelayed(this, 8_000L)
    }
  }

  fun attachCarContext(carContext: CarContext) {
    contextRef = WeakReference(carContext)
    carContext.getCarService(NavigationManager::class.java).setNavigationManagerCallback(
      object : NavigationManagerCallback {
        override fun onStopNavigation() = onHostStopNavigation()
      }
    )
    CarTubeLog.i("[nav] callback registered")
  }

  fun detachCarContext() {
    mainHandler.removeCallbacks(tripPulse)
    endNavigationSession("detach")
    contextRef.get()?.getCarService(NavigationManager::class.java)?.clearNavigationManagerCallback()
    contextRef = WeakReference(null)
    uiOpenClaimed = false
  }

  /** First open of car UI only — not on every resume. */
  fun claimWhileUiVisible(title: String, reason: String) {
    if (reason == "ui-open-once" && uiOpenClaimed) return
    if (reason == "ui-open-once") uiOpenClaimed = true
    lastTitle = title
    claimNavigationSlot(title, reason)
  }

  fun onPlaybackActive(title: String) {
    lastTitle = title
    claimNavigationSlot(title, "playback")
  }

  fun onPlaybackInactive() {
    mainHandler.removeCallbacks(tripPulse)
    endNavigationSession("playback-ended")
  }

  fun forceReclaim(title: String) {
    hostRevokedWhilePlaying = false
    sessionActive = false
    claimNavigationSlot(title, "user-reclaim")
  }

  fun refreshTripTitle(title: String) {
    if (!sessionActive) return
    lastTitle = title
    val ctx = contextRef.get() ?: return
    putActiveTrip(ctx.getCarService(NavigationManager::class.java), title, pulse = false)
  }

  private fun claimNavigationSlot(title: String, reason: String) {
    val carContext = contextRef.get() ?: run {
      CarTubeLog.d("[nav] claim skipped ($reason) — no CarContext")
      return
    }
    val nm = carContext.getCarService(NavigationManager::class.java)
    if (!sessionActive) {
      nm.navigationStarted()
      sessionActive = true
      hostRevokedWhilePlaying = false
      CarTubeLog.i("[nav] navigationStarted ($reason)")
      mainHandler.removeCallbacks(tripPulse)
      mainHandler.postDelayed(tripPulse, 8_000L)
    }
    putActiveTrip(nm, title, pulse = false)
  }

  private fun putActiveTrip(nm: NavigationManager, title: String, pulse: Boolean) {
    if (!sessionActive) return
    val label = title.ifBlank { "ipanHub" }
    val dest = Destination.Builder().setName(label).setAddress("Now playing").build()
    val distance = Distance.create(0.0, Distance.UNIT_METERS)
    val estimate = TravelEstimate.Builder(distance, ZonedDateTime.now())
      .setTripText(CarText.create(label))
      .setRemainingTimeSeconds(0)
      .setRemainingTimeColor(CarColor.DEFAULT)
      .build()
    val trip = Trip.Builder()
      .addDestination(dest, estimate)
      .setCurrentRoad(label)
      .setLoading(false)
      .build()
    nm.updateTrip(trip)
    if (!pulse) {
      CarTubeLog.i("[nav] updateTrip title=\"$label\"")
    }
  }

  private fun endNavigationSession(reason: String) {
    if (!sessionActive) return
    mainHandler.removeCallbacks(tripPulse)
    val carContext = contextRef.get()
    try {
      carContext?.getCarService(NavigationManager::class.java)?.navigationEnded()
      CarTubeLog.i("[nav] navigationEnded ($reason)")
    } catch (e: Exception) {
      CarTubeLog.w("[nav] navigationEnded failed: ${e.message}")
    }
    sessionActive = false
  }

  private fun onHostStopNavigation() {
    if (!sessionActive) return
    CarTubeLog.w("[nav] host onStopNavigation — another nav app may be active")
    sessionActive = false
    mainHandler.removeCallbacks(tripPulse)
    hostRevokedWhilePlaying = PlaybackController.hasActivePlayback
  }

  val isNavigationSessionActive: Boolean
    get() = sessionActive
}