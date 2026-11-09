package com.minim.music.data.stats

import com.minim.music.ui.RecapCard
import com.minim.music.ui.RecapDay
import com.minim.music.ui.RecapSlides
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

/** 5.7.0 u51: tapes in stats: counting, channels, combined totals, speed saved, streak, hiding. */
class TapeStatsTest {

    private val ist = ZoneId.of("Asia/Kolkata")
    private val day = 20261108

    private fun ms(h: Int, mi: Int = 0): Long =
        LocalDateTime.of(2026, 11, 8, h, mi).atZone(ist).toInstant().toEpochMilli()

    private fun play(id: String, channel: String, at: Long, channelId: String = "") =
        TapePlayRow(id, "Tape $id", channel, channelId, "https://i/$id", 3600, at)

    private fun listen(id: String, channel: String, ms: Long, hour: Int = 20, speed: Int = 100, channelId: String = "") =
        TapeListenRow(id, "Tape $id", channel, channelId, "https://i/$id", 3600, hour, speed, ms)

    private fun music(timeMs: Long = 0L, songs: Int = 0, artists: Int = 0, peak: Int = -1) =
        DayStats(day, timeMs, songs, artists, null, null, peak, null, false)

    @Test fun aTapeCountsAfter30SecondsLikeSongs() {
        assertEquals(30_000L, TapeStats.COUNT_AFTER_MS)
        // the player counts tapes in the same 30-second check songs go through, then saves them apart
        val src = java.io.File("src/main/java/com/minim/music/playback/PlaybackController.kt").readText()
        val check = src.indexOf("if (listenedMs >= need)")
        val tapeCount = src.indexOf("database.recordTapePlay(track, channelId)")
        val songCount = src.indexOf("database.recordCountedPlay(track, heard)")
        assertTrue(check in 1 until tapeCount)
        assertTrue(tapeCount < songCount)
        assertTrue(src.contains("const val COUNT_AFTER_MS = 30_000L"))
    }

    @Test fun tapesAndChannelsAddUp() {
        val plays = listOf(
            play("a", "SR PAY STORIES", ms(21), "UCsrpay000001"),
            play("b", "SR PAY STORIES", ms(22)),
            play("a", "SR PAY STORIES", ms(23)), // played again: still one tape
            play("c", "Kaushik STORIES", ms(20))
        )
        val listens = listOf(
            listen("a", "SR PAY STORIES", 40 * 60_000L, channelId = "UCsrpay000001"),
            listen("b", "SR PAY STORIES", 30 * 60_000L),
            listen("c", "Kaushik STORIES", 10 * 60_000L),
            listen("d", "Short Clips", 10_000L) // 10 s, never counted: no tape, no channel
        )
        val t = TapeStats.compute(plays, listens, ist)
        assertEquals(3, t.tapes)
        assertEquals(2, t.channels)
        assertEquals(80 * 60_000L + 10_000L, t.ms)
        assertEquals("SR PAY STORIES", t.topChannel!!.name)
        assertEquals(70 * 60_000L, t.topChannel!!.ms)
        assertEquals(2, t.topChannel!!.tapes)
        assertEquals("UCsrpay000001", t.topChannel!!.channelId)
        assertEquals("a", t.topTape!!.videoId)
        assertEquals(2, t.topTape!!.plays)
        assertTrue(t.shows)

        val list = TapeStats.tapes(plays, listens)
        assertEquals(listOf("a", "b", "c"), list.map { it.videoId })
        assertEquals("UCsrpay000001", list[0].channelId)
        val chans = TapeStats.channels(plays, listens)
        assertEquals(listOf("SR PAY STORIES", "Kaushik STORIES"), chans.map { it.name })
    }

    @Test fun aChannelCountsFromListeningAloneAfter30Seconds() {
        val t = TapeStats.compute(emptyList(), listOf(listen("x", "Long Talks", 45_000L)), ist)
        assertEquals(0, t.tapes)
        assertEquals(1, t.channels)
        assertTrue(t.shows)
    }

    @Test fun musicOnlyDayHidesTapes() {
        val t = TapeStats.compute(emptyList(), emptyList(), ist)
        assertEquals(TapeDay.NONE, t)
        assertFalse(t.shows)
        val s = TapeStats.combine(music(32 * 60_000L, 12, 5, peak = 9), t, LongArray(24), LongArray(24))
        assertEquals(music(32 * 60_000L, 12, 5, peak = 9), s)
        assertFalse(s.tapes.shows)
        assertEquals(32 * 60_000L, s.totalMs)
        assertFalse(RecapSlides.plan(s, RecapDay.NONE, false).contains(RecapSlides.Kind.TAPES))
        assertEquals(3, RecapCard.tiles(s).size)
        assertNull(TapeStats.savedLine(s.tapes))
    }

