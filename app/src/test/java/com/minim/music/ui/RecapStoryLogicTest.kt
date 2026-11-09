package com.minim.music.ui

import com.minim.music.data.stats.DayStats
import com.minim.music.data.stats.Streak
import com.minim.music.data.stats.TopArtist
import com.minim.music.data.stats.TopSong
import com.minim.music.ui.RecapSlides.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecapStoryLogicTest {

    private val min = 60_000L

    private fun day(
        timeMs: Long = 40 * min,
        songs: Int = 12,
        artists: Int = 4,
        song: TopSong? = TopSong("id1", "Cartoon (Official Video)", "Anirudh", "https://x/a.jpg", 4, 12 * min),
        artist: TopArtist? = TopArtist("Anirudh Ravichander", 20 * min, 6),
        peak: Int = 22,
        streak: Streak? = Streak("Anirudh", 3),
        key: Int = 20261021
    ) = DayStats(key, timeMs, songs, artists, song, artist, peak, streak, false)

    @Test fun planWithoutStoryDataKeepsTheCoreSlides() {
        // 5.8.2: the old peak and streak slides are gone (the day in hours and the artist line replace them)
        assertEquals(
            listOf(Kind.INTRO, Kind.TIME, Kind.TOP5, Kind.ARTIST, Kind.ROAST, Kind.PERSONA, Kind.CARD),
            RecapSlides.plan(day(), RecapDay.NONE, hasRoast = true)
        )
    }

    @Test fun barelyAnythingGetsIntroTimeAndCard() {
        val plan = RecapSlides.plan(day(song = null, artist = null, peak = -1, streak = null), RecapDay.NONE, hasRoast = false)
        assertEquals(listOf(Kind.INTRO, Kind.TIME, Kind.PERSONA, Kind.CARD), plan)
    }

    @Test fun emptyDayIsJustTheCard() {
        val empty = DayStats.empty(20261021)
        assertEquals(listOf(Kind.INTRO, Kind.CARD), RecapSlides.plan(empty, RecapDay.NONE, hasRoast = RecapRoast.line(empty) != null))
    }

    @Test fun roastIsNullOnlyWhenNothingPlayed() {
        assertNull(RecapRoast.line(DayStats.empty(20261021)))
        assertNotNull(RecapRoast.line(day()))
    }

    @Test fun roastIsTheSameEveryTimeForTheSameDay() {
        val d = day(song = TopSong("id", "On & On", "Cartoon", "", 9, 30 * min), timeMs = 5 * 60 * min, peak = 2)
        assertEquals(RecapRoast.line(d), RecapRoast.line(d))
        assertTrue(RecapRoast.line(d) in RecapRoast.candidates(d))
    }

    @Test fun replayRoastUsesCleanTitleAndCount() {
        val d = day(song = TopSong("id", "Cartoon (Official Video)", "Anirudh", "", 9, 30 * min), peak = 14, streak = null)
        val lines = RecapRoast.candidates(d).map { it.first }
        assertTrue(lines.contains("Cartoon, 9 times."))
        assertTrue(lines.none { it.contains("Official") })
    }

    @Test fun lateNightRoastMatchesPeakSlide() {
        val d = day(peak = 4)
        assertTrue(RecapRoast.candidates(d).any { it.second == "Sleep is a myth, right?" })
        assertEquals("Night owl hours.", RecapSlides.peakLine(4))
    }

    @Test fun quietShortDayFallsBackToShortAndSweet() {
        val d = day(timeMs = 6 * min, songs = 2, artists = 2, song = null, artist = null, peak = 14, streak = null)
        assertEquals(listOf("6m of music." to "Short and sweet."), RecapRoast.candidates(d))
    }
}
