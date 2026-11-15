package com.minim.music.data.share

import com.minim.music.data.model.LyricsLine
import com.minim.music.ui.FlowTiming
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 6.7.0: how long an animated lyrics card runs, where its audio starts, and the audio fades. */
class LyricsVideoPlanTest {

    private fun line(start: Long, text: String = "one two three four") = LyricsLine(start, 0, text)

    private val song = listOf(
        line(10_000), line(13_000), line(16_500), line(20_000), line(24_000), line(60_000), line(64_000)
    )

    @Test fun runsFromFirstStartToLastEnd() {
        val p = LyricsVideoPlan.plan(song, 1, 2, synced = true)!!
        val durs = FlowTiming.lineDurations(song)
        assertEquals(13_000L, p.lyricStartMs)
        assertEquals(13_000L, p.audioStartMs)
        assertEquals(16_500L + durs[2] - 13_000L, p.lyricsMs)
        assertFalse(p.capped)
        assertEquals(listOf(13_000L, 16_500L), p.lineStarts)
        assertEquals(listOf(durs[1], durs[2]), p.lineDurs)
    }

    @Test fun oneSecondEndingAfterTheLyrics() {
        val p = LyricsVideoPlan.plan(song, 0, 0, synced = true)!!
        assertEquals(1_000L, LyricsVideoPlan.ENDING_MS)
        assertEquals(p.lyricsMs + 1_000L, p.totalMs)
        assertEquals(p.lyricsMs, p.audioMs)
        assertEquals(((p.totalMs * 30 + 999) / 1000).toInt(), p.frames)
        assertEquals(0L, p.frameMs(0))
        assertEquals(1000L, p.frameMs(30))
    }

    @Test fun cappedAtThirtySeconds() {
        // 24 s to past 64 s is over 40 s of lyrics
        val p = LyricsVideoPlan.plan(song, 4, 6, synced = true)!!
        assertTrue(p.capped)
        assertEquals(30_000L, p.lyricsMs)
        assertEquals(31_000L, p.totalMs)
        assertEquals(930, p.frames)
    }

    @Test fun veryShortLineStillGetsASecond() {
        val quick = listOf(LyricsLine(5_000, 0, "hey"), LyricsLine(5_200, 0, "ho"))
        val p = LyricsVideoPlan.plan(quick, 0, 0, synced = true)!!
        assertEquals(LyricsVideoPlan.MIN_LYRICS_MS, p.lyricsMs)
    }

    @Test fun noPlanWithoutTimingOrABadRange() {
        assertNull(LyricsVideoPlan.plan(song, 0, 1, synced = false))
        assertNull(LyricsVideoPlan.plan(song, 2, 1, synced = true))
        assertNull(LyricsVideoPlan.plan(song, -1, 1, synced = true))
        assertNull(LyricsVideoPlan.plan(song, 0, 7, synced = true))
    }

    @Test fun offsetsMoveTheAudioNotTheLyrics() {
        // the file is 500 ms ahead and the user nudged it 200 ms: the audio starts earlier
        val p = LyricsVideoPlan.plan(song, 1, 1, synced = true, fileOffsetMs = 500, userOffsetMs = 200)!!
        assertEquals(12_300L, p.audioStartMs)
        assertEquals(13_000L, p.lyricStartMs)
        // a seek below zero keeps picture and sound together
        val early = LyricsVideoPlan.plan(listOf(line(300), line(4_000)), 0, 0, true, fileOffsetMs = 800)!!
        assertEquals(0L, early.audioStartMs)
        assertEquals(800L, early.lyricStartMs)
    }

    @Test fun audioFadesInAndOut() {
        val clip = 44_100L * 5
        val fade = 44_100L * 300 / 1000
        assertTrue(LyricsVideoPlan.gain(0, clip, fade) < 0.01f)
        assertEquals(1f, LyricsVideoPlan.gain(fade, clip, fade), 0f)
        assertEquals(1f, LyricsVideoPlan.gain(clip / 2, clip, fade), 0f)
        assertTrue(LyricsVideoPlan.gain(clip - 1, clip, fade) < 0.01f)
        assertEquals(0.5f, LyricsVideoPlan.gain(fade / 2 - 1, clip, fade), 0.01f)
        assertEquals(0f, LyricsVideoPlan.gain(clip, clip, fade), 0f)
        assertEquals(0f, LyricsVideoPlan.gain(-1, clip, fade), 0f)
        // never louder than the song
        for (f in 0 until clip step 997) assertTrue(LyricsVideoPlan.gain(f, clip, fade) <= 1f)
    }

    @Test fun keepsOnlyTheClipFromDecodedBuffers() {
        val rate = 48_000
        // buffer fully before or after the clip
        assertNull(LyricsVideoPlan.keep(0, 1024, rate, 1_000_000, 2_000_000))
        assertNull(LyricsVideoPlan.keep(2_000_000, 1024, rate, 1_000_000, 2_000_000))
        // buffer starting 10 ms before the clip: skip 480 frames
        assertEquals(480 to 544, LyricsVideoPlan.keep(990_000, 1024, rate, 1_000_000, 2_000_000))
        // inside: all of it
        assertEquals(0 to 1024, LyricsVideoPlan.keep(1_500_000, 1024, rate, 1_000_000, 2_000_000))
        // running past the end: cut at the end
        assertEquals(0 to 480, LyricsVideoPlan.keep(1_990_000, 1024, rate, 1_000_000, 2_000_000))
    }

    @Test fun wholeLineLightsUpQuickly() {
        assertEquals(0f, LyricsVideoPlan.lineLevel(9_000, 10_000), 0f)
        assertTrue(LyricsVideoPlan.lineLevel(10_000, 10_000) > 0.3f)
        assertEquals(1f, LyricsVideoPlan.lineLevel(10_200, 10_000), 0f)
    }
}
