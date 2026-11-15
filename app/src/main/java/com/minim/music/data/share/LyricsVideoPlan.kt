package com.minim.music.data.share

import com.minim.music.data.model.LyricsLine
import com.minim.music.ui.FlowTiming

/**
 * How long an animated lyrics card runs and where its audio comes from (6.7.0).
 * Fit: the lyrics part runs from the first picked line's start to the end of the last picked
 * line, capped at 30 seconds, then a fixed 1-second ending.
 * 30s / 45s / 60s: it starts at the first picked line and carries on through the lines after
 * the picked ones until the length is reached; the 1-second ending is inside the length. When
 * the song ends first, the video ends with the song, then the ending.
 */
object LyricsVideoPlan {
    const val MAX_LYRICS_MS = 30_000L
    const val MIN_LYRICS_MS = 1_000L
    const val ENDING_MS = 1_000L
    const val FADE_TO_END_MS = 400L
    const val AUDIO_FADE_MS = 300L
    const val FPS = 30
    /** Lines shown after the last line that starts inside the video (dim, for the look). */
    const val EXTRA_AFTER = 3
    /** When the song's length isn't known: how long after the last line's flow it's taken to end. */
    const val TAIL_MS = 4_000L
    /** Video bitrate: Fit (up to 31 s) and the longer lengths (60 s with sound stays under 13 MB). */
    const val VIDEO_BITRATE_FIT = 1_600_000
    const val VIDEO_BITRATE_LONG = 1_450_000
    const val AUDIO_BITRATE = 128_000

    data class Plan(
        /** Lyric-timeline time shown on the first frame. */
        val lyricStartMs: Long,
        /** Length of the lyrics part (before the ending). */
        val lyricsMs: Long,
        /** Where the song audio starts, in the audio file's own time. */
        val audioStartMs: Long,
        /** Start and flow length of each line on the card, on the lyric timeline. */
        val lineStarts: List<Long>,
        val lineDurs: List<Long>,
        val capped: Boolean,
        /** Which lines of the song are on the card, in order: the picked ones, then (30/45/60) the next ones. */
        val lineIndices: List<Int> = emptyList(),
        /** How many of [lineIndices] are the picked lines. */
        val pickedCount: Int = lineIndices.size,
        val length: LyricsCardOptions.Length = LyricsCardOptions.Length.FIT,
        /** 30/45/60: the song ends before the length, so the video is shorter. */
        val endsWithSong: Boolean = false
    ) {
        /** The card scrolls through lines after the picked ones (30/45/60). */
        val scrolls: Boolean get() = length != LyricsCardOptions.Length.FIT
        val videoBitrate: Int get() = bitrateFor(totalMs)
        val totalMs: Long get() = lyricsMs + ENDING_MS
        val frames: Int get() = ((totalMs * FPS + 999) / 1000).toInt()
        val audioMs: Long get() = lyricsMs
        /** Video time of a frame. */
        fun frameMs(frame: Int): Long = frame * 1000L / FPS
    }

    fun bitrateFor(totalMs: Long): Int =
        if (totalMs > MAX_LYRICS_MS + ENDING_MS) VIDEO_BITRATE_LONG else VIDEO_BITRATE_FIT

    /** Rough size of a video with sound, in bytes (container overhead left out). */
    fun estimatedBytes(totalMs: Long, sound: Boolean = true): Long =
        (bitrateFor(totalMs).toLong() + if (sound) AUDIO_BITRATE else 0) * totalMs / 8000

