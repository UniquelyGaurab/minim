package com.minim.music.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SeekMotionTest {

    @Test
    fun fingerPositionMapsToFractionAndClamps() {
        assertEquals(0f, SeekMotion.fractionAt(10f, 10f, 110f), 1e-6f)
        assertEquals(0.5f, SeekMotion.fractionAt(60f, 10f, 110f), 1e-6f)
        assertEquals(1f, SeekMotion.fractionAt(500f, 10f, 110f), 1e-6f)
        assertEquals(0f, SeekMotion.fractionAt(-50f, 10f, 110f), 1e-6f)
        assertEquals(0f, SeekMotion.fractionAt(5f, 10f, 10f), 1e-6f)
    }

    @Test
    fun targetTimeAndPillLabel() {
        assertEquals(167_000L, SeekMotion.targetMs(0.5f, 334_000L))
        assertEquals(0L, SeekMotion.targetMs(0.5f, 0L))
        assertEquals(334_000L, SeekMotion.targetMs(2f, 334_000L))
        assertEquals("2:47", SeekMotion.label(167_400L))
        assertEquals("0:00", SeekMotion.label(0L))
        assertEquals("1:02:05", SeekMotion.label(3_725_000L))
    }

    @Test
    fun tapVersusDragUsesTheTouchSlop() {
        assertFalse(SeekMotion.isDrag(100f, 104f, 8f))
        assertTrue(SeekMotion.isDrag(100f, 109f, 8f))
        assertTrue(SeekMotion.isDrag(100f, 90f, 8f))
    }

    @Test
    fun edgeTickOnlyWhenArrivingAtAnEnd() {
        assertEquals(-1, SeekMotion.edge(0f))
        assertEquals(1, SeekMotion.edge(1f))
        assertEquals(0, SeekMotion.edge(0.4f))
        assertTrue(SeekMotion.edgeTicks(0, -1))
        assertTrue(SeekMotion.edgeTicks(0, 1))
        assertFalse(SeekMotion.edgeTicks(1, 1)) // staying at the end doesn't keep ticking
        assertFalse(SeekMotion.edgeTicks(-1, 0))
    }

    @Test
    fun fillGlidesBetweenUpdatesOnlyWhilePlaying() {
        assertEquals(0.5f, SeekMotion.liveFraction(50_000, 100_000, 0, true), 1e-6f)
        assertEquals(0.505f, SeekMotion.liveFraction(50_000, 100_000, 500, true), 1e-6f)
        assertEquals(0.5f, SeekMotion.liveFraction(50_000, 100_000, 500, false), 1e-6f)
        assertEquals(1f, SeekMotion.liveFraction(99_900, 100_000, 5_000, true), 1e-6f)
        assertEquals(0f, SeekMotion.liveFraction(10, 0, 5, true), 1e-6f)
    }

    @Test
    fun grabbedBarIsThreeTimesThicker() {
        assertEquals(5f, SeekMotion.thickness(5f, 0f), 1e-6f)
        assertEquals(15f, SeekMotion.thickness(5f, 1f), 1e-6f)
    }

    @Test
    fun oldPositionsAreIgnoredUntilTheSeekLands() {
        assertFalse(SeekMotion.acceptsPosition(10_000, 120_000, 200))
        assertTrue(SeekMotion.acceptsPosition(119_500, 120_000, 200))
        assertTrue(SeekMotion.acceptsPosition(10_000, 120_000, SeekMotion.SEEK_HOLD_MS))
    }

    @Test
    fun soundDipsDownThenComesBackToFull() {
        assertEquals(1f, SeekMotion.dipOut(0), 1e-6f)
        assertEquals(SeekMotion.DIP_FLOOR, SeekMotion.dipOut(SeekMotion.DIP_OUT_MS), 1e-6f)
        assertEquals(SeekMotion.DIP_FLOOR, SeekMotion.dipIn(0), 1e-6f)
        assertEquals(1f, SeekMotion.dipIn(SeekMotion.DIP_IN_MS), 1e-6f)
        assertTrue(SeekMotion.dipIn(SeekMotion.DIP_IN_MS / 2) > 0.5f)
        // The whole dip is quick: well under half a second plus the wait for the new spot
        assertTrue(SeekMotion.DIP_OUT_MS + SeekMotion.DIP_IN_MS < 500L)
    }
}
