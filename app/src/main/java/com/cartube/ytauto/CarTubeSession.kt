package com.cartube.ytauto

import android.content.Intent
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner

class CarTubeSession : Session() {

    init {
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onCreate(owner: LifecycleOwner) {
                CarConnection.onSessionStarted()
            }

            override fun onDestroy(owner: LifecycleOwner) {
                CarConnection.onSessionStopped()
            }
        })
    }

    override fun onCreateScreen(intent: Intent): Screen {
        return if (PlaybackController.hasActivePlayback) {
            VideoPlayerScreen(carContext)
        } else {
            WaitingScreen(carContext)
        }
    }
}