package com.minim.music.data.stats

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class StatsEngineTest {

    private val ist = ZoneId.of("Asia/Kolkata")

    private fun ms(y: Int, mo: Int, d: Int, h: Int, mi: Int, s: Int = 0): Long =
        LocalDateTime.of(y, mo, d, h, mi, s).atZone(ist).toInstant().toEpochMilli()

    private fun play(
        id: String, artist: String, at: Long, durSec: Long = 200, tracked: Boolean = true,
        listened: Long = 30_000
    ) = PlayRow(id, "Song $id", artist, "", durSec, at, listened, tracked)

    // ---- days and splitting at midnight ----

    @Test fun dayKeyAndLabel() {
        assertEquals(20261022, Days.keyOf(ms(2026, 10, 22, 0, 0), ist))
        assertEquals(20261021, Days.keyOf(ms(2026, 10, 21, 23, 59, 59), ist))
        assertEquals("Thursday, 22 Oct", Days.label(20261022))
        assertEquals(20261031, Days.previous(20261101))
        assertEquals(ms(2026, 10, 22, 0, 0), Days.startMs(20261022, ist))
        assertEquals(ms(2026, 10, 23, 0, 0), Days.endMs(20261022, ist))
    }

    @Test fun songStartedAt1158PmIsSplitAtMidnight() {
        // 11:58 PM to 12:03 AM: 2 minutes for Wednesday, 3 minutes for Thursday
        val slices = ListenSplit.split(ms(2026, 10, 21, 23, 58), ms(2026, 10, 22, 0, 3), ist)
        assertEquals(
            listOf(ListenSlice(20261021, 23, 120_000), ListenSlice(20261022, 0, 180_000)),
            slices
        )
    }

    @Test fun splitWithinOneHourIsOneSlice() {
        val slices = ListenSplit.split(ms(2026, 10, 22, 10, 5), ms(2026, 10, 22, 10, 5, 40), ist)
        assertEquals(listOf(ListenSlice(20261022, 10, 40_000)), slices)
    }

    @Test fun splitAcrossHoursKeepsEverySecond() {
        val start = ms(2026, 10, 22, 9, 50)
        val end = ms(2026, 10, 22, 11, 10)
        val slices = ListenSplit.split(start, end, ist)
        assertEquals(listOf(9, 10, 11), slices.map { it.hour })
        assertEquals(end - start, slices.sumOf { it.ms })
        assertTrue(ListenSplit.split(end, start, ist).isEmpty())
    }

    @Test fun midnightSplitLandsInEachDaysStats() {
        val wed = StatsEngine.compute(
            20261021, emptyList(),
            listOf(ListenRow("a", "A", "Anirudh", "", 23, 120_000)), ist
        )
        val thu = StatsEngine.compute(
            20261022, emptyList(),
            listOf(ListenRow("a", "A", "Anirudh", "", 0, 180_000)), ist
        )
        assertEquals(120_000, wed.timeMs)
        assertEquals(180_000, thu.timeMs)
        assertEquals(0, thu.peakHour)
    }

    // ---- real time vs the old-data estimate ----

    @Test fun oldPlaysAreEstimatedWithTheSongsFullLength() {
        val old = play("a", "Anirudh", ms(2026, 10, 21, 22, 0), durSec = 240, tracked = false)
        assertEquals(240_000, StatsEngine.estimateMs(old))
        val unknownLength = play("b", "Anirudh", ms(2026, 10, 21, 22, 5), durSec = 0, tracked = false, listened = 31_000)
        assertEquals(31_000, StatsEngine.estimateMs(unknownLength))

        val day = StatsEngine.compute(20261021, listOf(old, unknownLength), emptyList(), ist)
        assertEquals(271_000, day.timeMs)
        assertTrue(day.estimated)
        assertEquals(22, day.peakHour)
    }

    @Test fun trackedPlaysUseRealTimeNotSongLength() {
        // a 4-minute song skipped at 0:40 counts 40 seconds, not 4 minutes
        val p = play("a", "Anirudh", ms(2026, 10, 22, 9, 0, 30), durSec = 240, tracked = true, listened = 40_000)
        val day = StatsEngine.compute(
            20261022, listOf(p),
            listOf(ListenRow("a", "Song a", "Anirudh", "", 9, 40_000)), ist
        )
        assertEquals(40_000, day.timeMs)
        assertFalse(day.estimated)
    }

    @Test fun installDayMixesOldEstimateAndRealTime() {
        val old = play("a", "Anirudh", ms(2026, 10, 22, 8, 0), durSec = 180, tracked = false)
        val new = play("b", "Arijit Singh", ms(2026, 10, 22, 11, 0), durSec = 300, tracked = true)
        val day = StatsEngine.compute(
            20261022, listOf(old, new),
            listOf(ListenRow("b", "Song b", "Arijit Singh", "", 11, 100_000)), ist
        )
        assertEquals(280_000, day.timeMs)
        assertEquals(2, day.songs)
        assertEquals(2, day.artists)
        assertEquals("Anirudh", day.topArtist?.name)
    }

    // ---- what a day shows ----

    @Test fun topSongTopArtistAndArtistCount() {
        val plays = listOf(
            play("a", "Anirudh, Arijit Singh", ms(2026, 10, 21, 20, 0)),
            play("a", "Anirudh", ms(2026, 10, 21, 20, 5)),
            play("b", "Arijit Singh", ms(2026, 10, 21, 21, 0)),
            play("c", "Pritam & Arijit Singh", ms(2026, 10, 21, 21, 10))
        )
        val listens = listOf(
            ListenRow("a", "Song a", "Anirudh", "", 20, 400_000),
            ListenRow("b", "Song b", "Arijit Singh", "", 21, 500_000),
            ListenRow("c", "Song c", "Pritam & Arijit Singh", "", 21, 100_000)
        )
        val day = StatsEngine.compute(20261021, plays, listens, ist)
        assertEquals(4, day.songs)
        assertEquals(3, day.artists) // Anirudh, Arijit Singh, Pritam
        assertEquals("a", day.topSong?.trackId)
        assertEquals(2, day.topSong?.plays)
        assertEquals("Arijit Singh", day.topArtist?.name) // most listening time
        assertEquals(21, day.peakHour)
        assertEquals(1_000_000, day.timeMs)
    }

    @Test fun primaryArtistTakesTheFirstName() {
        assertEquals("Arijit Singh", StatsEngine.primaryArtist("Arijit Singh, Pritam"))
        assertEquals("Pritam", StatsEngine.primaryArtist("Pritam & Arijit Singh"))
        assertEquals("Anirudh", StatsEngine.primaryArtist("Anirudh feat. Dhee"))
        assertEquals("Vijay", StatsEngine.primaryArtist("Vijay - Topic"))
        assertEquals("", StatsEngine.primaryArtist("Unknown Artist"))
    }

    // ---- quiet days ----

    @Test fun quietDayRule() {
        assertTrue(StatsEngine.isQuiet(4 * 60_000L + 59_000L, 3)) // under 5 minutes
        assertTrue(StatsEngine.isQuiet(20 * 60_000L, 0)) // no counted song
        assertFalse(StatsEngine.isQuiet(5 * 60_000L, 1))
        assertTrue(DayStats.empty(20261022).isQuiet)
    }

    @Test fun quietDayGetsNoPopupButIsStillTheRecap() {
        val quiet = StatsEngine.compute(
            20261021, listOf(play("a", "Anirudh", ms(2026, 10, 21, 9, 0))),
            listOf(ListenRow("a", "Song a", "Anirudh", "", 9, 120_000)), ist
        )
        assertTrue(quiet.isQuiet)
        assertTrue(quiet.hasHistory)
        assertFalse(RecapRules.shouldPopup(quiet, popupShownDay = 0, viewedDay = 0))
        assertTrue(RecapRules.showDot(quiet.day, viewedDay = 0))
    }

    @Test fun popupShowsOncePerRecap() {
        val big = StatsEngine.compute(
            20261021, listOf(play("a", "Anirudh", ms(2026, 10, 21, 9, 0))),
            listOf(ListenRow("a", "Song a", "Anirudh", "", 9, 900_000)), ist
        )
        assertTrue(RecapRules.shouldPopup(big, popupShownDay = 20261020, viewedDay = 20261020))
        assertFalse(RecapRules.shouldPopup(big, popupShownDay = 20261021, viewedDay = 0))
        assertFalse(RecapRules.shouldPopup(big, popupShownDay = 0, viewedDay = 20261021))
        assertFalse(RecapRules.showDot(20261021, viewedDay = 20261021))
        assertFalse(RecapRules.showDot(null, viewedDay = 0))
    }

    // ---- which day the recap shows ----

    @Test fun recapIsTheLatestPastDayWithHistory() {
        // listened Monday and Wednesday; opened Saturday: Wednesday's recap
        assertEquals(20261021, RecapRules.recapDay(20261024, listOf(20261019, 20261021)))
        // listening today never becomes the recap until the day is over
        assertEquals(20261021, RecapRules.recapDay(20261022, listOf(20261021, 20261022)))
        assertNull(RecapRules.recapDay(20261022, listOf(20261022)))
        assertNull(RecapRules.recapDay(20261022, emptyList()))
    }

    @Test fun recapOverrideRule() {
        // midnight alone (no new day with history) never replaces it
        assertFalse(RecapRules.replaces(current = 20261021, candidate = null, today = 20261023))
        // a newer past day with history replaces it
        assertTrue(RecapRules.replaces(current = 20261021, candidate = 20261022, today = 20261023))
        // today's listening doesn't, until it's a past day
        assertFalse(RecapRules.replaces(current = 20261021, candidate = 20261023, today = 20261023))
        // an older day doesn't
        assertFalse(RecapRules.replaces(current = 20261021, candidate = 20261019, today = 20261023))
        assertTrue(RecapRules.replaces(current = null, candidate = 20261021, today = 20261022))
    }

    private fun day(d: Int, minutes: Long, songs: Int) =
        DayStats(d, minutes * 60_000L, songs, songs, null, null, if (songs > 0) 9 else -1, null, false)

    @Test fun quietDayDoesNotReplaceABetterRecapButStillShows() {
        val big = day(20261021, 40, 9)
        val quiet = day(20261022, 3, 1)
        // opened on the 23rd: the recap stays on the 21st, the quiet 22nd still shows in the tab
        val pick = RecapRules.pick(20261023, listOf(quiet, big))
        assertEquals(20261021, pick.recap?.day)
        assertEquals(20261022, pick.quietDay?.day)
        assertFalse(RecapRules.replaces(big, quiet, today = 20261023))
        // a newer good day replaces it, and the quiet line goes away
        val newer = day(20261023, 25, 6)
        assertTrue(RecapRules.replaces(big, newer, today = 20261024))
        val pick2 = RecapRules.pick(20261024, listOf(quiet, big, newer))
        assertEquals(20261023, pick2.recap?.day)
        assertNull(pick2.quietDay)
    }

    @Test fun onlyQuietDaysMeansTheLatestQuietDayIsTheRecap() {
        val q1 = day(20261020, 2, 1)
        val q2 = day(20261021, 4, 0)
        val pick = RecapRules.pick(20261022, listOf(q1, q2))
        assertEquals(20261021, pick.recap?.day)
        assertNull(pick.quietDay)
        assertFalse(RecapRules.shouldPopup(pick.recap, popupShownDay = 0, viewedDay = 0))
        // a quiet day can replace another quiet one; a good day replaces a quiet one
        assertTrue(RecapRules.replaces(q1, q2, today = 20261022))
        assertTrue(RecapRules.replaces(q2, day(20261022, 30, 5), today = 20261023))
    }

    @Test fun midnightAloneKeepsTheRecapAndTodayIsIgnored() {
        val big = day(20261021, 40, 9)
        // two days later with nothing new: still the 21st
        assertEquals(20261021, RecapRules.pick(20261023, listOf(big)).recap?.day)
        // today's listening never shows as the recap
        assertEquals(20261021, RecapRules.pick(20261022, listOf(big, day(20261022, 50, 10))).recap?.day)
        assertNull(RecapRules.pick(20261022, emptyList()).recap)
        assertFalse(RecapRules.replaces(big, null, today = 20261023))
    }

    @Test fun skipAt40SecondsCountsFortySeconds() {
        // a skip at 0:40 with no counted play: 40 s of real time, no song counted
        val s = StatsEngine.compute(20261022, emptyList(), listOf(ListenRow("a", "Song a", "Anirudh", "", 10, 40_000)), ist)
        assertEquals(40_000L, s.timeMs)
        assertEquals(0, s.songs)
        assertEquals("40s", StatsFormat.duration(s.timeMs))
    }

    @Test fun aFewSecondsOfSkippingIsNotHistory() {
        assertFalse(StatsEngine.hasHistory(20_000, 0))
        assertTrue(StatsEngine.hasHistory(60_000, 0))
        assertTrue(StatsEngine.hasHistory(0, 1))
    }

    // ---- streaks ----

    @Test fun longestStreakBackToBack() {
        val t = ms(2026, 10, 21, 22, 0)
        val plays = listOf(
            play("1", "Arijit Singh", t),
            play("2", "Anirudh", t + 4 * 60_000),
            play("3", "Anirudh, Dhee", t + 8 * 60_000),
            play("4", "Anirudh", t + 12 * 60_000),
            play("5", "Arijit Singh", t + 16 * 60_000),
            play("6", "Arijit Singh", t + 20 * 60_000)
        )
        assertEquals(Streak("Anirudh", 3), StatsEngine.longestStreak(plays))
    }

    @Test fun streakBreaksOnALongGap() {
        val t = ms(2026, 10, 21, 9, 0)
        val plays = listOf(
            play("1", "Anirudh", t, durSec = 200),
            play("2", "Anirudh", t + 4 * 60_000, durSec = 200),
            // 3 hours later: not back to back
            play("3", "Anirudh", t + 3 * 3_600_000, durSec = 200)
        )
        assertNull(StatsEngine.longestStreak(plays))
    }

    @Test fun streakNeedsThreeAndIgnoresUnknownArtists() {
        val t = ms(2026, 10, 21, 9, 0)
        assertNull(StatsEngine.longestStreak(listOf(play("1", "Anirudh", t), play("2", "Anirudh", t + 60_000))))
        assertNull(
            StatsEngine.longestStreak(
                listOf(play("1", "", t), play("2", "", t + 60_000), play("3", "", t + 120_000))
            )
        )
        val unordered = listOf(
            play("3", "Anirudh", t + 8 * 60_000), play("1", "Anirudh", t), play("2", "Anirudh", t + 4 * 60_000)
        )
        assertEquals(Streak("Anirudh", 3), StatsEngine.longestStreak(unordered))
    }

    // ---- formatting ----

    @Test fun formats() {
        assertEquals("40s", StatsFormat.duration(40_000))
        assertEquals("12m", StatsFormat.duration(12 * 60_000L + 30_000))
        assertEquals("2h 14m", StatsFormat.duration((2 * 60 + 14) * 60_000L))
        assertEquals("3h", StatsFormat.duration(3 * 3_600_000L))
        assertEquals("11 PM", StatsFormat.hour(23))
        assertEquals("12 AM", StatsFormat.hour(0))
        assertEquals("12 PM", StatsFormat.hour(12))
        assertEquals("9 AM", StatsFormat.hour(9))
    }
}
