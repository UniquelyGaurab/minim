package com.minim.music.data.stats

/**
 * 5.8.3 (update 55): the extra numbers the recap story shows on its new slides: first listens,
 * hearted today, the longest session, compared with yesterday, the day streak and the top song's
 * all-time plays. Pure logic (unit tested); the database only hands it rows.
 */
data class RecapSong(
    val trackId: String,
    val title: String,
    val artist: String,
    val thumbnailUrl: String
)

/** The longest stretch of songs without a real stop. [ms] is the time actually heard in it. */
data class RecapSession(
    val ms: Long,
    val startMs: Long,
    val endMs: Long,
    val plays: Int,
    val firstThumb: String
)

/** The recapped day next to the day before it. */
data class RecapCompare(
    val todayMs: Long,
    val yesterdayMs: Long,
    val todaySongs: Int,
    val yesterdaySongs: Int,
    val todayArtists: Int,
    val yesterdayArtists: Int
) {
    val diffMs: Long get() = todayMs - yesterdayMs
}

data class RecapMore(
    /** Songs played for the first time ever on the day, in the order they were first played. */
    val firstListens: List<RecapSong> = emptyList(),
    /** Songs hearted on the day, in the order they were hearted. */
    val hearted: List<RecapSong> = emptyList(),
    val session: RecapSession? = null,
    val compare: RecapCompare? = null,
    /** "Day 12 in a row": the Stats day streak as of the recapped day (0 = the day broke it). */
    val streakDays: Int = 0,
    /** All-time plays of the day's #1 song, up to the end of the day (0 = unknown). */
    val topAllTime: Int = 0
) {
    companion object {
        val NONE = RecapMore()
    }
}

object RecapMoreMath {
    /** At most this many covers in the first listens grid (2 rows of 5). */
    const val GRID_MAX = 10

    /** At most this many rows in the hearted list. */
    const val LIST_MAX = 10

    /** A gap longer than this between two songs ends a session. */
    const val SESSION_GAP_MS = 10L * 60L * 1000L

    /** A session shorter than this isn't worth a slide. */
    const val SESSION_MIN_MS = 15L * 60L * 1000L

    /** A song counts as a play after this much is heard (see PlaybackController). */
    private const val COUNT_AFTER_MS = 30_000L

    /** The streak slide needs at least this many days in a row. */
    const val STREAK_MIN = 2

    /** Differences under this many ms read as "about the same". */
    const val SAME_MS = 60_000L

    /** "and 4 more" under a capped list, or null when everything fits. */
    fun moreLine(total: Int, max: Int): String? = if (total > max) "and ${total - max} more" else null

    /**
     * Songs whose first play ever falls on the day. [dayPlays] is the day's plays (any order);
     * [playedBefore] holds the track ids that already had a play before the day started.
     */
    fun firstListens(dayPlays: List<PlayRow>, playedBefore: Set<String>): List<RecapSong> {
        val seen = LinkedHashMap<String, RecapSong>()
        for (p in dayPlays.sortedBy { it.playedAt }) {
            if (p.trackId.isBlank() || p.trackId in playedBefore || p.trackId in seen) continue
            seen[p.trackId] = RecapSong(p.trackId, p.title, p.artist, p.thumbnailUrl)
        }
        return seen.values.toList()
    }

    /** "12 first listens", "1 first listen". */
    fun firstListensHeadline(n: Int): String = if (n == 1) "1 first listen" else "$n first listens"

    /** "3 songs hearted", "1 song hearted". */
    fun heartedHeadline(n: Int): String = if (n == 1) "1 song hearted" else "$n songs hearted"

    /** Time actually heard of one play (older rows without it fall back to the song's length). */
    private fun heard(p: PlayRow): Long = when {
        p.listenedMs > 0L -> p.listenedMs
        p.durationSec > 0L -> p.durationSec * 1000L
        else -> COUNT_AFTER_MS
    }

