package com.minim.music.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 6.7.0 revision: the scrolling window of a 30s / 45s / 60s lyrics card. */
class LyricsCardScrollTest {

    // eight lines, 100 px each with a 50 px gap: tops 0, 150, 300 ...
    private val tops = FloatArray(8) { it * 150f }
    private val bottoms = FloatArray(8) { it * 150f + 100f }
    private val totalH = bottoms.last()
    private val starts = List(8) { 1_000L + it * 3_000L }
    private val windowH = 500f
    private val picked = 2
    private val pickedH = bottoms[picked - 1]

    private fun offset(ms: Long) = LyricsCardScroll.offset(ms, starts, tops, totalH, windowH, picked, pickedH)

    @Test fun activeLineIsTheLastStarted() {
        assertEquals(0, LyricsCardScroll.activeLine(starts, 0))
        assertEquals(0, LyricsCardScroll.activeLine(starts, 1_000))
        assertEquals(1, LyricsCardScroll.activeLine(starts, 4_500))
        assertEquals(7, LyricsCardScroll.activeLine(starts, 99_000))
    }

    @Test fun stillWhileThePickedLinesAreSung() {
        assertEquals(0f, offset(0), 0f)
        assertEquals(0f, offset(4_200), 0f)
        assertEquals(0f, offset(6_999), 0f)
    }

    @Test fun laterLinesSettleNearTheTopThird() {
        // line 4 sung, well after its move
        val k = 4
        val settled = offset(starts[k] + 2_000)
        assertEquals(tops[k] - LyricsCardScroll.ANCHOR * windowH, settled, 0.01f)
        // the sung line is inside the window, with room for the next under it
        assertTrue(tops[k] - settled >= 0 && bottoms[k + 1] - settled <= windowH)
    }

    @Test fun movesSmoothly() {
        val k = 3
        val from = offset(starts[k] - 1)
        val to = offset(starts[k] + LyricsCardScroll.SCROLL_MS)
        val mid = offset(starts[k] + LyricsCardScroll.SCROLL_MS / 2)
        assertTrue(from < mid && mid < to)
        assertEquals((from + to) / 2, mid, 1f)
        // no jump in the first frame (30 fps)
        assertTrue(offset(starts[k] + 33) - from < (to - from) * 0.05f)
        // never goes backwards over the song
        var last = -1f
        for (ms in 0L..30_000L step 33) {
            val o = offset(ms)
            assertTrue(o >= last - 0.001f)
            last = o
        }
    }

    @Test fun stopsAtTheEndOfTheText() {
        assertEquals(totalH - windowH, offset(99_000), 0.01f)
    }

    @Test fun pickedLinesTooTallForTheWindowScrollToo() {
        val o = LyricsCardScroll.offset(starts[2] + 2_000, starts, tops, totalH, 250f, 5, bottoms[4])
        assertTrue(o > 0f)
    }

    @Test fun visibleLines() {
        assertEquals(0..3, LyricsCardScroll.visible(tops, bottoms, 0f, windowH))
        assertEquals(2..5, LyricsCardScroll.visible(tops, bottoms, 300f, windowH))
        assertTrue(LyricsCardScroll.visible(tops, bottoms, 5_000f, windowH).isEmpty())
    }

    @Test fun windowFitsFourToFiveLines() {
        val slot = 150f
        val h = LyricsCardScroll.windowHeight(pickedH = 100f, lineSlot = slot, maxH = 2_000f)
        // 4.5 lines of 150 px, less the last gap
        assertEquals(4.5f * slot - 50f, h, 0.5f)
        assertTrue(h / slot in 4f..5f)
        // big picked lines get the room they need, up to the most there is
        assertEquals(900f, LyricsCardScroll.windowHeight(900f, slot, 2_000f), 0f)
        assertEquals(800f, LyricsCardScroll.windowHeight(900f, slot, 800f), 0f)
    }
}
