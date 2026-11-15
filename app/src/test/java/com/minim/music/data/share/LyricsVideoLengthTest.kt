package com.minim.music.data.share

import com.minim.music.data.model.LyricsLine
import com.minim.music.data.share.LyricsCardOptions.Length
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 6.7.0 revision: the Length option (Fit / 30s / 45s / 60s) of an animated lyrics card. */
class LyricsVideoLengthTest {

    /** A song with a line every 4 s from 0:10 to 3:18, four minutes long. */
    private val song = (0 until 48).map { LyricsLine(10_000L + it * 4_000L, 0, "line number $it here") }
    private val songMs = 240_000L

    private fun plan(first: Int, last: Int, len: Length, dur: Long = songMs) =
        LyricsVideoPlan.plan(song, first, last, synced = true, length = len, songDurationMs = dur)!!

    @Test fun fitIsThePickedLinesCappedAtThirty() {
        val p = plan(2, 3, Length.FIT)
        assertEquals(Length.FIT, p.length)
        assertFalse(p.scrolls)
        assertEquals(listOf(2, 3), p.lineIndices)
        assertEquals(2, p.pickedCount)
        assertTrue(p.lyricsMs <= LyricsVideoPlan.MAX_LYRICS_MS)
        // five lines spanning more than 30 s are still cut at 30 s
        val gappy = listOf(0L, 9_000L, 18_000L, 27_000L, 36_000L, 45_000L).map { LyricsLine(it, 0, "la la la") }
        val f = LyricsVideoPlan.plan(gappy, 0, 4, synced = true, length = Length.FIT, songDurationMs = 200_000)!!
        assertTrue(f.capped)
        assertEquals(30_000L, f.lyricsMs)
        assertEquals(31_000L, f.totalMs)
    }

    @Test fun lengthsIncludeTheOneSecondEnding() {
        for ((len, total) in listOf(Length.S30 to 30_000L, Length.S45 to 45_000L, Length.S60 to 60_000L)) {
            val p = plan(5, 6, len)
            assertEquals(total, p.totalMs)
            assertEquals(total - LyricsVideoPlan.ENDING_MS, p.lyricsMs)
            // sound covers the whole song part
            assertEquals(p.lyricsMs, p.audioMs)
            assertTrue(p.scrolls)
            assertFalse(p.endsWithSong)
            assertFalse(p.capped)
            assertEquals(total * LyricsVideoPlan.FPS / 1000, p.frames.toLong())
        }
    }

    @Test fun startsAtTheFirstPickedLine() {
        val p = plan(5, 7, Length.S45)
        assertEquals(song[5].startMs, p.lyricStartMs)
        assertEquals(song[5].startMs, p.audioStartMs)
        assertEquals(3, p.pickedCount)
        assertEquals(listOf(5, 6, 7), p.lineIndices.take(3))
    }

    @Test fun carriesOnThroughTheFollowingLines() {
        val p = plan(5, 6, Length.S30)
        val end = p.lyricStartMs + p.lyricsMs
        // every later line that starts inside the video is on the card, in order
        val inside = (7 until song.size).filter { song[it].startMs < end }
        assertTrue(p.lineIndices.containsAll(inside))
        assertEquals(p.lineIndices.sorted(), p.lineIndices)
        // and at most three more after it
        val after = p.lineIndices.filter { song[it].startMs >= end }
        assertEquals(LyricsVideoPlan.EXTRA_AFTER, after.size)
        assertEquals(p.lineIndices.size, p.lineStarts.size)
        assertEquals(p.lineIndices.map { song[it].startMs }, p.lineStarts)
    }

    @Test fun songEndingEarlyEndsTheVideoThenTheEnding() {
        // picked at 3:10, the song ends at 4:00: 50 s left, so 60s becomes 50 s + the ending
        val p = plan(45, 45, Length.S60)
        assertTrue(p.endsWithSong)
        assertEquals(songMs - song[45].startMs, p.lyricsMs)
        assertEquals(p.lyricsMs + LyricsVideoPlan.ENDING_MS, p.totalMs)
        // 30s still fits
        assertFalse(plan(45, 45, Length.S30).endsWithSong)
        // the last lines: nothing after the song's last line
        assertEquals(listOf(45, 46, 47), p.lineIndices)
    }

    @Test fun unknownSongLengthUsesTheLastLine() {
        val p = plan(46, 46, Length.S60, dur = 0)
        assertTrue(p.endsWithSong)
        assertTrue(p.lyricsMs < 59_000L)
        assertTrue(p.lyricsMs >= LyricsVideoPlan.MIN_LYRICS_MS)
    }

    @Test fun blankLinesAreSkippedInTheWindow() {
        val withGap = song.toMutableList().apply { set(8, LyricsLine(song[8].startMs, 0, "  ")) }
        val p = LyricsVideoPlan.plan(withGap, 5, 6, synced = true, length = Length.S30, songDurationMs = songMs)!!
        assertFalse(8 in p.lineIndices)
    }

    @Test fun sixtySecondsWithSoundStaysUnderThirteenMegabytes() {
        assertTrue(LyricsVideoPlan.estimatedBytes(60_000) <= 13_000_000L)
        // Fit keeps the 6.7.0 quality
        assertEquals(LyricsVideoPlan.VIDEO_BITRATE_FIT, LyricsVideoPlan.bitrateFor(31_000))
        assertEquals(LyricsVideoPlan.VIDEO_BITRATE_LONG, plan(5, 6, Length.S45).videoBitrate)
    }

    @Test fun theSelectionLimitStaysAtFiveLines() {
        assertEquals(5, LyricSelection.MAX_LINES)
    }
}
