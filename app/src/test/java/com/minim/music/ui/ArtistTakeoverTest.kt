package com.minim.music.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class ArtistTakeoverTest {
    private val min = 60_000L

    @Test fun needsTenMinutesOfListening() {
        assertFalse(ArtistTakeover.qualifies(9 * min + 59_000L, hasPhoto = true))
        assertTrue(ArtistTakeover.qualifies(10 * min, hasPhoto = true))
        assertTrue(ArtistTakeover.qualifies(42 * min, hasPhoto = true))
    }

    @Test fun needsAPhoto() {
        assertFalse(ArtistTakeover.qualifies(30 * min, hasPhoto = false))
        assertFalse(ArtistTakeover.qualifies(0L, hasPhoto = false))
    }

    @Test fun rotationAtMostEveryTenMinutes() {
        val t0 = 5_000_000L
        assertTrue(ArtistTakeover.rotationAllowed(t0, null))
        assertFalse(ArtistTakeover.rotationAllowed(t0 + 9 * min, t0))
        assertFalse(ArtistTakeover.rotationAllowed(t0 + 10 * min - 1, t0))
        assertTrue(ArtistTakeover.rotationAllowed(t0 + 10 * min, t0))
    }

    @Test fun songTriggerNeedsTwoMinuteGap() {
        val t0 = 5_000_000L
        assertTrue(ArtistTakeover.songAllowed(t0, null))
        assertFalse(ArtistTakeover.songAllowed(t0 + 30_000L, t0))
        assertFalse(ArtistTakeover.songAllowed(t0 + 2 * min - 1, t0))
        assertTrue(ArtistTakeover.songAllowed(t0 + 2 * min, t0))
        // A song trigger 3 minutes after a takeover is fine, the rotation still waits
        assertTrue(ArtistTakeover.songAllowed(t0 + 3 * min, t0))
        assertFalse(ArtistTakeover.rotationAllowed(t0 + 3 * min, t0))
    }

    @Test fun matchesSongsByTheTopArtist() {
        assertTrue(ArtistTakeover.isByArtist("Anirudh Ravichander", "Anirudh Ravichander"))
        assertTrue(ArtistTakeover.isByArtist("anirudh ravichander", "Anirudh Ravichander"))
        assertFalse(ArtistTakeover.isByArtist("Arijit Singh", "Anirudh Ravichander"))
        assertFalse(ArtistTakeover.isByArtist(null, "Anirudh Ravichander"))
        assertFalse(ArtistTakeover.isByArtist("Arijit Singh", null))
    }

    @Test fun linesUseNameAndMinutes() {
        val (a, b) = ArtistTakeover.lines(0, "Anirudh Ravichander", 13 * min + 20_000L)
        assertEquals("Anirudh's running your day.", a)
        assertEquals("13m and counting.", b)
        val (c, _) = ArtistTakeover.lines(1, "Alan Walker", 10 * min)
        assertEquals("Alan Walker, 10 minutes deep.", c)
        for (i in 0 until ArtistTakeover.LINE_COUNT) {
            val (l1, l2) = ArtistTakeover.lines(i, "Alan Walker", 12 * min)
            assertTrue((l1 + l2).contains("Alan Walker"))
            assertTrue((l1 + l2).contains("12"))
        }
    }

    @Test fun pickerNeverRepeatsBackToBack() {
        val p = ArtistTakeover.LinePicker(Random(7))
        var last = p.next()
        repeat(200) {
            val n = p.next()
            assertNotEquals(last, n)
            last = n
        }
    }
}
