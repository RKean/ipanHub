package com.cartube.ytauto

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log

/**
 * Android Auto accepts Car App Library **template** apps only when the system records
 * the package as installed from Google Play ([PLAY_INSTALLER]).
 * Ordinary sideloads show up as media-only ([CarTubeMediaBrowserService]).
 */
object AaInstallTrust {

    const val TAG = "CarTube"
    const val PLAY_INSTALLER = "com.android.vending"

    fun installingPackageName(context: Context): String? {
        val pm = context.packageManager
        val pkg = context.packageName
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                pm.getInstallSourceInfo(pkg).installingPackageName
            } else {
                @Suppress("DEPRECATION")
                pm.getInstallerPackageName(pkg)
            }
        } catch (e: Exception) {
            Log.w(TAG, "[install] unable to read installer", e)
            null
        }
    }

    fun isTrustedForTemplateVideo(context: Context): Boolean =
        installingPackageName(context) == PLAY_INSTALLER

    fun modeDescription(context: Context): String {
        val installer = installingPackageName(context) ?: "(unknown)"
        return if (isTrustedForTemplateVideo(context)) {
            "Play-source install ($installer) — video car app should be available."
        } else {
            "Installed via $installer — Android Auto will only show the media (audio) player."
        }
    }
}