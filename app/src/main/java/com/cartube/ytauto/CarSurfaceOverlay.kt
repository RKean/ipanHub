package com.cartube.ytauto

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Surface
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Draws title and progress on the car [Surface] while controls are visible.
 * ExoPlayer and this overlay both use the same surface; we repaint on a short interval.
 */
object CarSurfaceOverlay {

    private const val TAG = "CarTube"
    private val handler = Handler(Looper.getMainLooper())
    private var targetSurface: Surface? = null
    private var visibleUntilMs = 0L

    private val paintBg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(190, 0, 0, 0)
    }
    private val paintTitle = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 36f
        isFakeBoldText = true
    }
    private val paintSub = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(220, 255, 200, 200)
        textSize = 28f
    }
    private val paintProgressBg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(120, 255, 255, 255)
    }
    private val paintProgressFg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(255, 255, 107, 107)
    }

    private val redrawTick = object : Runnable {
        override fun run() {
            drawOnce()
            if (shouldDraw()) {
                handler.postDelayed(this, 350L)
            }
        }
    }

    fun attachSurface(surface: Surface?) {
        targetSurface = surface
        if (surface == null) {
            handler.removeCallbacks(redrawTick)
        } else {
            bump()
        }
    }

    fun bump() {
        visibleUntilMs = System.currentTimeMillis() + 6_000L
        handler.removeCallbacks(redrawTick)
        handler.post(redrawTick)
    }

    private fun shouldDraw(): Boolean =
        targetSurface != null && System.currentTimeMillis() < visibleUntilMs

    private fun drawOnce() {
        if (!shouldDraw()) return
        val surface = targetSurface ?: return
        if (!surface.isValid) return

        val title = PlaybackController.title.ifBlank { "CarTube" }
        val position = PlaybackController.player?.currentPosition ?: 0L
        val duration = PlaybackController.player?.duration?.coerceAtLeast(0L) ?: 0L
        val progress = if (duration > 0) position.toFloat() / duration else 0f
        val timeLine = "${formatTime(position)} / ${formatTime(duration)}"

        try {
            val canvas: Canvas = surface.lockHardwareCanvas() ?: return
            try {
                val w = canvas.width.toFloat()
                val h = canvas.height.toFloat()
                val barHeight = 120f
                val top = h - barHeight - 24f
                canvas.drawRoundRect(RectF(24f, top, w - 24f, h - 24f), 16f, 16f, paintBg)
                canvas.drawText(title.take(48), 48f, top + 42f, paintTitle)
                canvas.drawText(timeLine, 48f, top + 78f, paintSub)
                val progLeft = 48f
                val progRight = w - 48f
                val progY = top + 98f
                canvas.drawRect(progLeft, progY, progRight, progY + 8f, paintProgressBg)
                canvas.drawRect(progLeft, progY, progLeft + (progRight - progLeft) * progress, progY + 8f, paintProgressFg)
            } finally {
                surface.unlockCanvasAndPost(canvas)
            }
        } catch (e: Exception) {
            Log.w(TAG, "[overlay] draw failed: ${e.message}")
        }
    }

    private fun formatTime(ms: Long): String {
        if (ms <= 0) return "0:00"
        val totalSec = TimeUnit.MILLISECONDS.toSeconds(ms)
        val min = totalSec / 60
        val sec = totalSec % 60
        return String.format(Locale.US, "%d:%02d", min, sec)
    }
}