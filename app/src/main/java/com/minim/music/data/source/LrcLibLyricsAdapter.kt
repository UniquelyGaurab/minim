package com.minim.music.data.source

import com.minim.music.util.SafeRegex
import android.net.Uri
import android.util.LruCache
import com.minim.music.data.model.LyricsData
import com.minim.music.data.model.LyricsLine
import com.minim.music.data.model.LyricsWord
import com.minim.music.data.model.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

/**
 * Timed & plain lyrics adapter behind LyricsProvider abstraction, backed by the public LrcLib API.
 * Line-level `[mm:ss.xx]` LRC timestamps produce synced lines; enhanced `<mm:ss.xx>` inline tags
 * produce word timings; otherwise plain (unsynchronized) lines are returned.
 */
class LrcLibLyricsAdapter(
    private val okHttpClient: OkHttpClient
) : LyricsProvider {

    private val memoryCache = LruCache<String, LyricsData>(40)

    /**
     * 5.4.0: lyrics saved with an offline song, by song id (null = not saved). Checked before
     * LrcLib so a saved song shows its lyrics with no signal.
     */
    @Volatile var offlineLyrics: ((String) -> RawLyrics?)? = null

    override suspend fun getLyrics(track: Track): LyricsData? = withContext(Dispatchers.IO) {
        memoryCache.get(track.id)?.let { return@withContext it }

        val saved = try { offlineLyrics?.invoke(track.id) } catch (_: Exception) { null }
        if (saved != null) {
            fromRaw(track.id, saved)?.let { data ->
                memoryCache.put(track.id, data)
                return@withContext data
            }
        }

        // LrcLib public timed-lyrics API
        fetchRaw(track)?.let { raw ->
            fromRaw(track.id, raw)?.let { data ->
                memoryCache.put(track.id, data)
                return@withContext data
            }
        }

        null
    }

    /** 5.4.0: the lyrics text exactly as LrcLib sends it (synced LRC, or plain), for saving offline. */
    fun fetchRaw(track: Track): RawLyrics? {
        val cleanTitle = cleanQueryMetadata(track.title)
        val cleanArtist = track.artist.removeSuffix(" - Topic").trim()
        return fetchFromLrcLib(cleanTitle, cleanArtist, track.durationSec)
    }

    /** 5.4.0: turns saved or fetched lyrics text into lines. */
    fun fromRaw(trackId: String, raw: RawLyrics): LyricsData? = try {
        if (raw.synced) parseLrcText(trackId, raw.text, "LrcLib") else parsePlainText(trackId, raw.text, "LrcLib")
    } catch (_: Exception) {
        null
    }

    override fun clearCache() {
        memoryCache.evictAll()
    }

    private fun fetchFromLrcLib(
        title: String,
        artist: String,
        durationSec: Long
    ): RawLyrics? {
        return try {
            val url = "https://lrclib.net/api/search?track_name=${Uri.encode(title)}&artist_name=${Uri.encode(artist)}"
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", "Minim/2.0 (https://minim.music)")
                .header("Accept", "application/json")
                .build()

            okHttpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val body = resp.body?.string()?.trim().orEmpty()
                if (!body.startsWith("[")) return null
                val arr = JSONArray(body)
                if (arr.length() == 0) return null

                // Pick best match by duration if known
                var bestObj: JSONObject = arr.getJSONObject(0)
                if (durationSec > 0) {
                    var bestDelta = Long.MAX_VALUE
                    for (i in 0 until arr.length()) {
                        val candidate = arr.getJSONObject(i)
                        val candDur = candidate.optLong("duration", 0L)
                        val hasSynced = candidate.optString("syncedLyrics").isNotBlank()
                        val delta = kotlin.math.abs(candDur - durationSec)
                        if (hasSynced && delta < bestDelta) {
                            bestDelta = delta
                            bestObj = candidate
                        }
                    }
                }

                val syncedLrc = bestObj.optString("syncedLyrics", "").trim()
                if (syncedLrc.isNotEmpty() && syncedLrc != "null") {
                    RawLyrics(synced = true, text = syncedLrc)
                } else {
                    val plain = bestObj.optString("plainLyrics", "").trim()
                    if (plain.isNotEmpty() && plain != "null") {
                        RawLyrics(synced = false, text = plain)
                    } else {
                        null
                    }
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    internal fun parseLrcText(trackId: String, lrcText: String, provider: String): LyricsData? {
        val rawLines = lrcText.lines()
        val parsed = ArrayList<LyricsLine>()
        var hasInlineWords = false
        var fileOffsetMs = 0L

        for (raw in rawLines) {
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) continue
            // [offset:+/-ms] tag: kept and applied at display time on top of the user's offset
            LRC_OFFSET_PATTERN.find(trimmed)?.let { m ->
                fileOffsetMs = m.groupValues[1].replace("+", "").toLongOrNull()?.coerceIn(-60_000L, 60_000L) ?: 0L
            }
            val matcher = LRC_LINE_PATTERN.matcher(trimmed)
            if (!matcher.find()) continue

            val min = matcher.group(1)?.toLongOrNull() ?: 0L
            val sec = matcher.group(2)?.toLongOrNull() ?: 0L
            val fracStr = matcher.group(3).orEmpty()
            val fracMs = when (fracStr.length) {
                1 -> (fracStr.toLongOrNull() ?: 0L) * 100L
                2 -> (fracStr.toLongOrNull() ?: 0L) * 10L
                3 -> fracStr.toLongOrNull() ?: 0L
                else -> 0L
            }
            val lineStartMs = (min * 60_000L) + (sec * 1_000L) + fracMs
            val contentAfterTag = trimmed.substring(matcher.end()).trim()
            if (contentAfterTag.isEmpty()) continue

            // Check for Enhanced LRC inline word timestamps: <00:12.34>word
            val inlineWords = parseEnhancedLrcWords(contentAfterTag)
            if (inlineWords.isNotEmpty()) {
                hasInlineWords = true
                val cleanLine = inlineWords.joinToString(" ") { it.text }
                parsed.add(
                    LyricsLine(
                        startMs = lineStartMs,
                        endMs = inlineWords.last().endMs,
                        text = cleanLine,
                        words = inlineWords
                    )
                )
            } else {
                val stripped = contentAfterTag.replace(INLINE_TAG_CLEANER, "").trim()
                if (stripped.isNotEmpty()) {
                    parsed.add(
                        LyricsLine(
                            startMs = lineStartMs,
                            endMs = -1L,
                            text = stripped,
                            words = emptyList()
                        )
                    )
                }
            }
        }

        if (parsed.isEmpty()) return null
        val sorted = parsed.sortedBy { it.startMs }
        val finalized = computeLineEndTimes(sorted)

        return LyricsData(
            trackId = trackId,
            lines = finalized,
            isSynced = true,
            hasWordTimestamps = hasInlineWords,
            providerName = provider,
            offsetMs = fileOffsetMs
        )
    }

    private fun parseEnhancedLrcWords(content: String): List<LyricsWord> {
        val matcher = ENHANCED_WORD_PATTERN.matcher(content)
        val rawTokens = ArrayList<Pair<Long, String>>()
        while (matcher.find()) {
            val min = matcher.group(1)?.toLongOrNull() ?: 0L
            val sec = matcher.group(2)?.toLongOrNull() ?: 0L
            val fracStr = matcher.group(3).orEmpty()
            val fracMs = when (fracStr.length) {
                1 -> (fracStr.toLongOrNull() ?: 0L) * 100L
                2 -> (fracStr.toLongOrNull() ?: 0L) * 10L
                else -> fracStr.take(3).toLongOrNull() ?: 0L
            }
            val startMs = (min * 60_000L) + (sec * 1_000L) + fracMs
            val wordText = matcher.group(4)?.trim().orEmpty()
            if (wordText.isNotEmpty()) {
                rawTokens.add(startMs to wordText)
            }
        }
        if (rawTokens.size < 2) return emptyList()
        val words = ArrayList<LyricsWord>(rawTokens.size)
        for (i in rawTokens.indices) {
            val (start, text) = rawTokens[i]
            val end = if (i + 1 < rawTokens.size) {
                rawTokens[i + 1].first.coerceAtLeast(start + 80L)
            } else {
                start + 500L
            }
            words.add(LyricsWord(text = text, startMs = start, endMs = end))
        }
        return words
    }

    private fun parsePlainText(trackId: String, plain: String, provider: String): LyricsData? {
        val lines = plain.lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { LyricsLine(startMs = 0L, endMs = 0L, text = it, words = emptyList()) }
        if (lines.isEmpty()) return null
        return LyricsData(
            trackId = trackId,
            lines = lines,
            isSynced = false,
            hasWordTimestamps = false,
            providerName = provider
        )
    }

    private fun computeLineEndTimes(lines: List<LyricsLine>): List<LyricsLine> {
        val result = ArrayList<LyricsLine>(lines.size)
        for (i in lines.indices) {
            val current = lines[i]
            val nextStart = if (i + 1 < lines.size) lines[i + 1].startMs else current.startMs + 6000L
            val computedEnd = if (current.endMs > current.startMs) {
                current.endMs
            } else {
                nextStart.coerceAtLeast(current.startMs + 500L)
            }
            result.add(current.copy(endMs = computedEnd))
        }
        return result
    }

    private fun cleanQueryMetadata(rawTitle: String): String {
        return rawTitle
            .replace(SafeRegex.of("\\s*\\((Official|Lyric|Audio|Video|Visualizer|HD|4K)[^)]*\\)", RegexOption.IGNORE_CASE), "")
            .replace(SafeRegex.of("\\s*\\[(Official|Lyric|Audio|Video|Visualizer|HD|4K)[^\\]]*\\]", RegexOption.IGNORE_CASE), "")
            .trim()
    }

    companion object {
        private val LRC_LINE_PATTERN = SafeRegex.pattern("^\\[(\\d{1,3}):(\\d{2})(?:[.:](\\d{1,3}))?\\]")
        private val ENHANCED_WORD_PATTERN =
            SafeRegex.pattern("<(\\d{1,3}):(\\d{2})(?:[.:](\\d{1,3}))?>\\s*([^<]+)")
        private val INLINE_TAG_CLEANER = SafeRegex.of("<[^>]+>")
        private val LRC_OFFSET_PATTERN = SafeRegex.of("^\\[offset:\\s*([+-]?\\d+)\\s*\\]", RegexOption.IGNORE_CASE)
    }
}

/** 5.4.0: lyrics as text, synced (LRC with timestamps) or plain, as saved with an offline song. */
data class RawLyrics(val synced: Boolean, val text: String)
