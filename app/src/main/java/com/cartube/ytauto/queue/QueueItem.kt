package com.cartube.ytauto.queue

import org.json.JSONObject

data class QueueItem(
    val videoId: String,
    val title: String,
    val channel: String = "",
    val durationSeconds: Long = 0L,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("videoId", videoId)
        put("title", title)
        put("channel", channel)
        put("durationSeconds", durationSeconds)
    }

    companion object {
        fun fromJson(obj: JSONObject): QueueItem = QueueItem(
            videoId = obj.getString("videoId"),
            title = obj.optString("title", "YouTube"),
            channel = obj.optString("channel", ""),
            durationSeconds = obj.optLong("durationSeconds", 0L)
        )
    }
}