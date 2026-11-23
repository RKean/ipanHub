package com.cartube.ytauto

import android.content.Context

object RecentSearchStore {
    private const val PREFS = "recent_search"
    private const val KEY = "queries"

    fun add(context: Context, query: String) {
        val q = query.trim()
        if (q.isEmpty()) return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val list = prefs.getStringSet(KEY, emptySet())?.toMutableList() ?: mutableListOf()
        list.remove(q)
        list.add(0, q)
        while (list.size > 10) list.removeAt(list.lastIndex)
        prefs.edit().putStringSet(KEY, list.toSet()).apply()
    }
}