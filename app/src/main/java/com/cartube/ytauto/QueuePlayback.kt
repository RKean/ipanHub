package com.cartube.ytauto

import android.content.Context
import com.cartube.ytauto.queue.QueueController
import com.cartube.ytauto.queue.QueueItem

object QueuePlayback {

    fun playNow(context: Context, item: QueueItem) {
        QueueController.playNow(item)
        CarTubePlaybackService.requestPlayCurrent(context, forceRestart = true)
    }

    fun playNextInQueue(context: Context, item: QueueItem) {
        QueueController.playNext(item)
        CarTubePlaybackService.requestPlayCurrent(context, forceRestart = false)
    }

    fun addToQueue(context: Context, item: QueueItem) {
        QueueController.addToEnd(item)
        if (QueueController.currentIndex() < 0) {
            CarTubePlaybackService.requestPlayCurrent(context, forceRestart = true)
        }
    }

    fun jumpTo(context: Context, index: Int) {
        QueueController.jumpTo(index) ?: return
        CarTubePlaybackService.requestPlayCurrent(context, forceRestart = true)
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