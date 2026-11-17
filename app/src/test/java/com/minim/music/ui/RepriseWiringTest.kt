package com.minim.music.ui

import com.minim.music.data.stats.DayStats
import com.minim.music.data.stats.Days
import com.minim.music.data.stats.ListenRow
import com.minim.music.data.stats.PlayRow
import com.minim.music.data.stats.RepriseClock
import com.minim.music.data.stats.RepriseClock.Phase
import com.minim.music.data.stats.RepriseScope
import com.minim.music.data.stats.StatsEngine
import com.minim.music.ui.RecapSlides.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

/** 6.9.0 part B (build 75): the Weekly / Monthly wiring, the teaser previews, the popups and the greeting. */
class RepriseWiringTest {

    private val zone = ZoneId.of("Asia/Kolkata")
    private val hour = 3_600_000L
    private val min = 60_000L

    private fun at(d: LocalDate, h: Int, m: Int = 0) = RepriseClock.at(d, h, m, zone)

    // ---- the stories open with a real plan, card last

    private val sunday = LocalDate.of(2026, 11, 8)
    private fun play(id: String, d: LocalDate, h: Int) =
        PlayRow(id, "Song $id", "Artist $id", "https://img/$id.jpg", 200L, at(d, h), 180_000L, true)

    private fun scope(period: RepriseScope.Period, start: LocalDate, plays: List<PlayRow>): Triple<RepriseScope, DayStats, RecapDay> {
        val listens = plays.groupBy { it.trackId }.map { (id, ps) -> ListenRow(id, "Song $id", "Artist $id", "https://img/$id.jpg", 20, ps.size * 20 * min) }
        val stats = StatsEngine.compute(Days.key(start), plays, listens, zone)
        val totals = plays.groupBy { Days.key(java.time.Instant.ofEpochMilli(it.playedAt).atZone(zone).toLocalDate()) }
            .mapValues { (_, ps) -> ps.size * 20 * min }
        val fromMs = start.atStartOfDay(zone).toInstant().toEpochMilli()
        val sc = RepriseScope.build(
            period, start, stats, plays, listens, totals, 0L, emptyList(), emptyList(), emptySet(), emptySet(),
            emptyList(), emptySet(), fromMs, fromMs + 31L * 24 * hour, LongArray(24), zone
        )
        return Triple(sc, stats, RecapDay.compute(stats, plays, listens, emptyList()))
    }

    private val weekPlays = (0L..6L).flatMap { i ->
        val d = sunday.plusDays(i)
        listOf(play("a", d, 9), play("b", d, 13), play("a", d, 21), play("c$i", d, 22))
    }

    @Test fun weeklyStoryOpensWithItsDatesAndCardLast() {
        val (sc, s, d) = scope(RepriseScope.Period.WEEK, sunday, weekPlays)
        assertTrue(sc.isWeek)
        assertEquals("Nov 8–14", sc.label)
        assertEquals(7, sc.days.size)
        val plan = RepriseSlides.plan(sc, s, d, hasRoast = false, hasPersona = false)
        assertTrue(plan.size >= 3)
        assertEquals(Kind.INTRO, plan.first())
        assertEquals(Kind.CARD, plan.last())
        assertTrue(sc.key.startsWith("w"))
        assertEquals(RecapPopupGate.weekKey(sunday), sc.key)
        // the top song is what plays behind the story
        assertEquals("a", s.topSong?.trackId)
    }

    @Test fun monthlyStoryOpensWithItsMonthAndCardLast() {
        val nov1 = LocalDate.of(2026, 11, 1)
        val (sc, s, d) = scope(RepriseScope.Period.MONTH, nov1, weekPlays)
        assertFalse(sc.isWeek)
        assertEquals("November 2026", sc.label)
        assertEquals(30, sc.days.size)
        assertEquals(RecapPopupGate.monthKey(YearMonth.of(2026, 11)), sc.key)
        val plan = RepriseSlides.plan(sc, s, d, hasRoast = false, hasPersona = false)
        assertEquals(Kind.INTRO, plan.first())
        assertEquals(Kind.CARD, plan.last())
    }

    // ---- the teaser window, its states and the previews

    @Test fun teaserOnlyInTheLastSevenDays() {
        assertEquals(Phase.HIDDEN, RepriseClock.teaser(at(LocalDate.of(2026, 11, 20), 12), true, zone).phase)
        assertTrue(RepriseClock.teaser(at(LocalDate.of(2026, 11, 24), 12), true, zone).phase != Phase.HIDDEN)
    }

