package com.minim.music.data.share

import com.minim.music.data.model.LyricsLine
import com.minim.music.ui.FlowTiming

/**
 * How long an animated lyrics card runs and where its audio comes from (6.7.0).
 * The lyrics part runs from the first picked line's start to the end of the last picked
 * line, capped at 30 seconds, then a fixed 1-second ending.
 */
object LyricsVideoPlan {
    const val MAX_LYRICS_MS = 30_000L
    const val MIN_LYRICS_MS = 1_000L
    const val ENDING_MS = 1_000L
    const val FADE_TO_END_MS = 400L
    const val AUDIO_FADE_MS = 300L
    const val FPS = 30

    data class Plan(
        /** Lyric-timeline time shown on the first frame. */
        val lyricStartMs: Long,
        /** Length of the lyrics part (before the ending). */
        val lyricsMs: Long,
        /** Where the song audio starts, in the audio file's own time. */
        val audioStartMs: Long,
        /** Start and flow length of each picked line, on the lyric timeline. */
        val lineStarts: List<Long>,
        val lineDurs: List<Long>,
        val capped: Boolean
    ) {
        val totalMs: Long get() = lyricsMs + ENDING_MS
        val frames: Int get() = ((totalMs * FPS + 999) / 1000).toInt()
        val audioMs: Long get() = lyricsMs
        /** Video time of a frame. */
        fun frameMs(frame: Int): Long = frame * 1000L / FPS
    }

    /**
     * @return null when the lyrics have no timing or the range is invalid.
     */
    fun plan(
        all: List<LyricsLine>,
        first: Int,
        last: Int,
        synced: Boolean,
        fileOffsetMs: Long = 0,
        userOffsetMs: Int = 0
    ): Plan? {
        if (!synced || first < 0 || last < first || last >= all.size) return null
        val durs = FlowTiming.lineDurations(all)
        val start = all[first].startMs
        val end = all[last].startMs + durs[last]
        val raw = end - start
        val lyricsMs = raw.coerceIn(MIN_LYRICS_MS, MAX_LYRICS_MS)
        val audioStart = FlowTiming.seekTargetForLine(start, fileOffsetMs, userOffsetMs)
        // When the offsets push the seek below zero, keep the picture in step with the audio.
        val lyricStart = audioStart + fileOffsetMs + userOffsetMs
        return Plan(
            lyricStartMs = lyricStart,
            lyricsMs = lyricsMs,
            audioStartMs = audioStart,
            lineStarts = (first..last).map { all[it].startMs },
            lineDurs = (first..last).map { durs[it] },
            capped = raw > MAX_LYRICS_MS
        )
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
