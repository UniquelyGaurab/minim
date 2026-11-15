package com.minim.music.data.share

import com.minim.music.data.ai.PhoneSongAudio
import com.minim.music.data.offline.FetchSource
import com.minim.music.data.offline.OfflineStore
import com.minim.music.data.offline.ParallelFetcher
import com.minim.music.data.source.StreamOption
import com.minim.music.data.source.StreamSelection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * 6.7.0: the song's audio for an animated lyrics card. A downloaded song uses its saved file;
 * otherwise the original-language audio track Minim streams (NewPipe) is fetched once into the
 * cache. Throws when it can't be had (offline, blocked); the sheet then offers to share without sound.
 */
class CardSongAudio(
    private val workDir: File,
    private val offline: () -> OfflineStore?,
    private val options: (videoId: String, fresh: Boolean) -> List<StreamOption>,
    private val fetcher: ParallelFetcher,
) {
    suspend fun fileFor(videoId: String): File = withContext(Dispatchers.IO) {
        offline()?.audioFor(videoId)?.takeIf { it.length() > 0 }?.let { return@withContext it }
        if (videoId.isBlank()) throw IOException("no song id")
        workDir.mkdirs()
        val name = "song-" + videoId.filter { it.isLetterOrDigit() || it == '-' || it == '_' } + ".m4a"
        val out = File(workDir, name)
        if (out.length() > 0) return@withContext out
        // keep only the latest song's audio
        workDir.listFiles()?.forEach { if (it.name.startsWith("song-")) it.delete() }
        val part = File(workDir, "$name.part")
        try {
            var pick = PhoneSongAudio.pickForGemini(options(videoId, false))
                ?: PhoneSongAudio.pickForGemini(options(videoId, true))
                ?: throw IOException("no audio stream")
            fetcher.fetch(FetchSource(pick.url, pick.contentLength), part, refresh = {
                StreamSelection.originalOnly(options(videoId, true), pick.trackId).firstOrNull {
                    it.itag == pick.itag && it.trackId == pick.trackId &&
                        it.contentLength == pick.contentLength && it.url.isNotBlank()
                }?.let { pick = it; FetchSource(it.url, it.contentLength) }
            })
            currentCoroutineContext().ensureActive()
            if (part.length() <= 0 || !part.renameTo(out)) throw IOException("download failed")
            out
        } finally {
            part.delete()
        }
    }
}
