package com.cartube.ytauto

import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import java.io.ByteArrayInputStream

/**
 * Blocks common YouTube ad/tracking requests and injects DOM cleanup for in-page players.
 */
object YouTubeAdBlocker {

    private val BLOCKED_HOST_SNIPPETS = listOf(
        "doubleclick.net",
        "googleadservices.com",
        "googlesyndication.com",
        "googleads.g.doubleclick.net",
        "adservice.google.com",
        "pagead2.googlesyndication.com",
        "youtube.com/pagead/",
        "youtube.com/get_midroll",
        "youtube.com/ptracking",
        "youtube.com/api/stats/ads",
    )

    private val BLOCKED_PATH_SNIPPETS = listOf(
        "/pagead/",
        "/ptracking",
        "/get_midroll_",
        "/api/stats/ads",
        "/adview",
        "/ads/",
    )

    fun shouldBlock(url: String): Boolean {
        val lower = url.lowercase()
        if (BLOCKED_HOST_SNIPPETS.any { lower.contains(it) }) return true
        return BLOCKED_PATH_SNIPPETS.any { lower.contains(it) }
    }

    fun intercept(request: WebResourceRequest): WebResourceResponse? {
        val url = request.url.toString()
        if (!shouldBlock(url)) return null
        return emptyResponse()
    }

    private fun emptyResponse(): WebResourceResponse {
        val body = ByteArrayInputStream(ByteArray(0))
        return WebResourceResponse("text/plain", "utf-8", body)
    }

    const val INJECT_JS = """
        (function(){
          try {
            var css = '.ad-container,.video-ads,.ytp-ad-module,.ytp-ad-overlay-slot,'
              + '#masthead-ad,.ytd-ad-slot-renderer,.ytd-display-ad-renderer{display:none!important}';
            var s = document.createElement('style'); s.textContent = css; document.head.appendChild(s);
          } catch(e) {}
          function killAdVideo() {
            var v = document.querySelector('video');
            if (!v) return;
            var ad = document.querySelector('.ad-showing') || document.querySelector('.ytp-ad-player-overlay');
            if (ad || (v.duration && v.duration < 90 && document.querySelector('.ytp-ad-skip-button'))) {
              try { v.currentTime = v.duration; } catch(e) {}
              var skip = document.querySelector('.ytp-ad-skip-button,.ytp-ad-skip-button-modern');
              if (skip) skip.click();
            }
          }
          setInterval(killAdVideo, 800);
        })();
    """
}