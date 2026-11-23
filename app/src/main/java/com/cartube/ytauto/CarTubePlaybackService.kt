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
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import kotlin.math.abs

/** Foreground [MediaSessionService] hosting ExoPlayer for Android Auto and phone background. */
class CarTubePlaybackService : MediaSessionService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var playJob: Job? = null
    private var hostPlayer: ExoPlayer? = null
    private var mediaSession: MediaSession? = null
    private var activeMode = PlaybackController.PlaybackMode.CAR
    private var pendingStartPositionMs = 0L
    private var resolvingVideoId: String? = null
    private var lastFailedVideoId: String? = null
    private var lastErrorBroadcastMs = 0L

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
            ACTION_START_VIDEO -> {
                val videoId = intent.getStringExtra(EXTRA_VIDEO_ID) ?: return START_NOT_STICKY
                val title = intent.getStringExtra(EXTRA_TITLE).orEmpty()
                activeMode = if (intent.getBooleanExtra(EXTRA_PHONE_BACKGROUND, false)) {
                    PlaybackController.PlaybackMode.PHONE_BACKGROUND
                } else {
                    PlaybackController.PlaybackMode.CAR
                }
                pendingStartPositionMs = intent.getLongExtra(EXTRA_START_POSITION_MS, 0L)
                if (handleDuplicateStartRequest(videoId, pendingStartPositionMs)) {
                    return START_STICKY
                }
                beginForeground(title)
                startVideoJob(videoId, title)
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        playJob?.cancel()
        serviceScope.cancel()
        stopPlayback()
        super.onDestroy()
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
                broadcastErrorOnce(
                    PlaybackController.videoId,
                    "Playback failed: ${detail?.take(120) ?: "unknown"}"
                )
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) = updateNotification()

            override fun onPlaybackStateChanged(playbackState: Int) = updateNotification()
        })
        hostPlayer = exo
        mediaSession = MediaSession.Builder(this, exo)
            .setId(TAG)
            .setCallback(sessionCallback)
            .build()
        Log.i(TAG, "[service] MediaSession ready for controllers")
    }

    private fun handleDuplicateStartRequest(videoId: String, seekMs: Long): Boolean {
        val currentId = PlaybackController.videoId
        val player = hostPlayer
        if (resolvingVideoId == videoId) {
            Log.i(TAG, "[service] skip duplicate — already resolving $videoId")
            return true
        }
        if (currentId == videoId && player != null && PlaybackController.hasActivePlayback) {
            val pos = player.currentPosition
            if (abs(pos - seekMs) < 3_000) {
                Log.i(TAG, "[service] skip duplicate — already on $videoId at ${pos}ms")
                if (!player.isPlaying) player.play()
                return true
            }
            Log.i(TAG, "[service] re-seek same video $videoId → $seekMs")
            player.seekTo(seekMs)
            player.play()
            return true
        }
        return false
    }

    private fun beginForeground(title: String) {
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(title.ifBlank { getString(R.string.app_name) }, false),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        )
    }

    private fun startVideoJob(videoId: String, title: String) {
        playJob?.cancel()
        playJob = serviceScope.launch {
            resolvingVideoId = videoId
            try {
                val resolved = withContext(Dispatchers.IO) {
                    YoutubeStreamResolver.resolveVideoId(videoId, applyWebViewCookies = true)
                }
                when (resolved) {
                    is YoutubeStreamResolver.ResolveResult.Err -> {
                        val msg = YoutubeStreamResolver.formatUserError(resolved)
                        Log.e(TAG, "[service] $msg", resolved.cause)
                        broadcastErrorOnce(videoId, msg)
                        stopSelf()
                        return@launch
                    }
                    is YoutubeStreamResolver.ResolveResult.Ok ->
                        playResolvedStream(
                            resolved.stream,
                            videoId,
                            title,
                            activeMode,
                            pendingStartPositionMs
                        )
                }
            } catch (_: CancellationException) {
                Log.i(TAG, "[service] resolve cancelled for $videoId (superseded)")
            } catch (e: Exception) {
                Log.e(TAG, "[service] playback failed", e)
                val msg = userMessageFor(e)
                if (msg.isNotBlank()) {
                    broadcastErrorOnce(videoId, msg)
                    stopSelf()
                }
            } finally {
                if (resolvingVideoId == videoId) resolvingVideoId = null
            }
        }
    }

    private fun userMessageFor(e: Exception): String {
        val name = e.javaClass.simpleName
        if (name.contains("Cancellation", ignoreCase = true)) return ""
        return "Playback setup failed: ${e.message?.take(120) ?: name}"
    }

    private fun broadcastErrorOnce(videoId: String, message: String) {
        if (message.isBlank()) return
        val now = System.currentTimeMillis()
        if (videoId == lastFailedVideoId && now - lastErrorBroadcastMs < 5_000) return
        lastFailedVideoId = videoId
        lastErrorBroadcastMs = now
        sendBroadcast(
            Intent(ACTION_PLAYBACK_ERROR)
                .setPackage(packageName)
                .putExtra(EXTRA_ERROR_MESSAGE, message)
        )
    }

    private suspend fun playResolvedStream(
        stream: YoutubeStreamResolver.ResolvedStream,
        videoId: String,
        title: String,
        mode: PlaybackController.PlaybackMode,
        startPositionMs: Long
    ) {
        Log.i(TAG, "[service] mode=$mode stream=${stream.via} seek=$startPositionMs")
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
        val displayTitle = title.ifBlank { "YouTube" }

        val surfaceReady = if (mode == PlaybackController.PlaybackMode.CAR) {
            CompletableDeferred<Unit>()
        } else {
            null
        }
        PlaybackController.attachPlayer(exo, displayTitle, videoId, mode, surfaceReady)

        if (mode == PlaybackController.PlaybackMode.CAR) {
            val gotSurface = withTimeoutOrNull(4_000L) { surfaceReady?.await() } != null
            Log.i(TAG, "[service] car surface ready=$gotSurface")
        }

        exo.setMediaSource(mediaSource)
        exo.prepare()
        if (startPositionMs > 0) {
            exo.seekTo(startPositionMs)
        }
        exo.play()
        updateNotification()
    }

    private fun stopPlayback() {
        playJob?.cancel()
        playJob = null
        resolvingVideoId = null
        PlaybackController.detachAndRelease()
        mediaSession?.release()
        mediaSession = null
        hostPlayer = null
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
    }

    private fun updateNotification() {
        val title = PlaybackController.title.ifBlank { getString(R.string.app_name) }
        val playing = hostPlayer?.isPlaying == true
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, buildNotification(title, playing))
    }

    private fun notificationBody(): String = when (PlaybackController.playbackMode) {
        PlaybackController.PlaybackMode.PHONE_BACKGROUND -> getString(R.string.notification_playing_phone)
        PlaybackController.PlaybackMode.CAR -> getString(R.string.notification_playing_on_car)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.notification_channel_desc)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(title: String, playing: Boolean): Notification {
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val toggleAction = if (playing) ACTION_MEDIA_PAUSE else ACTION_MEDIA_PLAY
        val actionIcon = if (playing) R.drawable.ic_pause else R.drawable.ic_play
        val actionTitle = if (playing) "Pause" else "Play"
        val mediaAction = PendingIntent.getService(
            this,
            1,
            Intent(this, CarTubePlaybackService::class.java).setAction(toggleAction),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopAction = PendingIntent.getService(
            this,
            2,
            Intent(this, CarTubePlaybackService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(notificationBody())
            .setContentIntent(contentIntent)
            .setOngoing(playing)
            .setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(actionIcon, actionTitle, mediaAction)
            .addAction(R.drawable.ic_stop, getString(R.string.notification_stop), stopAction)
            .setStyle(
                androidx.media.app.NotificationCompat.MediaStyle()
                    .setShowActionsInCompactView(0, 1)
            )
            .build()
    }

    companion object {
        const val TAG = "CarTube"
        const val CHANNEL_ID = "cartube_playback"
        const val NOTIFICATION_ID = 42

        const val ACTION_START_VIDEO = "com.cartube.ytauto.action.START_VIDEO"
        const val ACTION_MEDIA_PLAY = "com.cartube.ytauto.action.MEDIA_PLAY"
        const val ACTION_MEDIA_PAUSE = "com.cartube.ytauto.action.MEDIA_PAUSE"
        const val ACTION_STOP = "com.cartube.ytauto.action.STOP"
        const val ACTION_PLAYBACK_ERROR = "com.cartube.ytauto.action.PLAYBACK_ERROR"

        const val EXTRA_VIDEO_ID = "video_id"
        const val EXTRA_TITLE = "title"
        const val EXTRA_ERROR_MESSAGE = "error_message"
        const val EXTRA_PHONE_BACKGROUND = "phone_background"
        const val EXTRA_START_POSITION_MS = "start_position_ms"

        fun startCarPlayback(
            context: android.content.Context,
            videoId: String,
            title: String,
            startPositionMs: Long = 0L
        ) {
            startInternal(context, videoId, title, phoneBackground = false, startPositionMs)
        }

        fun startPhoneBackgroundPlayback(
            context: android.content.Context,
            videoId: String,
            title: String,
            startPositionMs: Long
        ) {
            startInternal(context, videoId, title, phoneBackground = true, startPositionMs)
        }

        private fun startInternal(
            context: android.content.Context,
            videoId: String,
            title: String,
            phoneBackground: Boolean,
            startPositionMs: Long
        ) {
            val intent = Intent(context, CarTubePlaybackService::class.java).apply {
                action = ACTION_START_VIDEO
                putExtra(EXTRA_VIDEO_ID, videoId)
                putExtra(EXTRA_TITLE, title)
                putExtra(EXTRA_PHONE_BACKGROUND, phoneBackground)
                putExtra(EXTRA_START_POSITION_MS, startPositionMs.coerceAtLeast(0L))
            }
            ContextCompat.startForegroundService(context, intent)
        }
    }
}