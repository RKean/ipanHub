package com.cartube.ytauto

import android.annotation.SuppressLint
import android.content.Context
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient

/** Hidden WebView used only to seed YouTube cookies for stream extraction. */
@SuppressLint("SetJavaScriptEnabled")
class CookieWebView(context: Context) : WebView(context) {

    init {
        visibility = View.GONE
        layoutParams = android.view.ViewGroup.LayoutParams(1, 1)
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
        webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                CookieBridge.syncToNewPipe()
            }
        }
    }

    fun warmUp() {
        loadUrl("https://m.youtube.com")
    }
}