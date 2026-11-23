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

/**
 * Claims Android Auto navigation focus so Coolwalk grants the map [android.view.Surface].
 *
 * The car **host** picks a single active navigation app. Waze with an active route can take
 * focus back; the host then calls [onStopNavigation] and we must comply (see NF-5).
 */
object CarNavigationClaim {

  private var contextRef = WeakReference<CarContext>(null)
  @Volatile private var sessionActive = false
  @Volatile var hostRevokedWhilePlaying: Boolean = false
    private set

  private val mainHandler = Handler(Looper.getMainLooper())
  private var lastTitle: String = ""
  private val tripPulse = object : Runnable {
    override fun run() {
      if (!sessionActive) return
      val ctx = contextRef.get() ?: return
      val nm = ctx.getCarService(NavigationManager::class.java)
      putActiveTrip(nm, lastTitle, pulse = true)
      mainHandler.postDelayed(this, 4_000L)
    }
  }

  fun attachCarContext(carContext: CarContext) {
    contextRef = WeakReference(carContext)
    val nm = carContext.getCarService(NavigationManager::class.java)
    nm.setNavigationManagerCallback(
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
  }

  /** User opened the car nav UI or resumed ipanHub — try to own the map slot. */
  fun claimWhileUiVisible(title: String, reason: String) {
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

  /** User tapped "Take map" after another nav app stole the card. */
  fun forceReclaim(title: String) {
    hostRevokedWhilePlaying = false
    sessionActive = false
    claimNavigationSlot(title, "user-reclaim")
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
      mainHandler.postDelayed(tripPulse, 4_000L)
    }
    putActiveTrip(nm, title, pulse = false)
  }

  private fun putActiveTrip(nm: NavigationManager, title: String, pulse: Boolean) {
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
    CarTubeLog.w("[nav] host onStopNavigation — another nav app (e.g. Waze) took focus")
    sessionActive = false
    mainHandler.removeCallbacks(tripPulse)
    hostRevokedWhilePlaying = PlaybackController.hasActivePlayback
  }

  val isNavigationSessionActive: Boolean
    get() = sessionActive
}