package com.cartube.ytauto

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import com.cartube.ytauto.databinding.ActivityMainBinding
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.schabi.newpipe.extractor.NewPipe

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var loadJob: Job? = null
    private var lastSentVideoId: String? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        val app = application as CarTubeApp
        app.appScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            CookieBridge.syncToNewPipe()
            NewPipe.init(NewPipeDownloader.getInstance())
        }

        binding.statusText.text = getString(R.string.phone_hint)

        binding.sendToCarButton.setOnClickListener {
            val url = binding.webView.url ?: return@setOnClickListener
            val videoId = YouTubeUrlParser.extractVideoId(url)
            if (videoId == null) {
                Toast.makeText(this, "Open a YouTube watch page first", Toast.LENGTH_SHORT).show()
            } else {
                sendVideoToCar(videoId, binding.webView.title ?: "YouTube")
            }
        }

        setupWebView(binding.webView, binding.progressBar, binding.statusText)
        if (savedInstanceState == null) {
            binding.webView.loadUrl("https://m.youtube.com")
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView(webView: WebView, progressBar: ProgressBar, statusText: TextView) {
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            userAgentString =
                "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 Chrome/121.0.0.0 Mobile Safari/537.36"
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                progressBar.isVisible = newProgress in 1..99
                progressBar.progress = newProgress
            }
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val url = request.url.toString()
                if (url.startsWith("http://") || url.startsWith("https://")) {
                    return false
                }
                return true
            }

            override fun onPageFinished(view: WebView, url: String) {
                CookieBridge.syncToNewPipe()
                val videoId = YouTubeUrlParser.extractVideoId(url)
                if (videoId != null) {
                    statusText.text = getString(R.string.now_playing_on_car)
                    maybeAutoSend(videoId, view.title ?: "YouTube")
                } else {
                    statusText.text = getString(R.string.phone_hint)
                    lastSentVideoId = null
                }
            }
        }
    }

    private fun maybeAutoSend(videoId: String, title: String) {
        if (videoId == lastSentVideoId) return
        sendVideoToCar(videoId, title)
    }

    private fun sendVideoToCar(videoId: String, title: String) {
        lastSentVideoId = videoId
        binding.statusText.text = getString(R.string.now_playing_on_car)
        CookieBridge.syncToNewPipe()
        loadJob?.cancel()
        val app = application as CarTubeApp
        loadJob = app.appScope.launch {
            YoutubePlayback.startOnCar(
                context = applicationContext,
                videoId = videoId,
                title = title,
                onError = { msg ->
                    runOnUiThread {
                        Toast.makeText(this@MainActivity, msg, Toast.LENGTH_LONG).show()
                        binding.statusText.text = msg
                    }
                }
            )
        }
    }

    override fun onDestroy() {
        loadJob?.cancel()
        super.onDestroy()
    }
}