    /**
     * @param songDurationMs the song's length (audio time); 0 when not known.
     * @return null when the lyrics have no timing or the range is invalid.
     */
    fun plan(
        all: List<LyricsLine>,
        first: Int,
        last: Int,
        synced: Boolean,
        fileOffsetMs: Long = 0,
        userOffsetMs: Int = 0,
        length: LyricsCardOptions.Length = LyricsCardOptions.Length.FIT,
        songDurationMs: Long = 0
    ): Plan? {
        if (!synced || first < 0 || last < first || last >= all.size) return null
        val durs = FlowTiming.lineDurations(all)
        val start = all[first].startMs
        val audioStart = FlowTiming.seekTargetForLine(start, fileOffsetMs, userOffsetMs)
        // When the offsets push the seek below zero, keep the picture in step with the audio.
        val lyricStart = audioStart + fileOffsetMs + userOffsetMs
        val secs = length.seconds
        if (secs == null) {
            val end = all[last].startMs + durs[last]
            val raw = end - start
            val picked = (first..last).toList()
            return Plan(
                lyricStartMs = lyricStart,
                lyricsMs = raw.coerceIn(MIN_LYRICS_MS, MAX_LYRICS_MS),
                audioStartMs = audioStart,
                lineStarts = picked.map { all[it].startMs },
                lineDurs = picked.map { durs[it] },
                capped = raw > MAX_LYRICS_MS,
                lineIndices = picked,
                pickedCount = picked.size
            )
        }
        val want = secs * 1000L - ENDING_MS
        // how much song there is from the first picked line (video time)
        val left = if (songDurationMs > 0) songDurationMs - audioStart
        else all.last().startMs + durs.last() + TAIL_MS - lyricStart
        val lyricsMs = minOf(want, left).coerceAtLeast(MIN_LYRICS_MS)
        val indices = windowLines(all, first, last, lyricStart + lyricsMs)
        return Plan(
            lyricStartMs = lyricStart,
            lyricsMs = lyricsMs,
            audioStartMs = audioStart,
            lineStarts = indices.map { all[it].startMs },
            lineDurs = indices.map { durs[it] },
            capped = false,
            lineIndices = indices,
            pickedCount = last - first + 1,
            length = length,
            endsWithSong = left < want
        )
    }

    /**
     * The lines on a 30/45/60 card: the picked ones, then each later line with words that starts
     * before [endLyricMs], then up to [EXTRA_AFTER] more so the card isn't empty below the last one.
     */
    fun windowLines(all: List<LyricsLine>, first: Int, last: Int, endLyricMs: Long): List<Int> {
        val out = (first..last).toMutableList()
        var extra = 0
        var j = last + 1
        while (j < all.size && extra < EXTRA_AFTER) {
            if (all[j].text.isNotBlank()) {
                if (all[j].startMs >= endLyricMs) extra++
                out += j
            }
            j++
        }
        return out
    }

    /** Gain for one audio frame of the clip: a short fade in and out, flat in between. */
    fun gain(frame: Long, clipFrames: Long, fadeFrames: Long): Float {
        if (frame < 0 || frame >= clipFrames) return 0f
        if (fadeFrames <= 0) return 1f
        val fade = minOf(fadeFrames, clipFrames / 2).coerceAtLeast(1)
        val inG = (frame + 1).toFloat() / fade
        val outG = (clipFrames - frame).toFloat() / fade
        return minOf(1f, inG, outG)
    }

    /**
     * The part of a decoded audio buffer that falls inside the clip.
     * @return the first frame to keep in the buffer and how many to keep, or null for none.
     */
    fun keep(ptsUs: Long, frames: Int, sampleRate: Int, startUs: Long, endUs: Long): Pair<Int, Int>? {
        if (frames <= 0 || sampleRate <= 0) return null
        val bufEndUs = ptsUs + frames * 1_000_000L / sampleRate
        if (bufEndUs <= startUs || ptsUs >= endUs) return null
        val skip = if (ptsUs < startUs) ((startUs - ptsUs) * sampleRate / 1_000_000L).toInt() else 0
        val until = if (bufEndUs > endUs) ((endUs - ptsUs) * sampleRate / 1_000_000L).toInt() else frames
        val n = until.coerceAtMost(frames) - skip
        return if (n <= 0) null else skip to n
    }

    /** Line fill level for one picked line at a lyric-timeline time (whole-line light-up). */
    fun lineLevel(nowMs: Long, lineStartMs: Long): Float {
        val x = ((nowMs - lineStartMs + 120) / 250f).coerceIn(0f, 1f)
        return x * x * (3 - 2 * x)
    }
}
