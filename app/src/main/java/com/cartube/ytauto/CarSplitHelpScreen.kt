package com.cartube.ytauto

import androidx.car.app.CarContext
import androidx.car.app.model.Action
import androidx.car.app.model.MessageTemplate
import androidx.car.app.model.Template

/** Shown when the host will not grant a map surface (another nav app owns Coolwalk). */
class CarSplitHelpScreen(carContext: CarContext) : SafeCarScreen(carContext) {

  override fun buildTemplate(): Template {
    val body = buildString {
      appendLine(carContext.getString(R.string.car_fix_split_steps))
      appendLine()
      append(carContext.getString(R.string.parked_only_hint))
    }
    return MessageTemplate.Builder(body)
      .setTitle(carContext.getString(R.string.car_surface_waiting_title))
      .addAction(
        Action.Builder()
          .setTitle(carContext.getString(R.string.back))
          .setOnClickListener { screenManager.pop() }
          .build()
      )
      .build()
  }
}