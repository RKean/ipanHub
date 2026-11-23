package com.cartube.ytauto

import android.util.Log
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.MessageTemplate
import androidx.car.app.model.Template

/** Base screen that never lets [onGetTemplate] crash the car host process. */
abstract class SafeCarScreen(carContext: CarContext) : Screen(carContext) {

    final override fun onGetTemplate(): Template {
        return try {
            buildTemplate()
        } catch (e: Exception) {
            Log.e(CarTubePlaybackService.TAG, "[car] template build failed", e)
            fallbackTemplate(e)
        }
    }

    protected abstract fun buildTemplate(): Template

    protected open fun fallbackTemplate(error: Exception): Template {
        val detail = error.message?.take(80).orEmpty()
        val body = if (detail.isEmpty()) {
            carContext.getString(R.string.car_template_error)
        } else {
            "${carContext.getString(R.string.car_template_error)}\n$detail"
        }
        return MessageTemplate.Builder(body)
            .setTitle(carContext.getString(R.string.app_name))
            .build()
    }
}