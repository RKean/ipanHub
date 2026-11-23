package com.cartube.ytauto

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ProgressBar
import android.widget.TextView
import android.util.Log
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import com.cartube.ytauto.databinding.ActivityMainBinding
import kotlinx.coroutines.launch
import org.schabi.newpipe.extractor.NewPipe

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var lastSentVideoId: String? = null

    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* continue regardless */ }

    private val playbackErrorReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != CarTubePlaybackService.ACTION_PLAYBACK_ERROR) return
            val msg = intent.getStringExtra(CarTubePlaybackService.EXTRA_ERROR_MESSAGE) ?: return
            Toast.makeText(this@MainActivity, msg, Toast.LENGTH_LONG).show()
            binding.statusText.text = msg
            lastSentVideoId = null
        }
    }

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
        binding.installBanner.setOnClickListener {
            startActivity(Intent(this, InstallGuideActivity::class.java))
        }
        if (savedInstanceState == null) {
            binding.webView.loadUrl("https://m.youtube.com")
        }
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter(CarTubePlaybackService.ACTION_PLAYBACK_ERROR)
        ContextCompat.registerReceiver(
            this,
            playbackErrorReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    override fun onStop() {
        unregisterReceiver(playbackErrorReceiver)
        super.onStop()
    }

    override fun onPause() {
        super.onPause()
        // Do not aggressively pause WebView while car playback runs; embedded video is silenced via JS.
        if (!PlaybackController.hasActivePlayback) {
            binding.webView.onPause()
        }
    }

    override fun onResume() {
        binding.webView.onResume()
        refreshInstallBanner()
        super.onResume()
    }

    private fun refreshInstallBanner() {
        val trusted = AaInstallTrust.isTrustedForTemplateVideo(this)
        binding.installBanner.visibility = android.view.View.VISIBLE
        binding.installBanner.text = if (trusted) {
            getString(R.string.install_banner_trusted)
        } else {
            getString(R.string.install_banner_untrusted)
        }
        binding.installBanner.setBackgroundColor(
            if (trusted) 0xFFE8F5E9.toInt() else 0xFFFFF3E0.toInt()
        )
        binding.installBanner.setTextColor(
            if (trusted) 0xFF2E7D32.toInt() else 0xFFE65100.toInt()
        )
        Log.i(AaInstallTrust.TAG, "[install] ${AaInstallTrust.modeDescription(this)}")
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
        ensureNotificationPermission()
        lastSentVideoId = videoId
        binding.statusText.text = getString(R.string.now_playing_on_car)
        CookieBridge.syncToNewPipe()
        WebViewPlaybackHelper.silenceEmbeddedPlayer(binding.webView)
        YoutubePlayback.requestPlayOnCar(applicationContext, videoId, title)
    }

    private fun ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            == PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}