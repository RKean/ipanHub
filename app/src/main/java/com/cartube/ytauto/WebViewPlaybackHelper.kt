package com.cartube.ytauto

import android.webkit.WebView
import org.json.JSONObject

object WebViewPlaybackHelper {

    private const val JS_READ_STATE = """
        (function() {
          var v = document.querySelector('video.html5-main-video')
            || document.querySelector('video');
          if (!v) return JSON.stringify({playing:false,pos:0});
          var playing = !v.paused && !v.ended && v.readyState > 2;
          return JSON.stringify({playing:playing,pos:Math.floor(v.currentTime*1000)});
        })();
    """

    data class EmbeddedVideoState(val playing: Boolean, val positionMs: Long)

    fun readEmbeddedVideoState(webView: WebView, onResult: (EmbeddedVideoState) -> Unit) {
        webView.evaluateJavascript(JS_READ_STATE) { raw -> onResult(parseJsJson(raw)) }
    }

    fun silenceEmbeddedPlayer(webView: WebView) {
        webView.evaluateJavascript(
            """
            (function(){
              var nodes = document.querySelectorAll('video');
              for (var i = 0; i < nodes.length; i++) {
                nodes[i].pause();
                nodes[i].muted = true;
                nodes[i].volume = 0;
              }
            })();
            """.trimIndent(),
            null
        )
    }

    private fun parseJsJson(raw: String?): EmbeddedVideoState {
        if (raw.isNullOrBlank() || raw == "null") return EmbeddedVideoState(false, 0L)
        return try {
            val text = raw.trim().removeSurrounding("\"")
                .replace("\\\\", "\\")
                .replace("\\\"", "\"")
            val obj = JSONObject(text)
            EmbeddedVideoState(
                playing = obj.optBoolean("playing", false),
                positionMs = obj.optLong("pos", 0L)
            )
        } catch (_: Exception) {
            EmbeddedVideoState(false, 0L)
        }
    }
}