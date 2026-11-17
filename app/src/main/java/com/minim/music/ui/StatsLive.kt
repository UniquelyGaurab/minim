package com.minim.music.ui

/**
 * 6.9.0 (75): when Stats' live refresh (every few seconds while a song plays) actually does work.
 *
 * Build 74 reloaded every pill, the calendar and the old Today boxes on every tick, even while Now
 * Playing covered Stats, and rebuilt the Stats views each time. On a low-end phone that left the
 * main thread and the garbage collector busy every 5 seconds, which is what made scrolling, the
 * Now Playing open and the marquee stutter.
 */
class StatsLive {

    private var periodAt = Long.MIN_VALUE / 2
    private var heatAt = Long.MIN_VALUE / 2

    /** Today on every tick; Week, Month and All time at most every [PERIOD_MS]; always when not live. */
    fun periodDue(live: Boolean, today: Boolean, now: Long): Boolean {
        if (!live || today || now - periodAt >= PERIOD_MS) {
            periodAt = now
            return true
        }
        return false
    }

    /** The calendar at most every [HEAT_MS] on live ticks; always when not live. */
    fun heatDue(live: Boolean, now: Long): Boolean {
        if (!live || now - heatAt >= HEAT_MS) {
            heatAt = now
            return true
        }
        return false
    }

    companion object {
        const val PERIOD_MS = 30_000L
        const val HEAT_MS = 60_000L

        /** Stats refreshes live only while its tab is showing and nothing covers it. */
        fun visible(statsTab: Boolean, nowPlayingOpen: Boolean, settingsOpen: Boolean, stopped: Boolean): Boolean =
            statsTab && !nowPlayingOpen && !settingsOpen && !stopped
    }
}
