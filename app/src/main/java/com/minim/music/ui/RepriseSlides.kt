package com.minim.music.ui

import com.minim.music.data.stats.DayStats
import com.minim.music.data.stats.RecapMoreMath
import com.minim.music.data.stats.RepriseScope
import com.minim.music.data.stats.StatsFormat
import com.minim.music.ui.RecapSlides.Kind

/**
 * 6.9.0: which slides a Weekly or Monthly Reprise gets, in order. Pure logic (unit tested). Same
 * rule as the daily story: a slide whose data is missing is left out, and the card is always last.
 */
object RepriseSlides {

    fun plan(sc: RepriseScope, s: DayStats, d: RecapDay, hasRoast: Boolean, hasPersona: Boolean): List<Kind> =
        if (sc.isWeek) planWeek(sc, s, d, hasRoast, hasPersona) else planMonth(sc, s, d, hasRoast, hasPersona)

    private fun hasTime(s: DayStats) = s.totalMs > 0L || s.songs > 0 || s.tapes.tapes > 0
    private fun hasTapes(s: DayStats): Boolean {
        val ch = s.tapes.topChannel
        return ch != null && ch.name.isNotBlank() && ch.ms > 0L
    }

    fun planWeek(sc: RepriseScope, s: DayStats, d: RecapDay, hasRoast: Boolean, hasPersona: Boolean): List<Kind> = buildList {
        add(Kind.INTRO)
        if (hasTime(s)) add(Kind.TIME)
        if (sc.busiestQuietest.first != null) add(Kind.WEEK_DAYS)
        if (sc.hasSoundtrack) add(Kind.SOUNDTRACK)
        if (RecapGuess.options(d, s.day) != null) add(Kind.GUESS)
        if (s.topSong != null) add(Kind.TOP5)
        if (s.topArtist != null && s.topArtist.name.isNotBlank()) add(Kind.ARTIST)
        if (sc.newArtists.isNotEmpty()) add(Kind.NEW_ARTISTS)
        if (sc.onRepeat != null) add(Kind.ON_REPEAT)
        if (sc.skipped != null) add(Kind.SKIPPED)
        if (hasTapes(s)) add(Kind.TAPES)
        if (sc.longestStreak >= RecapMoreMath.STREAK_MIN) add(Kind.STREAK)
        if (hasRoast) add(Kind.ROAST)
        if (hasPersona) add(Kind.PERSONA)
        add(Kind.CARD)
    }

    fun planMonth(sc: RepriseScope, s: DayStats, d: RecapDay, hasRoast: Boolean, hasPersona: Boolean): List<Kind> = buildList {
        add(Kind.INTRO)
        if (hasTime(s)) add(Kind.TIME)
        if (sc.heatMax > 0L) add(Kind.HEATMAP)
        if (sc.hasAnthems) add(Kind.ANTHEMS)
        if (RecapGuess.options(d, s.day) != null) add(Kind.GUESS)
        if (s.topSong != null) add(Kind.TOP5)
        if (sc.bestDay != null && sc.bestDayTop != null) add(Kind.BEST_DAY)
        if (sc.obsession != null) add(Kind.OBSESSION)
        if (sc.rising.isNotEmpty() || sc.fading.isNotEmpty()) add(Kind.RISING)
        if (sc.newArtists.isNotEmpty()) add(Kind.NEW_ARTISTS)
        if (sc.fermataHits.isNotEmpty()) add(Kind.FERMATA_HITS)
        if (hasTapes(s)) add(Kind.TAPES)
        if (sc.longestStreak >= RecapMoreMath.STREAK_MIN || sc.favouriteHour in 0..23) add(Kind.STREAK)
        if (hasRoast) add(Kind.ROAST)
        if (hasPersona) add(Kind.PERSONA)
        add(Kind.CARD)
    }

    /** The busiest/quietest line on the week-in-days slide. */
    fun weekDaysLine(sc: RepriseScope): String? {
        val (busy, quiet) = sc.busiestQuietest
        busy ?: return null
        val b = dayName(busy)
        return if (quiet != null && quiet != busy) "$b went hardest. ${dayName(quiet)} took it easy." else "$b went hardest."
    }

    fun dayName(key: Int): String = com.minim.music.data.stats.Days.date(key).dayOfWeek
        .getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.ENGLISH)

    /** "12 new artists · 18% of your listening". */
    fun newFindsLine(sc: RepriseScope): String {
        val n = sc.newArtists.size
        val count = if (n == 1) "1 new artist" else "$n new artists"
        val share = Math.round(sc.newShare * 100f)
        return if (share > 0) "$count  ·  $share% of your listening" else count
    }

    /** The monthly streak slide's second line: "Favourite hour: 10 PM". */
    fun favouriteHourLine(sc: RepriseScope): String? =
        if (sc.favouriteHour in 0..23) "Favourite hour: ${StatsFormat.hour(sc.favouriteHour)}" else null
}

/**
 * 6.9.0: the Weekly and Monthly roast, from the period's numbers in the greetings' voice. The pick
 * depends on the period, so the same Reprise always roasts you the same way.
 */
object RepriseRoast {

    fun candidates(sc: RepriseScope, s: DayStats): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        val w = sc.word
        val song = s.topSong
        val title = song?.let { Greetings.shortTitle(it.title, it.artist, 20) }
        val artist = s.topArtist?.name?.let { Greetings.shortArtist(it, 16) }
        val time = StatsFormat.duration(sc.totalMs)
        if (song != null && title != null && song.plays >= (if (sc.isWeek) 12 else 30)) {
            out.add("$title, ${song.plays} times this $w." to "It's asking for a restraining order.")
        } else if (song != null && title != null && song.plays >= 6) {
            out.add("$title, ${song.plays} times." to "Not that we're counting. We are.")
        }
        sc.onRepeat?.let { (c, _) ->
            if (c.count >= 5) out.add("${Greetings.shortTitle(c.title, c.artist, 20)}, ${c.count} times in one day." to "Are you okay?")
        }
        sc.skipped?.let { c ->
            out.add("You skipped ${Greetings.shortTitle(c.title, c.artist, 20)} ${c.count} times." to "And kept it anyway. Complicated.")
        }
        if (sc.favouriteHour in 0..4) out.add("Favourite hour: ${StatsFormat.hour(sc.favouriteHour)}." to "Sleep is a myth, right?")
        if (sc.totalMs >= (if (sc.isWeek) 20L else 80L) * 3_600_000L) out.add("$time of music." to "That's a part-time job.")
        if (sc.newArtists.size >= (if (sc.isWeek) 10 else 25)) out.add("${sc.newArtists.size} new artists." to "Commitment issues, but make it music.")
        if (artist != null && s.topArtist != null && s.timeMs > 0L && s.topArtist.ms * 2 >= s.timeMs) {
            out.add("Half your $w was $artist." to "Loyal. Maybe too loyal.")
        }
        if (sc.weekendMs * 2 >= sc.totalMs && sc.totalMs >= 3_600_000L) out.add("Weekends: loud. Weekdays: silent." to "Classic.")
        if (out.isEmpty()) {
            when {
                sc.totalMs < 2L * 3_600_000L -> out.add("$time all $w." to "Short and sweet.")
                artist != null -> out.add("$artist again?" to "Of course.")
                else -> out.add("${s.songs} songs, $time." to "A solid $w.")
            }
        }
        return out
    }

    fun line(sc: RepriseScope, s: DayStats): Pair<String, String>? {
        if (s.songs == 0 && s.timeMs <= 0L) return null
        val all = candidates(sc, s)
        return all[Math.floorMod(s.day, all.size)]
    }
}
