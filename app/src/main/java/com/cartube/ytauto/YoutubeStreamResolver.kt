package com.cartube.ytauto

import android.util.Log
import androidx.media3.common.MimeTypes
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.StreamExtractor
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.exceptions.SignInConfirmNotBotException
import org.schabi.newpipe.extractor.stream.VideoStream
import java.io.IOException

/**
 * Resolves a YouTube watch URL into an ExoPlayer-friendly stream with explicit fallbacks.
 */
object YoutubeStreamResolver {

    const val TAG = "CarTube"

    enum class StreamKind {
        PROGRESSIVE_MUXED,
        HLS,
        DASH,
        MERGED_PROGRESSIVE,
    }

    data class ResolvedStream(
        val kind: StreamKind,
        val primaryUrl: String,
        val secondaryUrl: String? = null,
        val mimeType: String?,
        val via: String,
    )

    sealed class ResolveResult {
        data class Ok(val stream: ResolvedStream) : ResolveResult()
        data class Err(val step: String, val detail: String, val cause: Throwable? = null) : ResolveResult()
    }

    fun resolveWatchUrl(watchUrl: String, applyWebViewCookies: Boolean = true): ResolveResult {
        val videoId = YouTubeUrlParser.extractVideoId(watchUrl)
            ?: return ResolveResult.Err("videoId", "Could not parse watch URL: $watchUrl")
        return resolveVideoId(videoId, applyWebViewCookies)
    }

    private fun resolveWithNewPipe(watchUrl: String, applyWebViewCookies: Boolean): ResolveResult {
        val downloader = NewPipeDownloader.getInstance()
        downloader.userAgent = NewPipeDownloader.DEFAULT_USER_AGENT

        val attempts = if (applyWebViewCookies) {
            listOf("with-cookies" to downloader.cookies, "no-cookies" to "")
        } else {
            listOf("no-cookies" to "")
        }

        ensureNewPipeInit()

        var lastErr: ResolveResult.Err? = null
        for ((label, cookies) in attempts) {
            downloader.cookies = cookies
            Log.i(TAG, "[resolve] newpipe attempt=$label url=$watchUrl cookieLen=${cookies.length}")
            when (val fetched = fetchPage(watchUrl)) {
                is ResolveResult.Err -> {
                    lastErr = fetched
                    continue
                }
                is ResolveResult.Ok -> return fetched
            }
        }
        return lastErr ?: ResolveResult.Err("newpipe", "No NewPipe attempts ran")
    }

    fun resolveVideoId(videoId: String, applyWebViewCookies: Boolean = true): ResolveResult {
        if (videoId.isBlank()) {
            return ResolveResult.Err("videoId", "Empty video id")
        }
        if (applyWebViewCookies) {
            try {
                CookieBridge.syncToNewPipe()
            } catch (e: Exception) {
                Log.w(TAG, "[resolve] cookie sync failed: ${e.message}")
            }
        }
        val watchUrl = YouTubeUrlParser.toWatchUrl(videoId)
        val cookiesSnapshot = NewPipeDownloader.getInstance().cookies
        val newPipe = resolveWithNewPipe(watchUrl, applyWebViewCookies)
        if (newPipe is ResolveResult.Ok) return newPipe

        Log.i(TAG, "[resolve] NewPipe failed, trying InnerTube fallback for $videoId")
        YoutubeInnerTubeClient.resolve(videoId, cookiesSnapshot)?.let { stream ->
            return ResolveResult.Ok(stream)
        }

        return when (newPipe) {
            is ResolveResult.Err -> maybeSignInHelp(newPipe)
            is ResolveResult.Ok -> newPipe
        }
    }

    private fun maybeSignInHelp(err: ResolveResult.Err): ResolveResult.Err {
        val bot = err.cause is SignInConfirmNotBotException ||
            err.detail.contains("not a bot", ignoreCase = true) ||
            err.detail.contains("LOGIN_REQUIRED", ignoreCase = true)
        return if (bot) {
            err.copy(
                detail = err.detail + " — sign in on the phone YouTube page, play the video once, then tap Send to car again."
            )
        } else {
            err
        }
    }

    private fun ensureNewPipeInit() {
        runCatching { NewPipe.getService(ServiceList.YouTube.serviceId) }
            .getOrElse { NewPipe.init(NewPipeDownloader.getInstance()) }
    }

    private fun fetchPage(watchUrl: String): ResolveResult {
        return try {
            val service = NewPipe.getService(ServiceList.YouTube.serviceId)
            val extractor = service.getStreamExtractor(watchUrl)
            fetchPageWithRetries(extractor)
            val fromExtractor = pickStream(extractor, "StreamExtractor")
            if (fromExtractor != null) return ResolveResult.Ok(fromExtractor)

            Log.i(TAG, "[resolve] extractor empty, trying StreamInfo.getInfo")
            val info = StreamInfo.getInfo(service, watchUrl)
            val fromInfo = pickStreamFromInfo(info)
            if (fromInfo != null) return ResolveResult.Ok(fromInfo)

            ResolveResult.Err(
                "pickStream",
                "No muxed/HLS/merge candidates (hls=${info.hlsUrl?.isNotBlank() == true}, " +
                    "dash=${info.dashMpdUrl?.isNotBlank() == true})"
            )
        } catch (e: Exception) {
            Log.e(TAG, "[resolve] fetchPage failed", e)
            ResolveResult.Err("fetchPage", e.message ?: e.javaClass.simpleName, e)
        }
    }

