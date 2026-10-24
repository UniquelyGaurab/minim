package com.minim.music.ui

import com.minim.music.data.local.AppPreferences
import com.minim.music.data.model.LyricsLine
import com.minim.music.data.model.LyricsWord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** v4.9.8 part 4: the Flow word by word timing, its clock and the Lyrics style setting. */
class FlowTimingTest {

    private fun words(text: String): List<String> = FlowTiming.splitWords(text).map { text.substring(it.start, it.end) }

    private fun lines(vararg starts: Long, text: String = "one two three four"): List<LyricsLine> =
        starts.map { LyricsLine(it, it + 6000L, text) }

    // --- Word split -----------------------------------------------------------------------------

    @Test fun `spaced text splits on whitespace`() {
        assertEquals(listOf("Tum", "hi", "ho,", "(oh)"), words("Tum  hi\tho, (oh)"))
    }

    @Test fun `hindi tamil and bengali split on spaces and keep whole words`() {
        assertEquals(listOf("तुम्हें", "देखा", "तो"), words("तुम्हें देखा तो"))
        assertEquals(listOf("நீ", "என்", "உயிரே"), words("நீ என் உயிரே"))
        assertEquals(listOf("ভালোবাসা", "তুমি"), words("ভালোবাসা তুমি"))
    }

    @Test fun `no space scripts split by character with latin kept together`() {
        assertEquals(listOf("我", "爱", "你"), words("我爱你"))
        assertEquals(listOf("君", "の", "名", "前"), words("君の名前"))
        assertEquals(listOf("Love", "你", "们"), words("Love你们"))
    }

    @Test fun `punctuation and the kana long mark stick to the character before`() {
        assertEquals(listOf("你", "好，", "世", "界！"), words("你好，世界！"))
        assertEquals(listOf("ラ", "ー").size - 1, words("ラー").size)
    }

    @Test fun `ranges cover the original text and blank lines have no words`() {
        val text = "  hello   world "
        val w = FlowTiming.splitWords(text)
        assertEquals("hello", text.substring(w[0].start, w[0].end))
        assertEquals("world", text.substring(w[1].start, w[1].end))
        assertTrue(FlowTiming.splitWords("   ").isEmpty())
        assertTrue(FlowTiming.splitWords("").isEmpty())
    }

    // --- Length weighting -----------------------------------------------------------------------

    @Test fun `weight counts letters not punctuation and is at least one`() {
        assertEquals(3, FlowTiming.letterCount("Hey,"))
        assertEquals(5, FlowTiming.letterCount("(hello)"))
        assertEquals(1, FlowTiming.letterCount("..."))
        // Combining vowel signs belong to their letter: fewer units than chars
        assertTrue(FlowTiming.letterCount("देखा") < "देखा".length)
    }

    @Test fun `longer words get more time`() {
        val b = FlowTiming.wordBounds(intArrayOf(1, 4, 8), 3000L)
        val d = (0 until 3).map { b[it + 1] - b[it] }
        assertTrue(d[0] < d[1] && d[1] < d[2])
        assertEquals(0L, b[0])
        assertEquals(3000L, b[3])
    }

    @Test fun `every word gets the minimum and the rest goes by weight`() {
        val b = FlowTiming.wordBounds(intArrayOf(1, 30), 1000L)
        assertTrue(b[1] - b[0] >= FlowTiming.MIN_WORD_MS)
        assertEquals(1000L, b[2])
    }

    @Test fun `a line too short for the minimum splits evenly`() {
        val b = FlowTiming.wordBounds(intArrayOf(1, 9, 1, 9), 200L)
        assertEquals(listOf(0L, 50L, 100L, 150L, 200L), b.toList())
    }

    @Test fun `bounds never go backwards`() {
        val b = FlowTiming.wordBounds(IntArray(25) { (it % 7) + 1 }, 4321L)
        for (i in 1 until b.size) assertTrue(b[i] >= b[i - 1])
        assertEquals(4321L, b.last())
    }

    // --- Line durations -------------------------------------------------------------------------

    @Test fun `typical line is the median of normal gaps`() {
        val l = lines(0, 3000, 6000, 9500, 12_000, 60_000)
        assertEquals(3000L, FlowTiming.typicalLineMs(l))
        assertEquals(FlowTiming.TYPICAL_DEFAULT_MS, FlowTiming.typicalLineMs(lines(0)))
    }

    @Test fun `lines run to the next start but long gaps and the last line are capped`() {
        val l = lines(0, 3000, 6000, 9000, 40_000)
        val d = FlowTiming.lineDurations(l)
        assertEquals(3000L, d[0])
        val cap = FlowTiming.lineCapMs(15, 3000L)
        assertEquals(cap, d[3])
        assertTrue(d[3] < 31_000L)
        assertEquals(cap, d[4])
    }

    @Test fun `long text earns a longer cap than the typical line`() {
        assertTrue(FlowTiming.lineCapMs(80, 3000L) > (3000L * FlowTiming.TYPICAL_CAP_FACTOR).toLong())
        assertEquals((3000L * FlowTiming.TYPICAL_CAP_FACTOR).toLong(), FlowTiming.lineCapMs(2, 3000L))
    }

