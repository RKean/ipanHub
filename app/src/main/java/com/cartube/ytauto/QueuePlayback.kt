package com.cartube.ytauto

import android.content.Context
import com.cartube.ytauto.queue.QueueController
import com.cartube.ytauto.queue.QueueItem

object QueuePlayback {

    fun playNow(context: Context, item: QueueItem) {
        QueueController.playNow(item)
        val sameTrack = PlaybackController.videoId == item.videoId
        CarTubePlaybackService.requestPlayCurrent(context, forceRestart = !sameTrack)
    }

    fun playNextInQueue(context: Context, item: QueueItem) {
        QueueController.playNext(item)
        StreamCache.warmResolve(item.videoId)
    }

    fun addToQueue(context: Context, item: QueueItem) {
        QueueController.addToEnd(item)
        StreamCache.warmResolve(item.videoId)
        if (QueueController.currentIndex() < 0) {
            CarTubePlaybackService.requestPlayCurrent(context, forceRestart = true)
        }
    }

    fun jumpTo(context: Context, index: Int) {
        val item = QueueController.jumpTo(index) ?: return
        val same = item.videoId == PlaybackController.videoId
        CarTubePlaybackService.requestPlayCurrent(context, forceRestart = !same)
    }

    fun skipNext(context: Context) {
        CarTubePlaybackService.requestSkipNext(context)
    }

    fun skipPrevious(context: Context) {
        CarTubePlaybackService.requestSkipPrevious(context)
    }

    fun fromSearchResult(result: YoutubeSearchResult): QueueItem = QueueItem(
        videoId = result.videoId,
        title = result.title,
        channel = result.channel,
        durationSeconds = result.durationSeconds
    )
}