package com.minim.music.ui

import com.minim.music.data.stats.StatsEngine
import kotlin.random.Random

/**
 * v4.9.3: the Home "top artist takeover" greeting. For 10 seconds the whole Home canvas becomes
 * today's top artist's photo. These are the rules for when it may show; MainActivity does the
 * drawing. Times are monotonic clock milliseconds (SystemClock.elapsedRealtime).
 */
object ArtistTakeover {
    /** Today's top artist only qualifies after 10 minutes of listening to them today. */
    const val MIN_LISTEN_MS = 10L * 60L * 1000L

    /** As one of the rotating greetings: at most once every 10 minutes. */
    const val ROTATION_GAP_MS = 10L * 60L * 1000L

    /** When one of their songs starts on Home: at least 2 minutes since the last takeover. */
    const val SONG_GAP_MS = 2L * 60L * 1000L

    /** How long it lasts, fades included. */
    const val DURATION_MS = 10_000L

    /** 5.5.2: during a festival theme it lasts 7 seconds, the same as one greeting. */
    const val THEME_DURATION_MS = 7_000L

    fun durationMs(themed: Boolean): Long = if (themed) THEME_DURATION_MS else DURATION_MS
    const val FADE_IN_MS = 1_100L
    const val FADE_OUT_MS = 1_100L
    const val EARLY_FADE_MS = 380L

    /** Slow documentary zoom over the 10 seconds. */
    const val ZOOM_TO = 1.13f

    /** Today's top artist (by listening time) qualifies: 10+ minutes today and a photo. */
    fun qualifies(topArtistMs: Long, hasPhoto: Boolean): Boolean =
        topArtistMs >= MIN_LISTEN_MS && hasPhoto

    /** [lastShownAt] null = not shown yet this session. */
    fun rotationAllowed(now: Long, lastShownAt: Long?): Boolean =
        lastShownAt == null || now - lastShownAt >= ROTATION_GAP_MS

    fun songAllowed(now: Long, lastShownAt: Long?): Boolean =
        lastShownAt == null || now - lastShownAt >= SONG_GAP_MS

    /** True when [trackArtist] (the song's artist line) is [topArtist], matched like the Stats tab counts them. */
    fun isByArtist(trackArtist: String?, topArtist: String?): Boolean {
        if (trackArtist.isNullOrBlank() || topArtist.isNullOrBlank()) return false
        val a = StatsEngine.artistKey(StatsEngine.primaryArtist(trackArtist))
        val b = StatsEngine.artistKey(topArtist)
        return a.isNotEmpty() && a == b
    }

    /** Whole minutes heard, never below 1. */
    fun minutes(ms: Long): Int = maxOf(1L, ms / 60_000L).toInt()

    /** First word of a long name for the punchier lines ("Anirudh" for "Anirudh Ravichander"). */
    fun shortName(name: String): String {
        val first = name.trim().substringBefore(' ')
        return if (name.length > 14 && first.length >= 3) first else name.trim()
    }

    const val LINE_COUNT = 8

    /** Two-line greeting [index] for [name] with [ms] heard today. */
    fun lines(index: Int, name: String, ms: Long): Pair<String, String> {
        val m = minutes(ms)
        val n = shortName(name)
        val mins = if (m == 1) "1 minute" else "$m minutes"
        return when (Math.floorMod(index, LINE_COUNT)) {
            0 -> "$n's running your day." to "${m}m and counting."
            1 -> "$n, $mins deep." to "No notes."
            2 -> "Today's headliner:" to "$n. ${m}m on stage."
            3 -> "${m}m of $n today." to "Rent's due soon."
            4 -> "$n called." to "You picked up for ${m}m."
            5 -> "Day sponsored by" to "$n. ${m}m so far."
            6 -> "$n: ${m}m today." to "Fan club, party of one."
            else -> "Still on $n?" to "${m}m says yes."
        }
    }

    /** Shuffles the lines so the same one doesn't come twice in a row. */
    class LinePicker(private val random: Random = Random.Default) {
        private var last = -1
        fun next(count: Int = LINE_COUNT): Int {
            if (count <= 1) return 0.also { last = 0 }
            var i = random.nextInt(count)
            if (i == last) i = (i + 1) % count
            last = i
            return i
        }
    }
}