    private fun fetchPageWithRetries(extractor: StreamExtractor) {
        var attempt = 0
        while (true) {
            try {
                extractor.fetchPage()
                Log.i(TAG, "[resolve] fetchPage ok name=${extractor.name}")
                return
            } catch (e: Exception) {
                val retry = attempt++ < 3 &&
                    (e.message?.contains("reloaded", ignoreCase = true) == true ||
                        e is IOException)
                if (!retry) throw e
                Log.w(TAG, "[resolve] fetchPage retry $attempt: ${e.message}")
                Thread.sleep(500L * attempt)
            }
        }
    }

    private fun pickStream(extractor: StreamExtractor, source: String): ResolvedStream? =
        pickStreamFromInfoLike(
            hls = safeString { extractor.hlsUrl },
            dash = safeString { extractor.dashMpdUrl },
            muxed = safeList { @Suppress("DEPRECATION") extractor.videoStreams }
                .filter { !it.isVideoOnly },
            videoOnly = safeList { extractor.videoOnlyStreams },
            audio = safeList { extractor.audioStreams },
            source = source
        )

    private fun pickStreamFromInfo(info: StreamInfo): ResolvedStream? =
        pickStreamFromInfoLike(
            hls = info.hlsUrl?.takeIf { it.isNotBlank() },
            dash = info.dashMpdUrl?.takeIf { it.isNotBlank() },
            muxed = info.videoStreams?.filter { !it.isVideoOnly }.orEmpty(),
            videoOnly = info.videoOnlyStreams.orEmpty(),
            audio = info.audioStreams.orEmpty(),
            source = "StreamInfo"
        )

    private fun pickStreamFromInfoLike(
        hls: String?,
        dash: String?,
        muxed: List<VideoStream>,
        videoOnly: List<VideoStream>,
        audio: List<AudioStream>,
        source: String,
    ): ResolvedStream? {
        Log.i(
            TAG,
            "[resolve] $source counts muxed=${muxed.size} videoOnly=${videoOnly.size} " +
                "audio=${audio.size} hls=${!hls.isNullOrBlank()} dash=${!dash.isNullOrBlank()}"
        )

        pickMuxed(muxed)?.let {
            return ResolvedStream(
                StreamKind.PROGRESSIVE_MUXED,
                it,
                mimeType = null,
                via = "$source/muxed-${muxed.maxOfOrNull { v -> v.height } ?: 0}p"
            )
        }

        if (!hls.isNullOrBlank()) {
            return ResolvedStream(
                StreamKind.HLS,
                hls,
                mimeType = MimeTypes.APPLICATION_M3U8,
                via = "$source/hls"
            )
        }

        pickMerge(videoOnly, audio)?.let { (v, a) ->
            return ResolvedStream(
                StreamKind.MERGED_PROGRESSIVE,
                v,
                secondaryUrl = a,
                mimeType = null,
                via = "$source/merge"
            )
        }

        if (!dash.isNullOrBlank()) {
            return ResolvedStream(
                StreamKind.DASH,
                dash,
                mimeType = MimeTypes.APPLICATION_MPD,
                via = "$source/dash"
            )
        }

        return null
    }

    private fun pickMuxed(muxed: List<VideoStream>): String? {
        if (muxed.isEmpty()) return null
        val sorted = muxed
            .filter { it.content.isNotBlank() }
            .sortedByDescending { it.height }
        val ideal = sorted.firstOrNull { it.height in 240..720 }
            ?: sorted.firstOrNull { it.height <= 720 }
            ?: sorted.firstOrNull()
        return ideal?.content
    }

    private fun pickMerge(
        videoOnly: List<VideoStream>,
        audio: List<AudioStream>
    ): Pair<String, String>? {
        val v = videoOnly
            .filter { it.content.isNotBlank() }
            .sortedBy { it.height }
            .firstOrNull { it.height in 240..720 }
            ?: videoOnly.filter { it.content.isNotBlank() }.minByOrNull { it.height }
        val a = audio
            .filter { it.content.isNotBlank() }
            .maxByOrNull { it.averageBitrate }
        if (v == null || a == null) return null
        return v.content to a.content
    }

    private inline fun safeString(block: () -> String?): String? = runCatching { block() }.getOrNull()

    private inline fun <T> safeList(block: () -> List<T>): List<T> =
        runCatching { block() }.getOrElse { emptyList() }

    fun formatUserError(err: ResolveResult.Err): String =
        "YouTube extract failed at «${err.step}»: ${err.detail}"
}