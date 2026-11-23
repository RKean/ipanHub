package com.cartube.ytauto

import android.content.Intent
import androidx.car.app.Screen
import androidx.car.app.Session

class CarTubeSession : Session() {

    override fun onCreateScreen(intent: Intent): Screen {
        return if (PlaybackController.hasActivePlayback) {
            VideoPlayerScreen(carContext)
        } else {
            WaitingScreen(carContext)
        }
    }
}