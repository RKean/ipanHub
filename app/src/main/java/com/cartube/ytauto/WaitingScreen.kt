package com.cartube.ytauto

import androidx.car.app.CarContext
import androidx.car.app.model.Action
import androidx.car.app.model.MessageTemplate
import androidx.car.app.model.Template
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner

class WaitingScreen(carContext: CarContext) : SafeCarScreen(carContext) {

    private val playbackListener: () -> Unit = { handlePlaybackChanged() }

    init {
        PlaybackController.playbackListener = playbackListener
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onDestroy(owner: LifecycleOwner) {
                if (PlaybackController.playbackListener === playbackListener) {
                    PlaybackController.playbackListener = null
                }
            }
        })
    }

    private fun handlePlaybackChanged() {
        if (PlaybackController.hasActivePlayback) {
            screenManager.push(VideoPlayerScreen(carContext))
        } else {
            invalidate()
        }
    }

    override fun buildTemplate(): Template {
        val body = buildString {
            append(carContext.getString(R.string.car_pick_on_phone))
            append("\n\n")
            append(carContext.getString(R.string.parked_only_hint))
        }
        return MessageTemplate.Builder(body)
            .setTitle(carContext.getString(R.string.app_name))
            .addAction(
                Action.Builder()
                    .setTitle(carContext.getString(R.string.refresh))
                    .setOnClickListener { invalidate() }
                    .build()
            )
            .build()
    }
}