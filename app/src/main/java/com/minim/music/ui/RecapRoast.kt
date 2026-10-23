package com.minim.music.ui

import com.minim.music.data.stats.DayStats
import com.minim.music.data.stats.StatsFormat

/**
 * v4.9.1: the recap story's roast slide, written from the day's numbers in the greetings' voice
 * (two short lines, cleaned-up titles). Only lines whose data exists are picked, and the pick
 * depends on the day, so the same recap always roasts you the same way.
 */
object RecapRoast {

    fun candidates(s: DayStats): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        val song = s.topSong
        val title = song?.let { Greetings.shortTitle(it.title, it.artist, 20) }
        val streak = s.streak
        val artist = s.topArtist?.name?.let { Greetings.shortArtist(it, 16) }
        val time = StatsFormat.duration(s.timeMs)

        if (song != null && title != null && song.plays >= 5) {
            out.add("$title, ${song.plays} times." to "Are you okay?")
            out.add("$title, ${song.plays} times." to "The song wants a break.")
        } else if (song != null && title != null && song.plays >= 3) {
            out.add("$title, ${song.plays} times." to "Not that we're counting.")
        }
        if (streak != null && streak.count >= 5) {
            val who = Greetings.shortArtist(streak.artist, 16)
            out.add("${streak.count} $who songs in a row." to "Loyal. Maybe too loyal.")
        }
        if (s.peakHour in 0..4) {
            out.add("Peak hour: ${StatsFormat.hour(s.peakHour)}." to "Sleep is a myth, right?")
        } else if (s.peakHour in 5..7) {
            out.add("Peak hour: ${StatsFormat.hour(s.peakHour)}." to "Early bird energy.")
        }
        if (s.timeMs >= 4L * 3_600_000L) out.add("$time of music." to "That's a whole shift.")
        if (s.artists >= 10) out.add("${s.artists} artists in a day." to "Very well-rounded.")
        if (s.artists == 1 && s.songs >= 3 && artist != null) out.add("Just $artist. All day." to "Commitment.")
        if (s.songs >= 30) out.add("${s.songs} songs." to "That's a marathon.")
        if (out.isEmpty()) {
            when {
                s.timeMs < 30L * 60_000L -> out.add("$time of music." to "Short and sweet.")
                artist != null -> out.add("$artist again?" to "Of course.")
                else -> out.add("${s.songs} songs, $time." to "A solid day.")
            }
        }
        return out
    }

    /** One line for the day; null only when nothing was played. */
    fun line(s: DayStats): Pair<String, String>? {
        if (s.songs == 0 && s.timeMs <= 0L) return null
        val all = candidates(s)
        return all[Math.floorMod(s.day, all.size)]
    }
}
