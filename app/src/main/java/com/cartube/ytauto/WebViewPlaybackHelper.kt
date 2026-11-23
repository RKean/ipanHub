package com.cartube.ytauto

import android.webkit.WebView

object WebViewPlaybackHelper {

    /** Pause and mute in-page YouTube so it does not compete with car ExoPlayer. */
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
              var a = document.querySelector('.html5-main-video, .video-stream');
              if (a && a.tagName === 'VIDEO') { a.pause(); a.muted = true; }
            })();
            """.trimIndent(),
            null
        )
    }
}