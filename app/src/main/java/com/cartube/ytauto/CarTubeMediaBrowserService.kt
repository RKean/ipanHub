package com.cartube.ytauto

import android.os.Bundle
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.MediaDescriptionCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.media.MediaBrowserServiceCompat

/**
 * Legacy Android Auto media registration so sideloaded builds pass host validation.
 * Car App Library apps declare <uses name="template"/>; on many Android Auto versions
 * "Unknown sources" does not whitelist TEMPLATE, but it does whitelist MEDIA.
 * Video UI still runs through [CarTubeCarAppService] (NavigationTemplate + surface).
 */
class CarTubeMediaBrowserService : MediaBrowserServiceCompat() {

    private lateinit var mediaSession: MediaSessionCompat

    override fun onCreate() {
        super.onCreate()
        mediaSession = MediaSessionCompat(this, TAG).apply {
            setCallback(SessionCallback())
            setPlaybackState(
                PlaybackStateCompat.Builder()
                    .setActions(
                        PlaybackStateCompat.ACTION_PLAY or
                            PlaybackStateCompat.ACTION_PAUSE or
                            PlaybackStateCompat.ACTION_SEEK_TO
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
        PlaybackController.removePlaybackListener(playbackSyncListener)
        mediaSession.release()
        super.onDestroy()
    }

    override fun onGetRoot(
        clientPackageName: String,
        clientUid: Int,
        rootHints: Bundle?
    ): BrowserRoot? {
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
            .setMediaId(ROOT_ID)
            .setTitle(getString(R.string.app_name))
            .setSubtitle(getString(R.string.phone_hint))
            .build()
        val rootItem = MediaBrowserCompat.MediaItem(
            description,
            MediaBrowserCompat.MediaItem.FLAG_BROWSABLE
        )
        result.sendResult(mutableListOf(rootItem))
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
                        PlaybackStateCompat.ACTION_SEEK_TO
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

    private inner class SessionCallback : MediaSessionCompat.Callback() {
        override fun onPlay() {
            PlaybackController.player?.play()
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
    }

    companion object {
        private const val TAG = "CarTubeMedia"
        const val ROOT_ID = "cartube_root"
    }
}