    /**
     * The longest run of songs where no gap between one song ending and the next starting is
     * longer than [gapMs]. A play is saved about 30 seconds in, so it started that much earlier.
     * The length is the time heard, so a short pause inside a run doesn't make it longer.
     */
    fun longestSession(plays: List<PlayRow>, gapMs: Long = SESSION_GAP_MS): RecapSession? {
        if (plays.isEmpty()) return null
        data class Span(val start: Long, val end: Long, val heard: Long, val thumb: String)
        val spans = plays.map { p ->
            val h = heard(p)
            val start = p.playedAt - minOf(h, COUNT_AFTER_MS)
            Span(start, start + h, h, p.thumbnailUrl)
        }.sortedBy { it.start }
        var best: RecapSession? = null
        var sStart = spans[0].start
        var sEnd = spans[0].end
        var sHeard = 0L
        var sPlays = 0
        var sThumb = spans[0].thumb
        fun close() {
            val s = RecapSession(sHeard, sStart, sEnd, sPlays, sThumb)
            if (best == null || s.ms > best!!.ms) best = s
        }
        for (sp in spans) {
            if (sPlays > 0 && sp.start - sEnd > gapMs) {
                close()
                sStart = sp.start
                sEnd = sp.end
                sHeard = 0L
                sPlays = 0
                sThumb = sp.thumb
            }
            sHeard += sp.heard
            sPlays++
            if (sp.end > sEnd) sEnd = sp.end
        }
        close()
        return best
    }

    /** The session worth a slide, or null. */
    fun sessionForSlide(s: RecapSession?): RecapSession? =
        s?.takeIf { it.ms >= SESSION_MIN_MS && it.plays >= 2 }

    /** Null when the day before has nothing to compare with. */
    fun compare(today: DayStats, yesterday: DayStats?): RecapCompare? {
        val y = yesterday ?: return null
        if (y.totalMs < SAME_MS && y.songs == 0 && y.tapes.tapes == 0) return null
        return RecapCompare(
            todayMs = today.totalMs, yesterdayMs = y.totalMs,
            todaySongs = today.songs, yesterdaySongs = y.songs,
            todayArtists = today.artists, yesterdayArtists = y.artists
        )
    }

    /**
     * "more than Saturday", "less than Saturday", or "as Saturday" under "Same". [before] names the
     * day before the recapped day; the recap is usually of yesterday, so a bare "yesterday" would
     * be ambiguous.
     */
    fun compareLine(c: RecapCompare, before: String): String = when {
        kotlin.math.abs(c.diffMs) < SAME_MS -> "as $before, give or take a minute"
        c.diffMs > 0L -> "more than $before"
        else -> "less than $before"
    }

    /** "Saturday" for the day before [day]. */
    fun beforeName(day: Int): String =
        Days.date(Days.previous(day)).dayOfWeek.getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.ENGLISH)

    /** The big number: "40m", or "Same" when it's within a minute. */
    fun compareBig(c: RecapCompare): String =
        if (kotlin.math.abs(c.diffMs) < SAME_MS) "Same" else StatsFormat.duration(kotlin.math.abs(c.diffMs))

    /** "4 more songs  ·  1 fewer artist", "same number of songs  ·  2 more artists". */
    fun compareCounts(c: RecapCompare): String {
        fun part(diff: Int, one: String, many: String): String = when {
            diff == 0 -> "same number of $many"
            diff > 0 -> "$diff more ${if (diff == 1) one else many}"
            else -> "${-diff} fewer ${if (diff == -1) one else many}"
        }
        return part(c.todaySongs - c.yesterdaySongs, "song", "songs") + "  ·  " +
            part(c.todayArtists - c.yesterdayArtists, "artist", "artists")
    }

    /**
     * The Stats day streak as of [day]: 0 when the day itself didn't reach 10 minutes (the streak
     * ended there), else the days in a row ending on it.
     */
    fun streakAsOf(day: Int, msByDay: Map<Int, Long>): Int =
        if ((msByDay[day] ?: 0L) < DayStreak.MIN_MS) 0 else DayStreak.count(day, msByDay)

    /** "Day 12 in a row". */
    fun streakLine(days: Int): String = "Day $days in a row"

    /** "16 today, 54 all time" on the #1 song. */
    fun allTimeLine(today: Int, allTime: Int): String = "$today today, ${maxOf(allTime, today)} all time"
}
