package com.minim.music.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 6.9.0 (75): Stats' live refresh only works while Stats is on screen, and not every pill every tick. */
class StatsLiveTest {

    @Test fun noLiveRefreshWhileCoveredOrAway() {
        assertTrue(StatsLive.visible(statsTab = true, nowPlayingOpen = false, settingsOpen = false, stopped = false))
        assertFalse(StatsLive.visible(statsTab = true, nowPlayingOpen = true, settingsOpen = false, stopped = false))
        assertFalse(StatsLive.visible(statsTab = true, nowPlayingOpen = false, settingsOpen = true, stopped = false))
        assertFalse(StatsLive.visible(statsTab = false, nowPlayingOpen = false, settingsOpen = false, stopped = false))
        assertFalse(StatsLive.visible(statsTab = true, nowPlayingOpen = false, settingsOpen = false, stopped = true))
    }

    @Test fun todayEveryTickOtherPillsEvery30sCalendarEveryMinute() {
        val l = StatsLive()
        assertTrue(l.periodDue(live = true, today = false, now = 0L))
        assertFalse(l.periodDue(live = true, today = false, now = 5_000L))
        assertFalse(l.periodDue(live = true, today = false, now = 29_999L))
        assertTrue(l.periodDue(live = true, today = false, now = 30_000L))
        assertTrue(l.periodDue(live = true, today = true, now = 31_000L))
        assertTrue(l.periodDue(live = false, today = false, now = 32_000L))
        assertTrue(l.heatDue(live = true, now = 0L))
        assertFalse(l.heatDue(live = true, now = 59_000L))
        assertTrue(l.heatDue(live = true, now = 60_000L))
        assertTrue(l.heatDue(live = false, now = 61_000L))
    }
}
