package com.minim.music.ui

import com.minim.music.data.share.LyricsCardOptions.Lyrics
import com.minim.music.ui.LyricsCardSpacing.Gap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 6.7.0: tight rows inside one wrapped lyric line, a clear gap between lines, Both paired up. */
class LyricsCardSpacingTest {

    private val orig = listOf("Peeti vi nahin", "Phir vi ik suroor jeha ae")
    private val tr = listOf("I haven't had a drink", null)

    @Test fun textIsSmallerThan660() {
        // 6.6.0 used 76 down to 38; 6.7.0 is 10 to 15 % smaller
        val big = LyricsCardSpacing.MAX_SIZE / 76f
        val small = LyricsCardSpacing.MIN_SIZE / 38f
        assertTrue(big in 0.85f..0.90f)
        assertTrue(small in 0.85f..0.90f)
    }

    @Test fun originalAndTranslationGetLineGaps() {
        for (mode in listOf(Lyrics.ORIGINAL, Lyrics.TRANSLATION)) {
            val p = LyricsCardSpacing.pieces(orig, tr, mode)
            assertEquals(2, p.size)
            assertEquals(Gap.LINE, p[0].gapAfter)
            assertEquals(Gap.NONE, p[1].gapAfter)
        }
        // Translation mode shows the original where a line has no translation
        val t = LyricsCardSpacing.pieces(orig, tr, Lyrics.TRANSLATION)
        assertEquals("I haven't had a drink", t[0].text)
        assertEquals(orig[1], t[1].text)
    }

    @Test fun bothPairsEachTranslationUnderItsOriginal() {
        val p = LyricsCardSpacing.pieces(orig + "Third", listOf("T1", null, "T3"), Lyrics.BOTH)
        assertEquals(listOf("Peeti vi nahin", "T1", orig[1], "Third", "T3"), p.map { it.text })
        assertEquals(listOf(false, true, false, false, true), p.map { it.sub })
        assertEquals(listOf(0, 0, 1, 2, 2), p.map { it.line })
        // small gap inside a pair, the line gap between pairs, nothing after the last
        assertEquals(listOf(Gap.PAIR, Gap.LINE, Gap.LINE, Gap.PAIR, Gap.NONE), p.map { it.gapAfter })
    }

    @Test fun wrappedRowsStayTightLinesGetHalfARow() {
        val row = 80f
        val p = LyricsCardSpacing.pieces(listOf("a long line that wraps", "short"), listOf(null, null), Lyrics.ORIGINAL)
        // the first lyric line wraps to 2 rows, the second is 1 row
        val (tops, total) = LyricsCardSpacing.stack(p, floatArrayOf(2 * row, row), row)
        assertEquals(0f, tops[0], 0f)
        // the two rows of line 1 have no gap between them (they're one block); line 2 starts after half a row
        assertEquals(2 * row + 0.5f * row, tops[1], 0.01f)
        assertEquals(3 * row + 0.5f * row, total, 0.01f)
        // the gap between lines is clearly bigger than between rows in a line (0)
        assertTrue(LyricsCardSpacing.gapPx(Gap.LINE, row) >= 0.45f * row)
        assertTrue(LyricsCardSpacing.gapPx(Gap.PAIR, row) < LyricsCardSpacing.gapPx(Gap.LINE, row) / 3)
        assertTrue(LyricsCardSpacing.ROW_MULT <= 1f)
    }

    @Test fun bothStacksPairsCloseTogether() {
        val row = 60f
        val p = LyricsCardSpacing.pieces(listOf("A", "B"), listOf("a", "b"), Lyrics.BOTH)
        val h = floatArrayOf(row, row * 0.6f, row, row * 0.6f)
        val (tops, total) = LyricsCardSpacing.stack(p, h, row)
        assertEquals(row + row * LyricsCardSpacing.PAIR_GAP, tops[1], 0.01f)
        val secondPair = tops[1] + h[1] + row * LyricsCardSpacing.LINE_GAP
        assertEquals(secondPair, tops[2], 0.01f)
        assertEquals(tops[3] + h[3], total, 0.01f)
    }

    @Test fun emptyIsNothing() {
        val (tops, total) = LyricsCardSpacing.stack(emptyList(), FloatArray(0), 50f)
        assertEquals(0, tops.size)
        assertEquals(0f, total, 0f)
    }

    @Test fun blurKeepsAFlatImageFlat() {
        val px = IntArray(16 * 16) { 0xFF336699.toInt() }
        LyricsCardSpacing.boxBlur(px, 16, 16, 3)
        assertTrue(px.all { it == 0xFF336699.toInt() })
        // and spreads a single bright pixel out
        val dot = IntArray(9 * 9) { 0xFF000000.toInt() }
        dot[4 * 9 + 4] = 0xFFFFFFFF.toInt()
        LyricsCardSpacing.boxBlur(dot, 9, 9, 1, passes = 1)
        assertTrue((dot[4 * 9 + 5] and 0xFF) > 0)
    }
}
