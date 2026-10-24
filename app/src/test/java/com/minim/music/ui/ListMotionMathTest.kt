package com.minim.music.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ListMotionMathTest {

    // ---- row entrance ----

    @Test
    fun staggerIsCappedAtTheTenthRow() {
        assertEquals(0L, RowEntranceMath.delayFor(0))
        assertEquals(25L, RowEntranceMath.delayFor(1))
        assertEquals(225L, RowEntranceMath.delayFor(9))
        // Row 10 onwards starts with row 9, so long lists don't drag on
        assertEquals(225L, RowEntranceMath.delayFor(10))
        assertEquals(225L, RowEntranceMath.delayFor(500))
        assertEquals(0L, RowEntranceMath.delayFor(-3))
    }

    @Test
    fun wholeEntranceFinishesInUnderHalfASecond() {
        assertEquals(465L, RowEntranceMath.TOTAL_MS)
        assertTrue(RowEntranceMath.TOTAL_MS < 500L)
        // Every row, however far down, is in place by the end
        for (rank in listOf(0, 5, 9, 10, 99)) assertEquals(1f, RowEntranceMath.progress(RowEntranceMath.TOTAL_MS, rank), 0f)
    }

    @Test
    fun rowsWaitForTheirTurnThenEaseIn() {
        assertEquals(0f, RowEntranceMath.progress(0L, 0), 0f)
        assertEquals(0f, RowEntranceMath.progress(49L, 2), 0f)
        val half = RowEntranceMath.progress(120L, 0)
        // Ease-out: past halfway at half time, never over 1
        assertTrue(half > 0.5f && half < 1f)
        assertEquals(1f, RowEntranceMath.progress(240L, 0), 0f)
        var last = 0f
        for (ms in 0L..RowEntranceMath.TOTAL_MS step 10L) {
            val p = RowEntranceMath.progress(ms, 3)
            assertTrue(p >= last && p <= 1f)
            last = p
        }
    }

    // ---- press scale ----

    @Test
    fun onlyPillCornersCount() {
        val d = 2.75f
        assertTrue(PressScaleMath.isPillCorner(999f * d, d))
        assertFalse(PressScaleMath.isPillCorner(16f * d, d))
        assertFalse(PressScaleMath.isPillCorner(20f * d, d))
        assertTrue(PressScaleMath.PRESSED_SCALE in 0.95f..0.97f)
        assertTrue(PressScaleMath.DOWN_MS in 100L..150L && PressScaleMath.UP_MS in 100L..150L)
    }

    // ---- stats count up ----

    @Test
    fun countRunsFromZeroToTheValue() {
        assertEquals("0", CountUpMath.countText(0, 42, 0f))
        assertEquals("42", CountUpMath.countText(0, 42, 1f))
        val mid = CountUpMath.valueAt(0, 42, 0.5f)
        assertTrue(mid in 22L..41L)
        var last = -1L
        for (i in 0..100) {
            val v = CountUpMath.valueAt(0, 42, i / 100f)
            assertTrue(v >= last && v <= 42)
            last = v
        }
    }

    @Test
    fun retargetGlidesFromWhereItIs() {
        assertEquals(30L, CountUpMath.valueAt(30, 12, 0f))
        assertEquals(12L, CountUpMath.valueAt(30, 12, 1f))
        assertTrue(CountUpMath.valueAt(30, 12, 0.5f) in 12L..30L)
    }

    @Test
    fun listenedTimeCountsWholeMinutesInTheCardsFormat() {
        val target = (83 * 60_000L) + 25_000L // 1h 23m 25s
        assertEquals("0m", CountUpMath.durationText(0, target, 0f))
        assertEquals("1h 23m", CountUpMath.durationText(0, target, 1f))
        // Mid count: whole minutes, never seconds, "Xm" below an hour and "Xh Ym" above
        for (i in 1..99) {
            val text = CountUpMath.durationText(0, target, i / 100f)
            assertFalse(text, text.endsWith("s"))
            assertTrue(text, Regex("""^(\d+m|\d+h|\d+h \d+m)$""").matches(text))
        }
        assertEquals("1h", CountUpMath.durationText(0, 3_600_000L, 1f))
    }

    @Test
    fun underAMinuteCountsSeconds() {
        assertEquals("0s", CountUpMath.durationText(0, 45_000L, 0f))
        assertEquals("45s", CountUpMath.durationText(0, 45_000L, 1f))
        val mid = CountUpMath.durationText(0, 45_000L, 0.5f)
        assertTrue(mid, mid.endsWith("s"))
    }

    // ---- up next ----

    @Test
    fun smallQueueChangesAnimateBigOnesRedraw() {
        val q = listOf("a", "b", "c", "d", "e")
        assertFalse(QueueDiffRules.isWholesale(q, listOf("a", "c", "b", "d", "e"))) // moved
        assertFalse(QueueDiffRules.isWholesale(q, listOf("a", "b", "d", "e"))) // removed
        assertFalse(QueueDiffRules.isWholesale(listOf("a", "b"), listOf("b"))) // removed down to one
        assertTrue(QueueDiffRules.isWholesale(emptyList(), q)) // first fill
        assertTrue(QueueDiffRules.isWholesale(q, emptyList())) // cleared
        assertTrue(QueueDiffRules.isWholesale(q, listOf("v", "w", "x", "y", "z"))) // new radio
        assertTrue(QueueDiffRules.isWholesale(listOf("a"), listOf("a") + (1..20).map { "n$it" })) // album added
    }

    @Test
    fun rowRedrawsWhenHighlightOrArrowsChange() {
        // Same song, same place, same highlight: nothing to redraw
        assertTrue(QueueDiffRules.sameContents("b", 1, 0, 5, "b", 1, 0, 5))
        // The highlight moved onto it
        assertFalse(QueueDiffRules.sameContents("b", 1, 0, 5, "b", 1, 1, 5))
        // Moved to the top: its up arrow turns off
        assertFalse(QueueDiffRules.sameContents("b", 1, 4, 5, "b", 0, 4, 5))
        // Became the last row: its down arrow turns off
        assertFalse(QueueDiffRules.sameContents("d", 3, 0, 5, "d", 3, 0, 4))
        // Moved within the middle with no state change: just glides, no redraw
        assertTrue(QueueDiffRules.sameContents("c", 2, 0, 5, "c", 3, 0, 5))
    }
}
