package com.minim.music.ui

import com.minim.music.data.stats.DayStats
import com.minim.music.data.stats.StatsEngine

/**
 * v4.9.1: which story slides a recap gets, in order. Pure logic (unit tested): a slide whose data
 * is missing is simply left out, and the summary card is always last.
 */
object RecapSlides {

    enum class Kind { TIME, SONG, ARTIST, PEAK, STREAK, ROAST, CARD }

    fun plan(s: DayStats, hasRoast: Boolean): List<Kind> = buildList {
        if (s.timeMs > 0L || s.songs > 0) add(Kind.TIME)
        if (s.topSong != null) add(Kind.SONG)
        if (s.topArtist != null && s.topArtist.name.isNotBlank()) add(Kind.ARTIST)
        if (s.peakHour in 0..23) add(Kind.PEAK)
        val streak = s.streak
        if (streak != null && streak.count >= StatsEngine.MIN_STREAK && streak.artist.isNotBlank()) add(Kind.STREAK)
        if (hasRoast) add(Kind.ROAST)
        add(Kind.CARD)
    }

    /** The peak-hour slide's small line. */
    fun peakLine(h: Int): String = when (h) {
        in 0..4 -> "Night owl hours."
        in 5..11 -> "Morning person, then."
        in 12..16 -> "An afternoon soundtrack."
        in 17..20 -> "Evening mood."
        else -> "Late night vibes."
    }
}
