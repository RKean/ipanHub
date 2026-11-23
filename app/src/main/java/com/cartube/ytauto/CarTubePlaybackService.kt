package com.cartube.ytauto

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
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

/** Foreground [MediaSessionService] — queue-driven; player lives in [PlaybackEngine]. */
class CarTubePlaybackService : MediaSessionService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var playJob: Job? = null
    private var prefetchJob: Job? = null
    private var bufferingWatchdog: Job? = null
    private var resolvingVideoId: String? = null
    private var lastFailedVideoId: String? = null
    private var lastErrorBroadcastMs = 0L
    private var currentStreamKind: YoutubeStreamResolver.StreamKind? = null
    @Volatile
    private var isBuffering = false
    private var bufferingSinceMs = 0L
    private var lastPlayheadMs = 0L
    private var trackSwitchEpoch = 0

    private val sessionCallback = object : MediaSession.Callback {
        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo
        ): MediaSession.ConnectionResult {
            CarTubeLog.i("[session] connect pkg=${controller.packageName} ${PlaybackEngine.playerHashLabel()}")
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session).build()
        }
    }

    override fun onCreate() {
        super.onCreate()
        CarTubeLog.i("[service] onCreate ${PlaybackEngine.playerHashLabel()}")
        QueueController.init(this)
        PlaybackEngine.init(this)
        createNotificationChannel()
        PlaybackEngine.ensureMediaSession(sessionCallback)
        attachEngineListenersOnce()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        return PlaybackEngine.mediaSession()
            ?: PlaybackEngine.ensureMediaSession(sessionCallback)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        CarTubeLog.d("[service] onStartCommand action=${intent?.action} startId=$startId")
        when (intent?.action) {
            ACTION_STOP -> {
                stopActivePlayback("ACTION_STOP")
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_MEDIA_PAUSE -> {
                playerAction("notification_pause") { it.pause() }
                updateNotification()
            }
            ACTION_MEDIA_PLAY -> {
                playerAction("notification_play") { it.play() }
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
                startQueueItemJob(item, if (force) 0L else currentPositionMs(), force, "PLAY_QUEUE_CURRENT")
            }
            ACTION_START_VIDEO -> {
                val videoId = intent.getStringExtra(EXTRA_VIDEO_ID) ?: return START_NOT_STICKY
                val title = intent.getStringExtra(EXTRA_TITLE).orEmpty()
                val item = QueueItem(videoId, title.ifBlank { "YouTube" })
                QueueController.playNow(item)
                beginForeground(item.title)
                PlaybackController.setLoading(item.title)
                startQueueItemJob(item, intent.getLongExtra(EXTRA_START_POSITION_MS, 0L), force = true, "START_VIDEO")
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        CarTubeLog.i("[service] onDestroy — keep process player ${PlaybackEngine.playerHashLabel()}")
        playJob?.cancel()
        prefetchJob?.cancel()
        bufferingWatchdog?.cancel()
        serviceScope.cancel()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    private fun currentPositionMs(): Long = PlaybackEngine.player()?.currentPosition ?: 0L

    private fun shouldNoOpForCurrent(videoId: String): Boolean {
        if (resolvingVideoId == videoId) {
            CarTubeLog.i("[service] no-op — already loading $videoId")
            return true
        }
        if (videoId != PlaybackController.videoId) return false
        val p = PlaybackEngine.player() ?: return false
        return when (p.playbackState) {
            Player.STATE_BUFFERING -> true
            Player.STATE_READY -> {
                if (!p.isPlaying) playerAction("noop_resume") { it.play() }
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
        startQueueItemJob(item, 0L, force = true, reason = if (delta > 0) "SKIP_NEXT" else "SKIP_PREV")
    }

    private fun attachEngineListenersOnce() {
        if (listenersAttached) return
        synchronized(CarTubePlaybackService::class.java) {
            if (listenersAttached) return
            val exo = PlaybackEngine.ensurePlayer()
            exo.addListener(object : Player.Listener {
                override fun onPlayerError(error: PlaybackException) {
                    val detail = error.cause?.message ?: error.message
                    CarTubeLog.e("[player] ExoPlayer error: $detail", error)
                    tryFallbackOrSkip("onPlayerError")
                }

                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    logPlayerState("isPlaying=$isPlaying")
                    updateNotification()
                }

                override fun onPlaybackStateChanged(playbackState: Int) {
                    isBuffering = playbackState == Player.STATE_BUFFERING
                    logPlayerState("state=$playbackState itemCount=${exo.mediaItemCount}")
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

                override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                    val id = mediaItem?.mediaId ?: "null"
                    CarTubeLog.i(
                        "[player] onMediaItemTransition id=$id reason=${transitionReason(reason)} " +
                            "count=${exo.mediaItemCount}"
                    )
                }

                override fun onPositionDiscontinuity(
                    oldPosition: Player.PositionInfo,
                    newPosition: Player.PositionInfo,
                    reason: Int
                ) {
                    CarTubeLog.i(
                        "[player] discontinuity reason=${discontinuityReason(reason)} " +
                            "old=${oldPosition.positionMs}@${oldPosition.mediaItemIndex} " +
                            "new=${newPosition.positionMs}@${newPosition.mediaItemIndex}"
                    )
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
                    CarTubeLog.e(
                        "[load] uri=${loadEventInfo.uri} bytes=${loadEventInfo.bytesLoaded} " +
                            "err=${error.message} canceled=$wasCanceled"
                    )
                }

                override fun onIsLoadingChanged(
                    eventTime: AnalyticsListener.EventTime,
                    isLoading: Boolean
                ) {
                    CarTubeLog.d("[load] isLoading=$isLoading")
                }
            })
            listenersAttached = true
            CarTubeLog.i("[service] engine listeners attached ${PlaybackEngine.playerHashLabel()}")
        }
    }

    private fun logPlayerState(extra: String) {
        val p = PlaybackEngine.player() ?: return
        CarTubeLog.i(
            "[player] $extra pos=${p.currentPosition} buffered=${p.bufferedPosition} " +
                "speed=${p.playbackParameters.speed}"
        )
    }

    private fun startBufferingWatchdog() {
        val p = PlaybackEngine.player() ?: return
        if (!shouldRunBufferingWatchdog(p)) return
        bufferingWatchdog?.cancel()
        bufferingWatchdog = serviceScope.launch {
            delay(7_000L)
            val player = PlaybackEngine.player() ?: return@launch
            if (player.playbackState != Player.STATE_BUFFERING) return@launch
            if (!shouldRunBufferingWatchdog(player)) {
                CarTubeLog.d("[watchdog] cancel — buffer recovered")
                return@launch
            }
            val progressed = player.currentPosition > lastPlayheadMs + 500
            if (progressed) return@launch
            CarTubeLog.w("[watchdog] stalled at ${player.currentPosition} — alternate stream")
            tryFallbackOrSkip("buffering_watchdog")
        }
    }

    private fun shouldRunBufferingWatchdog(player: ExoPlayer): Boolean {
        val ahead = player.bufferedPosition - player.currentPosition
        if (ahead > 2_000) {
            CarTubeLog.d("[watchdog] skip — ${ahead}ms buffered ahead")
            return false
        }
        if (player.currentPosition > 5_000) {
            CarTubeLog.d("[watchdog] skip — mid-track rebuffer pos=${player.currentPosition}")
            return false
        }
        return true
    }

    private fun cancelBufferingWatchdog() {
        bufferingWatchdog?.cancel()
        bufferingWatchdog = null
    }

    private fun tryFallbackOrSkip(trigger: String) {
        val item = QueueController.currentItem() ?: run {
            stopSelf()
            return
        }
        val kind = currentStreamKind
        StreamCache.invalidate(item.videoId)
        val skip = buildSet { kind?.let { add(it) } }
        if (skip.size < YoutubeStreamResolver.StreamKind.entries.size) {
            CarTubeLog.i("[service] fallback resolve skip=$skip trigger=$trigger")
            cancelBufferingWatchdog()
            playJob?.cancel()
            playJob = serviceScope.launch {
                resolvingVideoId = item.videoId
                try {
                    val resolved = withContext(Dispatchers.IO) {
                        ResolveCoordinator.resolve(
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
                            playResolvedStream(resolved.stream, item, mode, 0L, "fallback_$trigger")
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
            startQueueItemJob(next, 0L, force = true, reason = "TRACK_ENDED")
        } else {
            CarTubeLog.i("[service] queue ended")
        }
    }

    private fun skipAfterError(notice: String) {
        broadcastNotice(notice)
        PlaybackController.clearLoading()
        StreamCache.invalidate(PlaybackController.videoId)
        val next = QueueController.advanceToNext()
        if (next != null) {
            PlaybackController.setLoading(next.title)
            startQueueItemJob(next, 0L, force = true, reason = "SKIP_AFTER_ERROR")
        } else {
            stopSelf()
        }
    }

    private fun startQueueItemJob(item: QueueItem, seekMs: Long, force: Boolean, reason: String) {
        prefetchJob?.cancel()
        playJob?.cancel()
        val epoch = ++trackSwitchEpoch
        playJob = serviceScope.launch {
            resolvingVideoId = item.videoId
            val mode = playbackMode()
            try {
                preparePlayerForSwitch(reason)
                val cached = if (!force) StreamCache.peek(item.videoId) else null
                val resolved = if (cached != null) {
                    YoutubeStreamResolver.ResolveResult.Ok(cached)
                } else {
                    withContext(Dispatchers.IO) {
                        ResolveCoordinator.resolve(
                            item.videoId,
                            applyWebViewCookies = true,
                            preference = YoutubeStreamResolver.ResolvePreference.FAST_START
                        )
                    }
                }
                if (epoch != trackSwitchEpoch) {
                    CarTubeLog.d("[service] stale play job epoch $epoch for ${item.videoId}")
                    return@launch
                }
                when (resolved) {
                    is YoutubeStreamResolver.ResolveResult.Err -> {
                        CarTubeLog.e("[service] ${resolved.detail}", resolved.cause)
                        skipAfterError("Skipped — couldn't load video")
                        return@launch
                    }
                    is YoutubeStreamResolver.ResolveResult.Ok -> {
                        StreamCache.put(item.videoId, resolved.stream)
                        playResolvedStream(resolved.stream, item, mode, seekMs, reason)
                    }
                }
            } catch (_: CancellationException) {
                CarTubeLog.i("[service] cancelled ${item.videoId} reason=$reason")
            } catch (e: Exception) {
                CarTubeLog.e("[service] playback failed reason=$reason", e)
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

    private fun preparePlayerForSwitch(reason: String) {
        val p = PlaybackEngine.player() ?: return
        CarTubeLog.i(
            "[player] stop+clear reason=$reason target=${PlaybackController.videoId} " +
                "items=${p.mediaItemCount}"
        )
        p.stop()
        p.clearMediaItems()
    }

    private fun schedulePrefetchAfterReady() {
        prefetchJob?.cancel()
        prefetchJob = serviceScope.launch {
            delay(400L)
            val next = QueueController.nextItem() ?: return@launch
            val current = QueueController.currentItem()?.videoId
            if (next.videoId == current) return@launch
            if (StreamCache.peek(next.videoId) != null) return@launch
            CarTubeLog.i("[service] prefetch queue+1 ${next.videoId} (url only)")
            withContext(Dispatchers.IO) {
                when (val r = ResolveCoordinator.resolve(next.videoId, applyWebViewCookies = true)) {
                    is YoutubeStreamResolver.ResolveResult.Ok -> StreamCache.put(next.videoId, r.stream)
                    else -> CarTubeLog.w("[service] prefetch miss ${next.videoId}")
                }
            }
        }
    }

    private suspend fun playResolvedStream(
        stream: YoutubeStreamResolver.ResolvedStream,
        item: QueueItem,
        mode: PlaybackController.PlaybackMode,
        startPositionMs: Long,
        reason: String
    ) {
        currentStreamKind = stream.kind
        CarTubeLog.i("[service] play ${item.videoId} mode=$mode via=${stream.via} reason=$reason")
        val exo = PlaybackEngine.ensurePlayer()

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

        CarTubeLog.i("[player] setMediaSource id=${item.videoId} reason=$reason items_before=${exo.mediaItemCount}")
        exo.setMediaSource(mediaSource)
        CarTubeLog.i("[player] prepare id=${item.videoId} reason=$reason")
        exo.prepare()
        if (startPositionMs > 0) {
            CarTubeLog.i("[player] seekTo=$startPositionMs id=${item.videoId} reason=$reason")
            exo.seekTo(startPositionMs)
        }
        CarTubeLog.i("[player] play() id=${item.videoId} reason=$reason")
        exo.play()
        updateNotification()
    }

    private fun playerAction(reason: String, block: (ExoPlayer) -> Unit) {
        val p = PlaybackEngine.player() ?: return
        CarTubeLog.d("[player] $reason id=${PlaybackController.videoId}")
        block(p)
    }

    private fun stopActivePlayback(reason: String) {
        playJob?.cancel()
        playJob = null
        prefetchJob?.cancel()
        cancelBufferingWatchdog()
        resolvingVideoId = null
        preparePlayerForSwitch(reason)
        PlaybackController.detachUiOnly()
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
        val playing = PlaybackEngine.player()?.isPlaying == true
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
            .setSmallIcon(R.drawable.ic_notification)
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
        const val TAG = CarTubeLog.TAG
        const val SESSION_ID = "CarTubePlayback"
        const val CHANNEL_ID = "cartube_playback"
        const val NOTIFICATION_ID = 42

        @Volatile
        private var listenersAttached = false

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

        private fun transitionReason(reason: Int): String = when (reason) {
            Player.MEDIA_ITEM_TRANSITION_REASON_AUTO -> "AUTO"
            Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED -> "PLAYLIST_CHANGED"
            Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT -> "REPEAT"
            Player.MEDIA_ITEM_TRANSITION_REASON_SEEK -> "SEEK"
            else -> "UNKNOWN($reason)"
        }

        private fun discontinuityReason(reason: Int): String = when (reason) {
            Player.DISCONTINUITY_REASON_AUTO_TRANSITION -> "AUTO_TRANSITION"
            Player.DISCONTINUITY_REASON_SEEK -> "SEEK"
            Player.DISCONTINUITY_REASON_SEEK_ADJUSTMENT -> "SEEK_ADJUSTMENT"
            Player.DISCONTINUITY_REASON_SKIP -> "SKIP"
            Player.DISCONTINUITY_REASON_REMOVE -> "REMOVE"
            Player.DISCONTINUITY_REASON_INTERNAL -> "INTERNAL"
            else -> "UNKNOWN($reason)"
        }
    }
}