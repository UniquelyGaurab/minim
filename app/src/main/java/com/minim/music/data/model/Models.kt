package com.minim.music.data.model

import android.net.Uri
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata

data class Track(
    val id: String,
    val title: String,
    val artist: String,
    val durationSec: Long,
    val thumbnailUrl: String,
    val sourceUrl: String
) {
    fun toMediaItem(): MediaItem {
        val extras = Bundle().apply {
            putString(EXTRA_SOURCE_URL, sourceUrl)
            putString(EXTRA_THUMB_URL, thumbnailUrl)
            putLong(EXTRA_DURATION_SEC, durationSec)
        }
        val metadata = MediaMetadata.Builder()
            .setTitle(title)
            .setArtist(artist)
            .setArtworkUri(if (thumbnailUrl.isNotBlank()) Uri.parse(thumbnailUrl) else null)
            .setExtras(extras)
            .build()

        return MediaItem.Builder()
            .setMediaId(id)
            .setUri(Uri.parse("minim://track/$id"))
            .setMediaMetadata(metadata)
            .build()
    }

    companion object {
        private const val EXTRA_SOURCE_URL = "extra_source_url"
        private const val EXTRA_THUMB_URL = "extra_thumb_url"
        private const val EXTRA_DURATION_SEC = "extra_duration_sec"

        fun fromMediaItem(item: MediaItem): Track {
            val meta = item.mediaMetadata
            val extras = meta.extras
            val id = item.mediaId.ifBlank { "unknown" }
            val sourceUrl = extras?.getString(EXTRA_SOURCE_URL)
                ?: "https://www.youtube.com/watch?v=$id"
            val thumbUrl = extras?.getString(EXTRA_THUMB_URL)
                ?: meta.artworkUri?.toString().orEmpty()
            val durationSec = extras?.getLong(EXTRA_DURATION_SEC) ?: 0L

            return Track(
                id = id,
                title = meta.title?.toString()?.ifBlank { "Unknown Track" } ?: "Unknown Track",
                artist = meta.artist?.toString()?.ifBlank { "Unknown Artist" } ?: "Unknown Artist",
                durationSec = durationSec,
                thumbnailUrl = thumbUrl,
                sourceUrl = sourceUrl
            )
        }
    }
}

data class Playlist(
    val id: Long,
    val name: String,
    val trackCount: Int,
    /** 5.3.0: "spotify" / "youtube" / "text" for an imported playlist, else null. */
    val source: String? = null
)

data class LyricsWord(
    val text: String,
    val startMs: Long,
    val endMs: Long
)

data class LyricsLine(
    val startMs: Long,
    val endMs: Long,
    val text: String,
    val words: List<LyricsWord> = emptyList()
)

data class LyricsData(
    val trackId: String,
    val lines: List<LyricsLine>,
    val isSynced: Boolean,
    val hasWordTimestamps: Boolean,
    val providerName: String,
    /** v4.2.5: the LRC file's own [offset:] tag in ms (+ = show lines earlier), 0 when absent. */
    val offsetMs: Long = 0L
)

enum class LyricsStatus {
    IDLE,
    LOADING,
    READY,
    UNAVAILABLE
}

data class PlaybackUiState(
    val currentTrack: Track? = null,
    val queue: List<Track> = emptyList(),
    val currentIndex: Int = -1,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    /** 5.5.0: how far the current song has loaded (the seek bar's faint buffered part). */
    val bufferedPositionMs: Long = 0L,
    val repeatMode: Int = 0,
    val errorMessage: String? = null,
    val accentColor: Int? = null
)

sealed class SourceResult<out T> {
    data class Success<T>(val data: T) : SourceResult<T>()
    data class Error(val message: String, val cause: Throwable? = null) : SourceResult<Nothing>()
}
