package com.cartube.ytauto

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource

object YoutubePlayback {

    fun requestPlayOnCar(context: Context, videoId: String, title: String) {
        CarTubePlaybackService.startCarPlayback(context.applicationContext, videoId, title)
    }

    fun requestPhoneBackgroundPlay(
        context: Context,
        videoId: String,
        title: String,
        startPositionMs: Long
    ) {
        CarTubePlaybackService.startPhoneBackgroundPlayback(
            context.applicationContext,
            videoId,
            title,
            startPositionMs
        )
    }

    fun buildMediaSource(
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