    @Test fun eachPreviewLandsOnItsState() {
        val now = at(LocalDate.of(2026, 11, 17), 20, 8)
        fun t(k: ReprisePreview.Teaser) = RepriseClock.teaser(ReprisePreview.at(k, now, zone), ReprisePreview.unlocked(k), zone)
        val five = t(ReprisePreview.Teaser.FIVE_DAYS)
        assertEquals(Phase.DAYS, five.phase)
        assertEquals("in 5 days", five.countdown)
        val tomorrow = t(ReprisePreview.Teaser.TOMORROW)
        assertEquals(Phase.TOMORROW, tomorrow.phase)
        assertEquals("tomorrow at 6 PM", tomorrow.countdown)
        val last = t(ReprisePreview.Teaser.LAST_DAY)
        assertEquals(Phase.HOURS, last.phase)
        assertEquals("in 7 hr 12 min", last.countdown)
        val hourLeft = t(ReprisePreview.Teaser.FINAL_HOUR)
        assertEquals(Phase.FINAL_HOUR, hourLeft.phase)
        assertEquals("in 12:00", hourLeft.countdown)
        assertEquals(1_000L, RepriseClock.tickMs(hourLeft))
        // the drop: 8 s before 6 PM, then here
        val dropAt = ReprisePreview.at(ReprisePreview.Teaser.DROP, now, zone)
        assertEquals(Phase.FINAL_HOUR, RepriseClock.teaser(dropAt, true, zone).phase)
        val here = RepriseClock.teaser(dropAt + 8_000L, true, zone)
        assertEquals(Phase.HERE, here.phase)
        assertEquals("Your November Reprise is here", here.title)
        assertEquals(Phase.LOCKED, t(ReprisePreview.Teaser.LOCKED).phase)
    }

    // ---- the popups gate

    @Test fun weeklyPopupOnceFromSundayEightAndOnlyAboveTheMinimum() {
        val sun15 = LocalDate.of(2026, 11, 15)
        val wk = RecapPopupGate.weekKey(sunday)
        assertNull(RecapPopupGate.dueReprise(at(sun15, 7, 59), hour, 0L, "", "", zone).let { if (it?.isWeek == true && it.start == sunday) it else null })
        val due = RecapPopupGate.dueReprise(at(sun15, 8, 0), hour, 0L, "", "", zone)
        assertNotNull(due)
        assertTrue(due!!.isWeek)
        assertEquals(sunday, due.start)
        assertEquals(wk, due.key)
        // once each
        assertNull(RecapPopupGate.dueReprise(at(sun15, 9, 0), hour, 0L, wk, "", zone))
        // below 45 min nothing drops
        assertNull(RecapPopupGate.dueReprise(at(sun15, 9, 0), 44 * min, 0L, "", "", zone))
        // a stale week doesn't pop up days later
        assertNull(RecapPopupGate.dueReprise(at(sun15.plusDays(4), 9, 0), hour, 0L, "", "", zone))
    }

    @Test fun monthlyPopupFromSixOnTheLastDayAndGoesFirst() {
        val last = LocalDate.of(2026, 11, 30)
        assertNull(RecapPopupGate.dueReprise(at(last, 17, 59), 0L, 3 * hour, "", "", zone))
        val due = RecapPopupGate.dueReprise(at(last, 18, 0), hour, 3 * hour, "", "", zone)
        assertNotNull(due)
        assertFalse(due!!.isWeek)
        assertEquals(LocalDate.of(2026, 11, 1), due.start)
        assertNull(RecapPopupGate.dueReprise(at(last, 19, 0), 0L, 3 * hour, "", due.key, zone))
        assertNull(RecapPopupGate.dueReprise(at(last, 19, 0), 0L, 119 * min, "", "", zone))
        // the daily popup stays off
        assertFalse(RecapPopupGate.DAILY_POPUP)
    }

    // ---- Save as playlist and the greeting

    @Test fun monthPlaylistIsTheTopSongsBestFirstWithoutRepeats() {
        assertEquals(listOf("a", "b", "c"), ReprisePreview.monthPlaylistIds(listOf("b", "a", "", "c", "b"), "a"))
        assertEquals(50, ReprisePreview.monthPlaylistIds((1..80).map { "s$it" }, null).size)
        assertTrue(ReprisePreview.monthPlaylistIds(emptyList(), null).isEmpty())
        assertEquals("November 2026 · Reprise", RecapDay.monthPlaylistName(YearMonth.of(2026, 11)))
        val cover = RecapDay.monthCover(YearMonth.of(2026, 11), listOf(0xFF336699.toInt()))
        assertEquals(PlaylistLook.Mode.TYPE, cover.mode)
        assertTrue(cover.text.isNotBlank())
    }

    @Test fun dropGreetingOnTheLastDayEveryThirdSlot() {
        val line = RepriseClock.greetingLine(at(LocalDate.of(2026, 11, 30), 10), true, zone)
        assertEquals("Your November Reprise drops at 6.", line)
        assertNull(RepriseClock.greetingLine(at(LocalDate.of(2026, 11, 29), 10), true, zone))
        assertNull(RepriseClock.greetingLine(at(LocalDate.of(2026, 11, 30), 18), true, zone))
        assertTrue(Greetings.repriseTurn(3, Greetings.TIME_BASED, line, festival = false))
        assertFalse(Greetings.repriseTurn(4, Greetings.TIME_BASED, line, festival = false))
        assertFalse(Greetings.repriseTurn(3, Greetings.REPRISE_DROP, line, festival = false))
        assertFalse(Greetings.repriseTurn(3, Greetings.TIME_BASED, null, festival = false))
        assertFalse(Greetings.repriseTurn(3, Greetings.TIME_BASED, line, festival = true))
        assertEquals(line, Greetings.repriseLines(line!!).second)
    }

    @Test fun teaserMosaicTiles() {
        assertEquals(listOf(0, 0, 0, 0), RepriseTeaserArt.tiles(1))
        assertEquals(listOf(0, 1, 2, 3), RepriseTeaserArt.tiles(6))
        assertTrue(RepriseTeaserArt.tiles(0).isEmpty())
    }
}
