package com.cartube.ytauto

import android.content.Context
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList

object YoutubePlayback {

    private const val TAG = "CarTube"

    suspend fun resolveStreamUrl(watchUrl: String): String = withContext(Dispatchers.IO) {
        CookieBridge.syncToNewPipe()
        val service = NewPipe.getService(ServiceList.YouTube.serviceId)
        val extractor = service.getStreamExtractor(watchUrl)
        var attempts = 0
        while (true) {
            try {
                extractor.fetchPage()
                break
            } catch (e: Exception) {
                if (attempts++ < 3 && e.message?.contains("reloaded", ignoreCase = true) == true) {
                    Thread.sleep(400)
                } else {
                    throw e
                }
            }
        }

        val hls = runCatching { extractor.hlsUrl }.getOrNull()?.takeIf { it.isNotBlank() }
        val dash = runCatching { extractor.dashMpdUrl }.getOrNull()?.takeIf { it.isNotBlank() }
        @Suppress("DEPRECATION")
        val muxed = runCatching {
            extractor.videoStreams
                .filter { !it.isVideoOnly }
                .maxByOrNull { it.height }
                ?.content
                ?.takeIf { it.isNotBlank() }
        }.getOrNull()
        hls ?: dash ?: muxed ?: throw IllegalStateException("No playable stream found")
    }

    suspend fun startOnCar(
        context: Context,
        videoId: String,
        title: String,
        onError: (String) -> Unit = {}
    ) {
        val watchUrl = YouTubeUrlParser.toWatchUrl(videoId)
        try {
            val streamUrl = resolveStreamUrl(watchUrl)
            Log.d(TAG, "Stream resolved (${streamUrl.take(48)}…)")

            val cookies = NewPipeDownloader.getInstance().cookies
            val userAgent =
                "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 Chrome/121.0.0.0 Mobile Safari/537.36"

            val mimeType = when {
                streamUrl.contains(".m3u8", ignoreCase = true) ||
                    streamUrl.contains("/hls", ignoreCase = true) -> MimeTypes.APPLICATION_M3U8
                streamUrl.contains(".mpd", ignoreCase = true) ||
                    streamUrl.contains("/dash", ignoreCase = true) -> MimeTypes.APPLICATION_MPD
                else -> null
            }

            val okHttp = OkHttpClient.Builder().build()
            val dataSourceFactory = OkHttpDataSource.Factory(okHttp).setDefaultRequestProperties(
                buildMap {
                    put("User-Agent", userAgent)
                    if (cookies.isNotBlank()) put("Cookie", cookies)
                }
            )

            val trackSelector = DefaultTrackSelector(context).apply {
                setParameters(
                    buildUponParameters()
                        .setMaxVideoSize(854, 480)
                        .setMaxVideoBitrate(2_500_000)
                        .build()
                )
            }

            val mediaSourceFactory = DefaultMediaSourceFactory(context)
                .setDataSourceFactory(dataSourceFactory)

            val exo = ExoPlayer.Builder(context)
                .setTrackSelector(trackSelector)
                .setMediaSourceFactory(mediaSourceFactory)
                .build()

            exo.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(),
                true
            )

            val mediaItem = MediaItem.Builder()
                .setUri(streamUrl)
                .apply { mimeType?.let { setMimeType(it) } }
                .build()

            exo.addListener(object : Player.Listener {
                override fun onPlayerError(error: PlaybackException) {
                    Log.e(TAG, "ExoPlayer error: ${error.message}")
                    onError(error.message ?: "Playback error")
                }
            })

            val surfaceReady = CompletableDeferred<Unit>()
            PlaybackController.setPlayer(exo, title.ifBlank { "YouTube" }, videoId, surfaceReady)

            val gotSurface = withTimeoutOrNull(4_000L) { surfaceReady.await() } != null
            Log.d(TAG, "Car surface ready before prepare: $gotSurface")

            exo.setMediaItem(mediaItem)
            exo.prepare()
            exo.play()
        } catch (e: Exception) {
            Log.e(TAG, "startOnCar failed", e)
            onError(e.message ?: "Failed to start playback")
        }
    }
}