    @Test fun listenedIsSongsAndTapesTogether() {
        val tapes = TapeStats.compute(
            listOf(play("a", "SR PAY STORIES", ms(22))),
            listOf(listen("a", "SR PAY STORIES", 70 * 60_000L, hour = 22)), ist
        )
        val songHours = LongArray(24).also { it[9] = 30 * 60_000L }
        val s = TapeStats.combine(music(30 * 60_000L, 8, 4, peak = 9), tapes, songHours, TapeStats.hourMs(
            listOf(listen("a", "SR PAY STORIES", 70 * 60_000L, hour = 22))
        ))
        assertEquals(30 * 60_000L, s.timeMs) // songs alone stay as they were
        assertEquals(100 * 60_000L, s.totalMs)
        assertEquals(22, s.peakHour) // the tape hour beats the song hour
        assertEquals(8, s.songs)
        assertEquals(1, s.tapes.tapes)
        assertEquals(listOf("1h 40m", "8", "4", "1"), RecapCard.tiles(s).map { it.first })
    }

    @Test fun aTapeOnlyDayIsARealDay() {
        val tapes = TapeStats.compute(
            listOf(play("a", "SR PAY STORIES", ms(20))),
            listOf(listen("a", "SR PAY STORIES", 20 * 60_000L)), ist
        )
        val s = TapeStats.combine(DayStats.empty(day), tapes, LongArray(24), TapeStats.hourMs(
            listOf(listen("a", "SR PAY STORIES", 20 * 60_000L))
        ))
        assertTrue(s.hasHistory)
        assertFalse(s.isQuiet)
        assertEquals(20, s.peakHour)
        val plan = RecapSlides.plan(s, RecapDay.NONE, false)
        assertTrue(plan.contains(RecapSlides.Kind.TIME))
        assertTrue(plan.contains(RecapSlides.Kind.TAPES))
        assertEquals("top channel", RecapCard.pillPick(s)!!.label)
    }

    @Test fun timeSavedBySpeed() {
        // 44 min heard at 1.5x covered 66 min: 22 min saved
        val t = TapeStats.compute(
            listOf(play("a", "SR PAY STORIES", ms(20))),
            listOf(
                listen("a", "SR PAY STORIES", 44 * 60_000L, speed = 150),
                listen("a", "SR PAY STORIES", 10 * 60_000L, speed = 100)
            ), ist
        )
        assertEquals(22 * 60_000L, t.savedMs)
        assertEquals(150, t.speedPct)
        assertEquals("1.5x saved you 22 min", TapeStats.savedLine(t))

        val mixed = TapeStats.compute(
            listOf(play("a", "X", ms(20))),
            listOf(listen("a", "X", 40 * 60_000L, speed = 125), listen("a", "X", 20 * 60_000L, speed = 200)), ist
        )
        assertEquals(30 * 60_000L, mixed.savedMs)
        assertEquals("speeding up saved you 30 min", TapeStats.savedLine(mixed))

        val normal = TapeStats.compute(listOf(play("a", "X", ms(20))), listOf(listen("a", "X", 60 * 60_000L)), ist)
        assertNull(TapeStats.savedLine(normal))
        assertEquals("1.25x", TapeStats.speedLabel(125))
        assertEquals("2x", TapeStats.speedLabel(200))
        assertEquals("1h 5 min", TapeStats.savedText(65 * 60_000L))
    }

    @Test fun nightOwlWhenMostTapesStartAfter11Pm() {
        val late = TapeStats.compute(
            listOf(play("a", "X", ms(23, 10)), play("b", "X", ms(23, 50)), play("c", "X", ms(19))),
            listOf(listen("a", "X", 60_000L)), ist
        )
        assertTrue(late.nightOwl)
        val early = TapeStats.compute(
            listOf(play("a", "X", ms(23, 10)), play("b", "X", ms(18)), play("c", "X", ms(19))),
            listOf(listen("a", "X", 60_000L)), ist
        )
        assertFalse(early.nightOwl)
        val s = TapeStats.combine(DayStats.empty(day), late, LongArray(24), LongArray(24))
        assertTrue(RecapSlides.tapesLine(s).endsWith("Night owl."))
        assertEquals("3 tapes.", RecapSlides.tapesLine(TapeStats.combine(DayStats.empty(day), early, LongArray(24), LongArray(24))))
    }

    @Test fun streakCountsSongsOrTapes() {
        val today = 20261108
        val songs = mapOf(20261108 to 15 * 60_000L, 20261106 to 12 * 60_000L)
        val tapes = mapOf(20261107 to 40 * 60_000L, 20261105 to 4 * 60_000L)
        // without tapes, yesterday breaks it
        assertEquals(1, DayStreak.count(today, songs))
        // with tapes: Sunday (songs), Saturday (tapes), Friday (songs); Thursday has 4 min only
        assertEquals(3, DayStreak.count(today, DayStreak.merge(songs, tapes)))
        // songs and tapes add up on one day: 6 + 5 = 11 minutes
        assertEquals(
            1, DayStreak.count(today, DayStreak.merge(mapOf(today to 6 * 60_000L), mapOf(today to 5 * 60_000L)))
        )
        assertEquals(songs, DayStreak.merge(songs, emptyMap()))
    }
}
