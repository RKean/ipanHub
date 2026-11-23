package com.cartube.ytauto

import android.content.Context

object CarTubePrefs {
    private const val FILE = "cartube_prefs"
    private const val KEY_INSTALL_BANNER_DISMISSED = "install_banner_dismissed"

    fun isInstallBannerDismissed(context: Context): Boolean =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getBoolean(KEY_INSTALL_BANNER_DISMISSED, false)

    fun setInstallBannerDismissed(context: Context) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_INSTALL_BANNER_DISMISSED, true).apply()
    }
}