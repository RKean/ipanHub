package com.cartube.ytauto

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.suggestion.SuggestionExtractor
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

object YoutubeSuggestions {

    private const val TAG = "CarTube"
    private val http = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    fun fetch(query: String): List<String> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        return fetchNewPipe(q).ifEmpty { fetchGoogleSuggest(q) }
    }

    private fun fetchNewPipe(query: String): List<String> = try {
        runCatching { NewPipe.getService(ServiceList.YouTube.serviceId) }
            .getOrElse {
                NewPipe.init(NewPipeDownloader.getInstance())
                NewPipe.getService(ServiceList.YouTube.serviceId)
            }
        val service = NewPipe.getService(ServiceList.YouTube.serviceId)
        val extractor: SuggestionExtractor = service.suggestionExtractor
        extractor.suggestionList(query)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .take(12)
    } catch (e: Exception) {
        Log.w(TAG, "[suggest] newpipe failed: ${e.message}")
        emptyList()
    }

    private fun fetchGoogleSuggest(query: String): List<String> {
        return try {
            val encoded = URLEncoder.encode(query, StandardCharsets.UTF_8.toString())
            val url =
                "https://suggestqueries.google.com/complete/search?client=youtube&ds=yt&q=$encoded"
            val body = http.newCall(Request.Builder().url(url).get().build())
                .execute().use { it.body?.string().orEmpty() }
            val arr = JSONArray(body)
            if (arr.length() < 2) {
                emptyList()
            } else {
                val list = arr.getJSONArray(1)
                buildList {
                    for (i in 0 until minOf(list.length(), 12)) {
                        add(list.getString(i))
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "[suggest] google failed: ${e.message}")
            emptyList()
        }
    }
}