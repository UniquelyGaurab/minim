package com.minim.music.data.share

import com.minim.music.data.share.LyricSelection.Result
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 6.6.0 lyrics card: picking lines stays next to each other, 5 lines and about 180 characters at most. */
class LyricSelectionTest {

    private val ten = (0 until 10).map { "Line number $it" }

    @Test fun startsWithOneLine() {
        val s = LyricSelection(ten)
        assertFalse(s.isActive)
        assertTrue(s.start(4))
        assertEquals(4..4, s.range)
        assertEquals(1, s.count)
        assertEquals(listOf("Line number 4"), s.selected)
    }

    @Test fun addsAboveAndBelowOnly() {
        val s = LyricSelection(ten)
        s.start(4)
        assertEquals(Result.ADDED, s.tap(5))
        assertEquals(Result.ADDED, s.tap(3))
        assertEquals(3..5, s.range)
        // a line further away starts again from there, never leaving a gap
        assertEquals(Result.STARTED, s.tap(8))
        assertEquals(8..8, s.range)
    }

    @Test fun tappingAnEndRemovesIt() {
        val s = LyricSelection(ten)
        s.start(2); s.tap(3); s.tap(4)
        assertEquals(Result.REMOVED, s.tap(4))
        assertEquals(2..3, s.range)
        assertEquals(Result.REMOVED, s.tap(2))
        assertEquals(3..3, s.range)
        // the last line stays: there's always one picked until Back or Cancel
        assertEquals(Result.NOTHING, s.tap(3))
        assertEquals(3..3, s.range)
    }

    @Test fun middleTapDoesNothing() {
        val s = LyricSelection(ten)
        s.start(2); s.tap(3); s.tap(4)
        assertEquals(Result.NOTHING, s.tap(3))
        assertEquals(2..4, s.range)
    }

    @Test fun fiveLinesAtMost() {
        val s = LyricSelection(ten)
        s.start(0)
        for (i in 1..4) assertEquals(Result.ADDED, s.tap(i))
        assertEquals(5, s.count)
        assertEquals(Result.LIMIT_LINES, s.tap(5))
        assertEquals(0..4, s.range)
        assertEquals(LyricSelection.MAX_LINES, s.count)
    }

    @Test fun characterCap() {
        val long = List(6) { "x".repeat(70) }
        val s = LyricSelection(long)
        s.start(0)
        assertEquals(Result.ADDED, s.tap(1)) // 140
        assertEquals(Result.LIMIT_CHARS, s.tap(2)) // 210 > 180
        assertEquals(0..1, s.range)
        assertTrue(s.chars <= LyricSelection.MAX_CHARS)
    }

    @Test fun oneVeryLongLineOnItsOwnIsFine() {
        val s = LyricSelection(listOf("y".repeat(400), "short"))
        assertTrue(s.start(0))
        assertEquals(Result.LIMIT_CHARS, s.tap(1))
        assertEquals(0..0, s.range)
    }

    @Test fun countsCharactersNotCodeUnits() {
        // emoji are two UTF-16 units but one character
        val s = LyricSelection(listOf("🔥".repeat(90), "🔥".repeat(90)))
        s.start(0)
        assertEquals(Result.ADDED, s.tap(1))
        assertEquals(180, s.chars)
    }

    @Test fun blankLinesCantBePickedOrCrossed() {
        val s = LyricSelection(listOf("one", "  ", "three"))
        assertFalse(s.start(1))
        assertEquals(2, s.nearestSelectable(1))
        s.start(0)
        assertEquals(Result.NOTHING, s.tap(1))
        assertEquals(0..0, s.range)
    }

    @Test fun nearestSelectableAndEmpty() {
        assertEquals(-1, LyricSelection(emptyList()).nearestSelectable(0))
        assertEquals(-1, LyricSelection(listOf("", " ")).nearestSelectable(0))
        assertEquals(1, LyricSelection(listOf("a", "b", "")).nearestSelectable(9))
        assertEquals(0, LyricSelection(listOf("a")).nearestSelectable(-3))
    }

    @Test fun clearStops() {
        val s = LyricSelection(ten)
        s.start(1); s.tap(2)
        s.clear()
        assertFalse(s.isActive)
        assertEquals(0, s.count)
        assertEquals(emptyList<String>(), s.selected)
        assertEquals(Result.STARTED, s.tap(6))
    }

    @Test fun selectedLinesAreTrimmed() {
        val s = LyricSelection(listOf("  hello  ", "world "))
        s.start(0); s.tap(1)
        assertEquals(listOf("hello", "world"), s.selected)
    }
}
