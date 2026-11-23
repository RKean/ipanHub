package com.cartube.ytauto

import android.webkit.JavascriptInterface
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

    const val JS_INSTALL_PLAY_HOOK = """
        (function(){
          if (!window._cartubeMuting) window._cartubeMuting = false;
          function notify(v) {
            if (!v || window._cartubeMuting) return;
            try {
              CarTubeBridge.onVideoPlay(Math.floor((v.currentTime||0)*1000));
            } catch(e) {}
          }
          function hook(v) {
            if (!v || v._cartubeHooked) return;
            v._cartubeHooked = true;
            v.addEventListener('play', function(){ notify(v); });
            v.addEventListener('playing', function(){ notify(v); });
          }
          function scan() {
            var list = document.querySelectorAll('video');
            for (var i = 0; i < list.length; i++) hook(list[i]);
          }
          scan();
          try {
            new MutationObserver(scan).observe(document.documentElement, {childList:true, subtree:true});
          } catch(e) {}
        })();
    """

    data class EmbeddedVideoState(val playing: Boolean, val positionMs: Long)

    fun installPlayBridge(webView: WebView, bridge: PlayBridge) {
        webView.removeJavascriptInterface("CarTubeBridge")
        webView.addJavascriptInterface(bridge, "CarTubeBridge")
    }

    fun readEmbeddedVideoState(webView: WebView, onResult: (EmbeddedVideoState) -> Unit) {
        webView.evaluateJavascript(JS_READ_STATE) { raw -> onResult(parseJsJson(raw)) }
    }

    fun silenceEmbeddedPlayer(webView: WebView) {
        webView.evaluateJavascript(
            """
            (function(){
              window._cartubeMuting = true;
              var nodes = document.querySelectorAll('video');
              for (var i = 0; i < nodes.length; i++) {
                nodes[i].pause();
                nodes[i].muted = true;
                nodes[i].volume = 0;
              }
              setTimeout(function(){ window._cartubeMuting = false; }, 1200);
            })();
            """.trimIndent(),
            null
        )
    }

    fun injectPageHooks(webView: WebView) {
        webView.evaluateJavascript(YouTubeAdBlocker.INJECT_JS, null)
        webView.evaluateJavascript(JS_INSTALL_PLAY_HOOK, null)
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

    class PlayBridge(
        private val onPlay: (positionMs: Long) -> Unit
    ) {
        @JavascriptInterface
        fun onVideoPlay(positionMs: Int) {
            onPlay(positionMs.toLong())
        }
    }
}