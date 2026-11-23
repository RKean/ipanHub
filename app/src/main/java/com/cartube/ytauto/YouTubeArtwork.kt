package com.cartube.ytauto

import android.net.Uri

object YouTubeArtwork {
    fun thumbnailUri(videoId: String): Uri =
        Uri.parse("https://i.ytimg.com/vi/$videoId/hqdefault.jpg")
}