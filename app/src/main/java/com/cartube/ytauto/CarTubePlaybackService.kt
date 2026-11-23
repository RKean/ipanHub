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

/** Foreground [MediaSessionService] hosting ExoPlayer for Android Auto output. */
class CarTubePlaybackService : MediaSessionService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var playJob: Job? = null
    private var mediaSession: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? =
        mediaSession

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_STOP -> {
                stopPlayback()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_MEDIA_PAUSE -> {
                PlaybackController.player?.pause()
                updateNotification()
            }
            ACTION_MEDIA_PLAY -> {
                PlaybackController.player?.play()
                updateNotification()
            }
            ACTION_START_VIDEO -> {
                val videoId = intent.getStringExtra(EXTRA_VIDEO_ID) ?: return START_NOT_STICKY
                val title = intent.getStringExtra(EXTRA_TITLE).orEmpty()
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
            try {
                val resolved = withContext(Dispatchers.IO) {
                    YoutubeStreamResolver.resolveVideoId(videoId, applyWebViewCookies = true)
                }
                when (resolved) {
                    is YoutubeStreamResolver.ResolveResult.Err -> {
                        val msg = YoutubeStreamResolver.formatUserError(resolved)
                        Log.e(TAG, "[service] $msg", resolved.cause)
                        broadcastError(msg)
                        stopSelf()
                        return@launch
                    }
                    is YoutubeStreamResolver.ResolveResult.Ok ->
                        playResolvedStream(resolved.stream, videoId, title)
                }
            } catch (e: Exception) {
                Log.e(TAG, "[service] playback failed", e)
                broadcastError("Playback setup failed: ${e.message?.take(120)}")
                stopSelf()
            }
        }
    }

    private suspend fun playResolvedStream(
        stream: YoutubeStreamResolver.ResolvedStream,
        videoId: String,
        title: String
    ) {
        Log.i(TAG, "[service] stream ${stream.via}")

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

        val trackSelector = DefaultTrackSelector(this).apply {
            setParameters(
                buildUponParameters()
                    .setMaxVideoSize(1280, 720)
                    .setMaxVideoBitrate(4_000_000)
                    .build()
            )
        }

        val mediaSourceFactory = DefaultMediaSourceFactory(this)
            .setDataSourceFactory(dataSourceFactory)

        val exo = ExoPlayer.Builder(this)
            .setTrackSelector(trackSelector)
            .setMediaSourceFactory(mediaSourceFactory)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()

        exo.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                .build(),
            true
        )

        val mediaSource = YoutubePlayback.buildMediaSource(dataSourceFactory, stream)
        val displayTitle = title.ifBlank { "YouTube" }

        exo.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                val detail = error.cause?.message ?: error.message
                Log.e(TAG, "[service] ExoPlayer error: $detail", error)
                broadcastError("Playback failed: ${detail?.take(120) ?: "unknown"}")
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                updateNotification()
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                updateNotification()
            }
        })

        mediaSession?.release()
        mediaSession = MediaSession.Builder(this, exo).setId(TAG).build()

        val surfaceReady = CompletableDeferred<Unit>()
        PlaybackController.attachPlayer(exo, displayTitle, videoId, surfaceReady)

        val gotSurface = withTimeoutOrNull(4_000L) { surfaceReady.await() } != null
        Log.i(TAG, "[service] car surface ready=$gotSurface")

        exo.setMediaSource(mediaSource)
        exo.prepare()
        exo.play()
        updateNotification()
    }

    private fun stopPlayback() {
        mediaSession?.release()
        mediaSession = null
        PlaybackController.detachAndRelease()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
    }

    private fun updateNotification() {
        val title = PlaybackController.title.ifBlank { getString(R.string.app_name) }
        val playing = PlaybackController.player?.isPlaying == true
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, buildNotification(title, playing))
    }

    private fun broadcastError(message: String) {
        sendBroadcast(
            Intent(ACTION_PLAYBACK_ERROR)
                .setPackage(packageName)
                .putExtra(EXTRA_ERROR_MESSAGE, message)
        )
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
            .setContentText(getString(R.string.notification_playing_on_car))
            .setContentIntent(contentIntent)
            .setOngoing(playing)
            .setOnlyAlertOnce(true)
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

        fun startPlayback(context: android.content.Context, videoId: String, title: String) {
            val intent = Intent(context, CarTubePlaybackService::class.java).apply {
                action = ACTION_START_VIDEO
                putExtra(EXTRA_VIDEO_ID, videoId)
                putExtra(EXTRA_TITLE, title)
            }
            ContextCompat.startForegroundService(context, intent)
        }
    }
}