package com.cartube.ytauto

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Tracks Android Auto: head-unit projection and live Car App [androidx.car.app.Session].
 */
object CarConnection {

    private val activeSessions = AtomicInteger(0)
    private val projectionActive = AtomicBoolean(false)

    /** CarTube Session is on the head unit (Waiting or Video screen). */
    val isCarSessionActive: Boolean
        get() = activeSessions.get() > 0

    /**
     * Route WebView play events to the car ExoPlayer when projection or CarTube session is up.
     */
    val shouldAutoRouteToCar: Boolean
        get() = projectionActive.get() || isCarSessionActive

    fun setProjectionActive(active: Boolean) {
        projectionActive.set(active)
        android.util.Log.i(
            CarTubePlaybackService.TAG,
            "[car] projection active=$active session=${activeSessions.get()}"
        )
    }

    fun onSessionStarted() {
        val n = activeSessions.incrementAndGet()
        android.util.Log.i(CarTubePlaybackService.TAG, "[car] session started (count=$n)")
    }

    fun onSessionStopped() {
        val n = activeSessions.updateAndGet { (it - 1).coerceAtLeast(0) }
        android.util.Log.i(CarTubePlaybackService.TAG, "[car] session stopped (count=$n)")
    }
}