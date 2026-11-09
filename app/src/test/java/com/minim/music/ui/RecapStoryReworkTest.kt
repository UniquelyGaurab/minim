package com.minim.music.ui

import com.minim.music.data.stats.DayStats
import com.minim.music.data.stats.Days
import com.minim.music.data.stats.ListenRow
import com.minim.music.data.stats.PlayRow
import com.minim.music.data.stats.StatsEngine
import com.minim.music.data.stats.Streak
import com.minim.music.data.stats.TopArtist
import com.minim.music.data.stats.TopSong
import com.minim.music.playback.RecapMusic
import com.minim.music.ui.RecapSlides.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.ZoneId

/** 5.8.2 (update 54): the reworked recap story's logic. */
class RecapStoryReworkTest {

    private val zone = ZoneId.of("Asia/Kolkata")
    private val day = 20261108 // a Sunday
    private val min = 60_000L

    private fun at(hour: Int, minute: Int = 0): Long =
        Days.date(day).atTime(hour, minute).atZone(zone).toInstant().toEpochMilli()

    private fun play(id: String, hour: Int, minute: Int = 0, artist: String = "Artist $id") =
        PlayRow(id, "Song $id", artist, "https://img/$id.jpg", 200L, at(hour, minute), 180_000L, true)

    private fun listen(id: String, hour: Int, ms: Long, artist: String = "Artist $id") =
        ListenRow(id, "Song $id", artist, "https://img/$id.jpg", hour, ms)

    /** a x4, b x3, c x2, e x1 (longer), d x1, f x1 */
    private val plays = listOf(
        play("a", 8, 5), play("b", 9), play("a", 10), play("c", 12), play("b", 13), play("a", 18),
        play("d", 19), play("e", 20), play("c", 21), play("b", 21, 30), play("a", 22), play("f", 23, 10)
    )
    private val listens = plays.groupBy { it.trackId }.map { (id, ps) ->
        listen(id, ps.first().let { java.time.Instant.ofEpochMilli(it.playedAt).atZone(zone).hour }, ps.size * 3 * min + if (id == "e") 2 * min else 0L)
    }

    private fun stats(): DayStats = StatsEngine.compute(day, plays, listens, zone)

    @Test fun topFiveMatchesTheTopSongAndCountsDown() {
        val s = stats()
        val d = RecapDay.compute(s, plays, listens, emptyList(), zone)
        assertEquals(5, d.top.size)
        assertEquals(s.topSong!!.trackId, d.top[0].trackId)
        // d and f tie on plays and time: the later play (f, 11:10 PM) goes first, as for the top song
        assertEquals(listOf("a", "b", "c", "e", "f"), d.top.map { it.trackId })
        assertEquals(listOf("a", "b", "c", "e", "f", "d"), d.ranked)
    }

    @Test fun firstAndLastSongAndTheirTimes() {
        val d = RecapDay.compute(stats(), plays, listens, emptyList(), zone)
        assertEquals("a", d.first!!.trackId)
        assertEquals("f", d.last!!.trackId)
        assertTrue(d.hasFirstLast)
        assertFalse(d.sameFirstLast)
        assertEquals("8:05 AM", RecapDay.clock(d.first!!.playedAt, zone))
        assertEquals("11:10 PM", RecapDay.clock(d.last!!.playedAt, zone))
        assertEquals("12:00 AM", RecapDay.clock(at(0), zone))
        assertEquals("12:30 PM", RecapDay.clock(at(12, 30), zone))
    }

    @Test fun oneSongIsNoFirstAndLast() {
        val one = listOf(play("a", 9))
        val l = listOf(listen("a", 9, 3 * min))
        val d = RecapDay.compute(StatsEngine.compute(day, one, l, zone), one, l, emptyList(), zone)
        assertFalse(d.hasFirstLast)
        assertNull(RecapGuess.options(d, day))
    }

    @Test fun hoursIncludeTapesAndPeakAgreesWithStats() {
        val s = stats()
        val tape = com.minim.music.data.stats.TapeListenRow("v", "Tape", "SR PAY STORIES", "UC1", "", 3600L, 2, 100, 50 * min)
        val d = RecapDay.compute(s, plays, listens, listOf(tape), zone)
        assertEquals(50 * min, d.hours[2])
        assertEquals(24, d.hours.size)
        // the day's own peak (songs only here) wins when it has listening, so story and Stats agree
        assertEquals(s.peakHour, d.peakHour)
        val noPeak = RecapDay(LongArray(24).also { it[7] = 5 * min; it[15] = 9 * min }, null, null, emptyList(), emptyList())
        assertEquals(15, noPeak.peakHour)
        assertEquals(-1, RecapDay.NONE.peakHour)
    }

    @Test fun guessHasTheAnswerAndThreeDifferentCovers() {
        val d = RecapDay.compute(stats(), plays, listens, emptyList(), zone)
        for (k in listOf(day, day + 1, day + 2, 20261110)) {
            val o = RecapGuess.options(d, k)!!
            assertEquals(3, o.size)
            assertEquals(3, o.map { it.trackId }.toSet().size)
            assertTrue(o.any { it.trackId == "a" })
            assertEquals(o, RecapGuess.options(d, k)) // same recap, same order
        }
        // the answer doesn't always sit in the same place
        val spots = (0..5).map { k -> RecapGuess.options(d, day + k)!!.indexOfFirst { it.trackId == "a" } }.toSet()
        assertTrue(spots.size > 1)
    }

