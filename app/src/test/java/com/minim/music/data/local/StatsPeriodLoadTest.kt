package com.minim.music.data.local

import com.minim.music.data.stats.DayStats
import com.minim.music.data.stats.Days
import com.minim.music.data.stats.ListenRow
import com.minim.music.data.stats.PlayRow
import com.minim.music.data.stats.StatsEngine
import com.minim.music.data.stats.StatsPeriods
import com.minim.music.ui.RecapPopupGate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** 6.9.0 part A: what each Stats pill gets from its rows, and the daily popup staying gone. */
class StatsPeriodLoadTest {
    private val today = LocalDate.of(2026, 11, 17)
    private val todayKey = Days.key(today)

    private fun play(id: String, artist: String, day: LocalDate, min: Long) = PlayRow(
        id, "Song $id", artist, "https://img/$id", 200L, Days.startMs(Days.key(day)) + 10 * 3_600_000L, min * 60_000L, true
    )

    private fun listen(id: String, artist: String, min: Long) = ListenRow(id, "Song $id", artist, "https://img/$id", 10, min * 60_000L)

    private fun data(plays: List<PlayRow>, listens: List<ListenRow>, from: Int, to: Int): PeriodData {
        val stats = StatsEngine.compute(from, plays, listens)
        val totals = HashMap<Int, Long>()
        for (p in plays) totals[Days.keyOf(p.playedAt)] = (totals[Days.keyOf(p.playedAt)] ?: 0L) + p.listenedMs
        return PeriodData(from, to, stats, plays, listens, emptyList(), StatsEngine.hourMs(plays, listens), totals)
    }

    private fun week(): PeriodData {
        val (from, to) = StatsPeriods.range(StatsPeriods.Period.WEEK, today)
        val plays = (1..7).map { play("a$it", "Arijit Singh", today.minusDays((it % 2).toLong()), 4) } +
            (1..3).map { play("b$it", "Shreya Ghoshal", today, 3) }
        val listens = plays.map { listen(it.trackId, it.artist, it.listenedMs / 60_000L) }
        return data(plays, listens, from, to)
    }

    @Test fun weekModelHasTopFivesBarsAndCompare() {
        val d = week()
        val m = StatsPeriodLoad.assemble(StatsPeriods.Period.WEEK, today, d, prevMs = 0L, streak = 3, insights = emptyList())
        assertEquals(StatsPeriods.Period.WEEK, m.period)
        assertTrue(m.songs.size <= StatsPeriodLoad.TOP)
        assertEquals(StatsPeriodLoad.TOP, m.songs.size)
        assertEquals("Arijit Singh", m.artists.first().name)
        assertEquals(7, m.bars.size)
        assertTrue(m.compare.orEmpty().endsWith("vs last week"))
        assertEquals(StatsPeriods.long(d.stats.totalMs), m.total)
    }

    @Test fun allTimeHasNoCompareLine() {
        val m = StatsPeriodLoad.assemble(StatsPeriods.Period.ALL, today, week(), prevMs = null, streak = 0, insights = emptyList())
        assertNull(m.compare)
    }

    @Test fun numbersShowStreakAndPeakHourButNoTapesWithoutTapes() {
        val n = StatsPeriodLoad.numbers(week(), streak = 4).toMap()
        assertEquals("4", n["Day streak"])
        assertTrue(n.containsKey("Peak hour"))
        assertTrue(n.containsKey("Songs"))
        assertFalse(n.containsKey("Tapes"))
    }

    @Test fun emptyTodayStillLoads() {
        val empty = PeriodData(todayKey, todayKey, DayStats.empty(todayKey), emptyList(), emptyList(), emptyList(), LongArray(24), emptyMap())
        val m = StatsPeriodLoad.assemble(StatsPeriods.Period.TODAY, today, empty, prevMs = 0L, streak = 0, insights = emptyList())
        assertEquals("0 min", m.total)
        assertTrue(m.songs.isEmpty())
        assertNull(m.compare)
        assertFalse(StatsPeriodLoad.numbers(empty, 0).toMap().containsKey("Peak hour"))
    }

    @Test fun dailyRecapPopupIsGone() {
        assertFalse(RecapPopupGate.DAILY_POPUP)
    }
}
