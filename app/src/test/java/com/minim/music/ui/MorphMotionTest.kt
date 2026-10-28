package com.minim.music.ui

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MorphMotionTest {
    private val eps = 0.0001f

    // ---- progress mapping -------------------------------------------------------------------

    @Test fun swipeUpFromPillOpensByTheFinger() {
        assertEquals(0f, MorphMotion.dragProgress(0f, 0f, 600f), eps)
        assertEquals(0.5f, MorphMotion.dragProgress(0f, -300f, 600f), eps)
        assertEquals(1f, MorphMotion.dragProgress(0f, -600f, 600f), eps)
    }

    @Test fun swipeDownFromNowPlayingClosesByTheFinger() {
        assertEquals(1f, MorphMotion.dragProgress(1f, 0f, 600f), eps)
        assertEquals(0.75f, MorphMotion.dragProgress(1f, 150f, 600f), eps)
        assertEquals(0f, MorphMotion.dragProgress(1f, 600f, 600f), eps)
    }

    @Test fun progressNeverLeavesZeroToOne() {
        assertEquals(1f, MorphMotion.dragProgress(0f, -5000f, 600f), eps)
        assertEquals(0f, MorphMotion.dragProgress(0f, 400f, 600f), eps)
        assertEquals(0f, MorphMotion.dragProgress(1f, 9000f, 600f), eps)
        assertEquals(1f, MorphMotion.dragProgress(1f, -100f, 600f), eps)
    }

    @Test fun aCaughtMorphCarriesOnFromWhereItWas() {
        assertEquals(0.6f, MorphMotion.dragProgress(0.4f, -120f, 600f), eps)
    }

    @Test fun zeroTravelKeepsTheStart() {
        assertEquals(0.3f, MorphMotion.dragProgress(0.3f, -100f, 0f), eps)
    }

    // ---- snap thresholds ---------------------------------------------------------------------

    @Test fun openingReleasedPastAThirdSpringsOpen() {
        assertTrue(MorphMotion.settlesOpen(0.34f, 0f, startedOpen = false))
        assertTrue(MorphMotion.settlesOpen(0.9f, 0f, startedOpen = false))
    }

    @Test fun openingReleasedShortOfAThirdSpringsBack() {
        assertFalse(MorphMotion.settlesOpen(0.32f, 0f, startedOpen = false))
        assertFalse(MorphMotion.settlesOpen(0.05f, 0f, startedOpen = false))
    }

    @Test fun closingDraggedDownPastAThirdCloses() {
        assertFalse(MorphMotion.settlesOpen(0.66f, 0f, startedOpen = true))
        assertFalse(MorphMotion.settlesOpen(0.2f, 0f, startedOpen = true))
    }

    @Test fun closingDraggedDownLessThanAThirdSpringsBackOpen() {
        assertTrue(MorphMotion.settlesOpen(0.68f, 0f, startedOpen = true))
        assertTrue(MorphMotion.settlesOpen(0.95f, 0f, startedOpen = true))
    }

    // ---- flick -------------------------------------------------------------------------------

    @Test fun aFastFlickUpOpensEvenFromAShortSwipe() {
        assertTrue(MorphMotion.settlesOpen(0.08f, -MorphMotion.FLICK_DP_PER_S, startedOpen = false))
        assertTrue(MorphMotion.settlesOpen(0.08f, -2500f, startedOpen = false))
    }

    @Test fun aFastFlickDownClosesEvenFromAShortSwipe() {
        assertFalse(MorphMotion.settlesOpen(0.92f, MorphMotion.FLICK_DP_PER_S, startedOpen = true))
        assertFalse(MorphMotion.settlesOpen(0.92f, 3000f, startedOpen = true))
    }

    @Test fun aFlickBackTheOtherWayWins() {
        // Dragged most of the way up, then flicked back down: closes
        assertFalse(MorphMotion.settlesOpen(0.8f, 1500f, startedOpen = false))
        // Dragged most of the way down, then flicked back up: stays open
        assertTrue(MorphMotion.settlesOpen(0.2f, -1500f, startedOpen = true))
    }

    @Test fun aSlowReleaseIsNotAFlick() {
        assertFalse(MorphMotion.settlesOpen(0.1f, -(MorphMotion.FLICK_DP_PER_S - 1f), startedOpen = false))
        assertTrue(MorphMotion.settlesOpen(0.9f, MorphMotion.FLICK_DP_PER_S - 1f, startedOpen = true))
    }

    // ---- settle timing -----------------------------------------------------------------------

    @Test fun settleTakesLongerTheFurtherItGoes() {
        val short = MorphMotion.settleMs(0.8f, 1f, 0f, MorphMotion.OPEN_MS)
        val long = MorphMotion.settleMs(0.35f, 1f, 0f, MorphMotion.OPEN_MS)
        assertTrue(long > short)
        assertTrue(long <= MorphMotion.OPEN_MS)
        assertTrue(short >= MorphMotion.SETTLE_MIN_MS)
    }

    @Test fun aFastReleaseSettlesSooner() {
        val slow = MorphMotion.settleMs(0.4f, 1f, 0f, MorphMotion.OPEN_MS)
        val fast = MorphMotion.settleMs(0.4f, 1f, 8f, MorphMotion.OPEN_MS)
        assertTrue(fast < slow)
        assertTrue(fast >= MorphMotion.SETTLE_MIN_MS)
    }

    @Test fun alreadyThereTakesNoTime() {
        assertEquals(0L, MorphMotion.settleMs(1f, 1f, 0f, MorphMotion.OPEN_MS))
    }

    // ---- timeline ----------------------------------------------------------------------------

    @Test fun pillIsBlackAtTheStartAndAlbumColourAtTheEnd() {
        assertEquals(0f, MorphMotion.color(0f), eps)
        assertEquals(1f, MorphMotion.color(1f), eps)
        assertEquals(1f, MorphMotion.pillButtons(0f), eps)
        assertEquals(0f, MorphMotion.pillButtons(0.5f), eps)
    }

    @Test fun theRestFadesInStaggeredAndLate() {
        // Nothing extra shows while the pill is still small
        for (f in listOf(MorphMotion::header, MorphMotion::lyric, MorphMotion::controls, MorphMotion::pills)) {
            assertEquals(0f, f(0.3f), eps)
            assertEquals(1f, f(1f), eps)
        }
        // Each group a beat after the one before
        val p = 0.68f
        assertTrue(MorphMotion.header(p) > MorphMotion.lyric(p))
        assertTrue(MorphMotion.lyric(p) > MorphMotion.controls(p))
        assertTrue(MorphMotion.controls(p) > MorphMotion.pills(p))
    }

    @Test fun coverStaysInsideTheStretchingPillAllTheWay() {
        val pill = floatArrayOf(40f, 1800f, 1040f, 1910f)
        val miniCover = floatArrayOf(56f, 1815f, 136f, 1895f)
        val full = floatArrayOf(0f, 0f, 1080f, 2200f)
        val bigCover = floatArrayOf(146f, 420f, 934f, 1208f)
        for (i in 0..20) {
            val p = i / 20f
            val c = MorphMotion.lerpRect(pill, full, p)
            val a = MorphMotion.lerpRect(miniCover, bigCover, p)
            assertTrue(a[0] >= c[0] - eps && a[1] >= c[1] - eps && a[2] <= c[2] + eps && a[3] <= c[3] + eps)
        }
    }

    @Test fun screenUnderneathGetsLessDimmedAsItIsDraggedDown() {
        assertEquals(MorphMotion.DIM_MAX, MorphMotion.dim(1f), eps)
        assertTrue(MorphMotion.dim(0.6f) < MorphMotion.dim(0.9f))
        assertTrue(MorphMotion.dim(0.2f) < MorphMotion.dim(0.6f))
        assertEquals(0f, MorphMotion.dim(0f), eps)
    }

    @Test fun pillLosesItsRoundEnds() {
        assertEquals(27f, MorphMotion.corner(0f, 27f), eps)
        assertEquals(0f, MorphMotion.corner(1f, 27f), eps)
        assertTrue(MorphMotion.corner(0.5f, 27f) in 0f..27f)
    }

    @Test fun coverGoesFromRoundToTheBigRoundedSquare() {
        // On screen: 20 (half the 40dp round cover) -> 16 (the card)
        assertEquals(20f, MorphMotion.coverRadius(0f, 20f, 16f), eps)
        assertEquals(16f, MorphMotion.coverRadius(1f, 20f, 16f), eps)
        // The big cover shrunk to 40/300 needs 150 so it still shows round
        assertEquals(150f, MorphMotion.radiusForScale(20f, 40f / 300f), 0.01f)
        assertEquals(16f, MorphMotion.radiusForScale(16f, 1f), eps)
    }

    @Test fun rectsMoveInAStraightLine() {
        val pill = floatArrayOf(40f, 1800f, 1040f, 1910f)
        val full = floatArrayOf(0f, 0f, 1080f, 2200f)
        assertArrayEquals(pill, MorphMotion.lerpRect(pill, full, 0f), eps)
        assertArrayEquals(full, MorphMotion.lerpRect(pill, full, 1f), eps)
        assertArrayEquals(floatArrayOf(20f, 900f, 1060f, 2055f), MorphMotion.lerpRect(pill, full, 0.5f), eps)
    }

    @Test fun ticksOnlyWhenItLocks() {
        assertTrue(MorphMotion.locked(0.7f, 1f))
        assertTrue(MorphMotion.locked(0.2f, 0f))
        assertFalse(MorphMotion.locked(0.2f, 0.5f))
        assertFalse(MorphMotion.locked(1f, 1f))
    }
}
