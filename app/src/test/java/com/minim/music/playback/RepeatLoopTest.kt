package com.minim.music.playback

import androidx.media3.common.Player
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 5.8.3 (update 55): every repeat-one loop is a new play. The simulation below drives
 * [RepeatLoop] the way PlaybackController does: a one-second tick that credits listening time,
 * checks the position, and counts once 30 seconds are heard; plus the discontinuity the
 * MediaController reports when the song starts over.
 */
class RepeatLoopTest {

    /** A small copy of the controller's counting: one counted row per loop, time heard per row. */
    private class Sim(val durMs: Long, val repeatOne: Boolean) {
        val id = "song"
        var listenId: String? = id
        var heard = 0L
        var counted = false
        var lastTickPos = -1L
        val rows = ArrayList<Long>() // listened ms of each counted play
        var totalCredited = 0L
        private var rowIndex = -1

        fun startNewLoop(mediaId: String?) {
            if (!RepeatLoop.shouldStartNewLoop(mediaId == listenId, heard, durMs)) return
            if (rowIndex >= 0) rows[rowIndex] = heard
            heard = 0L; counted = false; rowIndex = -1; lastTickPos = -1L
        }

        fun discontinuity(reason: Int, oldId: String?, newId: String?) {
            if (RepeatLoop.isLoopDiscontinuity(reason, oldId, newId)) startNewLoop(newId)
        }

        fun tick(pos: Long) {
            if (RepeatLoop.isLoopByPosition(repeatOne, lastTickPos, pos, durMs)) startNewLoop(id)
            lastTickPos = pos
            heard += 1000L
            totalCredited += 1000L
            if (rowIndex >= 0) rows[rowIndex] = heard
            if (!counted && heard >= 30_000L) {
                counted = true
                rows.add(heard); rowIndex = rows.size - 1
            }
        }
    }

    /** Plays [loops] times through, ticking each second, with the discontinuity at each restart. */
    private fun playLoops(sim: Sim, loops: Int, sendDiscontinuity: Boolean = true) {
        val secs = (sim.durMs / 1000L).toInt()
        for (l in 0 until loops) {
            if (l > 0 && sendDiscontinuity) sim.discontinuity(Player.DISCONTINUITY_REASON_AUTO_TRANSITION, sim.id, sim.id)
            for (s in 1..secs) sim.tick(s * 1000L - 500L)
        }
    }

    @Test fun everyLoopCountsOnce() {
        val sim = Sim(180_000L, repeatOne = true)
        playLoops(sim, 4)
        assertEquals(4, sim.rows.size)
        // each row has its own listening time, and nothing is credited twice
        assertEquals(List(4) { 180_000L }, sim.rows)
        assertEquals(4 * 180_000L, sim.totalCredited)
        assertEquals(sim.totalCredited, sim.rows.sum())
    }

    @Test fun theTickAloneCatchesALoopIfTheDiscontinuityIsMissed() {
        val sim = Sim(180_000L, repeatOne = true)
        playLoops(sim, 3, sendDiscontinuity = false)
        assertEquals(3, sim.rows.size)
    }

    @Test fun discontinuityAndTickForTheSameLoopCountItOnce() {
        val sim = Sim(180_000L, repeatOne = true)
        playLoops(sim, 1)
        // both signals for the one restart
        sim.discontinuity(Player.DISCONTINUITY_REASON_AUTO_TRANSITION, sim.id, sim.id)
        for (s in 1..180) sim.tick(s * 1000L - 500L)
        sim.discontinuity(Player.DISCONTINUITY_REASON_AUTO_TRANSITION, sim.id, sim.id)
        sim.discontinuity(Player.DISCONTINUITY_REASON_AUTO_TRANSITION, sim.id, sim.id)
        for (s in 1..40) sim.tick(s * 1000L - 500L)
        assertEquals(3, sim.rows.size)
    }

    @Test fun aNormalPlayStillCountsOnce() {
        val sim = Sim(180_000L, repeatOne = false)
        playLoops(sim, 1)
        assertEquals(1, sim.rows.size)
        assertEquals(180_000L, sim.rows[0])
    }

    @Test fun aLoopStoppedBefore30SecondsDoesNotCount() {
        val sim = Sim(180_000L, repeatOne = true)
        playLoops(sim, 1)
        sim.discontinuity(Player.DISCONTINUITY_REASON_AUTO_TRANSITION, sim.id, sim.id)
        for (s in 1..20) sim.tick(s * 1000L - 500L)
        assertEquals(1, sim.rows.size)
    }

    @Test fun seekingBackFromTheMiddleIsNotALoop() {
        assertFalse(RepeatLoop.isLoopByPosition(true, 90_000L, 2_000L, 180_000L))
        assertFalse(RepeatLoop.isLoopDiscontinuity(Player.DISCONTINUITY_REASON_SEEK, "a", "a"))
        val sim = Sim(180_000L, repeatOne = true)
        for (s in 1..90) sim.tick(s * 1000L)
        for (s in 1..60) sim.tick(s * 1000L)
        assertEquals(1, sim.rows.size)
    }

    @Test fun movingToAnotherSongIsNotALoop() {
        assertFalse(RepeatLoop.isLoopDiscontinuity(Player.DISCONTINUITY_REASON_AUTO_TRANSITION, "a", "b"))
        assertFalse(RepeatLoop.isLoopDiscontinuity(Player.DISCONTINUITY_REASON_AUTO_TRANSITION, null, null))
        assertTrue(RepeatLoop.isLoopDiscontinuity(Player.DISCONTINUITY_REASON_AUTO_TRANSITION, "a", "a"))
        assertFalse(RepeatLoop.shouldStartNewLoop(false, 100_000L, 180_000L))
        // repeat off: the position check never fires
        assertFalse(RepeatLoop.isLoopByPosition(false, 179_000L, 500L, 180_000L))
        assertTrue(RepeatLoop.isLoopByPosition(true, 179_000L, 500L, 180_000L))
    }

    @Test fun shortSongsLoopToo() {
        val sim = Sim(20_000L, repeatOne = true)
        playLoops(sim, 5)
        // a 20 s song never reaches 30 s in one loop: the controller counts very short songs
        // at 80 % instead, so here we only check the loops restart cleanly
        assertEquals(0L, sim.rows.size.toLong())
        assertTrue(RepeatLoop.isLoopByPosition(true, 19_500L, 500L, 20_000L))
    }

    @Test fun theControllerListensForTheLoop() {
        val src = File("src/main/java/com/minim/music/playback/PlaybackController.kt").readText()
        assertTrue(src.contains("override fun onPositionDiscontinuity"))
        assertTrue(src.contains("RepeatLoop.isLoopDiscontinuity"))
        assertTrue(src.contains("RepeatLoop.isLoopByPosition"))
        assertTrue(src.contains("private fun startNewLoop"))
    }
}
