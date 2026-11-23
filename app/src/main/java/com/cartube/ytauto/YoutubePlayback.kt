package com.cartube.ytauto

import android.content.Context
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient

object YoutubePlayback {

    private const val TAG = YoutubeStreamResolver.TAG

    suspend fun startOnCar(
        context: Context,
        videoId: String,
        title: String,
        onError: (String) -> Unit = {}
    ) {
        try {
            val resolved = withContext(Dispatchers.IO) {
                YoutubeStreamResolver.resolveVideoId(videoId, applyWebViewCookies = true)
            }
            val stream = when (resolved) {
                is YoutubeStreamResolver.ResolveResult.Ok -> resolved.stream
                is YoutubeStreamResolver.ResolveResult.Err -> {
                    val msg = YoutubeStreamResolver.formatUserError(resolved)
                    Log.e(TAG, "[playback] $msg", resolved.cause)
                    onError(msg)
                    return
                }
            }

            Log.i(
                TAG,
                "[playback] using ${stream.via} kind=${stream.kind} url=${stream.primaryUrl.take(60)}"
            )

            val cookies = NewPipeDownloader.getInstance().cookies
            val userAgent = NewPipeDownloader.DEFAULT_USER_AGENT

            val okHttp = OkHttpClient.Builder().build()
            val dataSourceFactory = OkHttpDataSource.Factory(okHttp).setDefaultRequestProperties(
                buildMap {
                    put("User-Agent", userAgent)
                    put("Referer", "https://www.youtube.com/")
                    put("Origin", "https://www.youtube.com")
                    if (cookies.isNotBlank()) put("Cookie", cookies)
                }
            )

            val trackSelector = DefaultTrackSelector(context).apply {
                setParameters(
                    buildUponParameters()
                        .setMaxVideoSize(1280, 720)
                        .setMaxVideoBitrate(4_000_000)
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

            val mediaSource = buildMediaSource(dataSourceFactory, stream)

            exo.addListener(object : Player.Listener {
                override fun onPlayerError(error: PlaybackException) {
                    val detail = error.cause?.message ?: error.message
                    Log.e(TAG, "[playback] ExoPlayer error: $detail", error)
                    onError("Playback failed: ${detail?.take(120) ?: "unknown"}")
                }
            })

            val surfaceReady = CompletableDeferred<Unit>()
            PlaybackController.setPlayer(exo, title.ifBlank { "YouTube" }, videoId, surfaceReady)

            val gotSurface = withTimeoutOrNull(4_000L) { surfaceReady.await() } != null
            Log.i(TAG, "[playback] car surface ready=$gotSurface")

            exo.setMediaSource(mediaSource)
            exo.prepare()
            exo.play()
        } catch (e: Exception) {
            Log.e(TAG, "[playback] startOnCar failed", e)
            onError("Playback setup failed: ${e.message?.take(120) ?: e.javaClass.simpleName}")
        }
    }

    private fun buildMediaSource(
        dataSourceFactory: DataSource.Factory,
        stream: YoutubeStreamResolver.ResolvedStream
    ): MediaSource {
        val progressiveFactory = ProgressiveMediaSource.Factory(dataSourceFactory)
        val defaultFactory = DefaultMediaSourceFactory(dataSourceFactory)
        return when (stream.kind) {
            YoutubeStreamResolver.StreamKind.MERGED_PROGRESSIVE -> {
                val audioUrl = stream.secondaryUrl
                    ?: throw IllegalStateException("Merge stream missing audio URL")
                MergingMediaSource(
                    progressiveFactory.createMediaSource(MediaItem.fromUri(stream.primaryUrl)),
                    progressiveFactory.createMediaSource(MediaItem.fromUri(audioUrl))
                )
            }
            else -> {
                val builder = MediaItem.Builder().setUri(stream.primaryUrl)
                stream.mimeType?.let { builder.setMimeType(it) }
                defaultFactory.createMediaSource(builder.build())
            }
        }
    }
}