package com.cartube.ytauto

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.view.Surface
/**
 * Paints idle / waiting UI on the car map [Surface] when ExoPlayer is not rendering video.
 */
object CarSurfaceIdleRenderer {

  fun paintIdle(
      context: android.content.Context,
      surface: Surface,
      width: Int,
      height: Int,
      title: String,
      subtitle: String
  ) {
    if (!surface.isValid || width <= 0 || height <= 0) return
    if (PlaybackController.player?.playWhenReady == true &&
        PlaybackController.player?.isPlaying == true
    ) {
      return
    }
    var canvas: Canvas? = null
    try {
      canvas = surface.lockCanvas(Rect(0, 0, width, height)) ?: return
      canvas.drawColor(Color.parseColor("#121212"))
      val gold = Color.parseColor("#FFC107")
      val pad = (width * 0.06f).coerceAtLeast(24f)
      val logoSize = (height * 0.18f).coerceIn(48f, 140f)
      val logoRect = RectF(
          (width - logoSize) / 2f,
          height * 0.22f,
          (width + logoSize) / 2f,
          height * 0.22f + logoSize
      )
      val logoPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = gold }
      canvas.drawRoundRect(logoRect, 12f, 12f, logoPaint)
      val innerRect = RectF(logoRect)
      innerRect.inset(logoSize * 0.18f, logoSize * 0.18f)
      val inner = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#1A1A1A") }
      canvas.drawRoundRect(innerRect, 8f, 8f, inner)

      val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = (height * 0.055f).coerceIn(22f, 44f)
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
      }
      val subPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#B0B0B0")
        textSize = (height * 0.038f).coerceIn(16f, 28f)
        textAlign = Paint.Align.CENTER
      }
      val nowTitle = title.ifBlank { context.getString(R.string.app_name) }
      val nowSub = subtitle.ifBlank { context.getString(R.string.car_surface_idle_subtitle) }
      canvas.drawText(nowTitle, width / 2f, height * 0.52f, titlePaint)
      drawWrapped(canvas, nowSub, width / 2f, height * 0.58f, width - pad * 2, subPaint)
    } catch (e: Exception) {
      CarTubeLog.w("[surface] idle paint failed: ${e.message}")
    } finally {
      if (canvas != null) {
        try {
          surface.unlockCanvasAndPost(canvas)
        } catch (e: Exception) {
          CarTubeLog.w("[surface] idle unlock failed: ${e.message}")
        }
      }
    }
  }

  private fun drawWrapped(canvas: Canvas, text: String, cx: Float, startY: Float, maxWidth: Float, paint: Paint) {
    val words = text.split(' ')
    var line = StringBuilder()
    var y = startY
    val lh = paint.textSize * 1.25f
    for (word in words) {
      val probe = if (line.isEmpty()) word else "$line $word"
      if (paint.measureText(probe) > maxWidth && line.isNotEmpty()) {
        canvas.drawText(line.toString(), cx, y, paint)
        line = StringBuilder(word)
        y += lh
      } else {
        if (line.isEmpty()) line.append(word) else line.append(' ').append(word)
      }
    }
    if (line.isNotEmpty()) canvas.drawText(line.toString(), cx, y, paint)
  }
}