    @Test fun `real enhanced lrc word times are used when they match`() {
        val line = LyricsLine(1000L, 4000L, "a bb", listOf(LyricsWord("a", 1000L, 1500L), LyricsWord("bb", 2200L, 3000L)))
        val b = FlowTiming.realWordBounds(line, FlowTiming.splitWords(line.text), 3000L)!!
        assertEquals(listOf(0L, 1200L, 2000L), b.toList())
        val plain = LyricsLine(1000L, 4000L, "a bb")
        assertEquals(null, FlowTiming.realWordBounds(plain, FlowTiming.splitWords(plain.text), 3000L))
    }

    // --- Fill -----------------------------------------------------------------------------------

    @Test fun `words fill in order and smoothly`() {
        val b = FlowTiming.wordBounds(intArrayOf(3, 3, 3), 1800L)
        assertEquals(0f, FlowTiming.wordFill(b, 1, 0L), 0f)
        assertEquals(1f, FlowTiming.wordFill(b, 0, 1800L), 0f)
        assertTrue(FlowTiming.wordFill(b, 0, 300L) > FlowTiming.wordFill(b, 1, 300L))
        // No jumps: 1 ms never moves a word by more than a few percent
        var last = 0f
        for (t in -200L..2000L) {
            val f = FlowTiming.wordFill(b, 1, t)
            assertTrue(f >= last && f - last < 0.02f)
            last = f
        }
        assertEquals(1f, last, 0f)
    }

    @Test fun `unsung words never dip while the row fades up`() {
        assertEquals(1f, FlowTiming.dimFor(0.3f), 0f)
        assertEquals(FlowTiming.DIM, FlowTiming.dimFor(1f), 1e-6f)
        for (a in listOf(0.5f, 0.7f, 0.9f, 1f)) assertTrue(a * FlowTiming.dimFor(a) >= FlowTiming.DIM - 1e-6f)
    }

    // --- Offset ---------------------------------------------------------------------------------

    @Test fun `lyrics timing offset shifts words like lines`() {
        assertEquals(10_350L, FlowTiming.shiftedPosition(10_000L, 50L, 300))
        assertEquals(9_500L, FlowTiming.shiftedPosition(10_000L, 0L, -500))
        val l = lines(0, 3000, 6000)
        val b = FlowTiming.wordBounds(intArrayOf(5, 5), 3000L)
        val player = 2700L
        // + offset: line 1 is already active and its first word is filling
        val early = FlowTiming.shiftedPosition(player, 0L, 500)
        assertEquals(1, FlowTiming.activeIndex(l, early))
        assertTrue(FlowTiming.wordFill(b, 0, early - l[1].startMs) > 0f)
        // - offset: still on line 0
        assertEquals(0, FlowTiming.activeIndex(l, FlowTiming.shiftedPosition(player, 0L, -500)))
        assertEquals(-1, FlowTiming.activeIndex(lines(1000), 500L))
    }

    // --- Clock ----------------------------------------------------------------------------------

    private val ms = 1_000_000L

    @Test fun `clock runs on between ticks while playing and stops when paused`() {
        val c = FlowClock()
        assertTrue(c.sample(10_000L, true, 0L))
        assertEquals(10_100L, c.positionAt(100 * ms))
        assertEquals(10_200L, c.positionAt(200 * ms))
        assertFalse(c.sample(10_250L, false, 250 * ms))
        assertEquals(10_250L, c.positionAt(900 * ms))
    }

    @Test fun `clock eases small corrections without stepping back`() {
        val c = FlowClock()
        c.sample(0L, true, 0L)
        assertEquals(250L, c.positionAt(250 * ms))
        assertFalse(c.sample(200L, true, 250 * ms))
        assertTrue(c.positionAt(260 * ms) >= 250L)
        assertTrue(c.positionAt(400 * ms) < 400L)
    }

    @Test fun `clock takes a seek at once, either way`() {
        val c = FlowClock()
        c.sample(60_000L, true, 0L)
        c.positionAt(100 * ms)
        assertTrue(c.sample(5_000L, true, 250 * ms))
        assertEquals(5_000L, c.positionAt(250 * ms))
        assertTrue(c.sample(90_000L, true, 500 * ms))
        assertEquals(90_000L, c.positionAt(500 * ms))
    }

    @Test fun `clock never runs far ahead without news`() {
        val c = FlowClock()
        c.sample(0L, true, 0L)
        assertEquals(FlowClock.MAX_EXTRAPOLATE_MS, c.positionAt(10_000 * ms))
    }

    // --- Setting --------------------------------------------------------------------------------

    @Test fun `lyrics style defaults to flow`() {
        assertEquals(AppPreferences.LYRICS_STYLE_FLOW, AppPreferences.lyricsStyleOf(null))
        assertEquals(AppPreferences.LYRICS_STYLE_FLOW, AppPreferences.lyricsStyleOf("garbage"))
        assertEquals(AppPreferences.LYRICS_STYLE_LINE, AppPreferences.lyricsStyleOf("LINE"))
        assertEquals(AppPreferences.LYRICS_STYLE_FLOW, AppPreferences.lyricsStyleOf("FLOW"))
    }
}
