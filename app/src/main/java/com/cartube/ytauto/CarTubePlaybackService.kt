package com.cartube.ytauto

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.cartube.ytauto.queue.QueueController
import com.cartube.ytauto.queue.QueueItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import java.io.IOException

/** Foreground [MediaSessionService] — single ExoPlayer, queue-driven. */
class CarTubePlaybackService : MediaSessionService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var playJob: Job? = null
    private var prefetchJob: Job? = null
    private var bufferingWatchdog: Job? = null
    private var hostPlayer: ExoPlayer? = null
    private var mediaSession: MediaSession? = null
    private var resolvingVideoId: String? = null
    private var lastFailedVideoId: String? = null
    private var lastErrorBroadcastMs = 0L
    private var currentStreamKind: YoutubeStreamResolver.StreamKind? = null
  @Volatile
    private var isBuffering = false
    private var bufferingSinceMs = 0L
    private var lastPlayheadMs = 0L

    private val sessionCallback = object : MediaSession.Callback {
        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo
        ): MediaSession.ConnectionResult {
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session).build()
        }
    }

    override fun onCreate() {
        super.onCreate()
        QueueController.init(this)
        createNotificationChannel()
        ensureSessionAndPlayer()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        ensureSessionAndPlayer()
        return mediaSession
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_STOP -> {
                stopPlayback()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_MEDIA_PAUSE -> {
                hostPlayer?.pause()
                updateNotification()
            }
            ACTION_MEDIA_PLAY -> {
                hostPlayer?.play()
                updateNotification()
            }
            ACTION_SKIP_NEXT -> skipQueue(1)
            ACTION_SKIP_PREVIOUS -> skipQueue(-1)
            ACTION_PLAY_QUEUE_CURRENT -> {
                val force = intent.getBooleanExtra(EXTRA_FORCE_RESTART, false)
                val item = QueueController.currentItem() ?: return START_NOT_STICKY
                if (!force && shouldNoOpForCurrent(item.videoId)) return START_STICKY
                beginForeground(item.title)
                PlaybackController.setLoading(item.title)
                startQueueItemJob(item, if (force) 0L else hostPlayer?.currentPosition ?: 0L, force)
            }
            ACTION_START_VIDEO -> {
                val videoId = intent.getStringExtra(EXTRA_VIDEO_ID) ?: return START_NOT_STICKY
                val title = intent.getStringExtra(EXTRA_TITLE).orEmpty()
                val item = QueueItem(videoId, title.ifBlank { "YouTube" })
                QueueController.playNow(item)
                beginForeground(item.title)
                PlaybackController.setLoading(item.title)
                startQueueItemJob(item, intent.getLongExtra(EXTRA_START_POSITION_MS, 0L), force = true)
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        playJob?.cancel()
        prefetchJob?.cancel()
        bufferingWatchdog?.cancel()
        serviceScope.cancel()
        stopPlayback()
        super.onDestroy()
    }

    private fun shouldNoOpForCurrent(videoId: String): Boolean {
        if (resolvingVideoId == videoId) {
            Log.i(TAG, "[service] no-op — already loading $videoId")
            return true
        }
        if (videoId != PlaybackController.videoId) return false
        val p = hostPlayer ?: return false
        return when (p.playbackState) {
            Player.STATE_BUFFERING -> true
            Player.STATE_READY -> {
                if (!p.isPlaying) p.play()
                true
            }
            else -> false
        }
    }

    private fun skipQueue(delta: Int) {
        prefetchJob?.cancel()
        val item = when {
            delta > 0 -> QueueController.advanceToNext()
            else -> QueueController.backToPrevious()
        } ?: return
        beginForeground(item.title)
        PlaybackController.setLoading(item.title)
        startQueueItemJob(item, 0L, force = true)
    }

    private fun ensureSessionAndPlayer() {
        if (hostPlayer != null && mediaSession != null) return
        val trackSelector = DefaultTrackSelector(this).apply {
            setParameters(
                buildUponParameters()
                    .setMaxVideoSize(1280, 720)
                    .setMaxVideoBitrate(5_000_000)
                    .build()
            )
        }
        val exo = ExoPlayer.Builder(this)
            .setTrackSelector(trackSelector)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
        exo.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                .build(),
            true
        )
        exo.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                val detail = error.cause?.message ?: error.message
                Log.e(TAG, "[service] ExoPlayer error: $detail", error)
                tryFallbackOrSkip()
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                logPlayerState("isPlaying=$isPlaying")
                updateNotification()
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                isBuffering = playbackState == Player.STATE_BUFFERING
                logPlayerState("state=$playbackState")
                when (playbackState) {
                    Player.STATE_BUFFERING -> {
                        bufferingSinceMs = System.currentTimeMillis()
                        lastPlayheadMs = exo.currentPosition
                        startBufferingWatchdog()
                    }
                    Player.STATE_READY -> {
                        cancelBufferingWatchdog()
                        PlaybackController.clearLoading()
                        schedulePrefetchAfterReady()
                    }
                    Player.STATE_ENDED -> onTrackEnded()
                }
                updateNotification()
            }
        })
        exo.addAnalyticsListener(object : AnalyticsListener {
            override fun onLoadError(
                eventTime: AnalyticsListener.EventTime,
                loadEventInfo: LoadEventInfo,
                mediaLoadData: MediaLoadData,
                error: IOException,
                wasCanceled: Boolean
            ) {
                Log.e(
                    TAG,
                    "[load] uri=${loadEventInfo.uri} bytes=${loadEventInfo.bytesLoaded} err=${error.message}"
                )
            }

            override fun onIsLoadingChanged(
                eventTime: AnalyticsListener.EventTime,
                isLoading: Boolean
            ) {
                Log.d(TAG, "[load] isLoading=$isLoading")
            }
        })
        hostPlayer = exo
        mediaSession = MediaSession.Builder(this, exo)
            .setId(TAG)
            .setCallback(sessionCallback)
            .build()
        Log.i(TAG, "[service] MediaSession ready")
    }

    private fun logPlayerState(extra: String) {
        val p = hostPlayer ?: return
        Log.i(
            TAG,
            "[player] $extra pos=${p.currentPosition} buffered=${p.bufferedPosition} speed=${p.playbackParameters.speed}"
        )
    }

    private fun startBufferingWatchdog() {
        bufferingWatchdog?.cancel()
        bufferingWatchdog = serviceScope.launch {
            delay(7_000L)
            val p = hostPlayer ?: return@launch
            if (p.playbackState != Player.STATE_BUFFERING) return@launch
            val progressed = p.currentPosition > lastPlayheadMs + 500
            if (progressed) return@launch
            Log.w(TAG, "[service] buffering watchdog — trying alternate stream")
            tryFallbackOrSkip()
        }
    }

    private fun cancelBufferingWatchdog() {
        bufferingWatchdog?.cancel()
        bufferingWatchdog = null
    }

    private fun tryFallbackOrSkip() {
        val item = QueueController.currentItem() ?: run {
            stopSelf()
            return
        }
        val kind = currentStreamKind
        StreamCache.invalidate(item.videoId)
        val skip = buildSet {
            kind?.let { add(it) }
        }
        if (skip.size < YoutubeStreamResolver.StreamKind.entries.size) {
            Log.i(TAG, "[service] fallback resolve skip=$skip")
            cancelBufferingWatchdog()
            playJob?.cancel()
            playJob = serviceScope.launch {
                resolvingVideoId = item.videoId
                try {
                    val resolved = withContext(Dispatchers.IO) {
                        YoutubeStreamResolver.resolveVideoId(
                            item.videoId,
                            applyWebViewCookies = true,
                            preference = YoutubeStreamResolver.ResolvePreference.FAST_START,
                            skipKinds = skip
                        )
                    }
                    when (resolved) {
                        is YoutubeStreamResolver.ResolveResult.Ok -> {
                            StreamCache.put(item.videoId, resolved.stream)
                            val mode = playbackMode()
                            playResolvedStream(resolved.stream, item, mode, 0L)
                        }
                        else -> skipAfterError("Skipped — couldn't load video")
                    }
                } finally {
                    resolvingVideoId = null
                }
            }
        } else {
            skipAfterError("Skipped — playback stalled")
        }
    }

    private fun onTrackEnded() {
        val next = QueueController.advanceToNext()
        if (next != null) {
            PlaybackController.setLoading(next.title)
            startQueueItemJob(next, 0L, force = true)
        } else {
            Log.i(TAG, "[service] queue ended")
        }
    }

    private fun skipAfterError(notice: String) {
        broadcastNotice(notice)
        PlaybackController.clearLoading()
        StreamCache.invalidate(PlaybackController.videoId)
        val next = QueueController.advanceToNext()
        if (next != null) {
            PlaybackController.setLoading(next.title)
            startQueueItemJob(next, 0L, force = true)
        } else {
            stopSelf()
        }
    }

    private fun startQueueItemJob(item: QueueItem, seekMs: Long, force: Boolean) {
        prefetchJob?.cancel()
        playJob?.cancel()
        playJob = serviceScope.launch {
            resolvingVideoId = item.videoId
            val mode = playbackMode()
            try {
                preparePlayerForSwitch()
                val cached = if (!force) StreamCache.peek(item.videoId) else null
                val resolved = if (cached != null) {
                    YoutubeStreamResolver.ResolveResult.Ok(cached)
                } else {
                    withContext(Dispatchers.IO) {
                        YoutubeStreamResolver.resolveVideoId(
                            item.videoId,
                            applyWebViewCookies = true,
                            preference = YoutubeStreamResolver.ResolvePreference.FAST_START
                        )
                    }
                }
                when (resolved) {
                    is YoutubeStreamResolver.ResolveResult.Err -> {
                        Log.e(TAG, "[service] ${resolved.detail}", resolved.cause)
                        skipAfterError("Skipped — couldn't load video")
                        return@launch
                    }
                    is YoutubeStreamResolver.ResolveResult.Ok -> {
                        StreamCache.put(item.videoId, resolved.stream)
                        playResolvedStream(resolved.stream, item, mode, seekMs)
                    }
                }
            } catch (_: CancellationException) {
                Log.i(TAG, "[service] cancelled ${item.videoId}")
            } catch (e: Exception) {
                Log.e(TAG, "[service] playback failed", e)
                skipAfterError("Skipped — error")
            } finally {
                if (resolvingVideoId == item.videoId) resolvingVideoId = null
            }
        }
    }

    private fun playbackMode(): PlaybackController.PlaybackMode =
        if (CarConnection.shouldAutoRouteToCar) {
            PlaybackController.PlaybackMode.CAR
        } else {
            PlaybackController.PlaybackMode.PHONE_BACKGROUND
        }

    private fun preparePlayerForSwitch() {
        hostPlayer?.let { p ->
            p.stop()
            p.clearMediaItems()
        }
    }

    private fun schedulePrefetchAfterReady() {
        prefetchJob?.cancel()
        prefetchJob = serviceScope.launch {
            delay(400L)
            val next = QueueController.nextItem() ?: return@launch
            val current = QueueController.currentItem()?.videoId
            if (next.videoId == current) return@launch
            if (StreamCache.peek(next.videoId) != null) return@launch
            Log.i(TAG, "[service] prefetch queue+1 ${next.videoId}")
            withContext(Dispatchers.IO) {
                when (val r = YoutubeStreamResolver.resolveVideoId(next.videoId, applyWebViewCookies = true)) {
                    is YoutubeStreamResolver.ResolveResult.Ok -> StreamCache.put(next.videoId, r.stream)
                    else -> Log.w(TAG, "[service] prefetch miss ${next.videoId}")
                }
            }
        }
    }

    private suspend fun playResolvedStream(
        stream: YoutubeStreamResolver.ResolvedStream,
        item: QueueItem,
        mode: PlaybackController.PlaybackMode,
        startPositionMs: Long
    ) {
        currentStreamKind = stream.kind
        Log.i(TAG, "[service] play ${item.videoId} mode=$mode via=${stream.via}")
        ensureSessionAndPlayer()
        val exo = hostPlayer ?: return

        val cookies = NewPipeDownloader.getInstance().cookies
        val userAgent = NewPipeDownloader.DEFAULT_USER_AGENT
        val okHttp = OkHttpClient.Builder().build()
        val dataSourceFactory = OkHttpDataSource.Factory(okHttp).setDefaultRequestProperties(
            buildMap {
                put("User-Agent", userAgent)
                put("Referer", "https://www.youtube.com/")
                put("Origin", "https://www.youtube.com")
                if (cookies.isNotBlank()) put("Cookie", cookies)
            }
        )

        val mediaSource = YoutubePlayback.buildMediaSource(dataSourceFactory, stream)
        val surfaceReady = if (mode == PlaybackController.PlaybackMode.CAR) {
            CompletableDeferred<Unit>()
        } else {
            null
        }
        PlaybackController.attachPlayer(exo, item.title, item.videoId, mode, surfaceReady)

        if (mode == PlaybackController.PlaybackMode.CAR) {
            withTimeoutOrNull(4_000L) { surfaceReady?.await() }
        }

        exo.setMediaSource(mediaSource)
        exo.prepare()
        if (startPositionMs > 0) exo.seekTo(startPositionMs)
        exo.play()
        updateNotification()
    }

    private fun stopPlayback() {
        playJob?.cancel()
        playJob = null
        prefetchJob?.cancel()
        cancelBufferingWatchdog()
        resolvingVideoId = null
        PlaybackController.detachAndRelease()
        mediaSession?.release()
        mediaSession = null
        hostPlayer = null
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
    }

    private fun beginForeground(title: String) {
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(title.ifBlank { getString(R.string.app_name) }, false),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        )
    }

    private fun updateNotification() {
        val title = when {
            PlaybackController.isLoading -> getString(R.string.loading_title, PlaybackController.loadingTitle)
            else -> PlaybackController.title.ifBlank { getString(R.string.app_name) }
        }
        val playing = hostPlayer?.isPlaying == true
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(title, playing))
    }

    private fun notificationBody(): String {
        val buffer = when {
            PlaybackController.isLoading -> ""
            isBuffering -> " · Buffering…"
            else -> ""
        }
        val base = when (PlaybackController.playbackMode) {
            PlaybackController.PlaybackMode.PHONE_BACKGROUND -> getString(R.string.notification_playing_phone)
            PlaybackController.PlaybackMode.CAR -> getString(R.string.notification_playing_on_car)
        }
        return base + buffer
    }

    private fun broadcastErrorOnce(videoId: String, message: String) {
        if (message.isBlank()) return
        val now = System.currentTimeMillis()
        if (videoId == lastFailedVideoId && now - lastErrorBroadcastMs < 8_000) return
        lastFailedVideoId = videoId
        lastErrorBroadcastMs = now
        sendBroadcast(
            Intent(ACTION_PLAYBACK_ERROR).setPackage(packageName)
                .putExtra(EXTRA_ERROR_MESSAGE, message)
        )
    }

    private fun broadcastNotice(message: String) {
        sendBroadcast(
            Intent(ACTION_PLAYBACK_NOTICE).setPackage(packageName)
                .putExtra(EXTRA_NOTICE_MESSAGE, message)
        )
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(title: String, playing: Boolean): Notification {
        val contentIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val toggle = if (playing) ACTION_MEDIA_PAUSE else ACTION_MEDIA_PLAY
        val actionIcon = if (playing) R.drawable.ic_pause else R.drawable.ic_play
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(notificationBody())
            .setContentIntent(contentIntent)
            .setOngoing(playing || PlaybackController.isLoading)
            .setOnlyAlertOnce(true)
            .addAction(
                actionIcon, if (playing) "Pause" else "Play",
                PendingIntent.getService(
                    this, 1,
                    Intent(this, CarTubePlaybackService::class.java).setAction(toggle),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )
            .addAction(
                R.drawable.ic_skip_forward, "Next",
                PendingIntent.getService(
                    this, 3,
                    Intent(this, CarTubePlaybackService::class.java).setAction(ACTION_SKIP_NEXT),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )
            .build()
    }

    companion object {
        const val TAG = "CarTube"
        const val CHANNEL_ID = "cartube_playback"
        const val NOTIFICATION_ID = 42

        const val ACTION_START_VIDEO = "com.cartube.ytauto.action.START_VIDEO"
        const val ACTION_PLAY_QUEUE_CURRENT = "com.cartube.ytauto.action.PLAY_QUEUE_CURRENT"
        const val ACTION_SKIP_NEXT = "com.cartube.ytauto.action.SKIP_NEXT"
        const val ACTION_SKIP_PREVIOUS = "com.cartube.ytauto.action.SKIP_PREVIOUS"
        const val ACTION_MEDIA_PLAY = "com.cartube.ytauto.action.MEDIA_PLAY"
        const val ACTION_MEDIA_PAUSE = "com.cartube.ytauto.action.MEDIA_PAUSE"
        const val ACTION_STOP = "com.cartube.ytauto.action.STOP"
        const val ACTION_PLAYBACK_ERROR = "com.cartube.ytauto.action.PLAYBACK_ERROR"
        const val ACTION_PLAYBACK_NOTICE = "com.cartube.ytauto.action.PLAYBACK_NOTICE"

        const val EXTRA_VIDEO_ID = "video_id"
        const val EXTRA_TITLE = "title"
        const val EXTRA_ERROR_MESSAGE = "error_message"
        const val EXTRA_NOTICE_MESSAGE = "notice_message"
        const val EXTRA_START_POSITION_MS = "start_position_ms"
        const val EXTRA_FORCE_RESTART = "force_restart"

        fun requestPlayCurrent(context: android.content.Context, forceRestart: Boolean = true) {
            val intent = Intent(context, CarTubePlaybackService::class.java).apply {
                action = ACTION_PLAY_QUEUE_CURRENT
                putExtra(EXTRA_FORCE_RESTART, forceRestart)
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun requestSkipNext(context: android.content.Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, CarTubePlaybackService::class.java).setAction(ACTION_SKIP_NEXT)
            )
        }

        fun requestSkipPrevious(context: android.content.Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, CarTubePlaybackService::class.java).setAction(ACTION_SKIP_PREVIOUS)
            )
        }

        fun startCarPlayback(
            context: android.content.Context,
            videoId: String,
            title: String,
            startPositionMs: Long = 0L
        ) {
            val intent = Intent(context, CarTubePlaybackService::class.java).apply {
                action = ACTION_START_VIDEO
                putExtra(EXTRA_VIDEO_ID, videoId)
                putExtra(EXTRA_TITLE, title)
                putExtra(EXTRA_START_POSITION_MS, startPositionMs)
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun startPhoneBackgroundPlayback(
            context: android.content.Context,
            videoId: String,
            title: String,
            startPositionMs: Long
        ) {
            startCarPlayback(context, videoId, title, startPositionMs)
        }
    }
}