package com.cartube.ytauto

import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.security.MessageDigest

/**
 * Fallback when NewPipe returns no streams (often due to YouTube API churn or bot checks).
 * Uses the same InnerTube /player path the Web client uses, with optional WebView cookies.
 */
object YoutubeInnerTubeClient {

    private const val TAG = YoutubeStreamResolver.TAG
    private const val PLAYER_KEY = "AIzaSyAO_FJ2SlqU8Q4STEHLGCilw_Y9_11qcW8"
    private const val WEB_VERSION = "2.20250328.01.00"

    private val http = OkHttpClient.Builder()
        .followRedirects(true)
        .build()

    fun resolve(videoId: String, cookies: String): YoutubeStreamResolver.ResolvedStream? {
        val body = """
            {
              "context": {
                "client": {
                  "clientName": "WEB",
                  "clientVersion": "$WEB_VERSION",
                  "hl": "en",
                  "gl": "US",
                  "platform": "DESKTOP"
                }
              },
              "videoId": "$videoId",
              "contentCheckOk": true,
              "racyCheckOk": true
            }
        """.trimIndent()

        val req = Request.Builder()
            .url("https://www.youtube.com/youtubei/v1/player?key=$PLAYER_KEY&prettyPrint=false")
            .post(body.toRequestBody("application/json".toMediaType()))
            .header("Content-Type", "application/json")
            .header("User-Agent", NewPipeDownloader.DEFAULT_USER_AGENT)
            .header("Origin", "https://www.youtube.com")
            .header("Referer", "https://www.youtube.com/")
            .header("X-YouTube-Client-Name", "1")
            .header("X-YouTube-Client-Version", WEB_VERSION)
            .apply {
                if (cookies.isNotBlank()) {
                    header("Cookie", cookies)
                    sapisidHash(cookies)?.let { header("Authorization", it) }
                }
            }
            .build()

        return try {
            val text = http.newCall(req).execute().use { it.body?.string().orEmpty() }
            parsePlayerResponse(text)
        } catch (e: Exception) {
            Log.e(TAG, "[innertube] request failed", e)
            null
        }
    }

    private fun parsePlayerResponse(jsonText: String): YoutubeStreamResolver.ResolvedStream? {
        if (jsonText.isBlank()) return null
        val json = JSONObject(jsonText)
        val playability = json.optJSONObject("playabilityStatus")
        val status = playability?.optString("status").orEmpty()
        val reason = playability?.optString("reason").orEmpty()
        Log.i(TAG, "[innertube] playability status=$status reason=${reason.take(60)}")

        val streaming = json.optJSONObject("streamingData") ?: return null

        streaming.optString("hlsManifestUrl").takeIf { it.isNotBlank() }?.let { hls ->
            return YoutubeStreamResolver.ResolvedStream(
                YoutubeStreamResolver.StreamKind.HLS,
                hls,
                mimeType = androidx.media3.common.MimeTypes.APPLICATION_M3U8,
                via = "InnerTube/hls"
            )
        }

        val formats = streaming.optJSONArray("formats") ?: org.json.JSONArray()
        val adaptive = streaming.optJSONArray("adaptiveFormats") ?: org.json.JSONArray()

        fun urlFrom(obj: JSONObject): String? =
            obj.optString("url").takeIf { it.isNotBlank() }

        val muxed = mutableListOf<JSONObject>()
        for (i in 0 until formats.length()) {
            val f = formats.getJSONObject(i)
            if (urlFrom(f) != null) muxed.add(f)
        }
        muxed.maxByOrNull { it.optInt("height") }?.let { best ->
            return YoutubeStreamResolver.ResolvedStream(
                YoutubeStreamResolver.StreamKind.PROGRESSIVE_MUXED,
                urlFrom(best)!!,
                mimeType = null,
                via = "InnerTube/muxed-${best.optInt("height")}p"
            )
        }

        var bestVideo: JSONObject? = null
        var bestAudio: JSONObject? = null
        for (i in 0 until adaptive.length()) {
            val f = adaptive.getJSONObject(i)
            val url = urlFrom(f) ?: continue
            val mime = f.optString("mimeType")
            if (mime.startsWith("video/")) {
                val h = f.optInt("height")
                if (bestVideo == null || h > bestVideo!!.optInt("height")) bestVideo = f
            } else if (mime.startsWith("audio/")) {
                val br = f.optInt("bitrate")
                if (bestAudio == null || br > bestAudio!!.optInt("bitrate")) bestAudio = f
            }
        }
        if (bestVideo != null && bestAudio != null) {
            return YoutubeStreamResolver.ResolvedStream(
                YoutubeStreamResolver.StreamKind.MERGED_PROGRESSIVE,
                urlFrom(bestVideo)!!,
                secondaryUrl = urlFrom(bestAudio),
                mimeType = null,
                via = "InnerTube/merge-${bestVideo.optInt("height")}p"
            )
        }

        streaming.optString("dashManifestUrl").takeIf { it.isNotBlank() }?.let { dash ->
            return YoutubeStreamResolver.ResolvedStream(
                YoutubeStreamResolver.StreamKind.DASH,
                dash,
                mimeType = androidx.media3.common.MimeTypes.APPLICATION_MPD,
                via = "InnerTube/dash"
            )
        }
        return null
    }

    private fun sapisidHash(cookies: String): String? {
        val sapisid = cookies.split(";")
            .map { it.trim() }
            .firstOrNull { it.startsWith("__Secure-3PAPISID=") || it.startsWith("SAPISID=") }
            ?.substringAfter("=")
            ?.trim()
            ?: return null
        val ts = System.currentTimeMillis() / 1000
        val digest = MessageDigest.getInstance("SHA-1")
            .digest("$ts $sapisid https://www.youtube.com".toByteArray())
            .joinToString("") { "%02x".format(it) }
        return "SAPISIDHASH ${ts}_$digest"
    }
}