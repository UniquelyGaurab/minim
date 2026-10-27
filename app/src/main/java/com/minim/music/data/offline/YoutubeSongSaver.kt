package com.minim.music.data.offline

import com.minim.music.data.model.Track
import com.minim.music.data.source.RawLyrics
import com.minim.music.data.source.StreamOption
import com.minim.music.data.source.StreamSelection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import kotlin.coroutines.coroutineContext

/**
 * 5.4.0: saves one song the fast way: asks NewPipe for the song's audio formats right now
 * (links expire, so never earlier), takes the best one (usually Opus ~160 kbps), pulls it with
 * [ParallelFetcher], then saves the synced lyrics and the cover next to it.
 */
class YoutubeSongSaver(
    private val store: OfflineStore,
    private val options: (videoId: String, fresh: Boolean) -> List<StreamOption>,
    private val fetcher: ParallelFetcher,
    private val lyrics: (Track) -> RawLyrics?,
    private val art: (String) -> Pair<File, Boolean>?
) : SongSaver {

    override suspend fun save(track: Track, onProgress: (Float) -> Unit) {
        val id = track.id
        val first = withContext(Dispatchers.IO) { options(id, false) }
        var pick = StreamSelection.bestForSaving(first)
            ?: StreamSelection.bestForSaving(withContext(Dispatchers.IO) { options(id, true) })
            ?: throw IOException("YouTube didn't offer a file Minim can save for this song")
        val part = store.tempAudioFile(track.id)
        try {
            fetcher.fetch(
                FetchSource(pick.url, pick.contentLength),
                part,
                refresh = {
                    val fresh = withContext(Dispatchers.IO) { options(id, true) }
                    val same = fresh.firstOrNull { it.itag == pick.itag && it.contentLength == pick.contentLength && it.url.isNotBlank() }
                    same?.let { pick = it; FetchSource(it.url, it.contentLength) }
                }
            ) { bytes -> onProgress(0.95f * bytes / pick.contentLength) }
            coroutineContext.ensureActive()
            // Lyrics and cover are extras: a song still saves when either can't be had
            val savedLyrics = withContext(Dispatchers.IO) { runCatching { lyrics(track) }.getOrNull() }
            val cover = withContext(Dispatchers.IO) { runCatching { art(track.thumbnailUrl) }.getOrNull() }
            coroutineContext.ensureActive()
            withContext(Dispatchers.IO) {
                store.add(
                    track = track, downloaded = part, format = StreamSelection.fileFormat(pick),
                    bitrate = pick.bitrate, itag = pick.itag, lyrics = savedLyrics,
                    art = cover?.first, artIsVideoFrame = cover?.second ?: false
                )
            }
            onProgress(1f)
        } finally {
            part.delete()
        }
    }
}
