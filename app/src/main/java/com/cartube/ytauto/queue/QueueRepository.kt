package com.cartube.ytauto.queue

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

class QueueRepository(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(): QueueState {
        val raw = prefs.getString(KEY_STATE, null) ?: return QueueState()
        return try {
            val obj = JSONObject(raw)
            val arr = obj.optJSONArray("items") ?: JSONArray()
            val list = buildList {
                for (i in 0 until arr.length()) {
                    add(QueueItem.fromJson(arr.getJSONObject(i)))
                }
            }
            QueueState(
                items = list,
                currentIndex = obj.optInt("currentIndex", -1),
                autoplayRelated = obj.optBoolean("autoplayRelated", false)
            )
        } catch (_: Exception) {
            QueueState()
        }
    }

    fun save(state: QueueState) {
        val arr = JSONArray()
        state.items.forEach { arr.put(it.toJson()) }
        val obj = JSONObject().apply {
            put("items", arr)
            put("currentIndex", state.currentIndex)
            put("autoplayRelated", state.autoplayRelated)
        }
        prefs.edit().putString(KEY_STATE, obj.toString()).apply()
    }

    data class QueueState(
        val items: List<QueueItem> = emptyList(),
        val currentIndex: Int = -1,
        val autoplayRelated: Boolean = false,
    )

    companion object {
        private const val PREFS = "cartube_queue"
        private const val KEY_STATE = "queue_state"
    }
}