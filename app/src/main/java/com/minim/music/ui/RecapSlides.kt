package com.minim.music.ui

import com.minim.music.data.stats.DayStats
import com.minim.music.data.stats.RecapMore
import com.minim.music.data.stats.RecapMoreMath
import com.minim.music.data.stats.StatsEngine

/**
 * Which story slides a recap gets, in order. Pure logic (unit tested): a slide whose data is
 * missing is simply left out, and the summary card is always last.
 *
 * 5.8.2 (update 54): the reworked story. Intro, time, the day in hours (replaces the old peak
 * slide), first and last song, guess your top song, the top-5 countdown, top artist (the streak
 * is a line here now, not its own slide), tapes, the roast, the listening personality, the card.
 */
object RecapSlides {

    enum class Kind {
        INTRO, TIME, VS_YESTERDAY, HOURS, SESSION, FIRST_LAST, FIRST_LISTENS, GUESS, TOP5, ARTIST,
        HEARTED, TAPES, STREAK, ROAST, PERSONA, CARD
    }

    /**
     * 5.8.3 u55: [more] adds compared with yesterday (after the time), the longest session (after
     * the hours), first listens (after first and last), hearted today (after the artist) and the
     * day streak (before the roast). Each is left out when it has nothing to show.
     */
    fun plan(s: DayStats, d: RecapDay, hasRoast: Boolean, more: RecapMore = RecapMore.NONE): List<Kind> = buildList {
        add(Kind.INTRO)
        // 5.7.0 u51: listened is songs and tapes together
        val hasTime = s.totalMs > 0L || s.songs > 0 || s.tapes.tapes > 0
        if (hasTime) add(Kind.TIME)
        if (hasTime && more.compare != null) add(Kind.VS_YESTERDAY)
        if (d.peakHour in 0..23) add(Kind.HOURS)
        if (RecapMoreMath.sessionForSlide(more.session) != null) add(Kind.SESSION)
        if (d.hasFirstLast) add(Kind.FIRST_LAST)
        if (more.firstListens.isNotEmpty()) add(Kind.FIRST_LISTENS)
        if (RecapGuess.options(d, s.day) != null) add(Kind.GUESS)
        if (s.topSong != null) add(Kind.TOP5)
        if (s.topArtist != null && s.topArtist.name.isNotBlank()) add(Kind.ARTIST)
        if (more.hearted.isNotEmpty()) add(Kind.HEARTED)
        // 5.7.0 u51: "you spent 1h 10m with SR PAY STORIES", on a day with tapes
        val ch = s.tapes.topChannel
        if (ch != null && ch.name.isNotBlank() && ch.ms > 0L) add(Kind.TAPES)
        if (more.streakDays >= RecapMoreMath.STREAK_MIN) add(Kind.STREAK)
        if (hasRoast) add(Kind.ROAST)
        if (RecapPersonality.pick(s, d) != null) add(Kind.PERSONA)
        add(Kind.CARD)
    }

    /** How long each slide stays before moving on by itself, in ms. */
    fun durationMs(k: Kind): Long = when (k) {
        Kind.INTRO -> 3_800L
        Kind.TIME -> 5_200L
        Kind.VS_YESTERDAY -> 5_600L
        Kind.HOURS -> 6_200L
        Kind.SESSION -> 5_600L
        Kind.FIRST_LAST -> 6_000L
        Kind.FIRST_LISTENS -> 7_000L
        Kind.GUESS -> 2_600L // counted only after a cover is picked
        Kind.TOP5 -> 10_500L
        Kind.ARTIST -> 6_000L
        Kind.HEARTED -> 7_000L
        Kind.TAPES -> 5_200L
        Kind.STREAK -> 5_000L
        Kind.ROAST -> 5_200L
        Kind.PERSONA -> 6_000L
        Kind.CARD -> 7_000L
    }

    /** When the top-5 countdown hands the screen to #1, in ms from the slide's start. */
    fun top5RevealAt(rows: Int): Long = if (rows <= 0) 0L else 700L + rows * 750L + 500L

    /**
     * 5.8.2: the streak as one line on the top artist slide. "6 in a row at one point" when the
     * run was the top artist's own, else who it was.
     */
    fun streakLine(s: DayStats): String? {
        val st = s.streak ?: return null
        if (st.count < StatsEngine.MIN_STREAK || st.artist.isBlank()) return null
        val top = s.topArtist?.name.orEmpty()
        return if (StatsEngine.artistKey(st.artist) == StatsEngine.artistKey(top)) {
            "${st.count} in a row at one point"
        } else {
            "${st.count} ${Greetings.shortArtist(st.artist, 16)} songs in a row at one point"
        }
    }

    /** 5.7.0 u51: the tapes slide's small line: night owl when most tapes played after 11 PM. */
    fun tapesLine(s: DayStats): String {
        val t = s.tapes
        val count = if (t.tapes == 1) "1 tape" else "${t.tapes} tapes"
        val channels = if (t.channels > 1) " from ${t.channels} channels" else ""
        return if (t.nightOwl) "$count$channels. Night owl." else "$count$channels."
    }

    /** The day-in-hours slide's small line. */
    fun peakLine(h: Int): String = when (h) {
        in 0..4 -> "Night owl hours."
        in 5..11 -> "Morning person, then."
        in 12..16 -> "An afternoon soundtrack."
        in 17..20 -> "Evening mood."
        else -> "Late night vibes."
    }
}
