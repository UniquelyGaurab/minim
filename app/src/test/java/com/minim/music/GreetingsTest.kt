package com.minim.music

import com.minim.music.ui.GreetingFacts
import com.minim.music.ui.Greetings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import kotlin.random.Random

class GreetingsTest {

    private fun at(hour: Int, dow: Int = Calendar.WEDNESDAY): Calendar = Calendar.getInstance().apply {
        set(2026, Calendar.OCTOBER, 21, hour, 30)
        while (get(Calendar.DAY_OF_WEEK) != dow) add(Calendar.DAY_OF_MONTH, 1)
    }

    private val full = GreetingFacts(
        nowId = "a", nowTitle = "Cartoon, Jéja - On & On (feat. Daniel Levi) [NCS Release]",
        nowArtist = "Cartoon, Jéja, Daniel Levi", nowPlaying = true, nowLiked = false,
        lastId = "b", lastTitle = "Blinding Lights", lastArtist = "The Weeknd",
        playsToday = 7, topTrackWeekTitle = "On & On", topTrackWeekPlays = 6,
        topArtistWeek = "Cartoon - Topic", topArtistWeekPlays = 9, artistsWeek = 8,
        likedCount = 142, recentLikedTitle = "Levitating", topLikedArtist = "Dua Lipa", topLikedArtistCount = 5,
        playlistCount = 3, newestPlaylist = "Gym", biggestPlaylist = "Chill", biggestPlaylistSongs = 24
    )

    @Test
    fun shortening() {
        assertEquals("On & On", Greetings.shortTitle("Cartoon, Jéja - On & On (feat. Daniel Levi) [NCS Release]", "Cartoon, Jéja, Daniel Levi"))
        assertEquals("Cartoon", Greetings.shortArtist("Cartoon, Jéja, Daniel Levi"))
        assertEquals("Cartoon", Greetings.shortArtist("Cartoon - Topic"))
        assertEquals("Simon", Greetings.shortArtist("Simon & Garfunkel"))
        val long = Greetings.shortTitle("A Really Very Extremely Long Song Title That Goes On", null)
        assertTrue(long, long.length <= 22 && long.endsWith("…"))
        // Never empty, even for a title that is only brackets
        assertEquals("(Intro)", Greetings.shortTitle("(Intro)", null))
    }

    @Test
    fun everyFunGreetingRendersWithFullData() {
        var shown = 0
        for (hour in listOf(2, 9, 14, 20)) for (dow in listOf(Calendar.WEDNESDAY, Calendar.FRIDAY)) {
            for (id in Greetings.DATA_IDS) {
                val l = Greetings.dataLines(id, full, at(hour, dow)) ?: continue
                shown++
                assertTrue(l.first.isNotBlank() && l.second.isNotBlank())
                assertTrue("$l", l.first.length <= 34 && l.second.length <= 34)
                println("$id @$hour: ${l.first} / ${l.second}")
            }
        }
        assertTrue(shown > 40)
    }

    @Test
    fun noDataNoFunGreeting() {
        val empty = GreetingFacts()
        for (hour in 0..23) for (id in Greetings.DATA_IDS) {
            assertNull("$id at $hour", Greetings.dataLines(id, empty, at(hour, Calendar.FRIDAY)))
        }
        // Liked line never says 0
        assertNull(Greetings.dataLines(120, full.copy(likedCount = 0), at(12)))
        assertEquals("One liked song.", Greetings.dataLines(120, full.copy(likedCount = 1), at(12))!!.first)
        // Nothing playing: no now-playing lines
        val stopped = full.copy(nowId = null, nowTitle = null, nowArtist = null, nowPlaying = false)
        for (id in 100..105) assertNull(Greetings.dataLines(id, stopped, at(2)))
    }

    @Test
    fun rotationMixesAndNeverRepeats() {
        val rot = Greetings.Rotation(Random(7))
        rot.markShown(Greetings.TIME_BASED)
        var prev = Greetings.TIME_BASED
        var fun_ = 0
        repeat(400) {
            val g = rot.next { Greetings.dataLines(it, full, at(20)) != null }
            assertNotEquals(prev, g)
            if (Greetings.isData(g)) fun_++
            prev = g
        }
        assertEquals(200, fun_)
        // No data at all: only regular greetings, still no repeats
        val rot2 = Greetings.Rotation(Random(3))
        prev = -1
        repeat(100) {
            val g = rot2.next { false }
            assertTrue(g in 0 until Greetings.COUNT)
            assertNotEquals(prev, g)
            prev = g
        }
    }
}
