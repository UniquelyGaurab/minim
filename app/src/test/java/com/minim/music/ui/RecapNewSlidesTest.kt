package com.minim.music.ui

import com.minim.music.data.stats.DayStats
import com.minim.music.data.stats.DayStreak
import com.minim.music.data.stats.Days
import com.minim.music.data.stats.ListenRow
import com.minim.music.data.stats.PlayRow
import com.minim.music.data.stats.RecapCompare
import com.minim.music.data.stats.RecapMore
import com.minim.music.data.stats.RecapMoreMath
import com.minim.music.data.stats.RecapSession
import com.minim.music.data.stats.RecapSong
import com.minim.music.data.stats.StatsEngine
import com.minim.music.ui.RecapSlides.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

/** 5.8.3 (update 55): the new recap slides. */
class RecapNewSlidesTest {

    private val zone = ZoneId.of("Asia/Kolkata")
    private val day = 20261108 // a Sunday
    private val min = 60_000L

    private fun at(hour: Int, minute: Int = 0, second: Int = 0): Long =
        Days.date(day).atTime(hour, minute, second).atZone(zone).toInstant().toEpochMilli()

    /** A play is saved 30 s after it starts. */
    private fun play(id: String, startMs: Long, heardMs: Long = 180_000L) =
        PlayRow(id, "Song $id", "Artist $id", "https://img/$id.jpg", 180L, startMs + 30_000L, heardMs, true)

    private fun song(i: Int) = RecapSong("t$i", "Song $i", "Artist $i", "https://img/$i.jpg")

    private fun basePlays() = listOf(
        play("a", at(8)), play("b", at(9)), play("a", at(10)), play("c", at(12)), play("b", at(13))
    )

    private fun listens(plays: List<PlayRow>) = plays.groupBy { it.trackId }.map { (id, ps) ->
        ListenRow(id, "Song $id", "Artist $id", "https://img/$id.jpg", 9, ps.size * 3 * min)
    }

    private fun stats(plays: List<PlayRow> = basePlays()): DayStats =
        StatsEngine.compute(day, plays, listens(plays), zone)

    // ---- first listens -----------------------------------------------------------------------

    @Test fun firstListensAreOnlySongsNeverPlayedBeforeInFirstPlayOrder() {
        val plays = listOf(play("c", at(12)), play("a", at(8)), play("b", at(9)), play("a", at(10)), play("d", at(14)))
        val first = RecapMoreMath.firstListens(plays, playedBefore = setOf("b"))
        assertEquals(listOf("a", "c", "d"), first.map { it.trackId })
    }

    @Test fun firstListensGridCapsAtTenWithAndNMore() {
        assertEquals(10, RecapMoreMath.GRID_MAX)
        assertEquals("and 2 more", RecapMoreMath.moreLine(12, RecapMoreMath.GRID_MAX))
        assertNull(RecapMoreMath.moreLine(10, RecapMoreMath.GRID_MAX))
        assertNull(RecapMoreMath.moreLine(3, RecapMoreMath.GRID_MAX))
        assertEquals("12 first listens", RecapMoreMath.firstListensHeadline(12))
        assertEquals("1 first listen", RecapMoreMath.firstListensHeadline(1))
    }

    @Test fun firstListensSlideIsSkippedWhenThereAreNone() {
        val s = stats()
        val d = RecapDay.compute(s, basePlays(), emptyList(), emptyList(), zone)
        assertFalse(Kind.FIRST_LISTENS in RecapSlides.plan(s, d, false, RecapMore.NONE))
        assertTrue(Kind.FIRST_LISTENS in RecapSlides.plan(s, d, false, RecapMore(firstListens = listOf(song(1)))))
    }

    // ---- hearted today -----------------------------------------------------------------------

    @Test fun heartedListCapsAtTenWithAndNMore() {
        assertEquals(10, RecapMoreMath.LIST_MAX)
        assertEquals("and 5 more", RecapMoreMath.moreLine(15, RecapMoreMath.LIST_MAX))
        assertNull(RecapMoreMath.moreLine(10, RecapMoreMath.LIST_MAX))
        assertEquals("3 songs hearted", RecapMoreMath.heartedHeadline(3))
        assertEquals("1 song hearted", RecapMoreMath.heartedHeadline(1))
    }

    @Test fun heartedSlideIsSkippedWhenNothingWasHearted() {
        val s = stats()
        val d = RecapDay.compute(s, basePlays(), emptyList(), emptyList(), zone)
        assertFalse(Kind.HEARTED in RecapSlides.plan(s, d, false, RecapMore.NONE))
        assertTrue(Kind.HEARTED in RecapSlides.plan(s, d, false, RecapMore(hearted = (1..14).map { song(it) })))
    }

    // ---- longest session ---------------------------------------------------------------------

    @Test fun longestSessionBreaksOnGapsOverTenMinutes() {
        // 10 PM: four 3-minute songs back to back with a 5 minute pause = one session of 12 min heard
        // 8 AM: two songs, then a 20 minute gap, then one more
        val plays = listOf(
            play("m1", at(8, 0)), play("m2", at(8, 3)), play("m3", at(8, 26)),
            play("n1", at(22, 0)), play("n2", at(22, 3)), play("n3", at(22, 11)), play("n4", at(22, 14))
        )
        val s = RecapMoreMath.longestSession(plays)!!
        assertEquals(12 * min, s.ms)
        assertEquals(4, s.plays)
        assertEquals(at(22, 0), s.startMs)
        assertEquals("https://img/n1.jpg", s.firstThumb)
    }

