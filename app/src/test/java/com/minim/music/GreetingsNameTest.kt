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

/** v4.7: name greetings, no-name greetings and the greeting-only artist name rule. */
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
            lastId = "x", lastTitle = "Hukum", lastArtist = "Anirudh Ravichander", topArtistWeek = "Anirudh Ravichander"
        )
        val all = Greetings.DATA_IDS.mapNotNull { Greetings.dataLines(it, facts, at(15)) }
        val text = all.joinToString(" ") { it.first + " " + it.second }
        assertTrue(text.contains("Anirudh"))
        assertFalse(text.contains("Ravichander"))
    }

    @Test
    fun songTitleCleanupStillUsesTheFullArtist() {
        assertEquals("Hukum", Greetings.shortTitle("Anirudh Ravichander - Hukum", "Anirudh Ravichander"))
    }

    // ---- Name and no-name greetings ----

    @Test
    fun thereAreFiftyTwoGreetings() {
        assertEquals(6, Greetings.NAME_IDS.size)
        assertEquals(4, Greetings.NO_NAME_IDS.size)
        assertEquals(27, Greetings.DATA_IDS.size)
        assertEquals(15, Greetings.COUNT)
        assertEquals(52, Greetings.TOTAL)
        assertEquals(52, (Greetings.REGULAR_IDS + Greetings.DATA_IDS).toSet().size)
    }

    @Test
    fun nameGreetingsUseTheNameAndNeedOne() {
        for (id in Greetings.NAME_IDS) {
            for (hour in listOf(2, 9, 14, 20)) {
                val lines = Greetings.personalLines(id, "Gaurab", at(hour))
                assertNotNull("id $id", lines)
                assertTrue("id $id", (lines!!.first + lines.second).contains("Gaurab"))
            }
            assertNull(Greetings.personalLines(id, ""))
            assertNull(Greetings.personalLines(id, "   "))
            assertTrue(Greetings.fits(id, noFacts, "Gaurab"))
            assertFalse(Greetings.fits(id, noFacts, ""))
        }
        assertEquals("Evening, Gaurab.", Greetings.personalLines(201, "Gaurab", at(19))!!.first)
        assertEquals("Gaurab's back." to "Press play.", Greetings.personalLines(200, " Gaurab "))
    }

    @Test
    fun noNameGreetingsOnlyWithoutAName() {
        for (id in Greetings.NO_NAME_IDS) {
            assertNotNull(Greetings.personalLines(id, ""))
            assertNull(Greetings.personalLines(id, "Gaurab"))
            assertTrue(Greetings.fits(id, noFacts, ""))
            assertFalse(Greetings.fits(id, noFacts, "Gaurab"))
        }
        assertEquals("Hey, you." to "Yeah, you.", Greetings.personalLines(210, ""))
    }

    @Test
    fun personalGreetingsAreNotDataOnes() {
        for (id in Greetings.NAME_IDS + Greetings.NO_NAME_IDS) {
            assertTrue(Greetings.isPersonal(id))
            assertFalse(Greetings.isData(id))
        }
        for (id in Greetings.DATA_IDS) assertFalse(Greetings.isPersonal(id))
        for (id in 0 until Greetings.COUNT) assertFalse(Greetings.isPersonal(id))
    }

    private fun rotate(name: String, n: Int = 3000): List<Int> {
        val rot = Greetings.Rotation(Random(7))
        val out = mutableListOf<Int>()
        var last = -1
        repeat(n) {
            val g = rot.next { Greetings.fits(it, noFacts, name) }
            assertNotEquals("no repeats twice in a row", last, g)
            last = g
            out += g
        }
        return out
    }

    @Test
    fun rotationWithANameShowsNameGreetingsButNeverNoNameOnes() {
        val shown = rotate("Gaurab").toSet()
        assertTrue(shown.containsAll(Greetings.NAME_IDS))
        assertTrue(shown.none { it in Greetings.NO_NAME_IDS })
        assertTrue(shown.containsAll((0 until Greetings.COUNT).toList()))
    }

    @Test
    fun rotationWithoutANameShowsNoNameGreetingsButNeverNameOnes() {
        val shown = rotate("").toSet()
        assertTrue(shown.containsAll(Greetings.NO_NAME_IDS))
        assertTrue(shown.none { it in Greetings.NAME_IDS })
    }

    @Test
    fun nextRegularWithoutFitsNeverGivesAPersonalGreeting() {
        val rot = Greetings.Rotation(Random(3))
        repeat(500) { assertTrue(rot.nextRegular() in 0 until Greetings.COUNT) }
    }

    @Test
    fun longNamesStillProduceTwoLines() {
        val name = "Abcdefghijklmnopqrst" // the 20-character maximum
        for (id in Greetings.NAME_IDS) {
            val lines = Greetings.personalLines(id, name)!!
            assertTrue(lines.first.isNotBlank() && lines.second.isNotBlank())
        }
    }
}
