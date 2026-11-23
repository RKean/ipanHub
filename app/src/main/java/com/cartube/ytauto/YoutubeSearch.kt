package com.cartube.ytauto

import android.util.Log
import org.schabi.newpipe.extractor.InfoItem
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.search.SearchExtractor
import org.schabi.newpipe.extractor.stream.StreamInfoItem

data class YoutubeSearchResult(
    val videoId: String,
    val title: String,
    val channel: String,
    val durationSeconds: Long,
    val thumbnailUrl: String,
)

object YoutubeSearch {

    const val TAG = "CarTube"

    private fun ensureInit() {
        runCatching { NewPipe.getService(ServiceList.YouTube.serviceId) }
            .getOrElse { NewPipe.init(NewPipeDownloader.getInstance()) }
    }

    fun search(query: String): List<YoutubeSearchResult> {
        if (query.isBlank()) return emptyList()
        ensureInit()
        CookieBridge.syncToNewPipe()
        return try {
            val service = NewPipe.getService(ServiceList.YouTube.serviceId)
            val extractor: SearchExtractor = service.getSearchExtractor(query, listOf("videos"), "")
            extractor.fetchPage()
            extractor.initialPage.items.mapNotNull { toResult(it) }
        } catch (e: Exception) {
            Log.e(TAG, "[search] failed", e)
            emptyList()
        }
    }

    private fun toResult(item: InfoItem): YoutubeSearchResult? {
        if (item !is StreamInfoItem) return null
        val url = item.url ?: return null
        val id = YouTubeUrlParser.extractVideoId(url) ?: return null
        val thumb = item.thumbnails.firstOrNull()?.url ?: "https://i.ytimg.com/vi/$id/hqdefault.jpg"
        return YoutubeSearchResult(
            videoId = id,
            title = item.name.orEmpty().ifBlank { "YouTube" },
            channel = item.uploaderName.orEmpty(),
            durationSeconds = item.duration,
            thumbnailUrl = thumb
        )
    }

    fun resultFromVideoId(videoId: String, title: String = "YouTube"): YoutubeSearchResult {
        return YoutubeSearchResult(
            videoId = videoId,
            title = title,
            channel = "",
            durationSeconds = 0,
            thumbnailUrl = "https://i.ytimg.com/vi/$videoId/hqdefault.jpg"
        )
    }
}