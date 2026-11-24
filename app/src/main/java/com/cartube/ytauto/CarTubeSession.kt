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
                CarNavigationClaim.detachCarContext()
                PlaybackController.releaseCarSurface()
                CarConnection.onSessionStopped()
            }
        })
    }

    override fun onCreateScreen(intent: Intent): Screen {
        CarTubeLog.i("[car] onCreateScreen action=${intent.action}")
        return VideoPlayerScreen(carContext)
    }

    override fun onNewIntent(intent: Intent) {
        CarTubeLog.i("[car] onNewIntent action=${intent.action}")
    }
}