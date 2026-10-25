package com.minim.music

import com.minim.music.ui.GreetingFacts
import com.minim.music.ui.Greetings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import kotlin.random.Random

/** v4.7: the greeting-only artist name rule; 5.1.0: the built-in time greetings. */
class GreetingsNameTest {

    private fun at(hour: Int): Calendar = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, hour)
        set(Calendar.MINUTE, 0)
    }

    private val noFacts = GreetingFacts()

    // ---- Artist names in greetings ----

    @Test
    fun longNamesKeepOnlyTheFirstWord() {
        assertEquals("Anirudh", Greetings.shortArtist("Anirudh Ravichander"))
        assertEquals("Shreya", Greetings.shortArtist("Shreya Ghoshal"))
        assertEquals("Akhil", Greetings.shortArtist("Akhil Sachdeva"))
        assertEquals("Sid Sriram", Greetings.shortArtist("Sid Sriram - Topic"))
        assertEquals("Shankar", Greetings.shortArtist("Shankar Mahadevan - Topic"))
    }

    @Test
    fun namesUpToTwelveCharactersStayWhole() {
        assertEquals("Arijit Singh", Greetings.shortArtist("Arijit Singh")) // exactly 12
        assertEquals("Pritam", Greetings.shortArtist("Pritam"))
        assertEquals("Taylor Swift", Greetings.shortArtist("Taylor Swift"))
        assertEquals("Cartoon", Greetings.shortArtist("Cartoon, Jéja & Daniel Levi"))
    }

    @Test
    fun namesStartingWithTheAreNeverCut() {
        assertEquals("The Local Train", Greetings.shortArtist("The Local Train"))
        assertEquals("The Chainsmokers", Greetings.shortArtist("The Chainsmokers"))
        assertEquals("The Weeknd", Greetings.shortArtist("The Weeknd"))
    }

    @Test
    fun namesStartingWithInitialsAreNeverCut() {
        assertEquals("A.R. Rahman", Greetings.shortArtist("A.R. Rahman"))
        assertEquals("A. R. Rahman Live", Greetings.shortArtist("A. R. Rahman Live"))
        assertEquals("S.P. Balasubrahmanyam", Greetings.shortArtist("S.P. Balasubrahmanyam"))
        assertEquals("SP Balasubrahmanyam", Greetings.shortArtist("SP Balasubrahmanyam"))
        assertEquals("A R Rahman Hits", Greetings.shortArtist("A R Rahman Hits"))
    }

    @Test
    fun firstArtistIsTakenBeforeTheLengthRule() {
        // "Anirudh Ravichander" is the first artist, then it is shortened
        assertEquals("Anirudh", Greetings.shortArtist("Anirudh Ravichander, Arijit Singh"))
        assertEquals("Arijit Singh", Greetings.shortArtist("Arijit Singh feat. Shreya Ghoshal"))
    }

    @Test
    fun shortenedNamesShowInGreetings() {
        val facts = GreetingFacts(
            nowId = "x", nowTitle = "Hukum", nowArtist = "Anirudh Ravichander", nowPlaying = true,
            lastId = "y", lastTitle = "Chaleya", lastArtist = "Anirudh Ravichander",
            topArtistWeek = "Anirudh Ravichander", topArtistWeekPlays = 9
        )
        val data = facts.toData(at(15))
        assertEquals("Anirudh", data.value("artist"))
        assertEquals("Anirudh", data.value("lastartist"))
        assertEquals("Anirudh", data.value("topartist"))
    }

    @Test
    fun songTitleCleanupStillUsesTheFullArtist() {
        assertEquals("Hukum", Greetings.shortTitle("Anirudh Ravichander - Hukum", "Anirudh Ravichander"))
    }

    // ---- 5.1.0: the built-in greetings are only the time-based ones ----

    @Test
    fun onlyTheTimeGreetingsAreBuiltIn() {
        assertEquals(listOf(Greetings.TIME_BASED, Greetings.TIME_NAMED), Greetings.REGULAR_IDS)
        assertTrue(Greetings.fits(Greetings.TIME_BASED, noFacts, ""))
        assertTrue(Greetings.fits(Greetings.TIME_NAMED, noFacts, "Gaurab"))
        assertFalse(Greetings.fits(Greetings.TIME_NAMED, noFacts, "  "))
        // Old built-in ids (4.5 stat lines, 4.7 name lines) are gone
        for (old in listOf(2, 14, 100, 120, 143, 200, 213)) assertFalse(Greetings.fits(old, noFacts, "Gaurab"))
    }

    @Test
    fun timeGreetingsFollowTheClock() {
        assertEquals("Hi there!" to "Good Morning.", Greetings.lines(Greetings.TIME_BASED, "", at(9)))
        assertEquals("Hi Gaurab!" to "Good Evening.", Greetings.lines(Greetings.TIME_BASED, " Gaurab ", at(20)))
        assertEquals("Up late?" to "Night tunes it is.", Greetings.lines(Greetings.TIME_BASED, "", at(2)))
        assertEquals("Evening, Gaurab." to "What's on tonight?", Greetings.lines(Greetings.TIME_NAMED, "Gaurab", at(19)))
        assertEquals("Morning, Gaurab." to "Ease into it.", Greetings.lines(Greetings.TIME_NAMED, "Gaurab", at(7)))
        // The named one without a name falls back to the plain time greeting
        assertEquals(Greetings.lines(Greetings.TIME_BASED, "", at(14)), Greetings.lines(Greetings.TIME_NAMED, "", at(14)))
    }

    @Test
    fun withNoRemoteFileTheRotationNeverCrashes() {
        val rot = Greetings.Rotation(Random(7)) { com.minim.music.data.greetings.RemoteGreetingFile.EMPTY }
        rot.markShown(Greetings.TIME_BASED)
        // No name: the time greeting is the only one there is
        repeat(50) { assertEquals(Greetings.TIME_BASED, rot.next { Greetings.fits(it, noFacts, "") }) }
        // With a name the two time greetings take turns
        var last = Greetings.TIME_BASED
        repeat(50) {
            val g = rot.next { Greetings.fits(it, noFacts, "Gaurab") }
            assertNotEquals(last, g)
            last = g
        }
    }

    @Test
    fun longNamesStillProduceTwoLines() {
        val name = "Abcdefghijklmnopqrst" // the 20-character maximum
        for (hour in 0..23) for (id in Greetings.REGULAR_IDS) {
            val lines = Greetings.lines(id, name, at(hour))
            assertTrue(lines.first.isNotBlank() && lines.second.isNotBlank())
            assertNotNull(lines)
        }
    }
}
