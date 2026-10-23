package com.minim.music.data.stats

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

/** v4.9.2: the day streak rule and the lists behind today's tappable boxes. */
class TodayStatsTest {

    private val ist = ZoneId.of("Asia/Kolkata")
    private val min = 60_000L
    private val today = 20261022

    private fun ms(h: Int, mi: Int): Long =
        LocalDateTime.of(2026, 10, 22, h, mi).atZone(ist).toInstant().toEpochMilli()

    // ---- day streak ----

    @Test fun nothingAtAllIsZero() {
        assertEquals(0, DayStreak.count(today, emptyMap()))
    }

    @Test fun todayAloneAtTenMinutesIsOne() {
        assertEquals(1, DayStreak.count(today, mapOf(today to 10 * min)))
        assertEquals(0, DayStreak.count(today, mapOf(today to 10 * min - 1)))
    }

    @Test fun todayUnderTenMinutesDoesNotBreakIt() {
        val m = mapOf(20261021 to 30 * min, 20261020 to 12 * min, 20261019 to 10 * min, today to 3 * min)
        assertEquals(3, DayStreak.count(today, m))
    }

    @Test fun todayCountsOnceItReachesTenMinutes() {
        val m = mapOf(20261021 to 30 * min, 20261020 to 12 * min, 20261019 to 10 * min, today to 11 * min)
        assertEquals(4, DayStreak.count(today, m))
    }

    @Test fun aPastDayUnderTenMinutesResetsIt() {
        // Tuesday only 9 minutes: the streak is Wednesday (and today once it's 10+)
        val m = mapOf(20261021 to 40 * min, 20261020 to 9 * min, 20261019 to 60 * min, 20261018 to 60 * min)
        assertEquals(1, DayStreak.count(today, m))
        assertEquals(2, DayStreak.count(today, m + (today to 15 * min)))
    }

    @Test fun aMissedYesterdayWithListeningTodayIsOne() {
        val m = mapOf(20261020 to 60 * min, today to 20 * min)
        assertEquals(1, DayStreak.count(today, m))
        assertEquals(0, DayStreak.count(today, m - today))
    }

    @Test fun streakCrossesMonthsAndStopsAtTheCap() {
        val m = mapOf(20261101 to 20 * min, 20261031 to 20 * min, 20261030 to 20 * min)
        assertEquals(3, DayStreak.count(20261101, m))
        var d = today
        val every = HashMap<Int, Long>()
        repeat(DayStreak.MAX_DAYS + 50) { every[d] = 20 * min; d = Days.previous(d) }
        assertEquals(DayStreak.MAX_DAYS + 1, DayStreak.count(today, every))
    }

    // ---- today's sheets ----

    private fun play(id: String, artist: String, at: Long) =
        PlayRow(id, "Song $id", artist, "", 200, at, 30_000, true)

    @Test fun songsSheetCountsPlaysAndAddsUpToTheBox() {
        val plays = listOf(
            play("a", "Anirudh Ravichander", ms(9, 0)),
            play("b", "Arijit Singh", ms(9, 5)),
            play("a", "Anirudh Ravichander", ms(9, 10)),
            play("a", "Anirudh Ravichander", ms(9, 15))
        )
        val listens = listOf(ListenRow("a", "Song a", "Anirudh Ravichander", "", 9, 9 * min),
            ListenRow("b", "Song b", "Arijit Singh", "", 9, 3 * min))
        val songs = TodayLists.songs(plays, listens)
        assertEquals(listOf("a" to 3, "b" to 1), songs.map { it.trackId to it.plays })
        val stats = StatsEngine.compute(today, plays, listens, ist)
        assertEquals(stats.songs, songs.sumOf { it.plays })
    }

    @Test fun artistsSheetMatchesTheBoxAndTopArtist() {
        val plays = listOf(
            play("a", "Anirudh Ravichander, Shreya", ms(9, 0)),
            play("b", "Arijit Singh", ms(9, 5))
        )
        val listens = listOf(
            ListenRow("a", "Song a", "Anirudh Ravichander, Shreya", "", 9, 4 * min),
            ListenRow("b", "Song b", "Arijit Singh", "", 9, 7 * min),
            ListenRow("c", "Song c", "Pritam", "", 10, 40_000)
        )
        val artists = TodayLists.artists(plays, listens, ist)
        val stats = StatsEngine.compute(today, plays, listens, ist)
        assertEquals(listOf("Arijit Singh", "Anirudh Ravichander", "Pritam"), artists.map { it.name })
        assertEquals(stats.artists, artists.size)
        assertEquals(stats.topArtist, artists.first())
        assertEquals(7 * min, artists.first().ms)
    }
}
