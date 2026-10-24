package com.minim.music.ui

import com.minim.music.data.local.AppPreferences
import com.minim.music.data.model.LyricsData
import com.minim.music.data.model.LyricsLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** v4.9.8: the Now Playing lyric slot, the long-press without Up next and the tips. */
class NowPlayingLyricSlotTest {

    private fun data(lines: List<String>, synced: Boolean, provider: String = "lrclib") = LyricsData(
        trackId = "t",
        lines = lines.mapIndexed { i, s -> LyricsLine(i * 1000L, i * 1000L + 900L, s) },
        isSynced = synced,
        hasWordTimestamps = false,
        providerName = provider
    )

    @Test
    fun loading_isEmptyAndNotTappable() {
        val mode = NowPlayingLyricSlot.mode(null)
        assertEquals(NowPlayingLyricSlot.Mode.LOADING, mode)
        assertFalse(NowPlayingLyricSlot.isTappable(mode))
    }

    @Test
    fun noLyrics_showsUnavailableAndDoesNothingOnTap() {
        val mode = NowPlayingLyricSlot.mode(data(emptyList(), synced = false, provider = "none"))
        assertEquals(NowPlayingLyricSlot.Mode.NONE, mode)
        assertFalse(NowPlayingLyricSlot.isTappable(mode))
    }

    @Test
    fun onlyBlankLines_countAsNoLyrics() {
        assertEquals(NowPlayingLyricSlot.Mode.NONE, NowPlayingLyricSlot.mode(data(listOf("", "  "), synced = true)))
    }

    @Test
    fun syncedAndPlainLyrics_areTappable() {
        for (synced in listOf(true, false)) {
            val mode = NowPlayingLyricSlot.mode(data(listOf("", "Hello"), synced))
            assertEquals(NowPlayingLyricSlot.Mode.LINES, mode)
            assertTrue(NowPlayingLyricSlot.isTappable(mode))
        }
    }

    @Test
    fun unavailableTextIsFaint() {
        assertTrue(NowPlayingLyricSlot.UNAVAILABLE_ALPHA in 0.2f..0.5f)
        assertTrue(NowPlayingLyricSlot.UNAVAILABLE_TEXT_SP < 18)
    }

    @Test
    fun longPress_hasNoUpNextAnyMore_savedQueueChoiceReadsAsOff() {
        assertEquals(AppPreferences.GESTURE_DISABLED, AppPreferences.longPressActionOf("LPRESS_QUEUE"))
        assertEquals(AppPreferences.GESTURE_DISABLED, AppPreferences.longPressActionOf(null))
        assertEquals(AppPreferences.GESTURE_DISABLED, AppPreferences.longPressActionOf("junk"))
        assertEquals(AppPreferences.GESTURE_LPRESS_LYRICS, AppPreferences.longPressActionOf(AppPreferences.GESTURE_LPRESS_LYRICS))
        assertEquals(AppPreferences.GESTURE_DISABLED, AppPreferences.longPressActionOf(AppPreferences.GESTURE_DISABLED))
    }

    @Test
    fun tips_noLongerMentionSwipeUpLyricsOrLongPressQueue() {
        for (tip in Tips.all) {
            assertFalse(tip, tip.contains("Swipe up on Now Playing", ignoreCase = true))
            assertFalse(tip, tip.contains("Long-press the album art for the queue", ignoreCase = true))
        }
        assertTrue(Tips.all.any { it.contains("Up Next") })
        assertTrue(Tips.all.any { it.contains("lyric line") })
    }

    // ---- v4.9.9 part 1 ----

    private fun src(path: String): String {
        val f = listOf(java.io.File(path), java.io.File("app/$path")).first { it.exists() }
        return f.readText()
    }

    @Test
    fun `lines get side margins and a max width, centred`() {
        // 393 dp phone at density 1: the 48 dp minimum wins
        assertEquals(48, NowPlayingLyricSlot.sidePadding(393, 48, 400))
        // tablet: the line is capped at 400 and centred
        assertEquals(200, NowPlayingLyricSlot.sidePadding(800, 48, 400))
        // narrower than the old 30 dp margins everywhere
        assertTrue(NowPlayingLyricSlot.SIDE_MIN_DP > 30f)
        assertTrue(NowPlayingLyricSlot.LINE_MAX_DP <= 560f)
    }

    @Test
    fun `next line slides, a seek swaps, no animation when it can't be seen or animations are off`() {
        assertEquals(NowPlayingLyricSlot.Change.SLIDE, NowPlayingLyricSlot.change(jump = false, animate = true, canAnimate = true))
        assertEquals(NowPlayingLyricSlot.Change.SWAP, NowPlayingLyricSlot.change(jump = true, animate = true, canAnimate = true))
        assertEquals(NowPlayingLyricSlot.Change.INSTANT, NowPlayingLyricSlot.change(jump = false, animate = true, canAnimate = false))
        assertEquals(NowPlayingLyricSlot.Change.INSTANT, NowPlayingLyricSlot.change(jump = true, animate = false, canAnimate = true))
    }

    @Test
    fun `a lagging tick is not a step back while Flow plays`() {
        assertTrue(NowPlayingLyricSlot.ignoreTick(4, 5, flowRunning = true, playing = true))
        // paused, Line style or moving forward: follow the tick
        assertFalse(NowPlayingLyricSlot.ignoreTick(4, 5, flowRunning = true, playing = false))
        assertFalse(NowPlayingLyricSlot.ignoreTick(4, 5, flowRunning = false, playing = true))
        assertFalse(NowPlayingLyricSlot.ignoreTick(6, 5, flowRunning = true, playing = true))
    }

    @Test
    fun `animation timings follow the rules - subtle, 150 to 300 ms`() {
        for (ms in listOf(NowPlayingLyricSlot.SLIDE_IN_MS, NowPlayingLyricSlot.SLIDE_OUT_MS, NowPlayingLyricSlot.SWAP_MS)) {
            assertTrue("$ms", ms in 150L..300L)
        }
        assertEquals(250L, NowPlayingLyricSlot.SLIDE_IN_MS)
        assertTrue(NowPlayingLyricSlot.SLIDE_DP in 6f..16f)
    }

    @Test
    fun `the slot reuses Flow and stops its frames when hidden`() {
        val v = src("src/main/java/com/minim/music/ui/NowPlayingLyricView.kt")
        for (needle in listOf(
            ": LyricLineView(", "FlowClock()", "FlowTiming.lineDurations(", "onLinePosition(pos)",
            "Choreographer.getInstance().postFrameCallback", "removeFrameCallback",
            "onVisibilityAggregated", "fun setHostVisible", "fun setCovered",
            "BREAK_STRATEGY_BALANCED", "ValueAnimator.areAnimatorsEnabled()", "DecelerateInterpolator",
            "tag = \"text:fg\""
        )) assertTrue(needle, v.contains(needle))
        val main = src("src/main/java/com/minim/music/ui/MainActivity.kt")
        val tick = main.substringAfter("private fun updateLyricLine(").substringBefore("\n    }")
        assertTrue(tick.contains("npLyric.onPosition(index, shifted, playing, animate)"))
        assertTrue(main.contains("npLyric.setHostVisible(false)"))
        assertTrue(main.contains("npLyric.setCovered("))
        assertTrue(main.contains("npLyric.flowStyle = app.preferences.isFlowLyrics"))
        assertTrue(main.contains("npLyric.setLyrics(data, getString(R.string.np_lyrics_unavailable)"))
        assertFalse(main.contains("fun setNpLyric("))
    }
}