    @Test fun aRealStopEndsTheSession() {
        val plays = listOf(play("a", at(10, 0)), play("b", at(10, 14)))
        assertEquals(1, RecapMoreMath.longestSession(plays)!!.plays)
    }

    @Test fun shortSessionsGetNoSlide() {
        val tiny = RecapSession(10 * min, at(9), at(9, 10), 3, "")
        assertNull(RecapMoreMath.sessionForSlide(tiny))
        val one = RecapSession(40 * min, at(9), at(9, 40), 1, "")
        assertNull(RecapMoreMath.sessionForSlide(one))
        val good = RecapSession(130 * min, at(22), at(23, 59), 40, "")
        assertNotNull(RecapMoreMath.sessionForSlide(good))
        assertNull(RecapMoreMath.longestSession(emptyList()))
    }

    // ---- compared with yesterday -------------------------------------------------------------

    @Test fun comparedWithTheDayBefore() {
        val today = stats().copy(timeMs = 100 * min, songs = 20, artists = 6)
        val before = DayStats.empty(Days.previous(day)).copy(timeMs = 60 * min, songs = 16, artists = 7)
        val c = RecapMoreMath.compare(today, before)!!
        assertEquals(40 * min, c.diffMs)
        assertEquals("40m", RecapMoreMath.compareBig(c))
        assertEquals("more than Saturday", RecapMoreMath.compareLine(c, RecapMoreMath.beforeName(day)))
        assertEquals("4 more songs  ·  1 fewer artist", RecapMoreMath.compareCounts(c))
        val less = RecapCompare(30 * min, 90 * min, 5, 5, 2, 2)
        assertEquals("less than Saturday", RecapMoreMath.compareLine(less, "Saturday"))
        assertEquals("same number of songs  ·  same number of artists", RecapMoreMath.compareCounts(less))
        val same = RecapCompare(30 * min, 30 * min + 20_000L, 5, 5, 2, 2)
        assertEquals("Same", RecapMoreMath.compareBig(same))
    }

    @Test fun noDataForTheDayBeforeSkipsTheSlide() {
        val today = stats()
        assertNull(RecapMoreMath.compare(today, null))
        assertNull(RecapMoreMath.compare(today, DayStats.empty(Days.previous(day))))
        val d = RecapDay.compute(today, basePlays(), emptyList(), emptyList(), zone)
        assertFalse(Kind.VS_YESTERDAY in RecapSlides.plan(today, d, false, RecapMore.NONE))
    }

    // ---- day streak and all-time -------------------------------------------------------------

    @Test fun dayStreakIsTheStatsStreakAsOfThatDay() {
        val ms = HashMap<Int, Long>()
        var d = day
        repeat(12) { ms[d] = 20 * min; d = Days.previous(d) }
        assertEquals(12, RecapMoreMath.streakAsOf(day, ms))
        assertEquals(DayStreak.count(day, ms), RecapMoreMath.streakAsOf(day, ms))
        assertEquals("Day 12 in a row", RecapMoreMath.streakLine(12))
        // under 10 minutes on the day itself: the streak ended there
        ms[day] = 5 * min
        assertEquals(0, RecapMoreMath.streakAsOf(day, ms))
    }

    @Test fun streakSlideNeedsTwoDaysInARow() {
        val s = stats()
        val d = RecapDay.compute(s, basePlays(), emptyList(), emptyList(), zone)
        assertFalse(Kind.STREAK in RecapSlides.plan(s, d, false, RecapMore(streakDays = 1)))
        assertTrue(Kind.STREAK in RecapSlides.plan(s, d, false, RecapMore(streakDays = 12)))
    }

    @Test fun allTimeLineOnTheNumberOne() {
        assertEquals("16 today, 54 all time", RecapMoreMath.allTimeLine(16, 54))
        // never less all time than today
        assertEquals("3 today, 3 all time", RecapMoreMath.allTimeLine(3, 2))
    }

    // ---- order -------------------------------------------------------------------------------

    @Test fun everythingInOrderWithTheCardLast() {
        val plays = basePlays() + listOf(play("d", at(18)), play("e", at(19)))
        val s = stats(plays)
        val d = RecapDay.compute(s, plays, listens(plays), emptyList(), zone)
        val more = RecapMore(
            firstListens = listOf(song(1)), hearted = listOf(song(2)),
            session = RecapSession(40 * min, at(8), at(9), 12, ""),
            compare = RecapCompare(90 * min, 60 * min, 7, 5, 5, 4), streakDays = 5, topAllTime = 30
        )
        assertEquals(
            listOf(
                Kind.INTRO, Kind.TIME, Kind.VS_YESTERDAY, Kind.HOURS, Kind.SESSION, Kind.FIRST_LAST, Kind.FIRST_LISTENS,
                Kind.GUESS, Kind.TOP5, Kind.ARTIST, Kind.HEARTED, Kind.STREAK, Kind.ROAST, Kind.PERSONA, Kind.CARD
            ),
            RecapSlides.plan(s, d, hasRoast = true, more = more)
        )
        // with nothing extra it's exactly the 5.8.2 story
        assertEquals(
            listOf(Kind.INTRO, Kind.TIME, Kind.HOURS, Kind.FIRST_LAST, Kind.GUESS, Kind.TOP5, Kind.ARTIST, Kind.ROAST, Kind.PERSONA, Kind.CARD),
            RecapSlides.plan(s, d, hasRoast = true)
        )
    }
}
