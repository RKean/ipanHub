package com.cartube.ytauto

import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * Best-effort: bring ipanHub's [CarTubeCarAppService] / [NavigationTemplate] to the AA display.
 *
 * Sideloaded hosts often register only the **media** browse entry; these intents nudge Gearhead
 * to start the template **navigation** session when playback begins or when the user opens ipanHub.
 */
object CarUiLauncher {

    const val ACTION_OPEN_CAR_VIDEO = "com.cartube.ytauto.action.OPEN_CAR_VIDEO"
    const val EXTRA_REASON = "reason"
    const val MEDIA_ID_OPEN_CAR = "ipanhub_open_car_video"

    private const val GEARHEAD = CarTubeMediaBrowserService.GEARHEAD_PACKAGE
    private const val LAUNCH_COOLDOWN_MS = 2_500L

    @Volatile private var lastLaunchMs = 0L

    fun launchCarVideoUi(context: Context, reason: String) {
        val now = System.currentTimeMillis()
        if (now - lastLaunchMs < LAUNCH_COOLDOWN_MS) {
            CarTubeLog.d("[car-launch] skip cooldown reason=$reason")
            return
        }
        if (!CarConnection.isAndroidAutoProjectionActive && !CarConnection.isCarSessionActive) {
            CarTubeLog.d("[car-launch] skip (no AA projection) reason=$reason")
            return
        }
        lastLaunchMs = now
        val app = context.applicationContext
        CarTubeLog.i("[car-launch] reason=$reason")

        try {
            app.startActivity(
                Intent(app, CarNavigateTrampolineActivity::class.java).apply {
                    action = ACTION_OPEN_CAR_VIDEO
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    putExtra(EXTRA_REASON, reason)
                }
            )
        } catch (e: Exception) {
            CarTubeLog.w("[car-launch] trampoline failed: ${e.message}")
        }

        try {
            app.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=ipanhub")).apply {
                    setPackage(app.packageName)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
        } catch (e: Exception) {
            CarTubeLog.w("[car-launch] geo navigate failed: ${e.message}")
        }

    }
}