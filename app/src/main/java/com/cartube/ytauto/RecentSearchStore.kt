package com.cartube.ytauto

import android.content.Context
import org.json.JSONArray

object RecentSearchStore {
    private const val PREFS = "recent_search"
    private const val KEY = "queries_json"

    fun list(context: Context): List<String> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
            ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    add(arr.getString(i))
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun add(context: Context, query: String) {
        val q = query.trim()
        if (q.isEmpty()) return
        val list = list(context).toMutableList()
        list.remove(q)
        list.add(0, q)
        while (list.size > 12) list.removeAt(list.lastIndex)
        save(context, list)
    }

    fun remove(context: Context, query: String) {
        val list = list(context).toMutableList()
        list.remove(query.trim())
        save(context, list)
    }

    private fun save(context: Context, list: List<String>) {
        val arr = JSONArray()
        list.forEach { arr.put(it) }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, arr.toString()).apply()
    }
}