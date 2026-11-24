package com.cartube.ytauto

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.MediaDescriptionCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.media.MediaBrowserServiceCompat

/**
 * Media entry for sideloaded AA launcher visibility. Playback controls + hand off to
 * [CarTubeCarAppService] for [NavigationTemplate] video.
 */
class CarTubeMediaBrowserService : MediaBrowserServiceCompat() {

    private lateinit var mediaSession: MediaSessionCompat
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onCreate() {
        super.onCreate()
        mediaSession = MediaSessionCompat(this, TAG).apply {
            setCallback(SessionCallback())
            setPlaybackState(
                PlaybackStateCompat.Builder()
                    .setActions(
                        PlaybackStateCompat.ACTION_PLAY or
                            PlaybackStateCompat.ACTION_PAUSE or
                            PlaybackStateCompat.ACTION_SEEK_TO or
                            PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                            PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                            PlaybackStateCompat.ACTION_PLAY_FROM_MEDIA_ID
                    )
                    .setState(PlaybackStateCompat.STATE_NONE, 0, 0f)
                    .build()
            )
            isActive = true
        }
        sessionToken = mediaSession.sessionToken
        syncPlaybackState()
        PlaybackController.addPlaybackListener(playbackSyncListener)
    }

    private val playbackSyncListener: () -> Unit = { syncPlaybackState() }

    override fun onDestroy() {
        mainHandler.removeCallbacksAndMessages(null)
        PlaybackController.removePlaybackListener(playbackSyncListener)
        mediaSession.release()
        super.onDestroy()
    }

    override fun onGetRoot(
        clientPackageName: String,
        clientUid: Int,
        rootHints: Bundle?
    ): BrowserRoot? {
        if (isAndroidAutoHost(clientPackageName) && !CarConnection.isCarSessionActive) {
            CarTubeLog.i("[media] AA browse connect client=$clientPackageName -> hand off to CarApp")
            mainHandler.postDelayed({
                CarUiLauncher.launchCarVideoUi(applicationContext, "aa-browse-root")
            }, 600L)
        }
        return BrowserRoot(ROOT_ID, null)
    }

    override fun onLoadChildren(
        parentId: String,
        result: Result<MutableList<MediaBrowserCompat.MediaItem>>
    ) {
        if (parentId != ROOT_ID) {
            result.sendResult(mutableListOf())
            return
        }
        val description = MediaDescriptionCompat.Builder()
            .setMediaId(CarUiLauncher.MEDIA_ID_OPEN_CAR)
            .setTitle(getString(R.string.app_name))
            .setSubtitle(getString(R.string.aa_media_launch))
            .build()
        val openCar = MediaBrowserCompat.MediaItem(
            description,
            MediaBrowserCompat.MediaItem.FLAG_PLAYABLE or MediaBrowserCompat.MediaItem.FLAG_BROWSABLE
        )
        result.sendResult(mutableListOf(openCar))
    }

    private fun isAndroidAutoHost(clientPackageName: String): Boolean {
        return clientPackageName == GEARHEAD_PACKAGE ||
            clientPackageName.startsWith("com.google.android.projection.")
    }

    private fun syncPlaybackState() {
        val player = PlaybackController.player
        val state = when {
            player == null -> PlaybackStateCompat.STATE_NONE
            player.isPlaying -> PlaybackStateCompat.STATE_PLAYING
            player.playbackState == androidx.media3.common.Player.STATE_BUFFERING ->
                PlaybackStateCompat.STATE_BUFFERING
            else -> PlaybackStateCompat.STATE_PAUSED
        }
        mediaSession.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setActions(
                    PlaybackStateCompat.ACTION_PLAY or
                        PlaybackStateCompat.ACTION_PAUSE or
                        PlaybackStateCompat.ACTION_SEEK_TO or
                        PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                        PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                        PlaybackStateCompat.ACTION_PLAY_FROM_MEDIA_ID
                )
                .setState(state, player?.currentPosition ?: 0L, 1f)
                .build()
        )
        val title = PlaybackController.title
        val videoId = PlaybackController.videoId
        val builder = android.support.v4.media.MediaMetadataCompat.Builder()
        if (title.isNotBlank()) {
            builder.putString(android.support.v4.media.MediaMetadataCompat.METADATA_KEY_TITLE, title)
        }
        if (videoId.isNotBlank()) {
            val art = YouTubeArtwork.thumbnailUri(videoId).toString()
            builder.putString(
                android.support.v4.media.MediaMetadataCompat.METADATA_KEY_ALBUM_ART_URI,
                art
            )
            builder.putString(
                android.support.v4.media.MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON_URI,
                art
            )
        }
        if (title.isNotBlank() || videoId.isNotBlank()) {
            mediaSession.setMetadata(builder.build())
        }
    }

    private fun requestCarVideoUi(reason: String) {
        CarUiLauncher.launchCarVideoUi(applicationContext, reason)
    }

    private inner class SessionCallback : MediaSessionCompat.Callback() {
        override fun onPlay() {
            requestCarVideoUi("media-session-play")
            PlaybackController.player?.play()
            syncPlaybackState()
        }

        override fun onPlayFromMediaId(mediaId: String?, extras: Bundle?) {
            requestCarVideoUi("media-play-id-$mediaId")
            if (mediaId == CarUiLauncher.MEDIA_ID_OPEN_CAR) {
                if (PlaybackController.hasActivePlayback) {
                    PlaybackController.player?.play()
                } else {
                    CarTubePlaybackService.requestPlayCurrent(this@CarTubeMediaBrowserService)
                }
            } else {
                PlaybackController.player?.play()
            }
            syncPlaybackState()
        }

        override fun onPause() {
            PlaybackController.player?.pause()
            syncPlaybackState()
        }

        override fun onSeekTo(pos: Long) {
            PlaybackController.player?.seekTo(pos)
            syncPlaybackState()
        }

        override fun onSkipToNext() {
            if (!com.cartube.ytauto.queue.QueueController.hasNext()) return
            CarTubePlaybackService.requestSkipNext(this@CarTubeMediaBrowserService)
            syncPlaybackState()
        }

        override fun onSkipToPrevious() {
            if (!com.cartube.ytauto.queue.QueueController.hasPrevious()) return
            CarTubePlaybackService.requestSkipPrevious(this@CarTubeMediaBrowserService)
            syncPlaybackState()
        }
    }

    companion object {
        private const val TAG = "CarTubeMedia"
        const val ROOT_ID = "cartube_root"
        const val GEARHEAD_PACKAGE = "com.google.android.projection.gearhead"
    }
}