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
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ImageButton
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.car.app.connection.CarConnection as AaCarConnection
import androidx.core.view.isVisible
import com.cartube.ytauto.databinding.ActivityMainBinding
import com.google.android.material.button.MaterialButton
import com.google.android.material.imageview.ShapeableImageView
import kotlinx.coroutines.launch
import org.schabi.newpipe.extractor.NewPipe
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var lastSentVideoId: String? = null
    private var handoffInFlight = false
    private var userSeeking = false
    private var suppressWebViewPlayHook = false
    private var lastAutoCarVideoId: String? = null
    private var lastAutoCarAtMs = 0L
    private val mainHandler = Handler(Looper.getMainLooper())
    private val playBridge = WebViewPlaybackHelper.PlayBridge { pos -> onWebViewVideoPlay(pos) }

    private val silenceRunnable = object : Runnable {
        override fun run() {
            if (PlaybackController.hasActivePlayback &&
                PlaybackController.playbackMode == PlaybackController.PlaybackMode.CAR
            ) {
                WebViewPlaybackHelper.silenceEmbeddedPlayer(binding.webView)
                binding.carRemoteOverlay.isVisible = true
            } else if (PlaybackController.hasActivePlayback &&
                PlaybackController.playbackMode == PlaybackController.PlaybackMode.PHONE_BACKGROUND
            ) {
                WebViewPlaybackHelper.silenceEmbeddedPlayer(binding.webView)
                binding.carRemoteOverlay.isVisible = false
            } else {
                binding.carRemoteOverlay.isVisible = false
            }
            mainHandler.postDelayed(this, 2_000L)
        }
    }
    private val tickRunnable = object : Runnable {
        override fun run() {
            refreshMiniPlayerUi()
            mainHandler.postDelayed(this, 500L)
        }
    }

    private lateinit var miniTitle: TextView
    private lateinit var miniChannel: TextView
    private lateinit var miniTime: TextView
    private lateinit var miniSeek: SeekBar
    private lateinit var miniThumb: ShapeableImageView
    private lateinit var miniPlayPause: MaterialButton

    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* continue regardless */ }

    private val playbackErrorReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != CarTubePlaybackService.ACTION_PLAYBACK_ERROR) return
            val msg = intent.getStringExtra(CarTubePlaybackService.EXTRA_ERROR_MESSAGE) ?: return
            Toast.makeText(this@MainActivity, msg, Toast.LENGTH_LONG).show()
            lastSentVideoId = null
        }
    }

    private val uiSyncListener = {
        refreshMiniPlayerUi()
        PlaybackClient.ensureConnected(applicationContext)
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        miniTitle = findViewById(R.id.miniTitle)
        miniChannel = findViewById(R.id.miniChannel)
        miniTime = findViewById(R.id.miniTime)
        miniSeek = findViewById(R.id.miniSeek)
        miniThumb = findViewById(R.id.miniThumb)
        miniPlayPause = findViewById(R.id.miniPlayPause)

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

        binding.toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }

        miniPlayPause.setOnClickListener {
            if (PlaybackClient.isPlaying()) PlaybackClient.pause() else PlaybackClient.play()
        }
        findViewById<MaterialButton>(R.id.seekBack10).setOnClickListener {
            PlaybackClient.seekBy(-10_000L)
        }
        findViewById<MaterialButton>(R.id.seekForward10).setOnClickListener {
            PlaybackClient.seekBy(10_000L)
        }

        miniSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                val duration = PlaybackClient.durationMs()
                if (duration > 0) {
                    PlaybackClient.seekTo(duration * progress / 1000L)
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                userSeeking = true
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                userSeeking = false
            }
        })

        val searchInput = binding.searchInput
        searchInput.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN) {
                performSearch(searchInput.text?.toString().orEmpty())
                true
            } else {
                false
            }
        }

        setupWebView()
        binding.installBanner.setOnClickListener {
            startActivity(Intent(this, InstallGuideActivity::class.java))
        }
        findViewById<ImageButton>(R.id.installBannerDismiss).setOnClickListener {
            CarTubePrefs.setInstallBannerDismissed(this)
            binding.installBannerCard.isVisible = false
        }

        if (savedInstanceState == null) {
            binding.webView.loadUrl("https://m.youtube.com")
        }
    }

    private fun onWebViewVideoPlay(positionMs: Long) {
        mainHandler.post {
            if (suppressWebViewPlayHook) return@post
            if (!CarConnection.shouldAutoRouteToCar) return@post
            val videoId = YouTubeUrlParser.extractVideoId(binding.webView.url.orEmpty()) ?: return@post
            routePlaybackToCar(videoId, pageTitle(), positionMs)
        }
    }

    private fun routePlaybackToCar(videoId: String, title: String, positionMs: Long) {
        if (suppressWebViewPlayHook) return
        val now = System.currentTimeMillis()
        if (videoId == lastAutoCarVideoId && now - lastAutoCarAtMs < 1_000) return

        if (videoId == PlaybackController.videoId && PlaybackController.hasActivePlayback &&
            PlaybackController.playbackMode == PlaybackController.PlaybackMode.CAR
        ) {
            val current = PlaybackClient.positionMs()
            if (kotlin.math.abs(current - positionMs) < 3_000 && PlaybackClient.isPlaying()) {
                return
            }
            PlaybackClient.seekTo(positionMs)
            PlaybackClient.play()
            lastSentVideoId = videoId
            return
        }

        ensureNotificationPermission()
        CookieBridge.syncToNewPipe()
        suppressWebViewPlayHook = true
        WebViewPlaybackHelper.silenceEmbeddedPlayer(binding.webView)
        mainHandler.postDelayed({ suppressWebViewPlayHook = false }, 1_500)

        lastAutoCarVideoId = videoId
        lastAutoCarAtMs = now
        lastSentVideoId = videoId
        YoutubePlayback.requestPlayOnCar(applicationContext, videoId, title, positionMs)
        ThumbnailLoader.loadYouTubeThumbnail(videoId, miniThumb)
        binding.carRemoteOverlay.isVisible = true
        binding.remoteTitle.text = title
        Log.i(CarTubePlaybackService.TAG, "[auto-car] video=$videoId pos=$positionMs session=active")
    }

    private fun performSearch(query: String) {
        val q = query.trim()
        if (q.isEmpty()) return
        val encoded = URLEncoder.encode(q, StandardCharsets.UTF_8.toString())
        binding.webView.loadUrl("https://m.youtube.com/results?search_query=$encoded")
    }

    override fun onStart() {
        super.onStart()
        AaCarConnection(this).type.observe(this) { state ->
            CarConnection.setProjectionActive(
                state == AaCarConnection.CONNECTION_TYPE_PROJECTION
            )
        }
        PlaybackClient.connect(applicationContext)
        PlaybackController.addPlaybackListener(uiSyncListener)
        PlaybackClient.addUiListener(uiSyncListener)
        val filter = IntentFilter(CarTubePlaybackService.ACTION_PLAYBACK_ERROR)
        ContextCompat.registerReceiver(
            this,
            playbackErrorReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        mainHandler.post(tickRunnable)
        mainHandler.post(silenceRunnable)
    }

    override fun onStop() {
        mainHandler.removeCallbacks(tickRunnable)
        mainHandler.removeCallbacks(silenceRunnable)
        PlaybackClient.removeUiListener(uiSyncListener)
        PlaybackController.removePlaybackListener(uiSyncListener)
        PlaybackClient.disconnect(applicationContext)
        unregisterReceiver(playbackErrorReceiver)
        if (!handoffInFlight && !PlaybackController.hasActivePlayback) {
            binding.webView.onPause()
        }
        super.onStop()
    }

    override fun onPause() {
        attemptWebViewBackgroundHandoff()
        super.onPause()
    }

    override fun onResume() {
        binding.webView.onResume()
        refreshInstallBanner()
        refreshMiniPlayerUi()
        super.onResume()
    }

    private fun attemptWebViewBackgroundHandoff() {
        if (CarConnection.shouldAutoRouteToCar) return
        if (PlaybackController.hasActivePlayback || handoffInFlight) return

        val videoId = YouTubeUrlParser.extractVideoId(binding.webView.url.orEmpty()) ?: run {
            binding.webView.onPause()
            return
        }
        handoffInFlight = true
        WebViewPlaybackHelper.readEmbeddedVideoState(binding.webView) { state ->
            handoffInFlight = false
            if (state.playing) {
                Log.i(
                    CarTubePlaybackService.TAG,
                    "[handoff] phone background video=$videoId pos=${state.positionMs}"
                )
                ensureNotificationPermission()
                CookieBridge.syncToNewPipe()
                WebViewPlaybackHelper.silenceEmbeddedPlayer(binding.webView)
                val title = pageTitle()
                YoutubePlayback.requestPhoneBackgroundPlay(
                    applicationContext,
                    videoId,
                    title,
                    state.positionMs
                )
                lastSentVideoId = videoId
            } else {
                binding.webView.onPause()
            }
        }
    }

    private fun refreshInstallBanner() {
        if (CarTubePrefs.isInstallBannerDismissed(this)) {
            binding.installBannerCard.isVisible = false
            return
        }
        val trusted = AaInstallTrust.isTrustedForTemplateVideo(this)
        binding.installBannerCard.isVisible = true
        binding.installBanner.text = if (trusted) {
            getString(R.string.install_banner_trusted)
        } else {
            getString(R.string.install_banner_untrusted)
        }
        binding.installBannerCard.setCardBackgroundColor(
            ContextCompat.getColor(
                this,
                if (trusted) R.color.banner_ok else R.color.banner_warn
            )
        )
        binding.installBanner.setTextColor(
            ContextCompat.getColor(
                this,
                if (trusted) R.color.banner_ok_text else R.color.banner_warn_text
            )
        )
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        val webView = binding.webView
        WebViewPlaybackHelper.installPlayBridge(webView, playBridge)
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
                binding.progressBar.isVisible = newProgress in 1..99
                binding.progressBar.progress = newProgress
            }
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val url = request.url.toString()
                return !(url.startsWith("http://") || url.startsWith("https://"))
            }

            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest
            ) = YouTubeAdBlocker.intercept(request) ?: super.shouldInterceptRequest(view, request)

            override fun onPageFinished(view: WebView, url: String) {
                CookieBridge.syncToNewPipe()
                WebViewPlaybackHelper.injectPageHooks(view)
                val videoId = YouTubeUrlParser.extractVideoId(url)
                if (videoId == null) {
                    lastSentVideoId = null
                }
                if (CarConnection.shouldAutoRouteToCar && videoId != null) {
                    WebViewPlaybackHelper.silenceEmbeddedPlayer(view)
                }
            }
        }
    }

    private fun pageTitle(): String = binding.webView.title?.substringBefore(" - YouTube").orEmpty()
        .ifBlank { "YouTube" }

    private fun refreshMiniPlayerUi() {
        val active = PlaybackController.hasActivePlayback
        val title = PlaybackController.title
        val videoId = PlaybackController.videoId
        findViewById<com.google.android.material.card.MaterialCardView>(R.id.miniPlayer)
            ?.isVisible = active

        if (active && title.isNotBlank()) {
            miniTitle.text = title
            miniChannel.text = when (PlaybackController.playbackMode) {
                PlaybackController.PlaybackMode.CAR -> getString(R.string.notification_playing_on_car)
                PlaybackController.PlaybackMode.PHONE_BACKGROUND -> getString(R.string.notification_playing_phone)
            }
            if (videoId.isNotBlank()) {
                ThumbnailLoader.loadYouTubeThumbnail(videoId, miniThumb)
            }
            binding.remoteTitle.text = title
        }

        if (!active) return

        val playing = PlaybackClient.isPlaying()
        miniPlayPause.setIconResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play)

        val duration = PlaybackClient.durationMs()
        val position = PlaybackClient.positionMs()
        if (!userSeeking && duration > 0) {
            miniSeek.progress = ((position * 1000L) / duration).toInt().coerceIn(0, 1000)
        }
        miniTime.text = "${formatTime(position)} / ${formatTime(duration)}"
    }

    private fun formatTime(ms: Long): String {
        if (ms <= 0) return "0:00"
        val totalSec = TimeUnit.MILLISECONDS.toSeconds(ms)
        val min = totalSec / 60
        val sec = totalSec % 60
        return String.format(Locale.US, "%d:%02d", min, sec)
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