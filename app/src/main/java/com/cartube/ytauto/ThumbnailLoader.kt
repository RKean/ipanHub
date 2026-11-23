package com.cartube.ytauto

import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.widget.ImageView
import java.net.URL
import java.util.concurrent.Executors

object ThumbnailLoader {

    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    fun loadYouTubeThumbnail(videoId: String, into: ImageView) {
        if (videoId.isBlank()) return
        val target = videoId
        io.execute {
            val bmp = try {
                val url = URL("https://i.ytimg.com/vi/$target/hqdefault.jpg")
                url.openStream().use { BitmapFactory.decodeStream(it) }
            } catch (_: Exception) {
                null
            }
            main.post {
                if (bmp != null) into.setImageBitmap(bmp)
            }
        }
    }
}