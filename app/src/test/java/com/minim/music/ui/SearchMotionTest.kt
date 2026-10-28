package com.minim.music.ui

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchMotionTest {

    @Test
    fun atTheStartOnlyThePillShows() {
        assertEquals(0f, SearchMotion.backdrop(0f), 0f)
        assertEquals(0f, SearchMotion.shape(0f), 0f)
        assertEquals(1f, SearchMotion.sourceContent(0f), 0f)
        assertEquals(0f, SearchMotion.realBar(0f), 0f)
        assertEquals(0f, SearchMotion.goButton(0f), 0f)
        assertEquals(1f, SearchMotion.magnifier(0f), 0f)
    }

    @Test
    fun atTheEndTheRealSearchScreenHasTakenOver() {
        assertEquals(1f, SearchMotion.backdrop(1f), 0f)
        assertEquals(1f, SearchMotion.shape(1f), 0f)
        assertEquals(0f, SearchMotion.sourceContent(1f), 0f)
        assertEquals(1f, SearchMotion.realBar(1f), 0f)
        assertEquals(1f, SearchMotion.logo(1f), 0f)
        assertEquals(1f, SearchMotion.goButton(1f), 0f)
        assertEquals(0f, SearchMotion.magnifier(1f), 0f)
        assertEquals(1f, SearchMotion.fillToOutline(1f), 0f)
    }

    @Test
    fun closingRetracesOpeningAndTheShapeOnlyGrows() {
        // Closing runs t from 1 back to 0 through the same functions, so it retraces opening;
        // the real bar must be fully gone before the pill finishes shrinking back
        assertEquals(0f, SearchMotion.realBar(0.7f), 0f)
        assertTrue(SearchMotion.shape(0.7f) < 1f)
        // And the shape only ever grows while opening
        var last = -1f
        for (i in 0..100) {
            val v = SearchMotion.shape(i / 100f)
            assertTrue(v >= last)
            last = v
        }
    }

    @Test
    fun rowsFadeInOneAfterAnother() {
        assertEquals(SearchMotion.LIST_START_MS, SearchMotion.listDelay(0))
        assertTrue(SearchMotion.listDelay(1) > SearchMotion.listDelay(0))
        assertTrue(SearchMotion.listDelay(3) > SearchMotion.listDelay(2))
        // Long lists don't keep you waiting: the cascade is capped
        assertEquals(SearchMotion.listDelay(SearchMotion.LIST_MAX_STAGGER), SearchMotion.listDelay(50))
    }

    @Test
    fun rectAndColourInterpolation() {
        val a = floatArrayOf(0f, 0f, 100f, 30f)
        val b = floatArrayOf(50f, 10f, 300f, 44f)
        assertArrayEquals(a, SearchMotion.lerpRect(a, b, 0f), 0f)
        assertArrayEquals(b, SearchMotion.lerpRect(a, b, 1f), 0f)
        assertArrayEquals(floatArrayOf(25f, 5f, 200f, 37f), SearchMotion.lerpRect(a, b, 0.5f), 1e-4f)
        assertEquals(0xFF000000.toInt(), SearchMotion.lerpColor(0xFF000000.toInt(), 0xFFFFFFFF.toInt(), 0f))
        assertEquals(0xFFFFFFFF.toInt(), SearchMotion.lerpColor(0xFF000000.toInt(), 0xFFFFFFFF.toInt(), 1f))
    }

    @Test
    fun windowsClampAndEaseEnds() {
        assertEquals(0f, SearchMotion.window(0.1f, 0.2f, 0.8f), 0f)
        assertEquals(0.5f, SearchMotion.window(0.5f, 0.2f, 0.8f), 1e-6f)
        assertEquals(1f, SearchMotion.window(0.9f, 0.2f, 0.8f), 0f)
        assertEquals(0f, SearchMotion.ease(0f), 0f)
        assertEquals(1f, SearchMotion.ease(1f), 1e-6f)
        assertEquals(0.5f, SearchMotion.ease(0.5f), 1e-6f)
    }
}
