package com.cartube.ytauto

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle

/**
 * Phone-side trampoline for NF-6 style navigation intents while Android Auto projection is active.
 * Does not show UI; finishes immediately after nudging the host.
 */
class CarNavigateTrampolineActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val reason = intent?.getStringExtra(CarUiLauncher.EXTRA_REASON) ?: "trampoline"
        CarTubeLog.i("[car-launch] trampoline reason=$reason")
        try {
            startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=ipanhub")).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
        } catch (e: Exception) {
            CarTubeLog.w("[car-launch] trampoline external geo: ${e.message}")
        }
        finish()
    }
}