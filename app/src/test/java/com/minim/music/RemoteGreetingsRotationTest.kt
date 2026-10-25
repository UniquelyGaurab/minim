package com.minim.music

import com.minim.music.data.greetings.DateRange
import com.minim.music.data.greetings.RemoteGreeting
import com.minim.music.data.greetings.RemoteGreetingFile
import com.minim.music.ui.GreetingFacts
import com.minim.music.ui.Greetings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import kotlin.random.Random

class RemoteGreetingsRotationTest {

    private fun day(y: Int, m: Int, d: Int): Calendar = Calendar.getInstance().apply { set(y, m - 1, d, 12, 0) }

    private val diwali = DateRange(11, 6, 11, 10, 2026, 11, 8)

    private val remoteFile = RemoteGreetingFile(
        version = 1,
        anytime = listOf(
            RemoteGreeting("Volume up.", "Worries down."),
            RemoteGreeting("{name} on the decks.", "Crowd goes wild.")
        ),
        occasions = listOf(
            RemoteGreeting("Happy Diwali!", "Lights up.", diwali),
            RemoteGreeting("Diya on,", "bass on.", diwali),
            RemoteGreeting("Happy Diwali, {name}!", "Make it sparkle.", diwali)
        )
    )

    /** Runs [n] picks the way MainActivity does, with nothing playing (only empty facts). */
    private fun picks(file: RemoteGreetingFile, now: Calendar, name: String, n: Int, seed: Int = 7): List<Int> {
        val r = Greetings.Rotation(Random(seed)) { file }
        r.markShown(Greetings.TIME_BASED)
        return (1..n).map {
            r.next(now) { id ->
                if (Greetings.isRemote(id)) Greetings.remoteLines(id, name, now, file) != null
                else Greetings.fits(id, GreetingFacts(), name, now)
            }
        }
    }

    @Test
    fun idRangesDoNotOverlap() {
        assertTrue(Greetings.isStat(3000) && !Greetings.isAnytime(3000) && !Greetings.isOccasion(3000))
        assertTrue(!Greetings.isStat(1000) && !Greetings.isStat(5000))
        assertTrue(Greetings.isAnytime(1000) && !Greetings.isOccasion(1000))
        assertTrue(Greetings.isOccasion(5000) && !Greetings.isAnytime(5000))
        assertTrue(!Greetings.isRemote(Greetings.TIME_BASED) && !Greetings.isRemote(Greetings.TIME_NAMED))
    }

    @Test
    fun anytimeLinesJoinTheRegularRotation() {
        val ids = picks(remoteFile, day(2026, 7, 1), "Gaurab", 400)
        assertTrue(1000 in ids)
        assertTrue(1001 in ids)
        // No occasion outside its dates
        assertTrue(ids.none { Greetings.isOccasion(it) })
    }

    @Test
    fun nameLinesNeedAName() {
        val ids = picks(remoteFile, day(2026, 11, 8), "", 400)
        assertTrue(1001 !in ids)
        assertTrue(5002 !in ids)
        assertTrue(1000 in ids)
        assertTrue(5000 in ids)
        assertNull(Greetings.remoteLines(5002, "", day(2026, 11, 8), remoteFile))
        assertEquals("Happy Diwali, Gaurab!" to "Make it sparkle.", Greetings.remoteLines(5002, "Gaurab", day(2026, 11, 8), remoteFile))
    }

    @Test
    fun occasionsOnlyInsideTheirDatesAndMoreOftenOnTheDay() {
        val before = picks(remoteFile, day(2026, 11, 5), "Gaurab", 300)
        val inRange = picks(remoteFile, day(2026, 11, 7), "Gaurab", 300)
        val onDay = picks(remoteFile, day(2026, 11, 8), "Gaurab", 300)
        val nextYear = picks(remoteFile, day(2027, 11, 8), "Gaurab", 300)
        assertEquals(0, before.count { Greetings.isOccasion(it) })
        assertEquals(0, nextYear.count { Greetings.isOccasion(it) })
        val a = inRange.count { Greetings.isOccasion(it) }
        val b = onDay.count { Greetings.isOccasion(it) }
        assertEquals(50, a)   // every 6th greeting
        assertEquals(100, b)  // every 3rd greeting
        // On the day itself the first greeting after the time-based one is a festival one
        assertTrue(Greetings.isOccasion(onDay.first()))
    }

    @Test
    fun neverTwiceInARowWithRemoteLines() {
        for (seed in 1..20) {
            val ids = picks(remoteFile, day(2026, 11, 8), "Gaurab", 200, seed)
            ids.zipWithNext().forEach { (x, y) -> assertNotEquals("seed $seed", x, y) }
            assertNotEquals(Greetings.TIME_BASED, ids.first())
        }
    }

    @Test
    fun singleOccasionLineStillNeverRepeatsBackToBack() {
        val one = RemoteGreetingFile(1, emptyList(), listOf(RemoteGreeting("Jai Hind!", "Play it proud.", DateRange(8, 15, 8, 15))), 0)
        val ids = picks(one, day(2027, 8, 15), "", 120)
        assertEquals(40, ids.count { it == 5000 })
        // 5.1.0: with no name and no other lines the time greeting is the only regular one and may
        // come back, but the festival line never shows twice in a row
        ids.zipWithNext().forEach { (x, y) -> assertTrue(x != 5000 || y != 5000) }
    }

    @Test
    fun emptyRemoteFileBehavesLikeBefore() {
        val ids = picks(RemoteGreetingFile.EMPTY, day(2026, 11, 8), "Gaurab", 200)
        assertTrue(ids.none { Greetings.isRemote(it) })
        // 5.1.0: only the built-in time greetings are left, taking turns
        assertEquals(setOf(Greetings.TIME_BASED, Greetings.TIME_NAMED), ids.toSet())
        ids.zipWithNext().forEach { (x, y) -> assertNotEquals(x, y) }
    }

    @Test
    fun fileSwapMidRotationIsSafe() {
        var file = remoteFile
        val r = Greetings.Rotation(Random(3)) { file }
        val now = day(2026, 11, 8)
        val fits: (Int) -> Boolean = { id ->
            if (Greetings.isRemote(id)) Greetings.remoteLines(id, "G", now, file) != null
            else Greetings.fits(id, GreetingFacts(), "G", now)
        }
        r.markShown(Greetings.TIME_BASED)
        repeat(30) { r.next(now, fits) }
        file = RemoteGreetingFile.EMPTY
        val after = (1..100).map { r.next(now, fits) }
        assertTrue(after.none { Greetings.isRemote(it) })
        // A remote id that left the file resolves to nothing (MainActivity then picks a regular one)
        assertNull(Greetings.remoteLines(5000, "G", now, RemoteGreetingFile.EMPTY))
        assertNull(Greetings.remoteLines(1000, "G", now, RemoteGreetingFile.EMPTY))
    }

    @Test
    fun nextRegularFallbackIncludesAnytimeOnlyWhenTheyFit() {
        val r = Greetings.Rotation(Random(5)) { remoteFile }
        val strict = (1..200).map { r.nextRegular() }
        assertTrue(strict.all { it == Greetings.TIME_BASED })
        val open = (1..200).map {
            r.nextRegular { id ->
                if (Greetings.isRemote(id)) Greetings.remoteLines(id, "G", day(2026, 11, 8), remoteFile) != null
                else Greetings.fits(id, GreetingFacts(), "G")
            }
        }
        assertTrue(open.any { Greetings.isAnytime(it) })
        assertTrue(open.none { Greetings.isOccasion(it) })
    }
}