    @Test fun fullDayPlanIsInOrderWithCardLast() {
        val s = stats().copy(streak = Streak("Artist a", 3))
        val d = RecapDay.compute(s, plays, listens, emptyList(), zone)
        assertEquals(
            listOf(Kind.INTRO, Kind.TIME, Kind.HOURS, Kind.FIRST_LAST, Kind.GUESS, Kind.TOP5, Kind.ARTIST, Kind.ROAST, Kind.PERSONA, Kind.CARD),
            RecapSlides.plan(s, d, hasRoast = true)
        )
        // the old peak-hour and streak slides are gone for good
        assertEquals(
            listOf(
                "INTRO", "TIME", "VS_YESTERDAY", "HOURS", "SESSION", "FIRST_LAST", "FIRST_LISTENS", "GUESS", "TOP5",
                "ARTIST", "HEARTED", "TAPES", "STREAK", "ROAST", "PERSONA", "CARD"
            ),
            Kind.values().map { it.name }
        )
    }

    @Test fun fewerThanThreeSongsSkipsTheGuess() {
        val two = listOf(play("a", 9), play("b", 10), play("a", 11))
        val l = listOf(listen("a", 9, 6 * min), listen("b", 10, 3 * min))
        val s = StatsEngine.compute(day, two, l, zone)
        val d = RecapDay.compute(s, two, l, emptyList(), zone)
        val plan = RecapSlides.plan(s, d, hasRoast = false)
        assertFalse(Kind.GUESS in plan)
        assertTrue(Kind.TOP5 in plan)
        assertEquals(2, d.top.size)
    }

    @Test fun introUsesTheRealDay() {
        assertEquals("Your Sunday, in music.", RecapDay.introLine(day))
        assertEquals("Your Monday, in music.", RecapDay.introLine(20261109))
        assertTrue(RecapDay.playlistName(day).endsWith("top songs"))
        assertTrue(RecapDay.playlistName(day).startsWith(Days.label(day)))
    }

    @Test fun streakIsALineOnTheArtistSlide() {
        val base = DayStats(day, 40 * min, 12, 4, TopSong("a", "A", "Anirudh", "", 4, 12 * min),
            TopArtist("Anirudh Ravichander", 20 * min, 6), 22, Streak("Anirudh Ravichander", 6), false)
        assertEquals("6 in a row at one point", RecapSlides.streakLine(base))
        assertEquals("4 Arijit Singh songs in a row at one point", RecapSlides.streakLine(base.copy(streak = Streak("Arijit Singh", 4))))
        assertNull(RecapSlides.streakLine(base.copy(streak = Streak("Anirudh Ravichander", 2))))
        assertNull(RecapSlides.streakLine(base.copy(streak = null)))
    }

    // ---- personality ----

    private fun hours(vararg pairs: Pair<Int, Long>) = LongArray(24).also { for ((h, ms) in pairs) it[h] = ms }
    private fun dayWith(h: LongArray) = RecapDay(h, null, null, emptyList(), emptyList())

    @Test fun onRepeat() {
        val s = DayStats(day, 60 * min, 10, 5, TopSong("a", "Cartoon (Official Video)", "Anirudh", "", 5, 20 * min),
            TopArtist("Anirudh", 25 * min, 6), 14, null, false)
        val p = RecapPersonality.pick(s, dayWith(hours(14 to 60 * min)))!!
        assertEquals(RecapPersonality.Type.ON_REPEAT, p.type)
        assertEquals("On repeat", p.label)
        assertTrue(p.line.startsWith("Cartoon, 5 times"))
    }

    @Test fun loyalistAndExplorer() {
        val loyal = DayStats(day, 60 * min, 10, 3, TopSong("a", "A", "Anirudh", "", 2, 6 * min),
            TopArtist("Anirudh Ravichander", 40 * min, 7), 14, null, false)
        assertEquals(RecapPersonality.Type.LOYALIST, RecapPersonality.pick(loyal, dayWith(hours(14 to 60 * min)))!!.type)
        val explorer = DayStats(day, 60 * min, 14, 11, TopSong("a", "A", "X", "", 2, 6 * min),
            TopArtist("X", 10 * min, 2), 14, null, false)
        val p = RecapPersonality.pick(explorer, dayWith(hours(14 to 60 * min)))!!
        assertEquals(RecapPersonality.Type.EXPLORER, p.type)
        assertEquals("11 artists. Always after the next one.", p.line)
    }

