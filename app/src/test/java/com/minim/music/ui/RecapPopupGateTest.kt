package com.minim.music.ui

import com.minim.music.data.stats.DayStats
import com.minim.music.ui.RecapPopupGate.Action
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecapPopupGateTest {
    private val day = 20261021
    private fun stats(timeMin: Long, songs: Int, d: Int = day) =
        DayStats(d, timeMin * 60_000L, songs, 1, null, null, 22, null, false)

    private val big = stats(45, 12)

    // ---- once per recap / quiet days / viewed ----

    @Test fun eligibleForANewRecap() {
        assertTrue(RecapPopupGate.eligible(big, popupShownDay = 0, viewedDay = 0))
        assertTrue(RecapPopupGate.eligible(big, popupShownDay = day - 1, viewedDay = day - 1))
    }

    @Test fun oncePerRecap() {
        assertFalse(RecapPopupGate.eligible(big, popupShownDay = day, viewedDay = 0))
    }

    @Test fun notAfterTheRecapWasViewed() {
        assertFalse(RecapPopupGate.eligible(big, popupShownDay = 0, viewedDay = day))
    }

    @Test fun quietDayGetsNoPopup() {
        assertFalse(RecapPopupGate.eligible(stats(3, 1), 0, 0)) // under 5 min
        assertFalse(RecapPopupGate.eligible(stats(40, 0), 0, 0)) // no counted song
        assertFalse(RecapPopupGate.eligible(null, 0, 0))
    }

    @Test fun aNewerRecapShowsAgain() {
        val next = stats(30, 8, day + 1)
        assertTrue(RecapPopupGate.eligible(next, popupShownDay = day, viewedDay = day))
    }

    // ---- night-owl rule ----

    @Test fun showsOnHomeWithNothingPlaying() {
        val g = RecapPopupGate()
        assertEquals(Action.SHOW, g.update(true, onHome = true, nowPlayingOpen = false, musicPlaying = false))
        assertFalse(g.isHeld)
    }

    @Test fun neverOnTopOfNowPlaying() {
        val g = RecapPopupGate()
        assertEquals(Action.HOLD, g.update(true, onHome = true, nowPlayingOpen = true, musicPlaying = true))
        assertEquals(Action.HOLD, g.update(true, onHome = true, nowPlayingOpen = true, musicPlaying = false))
        assertTrue(g.isHeld)
        // Back on Home (music still playing): now it shows
        assertEquals(Action.SHOW, g.update(true, onHome = true, nowPlayingOpen = false, musicPlaying = true))
    }

    @Test fun musicPlayingOnHomeHoldsUntilBackOnHome() {
        val g = RecapPopupGate()
        assertEquals(Action.HOLD, g.update(true, onHome = true, nowPlayingOpen = false, musicPlaying = true))
        assertEquals(Action.HOLD, g.update(true, onHome = true, nowPlayingOpen = false, musicPlaying = true))
        // Opens Now Playing, then comes back to Home
        assertEquals(Action.HOLD, g.update(true, onHome = true, nowPlayingOpen = true, musicPlaying = true))
        assertEquals(Action.SHOW, g.update(true, onHome = true, nowPlayingOpen = false, musicPlaying = true))
    }

    @Test fun musicStoppingOnHomeLetsItThrough() {
        val g = RecapPopupGate()
        assertEquals(Action.HOLD, g.update(true, onHome = true, nowPlayingOpen = false, musicPlaying = true))
        assertEquals(Action.SHOW, g.update(true, onHome = true, nowPlayingOpen = false, musicPlaying = false))
    }

    @Test fun otherScreensHoldUntilHome() {
        val g = RecapPopupGate()
        assertEquals(Action.HOLD, g.update(true, onHome = false, nowPlayingOpen = false, musicPlaying = false))
        assertEquals(Action.HOLD, g.update(true, onHome = false, nowPlayingOpen = false, musicPlaying = true))
        assertEquals(Action.SHOW, g.update(true, onHome = true, nowPlayingOpen = false, musicPlaying = true))
    }

    @Test fun busyWaitsWithoutCountingAsLeavingHome() {
        val g = RecapPopupGate()
        assertEquals(Action.HOLD, g.update(true, onHome = true, nowPlayingOpen = false, musicPlaying = true))
        // A song menu sheet on Home opens and closes: still held while music plays
        assertEquals(Action.HOLD, g.update(true, true, false, true, busy = true))
        assertEquals(Action.HOLD, g.update(true, onHome = true, nowPlayingOpen = false, musicPlaying = true))
        // Busy wins even on Home with nothing playing
        assertEquals(Action.HOLD, g.update(true, true, false, false, busy = true))
        assertEquals(Action.SHOW, g.update(true, onHome = true, nowPlayingOpen = false, musicPlaying = false))
    }

    @Test fun showStaysShowUntilMarkedThenResets() {
        val g = RecapPopupGate()
        g.update(true, onHome = false, nowPlayingOpen = false, musicPlaying = true)
        assertEquals(Action.SHOW, g.update(true, true, false, true))
        // The delayed re-check still says SHOW
        assertEquals(Action.SHOW, g.update(true, true, false, true))
        // Shown -> not eligible -> nothing, and the hold is cleared for the next recap
        assertEquals(Action.NONE, g.update(false, true, false, true))
        assertFalse(g.isHeld)
        assertEquals(Action.HOLD, g.update(true, true, false, true))
    }
}
