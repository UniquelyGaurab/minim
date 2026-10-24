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
}
