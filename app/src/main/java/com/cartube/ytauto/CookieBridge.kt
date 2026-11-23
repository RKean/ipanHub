package com.cartube.ytauto

import android.webkit.CookieManager

object CookieBridge {

    private val youtubeDomains = listOf(
        "https://youtube.com",
        "https://www.youtube.com",
        "https://m.youtube.com"
    )

    fun syncToNewPipe() {
        val manager = CookieManager.getInstance()
        val merged = buildString {
            for (domain in youtubeDomains) {
                val chunk = manager.getCookie(domain).orEmpty()
                if (chunk.isNotBlank()) {
                    if (isNotEmpty()) append("; ")
                    append(chunk)
                }
            }
        }
        NewPipeDownloader.getInstance().cookies = merged
    }
}