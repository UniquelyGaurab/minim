package com.minim.music.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot

class HeartMotionTest {

    @Test
    fun tapFlipsBothWays() {
        assertTrue(HeartMotion.afterTap(false))
        assertFalse(HeartMotion.afterTap(true))
    }

    @Test
    fun doubleTapLikesButNeverUnlikes() {
        assertTrue(HeartMotion.afterDoubleTap(false))
        assertTrue(HeartMotion.doubleTapChanges(false))
        // Double tapping a liked song keeps it liked and changes nothing
        assertTrue(HeartMotion.afterDoubleTap(true))
        assertFalse(HeartMotion.doubleTapChanges(true))
    }

    @Test
    fun likeSpringPeaksAboutOnePointTwoAndSettlesAtOne() {
        assertEquals(HeartMotion.PRESS_SCALE, HeartMotion.likeScale(0f), 1e-4f)
        val peak = (0..100).maxOf { HeartMotion.likeScale(it / 100f) }
        assertEquals(1.2f, peak, 0.01f)
        // A small bounce under 1 after the peak, then exactly 1
        val after = (36..99).minOf { HeartMotion.likeScale(it / 100f) }
        assertTrue(after < 1f && after > 0.9f)
        assertEquals(1f, HeartMotion.likeScale(1f), 1e-6f)
    }

    @Test
    fun unlikeShrinksSlightlyAndNeverGrows() {
        val max = (0..100).maxOf { HeartMotion.unlikeScale(it / 100f, 1f) }
        val min = (0..100).minOf { HeartMotion.unlikeScale(it / 100f, 1f) }
        assertEquals(1f, max, 1e-4f)
        assertEquals(HeartMotion.UNLIKE_DIP, min, 0.01f)
        assertEquals(1f, HeartMotion.unlikeScale(1f), 1e-6f)
    }

    @Test
    fun inkSpreadsFromNothingToFull() {
        assertEquals(0f, HeartMotion.inkRadius(0f), 0f)
        assertEquals(1f, HeartMotion.inkRadius(1f), 0f)
        assertTrue(HeartMotion.inkRadius(0.5f) > 0.5f) // fast then slowing
    }

    @Test
    fun burstHasSixDotsThatFlyOutAndAreGoneBy300ms() {
        assertEquals(6, HeartMotion.BURST_DOTS)
        assertEquals(300L, HeartMotion.BURST_MS)
        for (i in 0 until HeartMotion.BURST_DOTS) {
            val start = HeartMotion.burstDot(i, 0f, 10f, 30f, 3f)
            val mid = HeartMotion.burstDot(i, 0.5f, 10f, 30f, 3f)
            val end = HeartMotion.burstDot(i, 1f, 10f, 30f, 3f)
            assertEquals(10f, hypot(start.dx, start.dy), 1e-3f)
            assertTrue(hypot(mid.dx, mid.dy) > 10f)
            assertEquals(30f, hypot(end.dx, end.dy), 1e-3f)
            assertEquals(0f, end.alpha, 1e-6f)
            assertEquals(0f, end.radius, 1e-6f)
        }
    }

    @Test
    fun bigHeartFadesUpThenFloatsAwayAndVanishes() {
        val start = HeartMotion.bigHeart(0f)
        val held = HeartMotion.bigHeart(0.4f)
        val end = HeartMotion.bigHeart(1f)
        assertEquals(0f, start.alpha, 1e-6f)
        assertEquals(1f, held.alpha, 1e-6f)
        assertEquals(0f, held.rise, 1e-6f)
        assertEquals(0f, end.alpha, 1e-6f)
        assertEquals(1f, end.rise, 1e-6f)
    }
}