    @Test fun nightOwlEarlyBirdMarathonerSnackerAndMixer() {
        val plain = DayStats(day, 50 * min, 6, 4, TopSong("a", "A", "X", "", 2, 6 * min), TopArtist("X", 15 * min, 2), 0, null, false)
        assertEquals(RecapPersonality.Type.NIGHT_OWL, RecapPersonality.pick(plain, dayWith(hours(0 to 30 * min, 23 to 15 * min, 14 to 5 * min)))!!.type)
        assertEquals(RecapPersonality.Type.EARLY_BIRD, RecapPersonality.pick(plain, dayWith(hours(6 to 40 * min, 14 to 10 * min)))!!.type)
        assertEquals(RecapPersonality.Type.MOOD_MIXER, RecapPersonality.pick(plain, dayWith(hours(14 to 50 * min)))!!.type)
        val long = plain.copy(timeMs = 4 * 60 * min)
        assertEquals(RecapPersonality.Type.MARATHONER, RecapPersonality.pick(long, dayWith(hours(14 to 4 * 60 * min)))!!.type)
        val short = plain.copy(timeMs = 8 * min, songs = 2, artists = 2)
        assertEquals(RecapPersonality.Type.SNACKER, RecapPersonality.pick(short, dayWith(hours(14 to 8 * min)))!!.type)
        assertNull(RecapPersonality.pick(DayStats.empty(day), RecapDay.NONE))
    }

    @Test fun personalityAlsoTagsAreTheOtherMatches() {
        val s = DayStats(day, 4 * 60 * min, 30, 3, TopSong("a", "A", "Anirudh", "", 12, 60 * min),
            TopArtist("Anirudh", 3 * 60 * min, 20), 1, null, false)
        val p = RecapPersonality.pick(s, dayWith(hours(1 to 4 * 60 * min)))!!
        assertEquals(RecapPersonality.Type.ON_REPEAT, p.type)
        assertTrue(p.also.size <= 2)
        assertTrue(RecapPersonality.Type.LOYALIST in p.also)
    }

    // ---- motion and music maths ----

    @Test fun wordsFadeInOrder() {
        assertEquals(listOf(0, 5, 13), RecapMotion.wordStarts("Your Sunday, in"))
        assertEquals(listOf(0), RecapMotion.wordStarts(""))
        assertEquals(0f, RecapMotion.wordAlpha(0f, 0, 4), 0f)
        assertEquals(1f, RecapMotion.wordAlpha(1f, 3, 4), 0f)
        assertTrue(RecapMotion.wordAlpha(0.3f, 0, 4) > RecapMotion.wordAlpha(0.3f, 2, 4))
    }

    @Test fun countUpEndsExactlyOnTheTotal() {
        val total = 2 * 3_600_000L + 14 * min + 25_000L
        assertEquals(0L, RecapMotion.countValue(total, 0f))
        assertEquals(total, RecapMotion.countValue(total, 1f))
        assertEquals(0L, RecapMotion.countValue(total, 0.5f) % min)
        assertTrue(RecapMotion.countValue(total, 0.5f) < total)
    }

    @Test fun barsGrowLeftToRight() {
        assertTrue(RecapMotion.barGrow(0.3f, 0) > RecapMotion.barGrow(0.3f, 23))
        for (h in 0..23) assertEquals(1f, RecapMotion.barGrow(1f, h), 0f)
    }

    @Test fun storySongStartsPastTheIntro() {
        assertEquals(0L, RecapMusic.startAt(0L))
        assertEquals(0L, RecapMusic.startAt(45_000L))
        assertEquals(60_000L, RecapMusic.startAt(200_000L))
        assertEquals(15_000L, RecapMusic.startAt(60_000L))
    }

    @Test fun top5RevealComesAfterTheLastRow() {
        assertTrue(RecapSlides.top5RevealAt(4) > 700L + 3 * 750L)
        assertTrue(RecapSlides.top5RevealAt(4) < RecapSlides.durationMs(Kind.TOP5) - 4_000L)
        assertEquals(0L, RecapSlides.top5RevealAt(0))
    }

    // ---- left out on purpose ----

    private fun src(name: String) = File("src/main/java/com/minim/music/ui/$name").readText()

    @Test fun noGeminiInTheRecap() {
        for (f in listOf("RecapStory.kt", "RecapDay.kt", "RecapSlides.kt", "RecapCard.kt", "RecapRoast.kt", "RecapMotion.kt")) {
            assertFalse(f, src(f).contains("Gemini", ignoreCase = true))
            assertFalse(f, src(f).contains("generateContent"))
        }
        assertFalse(File("src/main/java/com/minim/music/playback/RecapMusic.kt").readText().contains("Gemini", ignoreCase = true))
    }

    @Test fun theBefore49NoteIsGone() {
        val res = File("src/main/res/values/strings.xml").readText()
        for (f in listOf(res, src("RecapStory.kt"), src("RecapCard.kt"), src("StatsScreen.kt"))) {
            assertFalse(f.contains("before 4.9", ignoreCase = true))
            assertFalse(f.contains("before v4.9", ignoreCase = true))
            assertFalse(f.contains("from before 4.9", ignoreCase = true))
        }
    }

    @Test fun storyNeverRecordsPlays() {
        val m = File("src/main/java/com/minim/music/playback/RecapMusic.kt").readText()
        for (bad in listOf("recordCountedPlay", "addListenTime", "playbackController", "MediaSession")) assertFalse(bad, m.contains(bad))
        assertNotNull(m)
    }
}
