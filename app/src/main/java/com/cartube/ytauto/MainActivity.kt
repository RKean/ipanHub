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
import android.text.Editable
import android.text.TextWatcher
import android.view.KeyEvent
import android.view.View
import android.widget.ImageButton
import android.widget.SeekBar
import android.widget.TextView
import android.content.ClipData
import android.content.ClipboardManager
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.car.app.connection.CarConnection as AaCarConnection
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.cartube.ytauto.databinding.ActivityMainBinding
import com.cartube.ytauto.BuildConfig
import com.cartube.ytauto.queue.QueueController
import com.cartube.ytauto.queue.QueueItem
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.imageview.ShapeableImageView
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.NewPipe
import java.util.Locale
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val scope = CoroutineScope(Dispatchers.Main)
    private var searchJob: Job? = null
    private var suggestJob: Job? = null
    private var suggestSeq = 0
    private var userSeeking = false
    private var showingQueue = false
    private val mainHandler = Handler(Looper.getMainLooper())

    private lateinit var searchAdapter: SearchResultsAdapter
    private lateinit var queueAdapter: QueueListAdapter
    private lateinit var suggestionsAdapter: SuggestionsAdapter

    private val miniTitle: TextView by lazy { findViewById(R.id.miniTitle) }
    private val miniChannel: TextView by lazy { findViewById(R.id.miniChannel) }
    private val miniTime: TextView by lazy { findViewById(R.id.miniTime) }
    private val miniSeek: SeekBar by lazy { findViewById(R.id.miniSeek) }
    private val miniThumb: ShapeableImageView by lazy { findViewById(R.id.miniThumb) }
    private val miniPlayPause: MaterialButton by lazy { findViewById(R.id.miniPlayPause) }

    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    private val queueListener = { refreshQueueUi(); refreshMiniPlayerUi() }
    private val uiSyncListener = {
        refreshMiniPlayerUi()
        PlaybackClient.ensureConnected(applicationContext)
    }

    private val tickRunnable = object : Runnable {
        override fun run() {
            refreshMiniPlayerUi()
            mainHandler.postDelayed(this, 500L)
        }
    }

    private val errorReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                CarTubePlaybackService.ACTION_PLAYBACK_ERROR -> {
                    val msg = intent.getStringExtra(CarTubePlaybackService.EXTRA_ERROR_MESSAGE) ?: return
                    Snackbar.make(binding.root, msg, Snackbar.LENGTH_LONG).show()
                }
                CarTubePlaybackService.ACTION_PLAYBACK_NOTICE -> {
                    val msg = intent.getStringExtra(CarTubePlaybackService.EXTRA_NOTICE_MESSAGE) ?: return
                    Snackbar.make(binding.root, msg, Snackbar.LENGTH_SHORT).show()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        QueueController.init(this)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        scope.launch(Dispatchers.IO) {
            CookieBridge.syncToNewPipe()
            NewPipe.init(NewPipeDownloader.getInstance())
        }

        val cookieHost = binding.cookieWebViewHost
        val cookieWebView = CookieWebView(this)
        cookieHost.addView(cookieWebView)
        cookieWebView.warmUp()

        setupLists()
        setupSuggestions()
        setupSearch()
        setupMiniPlayer()
        setupTabs()
        setupToolbar()
        refreshInstallBanner()
        handleShareIntent(intent)
    }

    private fun setupLists() {
        searchAdapter = SearchResultsAdapter(
            onPlayNow = { result ->
                ensureNotificationPermission()
                QueuePlayback.playNow(this, QueuePlayback.fromSearchResult(result))
                RecentSearchStore.add(this, binding.searchInput.text?.toString().orEmpty())
            },
            onPlayNext = { result ->
                StreamCache.warmResolve(result.videoId)
                QueuePlayback.playNextInQueue(this, QueuePlayback.fromSearchResult(result))
                Toast.makeText(this, R.string.added_play_next, Toast.LENGTH_SHORT).show()
            },
            onAddQueue = { result ->
                StreamCache.warmResolve(result.videoId)
                QueuePlayback.addToQueue(this, QueuePlayback.fromSearchResult(result))
                Toast.makeText(this, R.string.added_to_queue, Toast.LENGTH_SHORT).show()
            }
        )
        queueAdapter = QueueListAdapter(
            onJump = { index -> QueuePlayback.jumpTo(this, index) },
            onRemove = { index -> QueueController.removeAt(index) }
        )
        binding.searchList.layoutManager = LinearLayoutManager(this)
        binding.searchList.adapter = searchAdapter
        binding.queueList.layoutManager = LinearLayoutManager(this)
        binding.queueList.adapter = queueAdapter
        ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN,
            0
        ) {
            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean {
                val from = viewHolder.bindingAdapterPosition
                val to = target.bindingAdapterPosition
                QueueController.move(from, to)
                return true
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) = Unit
        }).attachToRecyclerView(binding.queueList)
    }

    private fun setupSuggestions() {
        suggestionsAdapter = SuggestionsAdapter(
            onPick = { query ->
                binding.searchInput.setText(query)
                hideSuggestions()
                runSearch(query)
            },
            onFill = { query ->
                binding.searchInput.setText(query)
                binding.searchInput.setSelection(query.length)
                requestSuggestions(query)
            },
            onRemove = { query ->
                RecentSearchStore.remove(this, query)
                showRecentIfEmptyField()
            }
        )
        binding.suggestionsList.layoutManager = LinearLayoutManager(this)
        binding.suggestionsList.adapter = suggestionsAdapter
    }

    private fun setupSearch() {
        val input = binding.searchInput
        input.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus && input.text.isNullOrBlank()) showRecentIfEmptyField()
            if (!hasFocus) hideSuggestions()
        }
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                val text = s?.toString().orEmpty()
                if (text.isBlank()) showRecentIfEmptyField() else requestSuggestions(text)
            }
        })
        input.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN) {
                val q = input.text?.toString().orEmpty()
                hideSuggestions()
                runSearch(q)
                true
            } else false
        }
    }

    private fun showRecentIfEmptyField() {
        if (!binding.searchInput.hasFocus()) return
        val recent = RecentSearchStore.list(this)
        suggestionsAdapter.showRecentRemove = true
        suggestionsAdapter.setSuggestions(recent)
        binding.suggestionsCard.isVisible = recent.isNotEmpty()
    }

    private fun requestSuggestions(prefix: String) {
        suggestJob?.cancel()
        val seq = ++suggestSeq
        suggestJob = scope.launch {
            delay(250)
            if (seq != suggestSeq) return@launch
            val list = withContext(Dispatchers.IO) { YoutubeSuggestions.fetch(prefix) }
            if (seq != suggestSeq) return@launch
            suggestionsAdapter.showRecentRemove = false
            suggestionsAdapter.setSuggestions(list)
            binding.suggestionsCard.isVisible = list.isNotEmpty()
        }
    }

    private fun hideSuggestions() {
        binding.suggestionsCard.isVisible = false
        suggestJob?.cancel()
    }

    private fun runSearch(query: String) {
        val q = query.trim()
        if (q.isEmpty()) return
        hideSuggestions()
        RecentSearchStore.add(this, q)
        searchJob?.cancel()
        if (showingQueue) showSearchTab()
        binding.searchLoading.isVisible = true
        binding.emptyLabel.isVisible = false
        searchJob = scope.launch {
            val results = withContext(Dispatchers.IO) { YoutubeSearch.search(q) }
            binding.searchLoading.isVisible = false
            searchAdapter.setResults(results)
            binding.emptyLabel.isVisible = results.isEmpty()
            binding.emptyLabel.text = if (results.isEmpty()) {
                getString(R.string.search_no_results)
            } else {
                ""
            }
        }
    }

    private fun setupMiniPlayer() {
        miniPlayPause.setOnClickListener {
            if (PlaybackClient.isPlaying()) PlaybackClient.pause() else PlaybackClient.play()
        }
        findViewById<MaterialButton>(R.id.seekBack10).setOnClickListener {
            PlaybackClient.seekBy(-10_000L)
        }
        findViewById<MaterialButton>(R.id.seekForward10).setOnClickListener {
            PlaybackClient.seekBy(10_000L)
        }
        findViewById<MaterialButton>(R.id.queuePrevious).setOnClickListener {
            QueuePlayback.skipPrevious(this)
        }
        findViewById<MaterialButton>(R.id.queueNext).setOnClickListener {
            QueuePlayback.skipNext(this)
        }
        miniSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val d = PlaybackClient.durationMs()
                    if (d > 0) PlaybackClient.seekTo(d * progress / 1000L)
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) { userSeeking = true }
            override fun onStopTrackingTouch(seekBar: SeekBar?) { userSeeking = false }
        })
    }

    private fun setupTabs() {
        binding.chipSearch.setOnClickListener { showSearchTab() }
        binding.chipQueue.setOnClickListener { showQueueTab() }
    }

    private fun showSearchTab() {
        showingQueue = false
        binding.searchList.isVisible = true
        binding.queueList.isVisible = false
        binding.chipSearch.isChecked = true
        hideSuggestions()
    }

    private fun showQueueTab() {
        showingQueue = true
        binding.searchList.isVisible = false
        binding.queueList.isVisible = true
        binding.chipQueue.isChecked = true
        hideSuggestions()
        refreshQueueUi()
    }

    private fun setupToolbar() {
        binding.toolbar.setOnMenuItemClickListener {
            when (it.itemId) {
                R.id.action_about -> {
                    Toast.makeText(
                        this,
                        "${getString(R.string.about_title)} · ${BuildConfig.VERSION_NAME}",
                        Toast.LENGTH_LONG
                    ).show()
                    true
                }
                R.id.action_clear_queue -> {
                    QueueController.clear()
                    true
                }
                R.id.action_install_guide -> {
                    startActivity(Intent(this, InstallGuideActivity::class.java))
                    true
                }
                R.id.action_copy_logs -> {
                    copyDebugLogs()
                    true
                }
                else -> false
            }
        }
        binding.installBanner.setOnClickListener {
            startActivity(Intent(this, InstallGuideActivity::class.java))
        }
        findViewById<ImageButton>(R.id.installBannerDismiss).setOnClickListener {
            CarTubePrefs.setInstallBannerDismissed(this)
            binding.installBannerCard.isVisible = false
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShareIntent(intent)
    }

    private fun copyDebugLogs() {
        val text = CarTubeLog.dump()
        val cm = getSystemService(ClipboardManager::class.java)
        cm.setPrimaryClip(ClipData.newPlainText(getString(R.string.debug_logs_clip), text))
        val share = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        startActivity(Intent.createChooser(share, getString(R.string.debug_logs_share)))
    }

    private fun handleShareIntent(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return
        val id = YouTubeUrlParser.extractVideoId(text) ?: return
        QueuePlayback.playNow(this, QueueItem(id, "YouTube"))
        Snackbar.make(binding.root, R.string.link_added, Snackbar.LENGTH_SHORT).show()
    }

    override fun onStart() {
        super.onStart()
        AaCarConnection(this).type.observe(this) { state ->
            CarConnection.setProjectionActive(state == AaCarConnection.CONNECTION_TYPE_PROJECTION)
        }
        PlaybackClient.connect(applicationContext)
        QueueController.addListener(queueListener)
        PlaybackController.addPlaybackListener(uiSyncListener)
        PlaybackClient.addUiListener(uiSyncListener)
        val filter = IntentFilter().apply {
            addAction(CarTubePlaybackService.ACTION_PLAYBACK_ERROR)
            addAction(CarTubePlaybackService.ACTION_PLAYBACK_NOTICE)
        }
        ContextCompat.registerReceiver(this, errorReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        mainHandler.post(tickRunnable)
    }

    override fun onStop() {
        mainHandler.removeCallbacks(tickRunnable)
        QueueController.removeListener(queueListener)
        PlaybackClient.removeUiListener(uiSyncListener)
        PlaybackController.removePlaybackListener(uiSyncListener)
        unregisterReceiver(errorReceiver)
        super.onStop()
    }

    private fun refreshQueueUi() {
        queueAdapter.setQueue(QueueController.allItems(), QueueController.currentIndex())
        binding.emptyLabel.isVisible = showingQueue && QueueController.allItems().isEmpty()
        if (showingQueue && QueueController.allItems().isEmpty()) {
            binding.emptyLabel.text = getString(R.string.queue_empty)
            binding.emptyLabel.isVisible = true
        }
    }

    private fun refreshMiniPlayerUi() {
        val active = PlaybackController.hasActivePlayback
        findViewById<View>(R.id.miniPlayer).isVisible = active || QueueController.allItems().isNotEmpty()
        val title = PlaybackController.title
        val videoId = PlaybackController.videoId
        if (PlaybackController.isLoading) {
            miniTitle.text = getString(R.string.loading_title, PlaybackController.loadingTitle)
            miniChannel.text = getString(R.string.loading_subtitle)
        } else if (active && title.isNotBlank()) {
            miniTitle.text = title
            miniChannel.text = when (PlaybackController.playbackMode) {
                PlaybackController.PlaybackMode.CAR -> getString(R.string.notification_playing_on_car)
                PlaybackController.PlaybackMode.PHONE_BACKGROUND -> getString(R.string.notification_playing_phone)
            }
            if (videoId.isNotBlank()) ThumbnailLoader.loadYouTubeThumbnail(videoId, miniThumb)
        } else if (QueueController.currentItem() != null) {
            val item = QueueController.currentItem()!!
            miniTitle.text = item.title
            miniChannel.text = item.channel
            ThumbnailLoader.loadYouTubeThumbnail(item.videoId, miniThumb)
        } else {
            miniTitle.text = getString(R.string.nothing_playing)
            miniChannel.text = getString(R.string.mini_player_subtitle)
        }
        val queuePrev = findViewById<MaterialButton>(R.id.queuePrevious)
        val queueNext = findViewById<MaterialButton>(R.id.queueNext)
        queuePrev.isEnabled = QueueController.hasPrevious()
        queueNext.isEnabled = QueueController.hasNext()
        queuePrev.alpha = if (queuePrev.isEnabled) 1f else 0.38f
        queueNext.alpha = if (queueNext.isEnabled) 1f else 0.38f
        if (!active && !PlaybackController.isLoading) return
        miniPlayPause.setIconResource(
            if (PlaybackClient.isPlaying()) R.drawable.ic_pause else R.drawable.ic_play
        )
        val duration = PlaybackClient.durationMs()
        val position = PlaybackClient.positionMs()
        if (!userSeeking && duration > 0) {
            miniSeek.progress = ((position * 1000L) / duration).toInt().coerceIn(0, 1000)
        }
        miniTime.text = "${formatTime(position)} / ${formatTime(duration)}"
    }

    private fun refreshInstallBanner() {
        if (CarTubePrefs.isInstallBannerDismissed(this)) {
            binding.installBannerCard.isVisible = false
            return
        }
        val trusted = AaInstallTrust.isTrustedForTemplateVideo(this)
        binding.installBannerCard.isVisible = true
        binding.installBanner.text = getString(
            if (trusted) R.string.install_banner_trusted else R.string.install_banner_untrusted
        )
        binding.installBannerCard.setCardBackgroundColor(
            ContextCompat.getColor(this, if (trusted) R.color.banner_ok else R.color.banner_warn)
        )
        binding.installBanner.setTextColor(
            ContextCompat.getColor(this, if (trusted) R.color.banner_ok_text else R.color.banner_warn_text)
        )
    }

    private fun formatTime(ms: Long): String {
        if (ms <= 0) return "0:00"
        val totalSec = TimeUnit.MILLISECONDS.toSeconds(ms)
        return String.format(Locale.US, "%d:%02d", totalSec / 60, totalSec % 60)
    }

    private fun ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            == PackageManager.PERMISSION_GRANTED
        ) return
        notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}