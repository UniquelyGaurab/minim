package com.minim.music.data.stats

import java.text.NumberFormat
import java.util.Locale

/**
 * 6.9.0 b78: the two ways Minim writes listening time.
 *
 * - [stats], for the Stats tab: "45 min", "2 hr 15 min", "2 hr", and from 100 hours "142 hrs".
 * - [reprise], for every Reprise story, slide and share card: minutes only, never hours,
 *   with thousands separators: "4,380 minutes", "1 minute", or "4,380 min" where space is tight.
 *
 * Time is counted in whole minutes, rounded down (so 59 s is "0 min").
 */
object TimeFormat {
    /** From this many hours on, Stats writes only the hours ("142 hrs"). */
    const val BIG_HOURS = 100L

    fun minutes(ms: Long): Long = ms.coerceAtLeast(0L) / 60_000L

    /** Stats: "0 min", "1 min", "45 min", "1 hr", "2 hr 15 min", "99 hr 59 min", "100 hrs", "142 hrs". */
    fun stats(ms: Long): String {
        val mins = minutes(ms)
        val h = mins / 60L
        val m = mins % 60L
        return when {
            h == 0L -> "$m min"
            h >= BIG_HOURS -> "${group(h)} hrs"
            m == 0L -> "$h hr"
            else -> "$h hr $m min"
        }
    }

    /** Reprise: "0 minutes", "1 minute", "4,380 minutes"; [short] gives "4,380 min". */
    fun reprise(ms: Long, short: Boolean = false): String = repriseMinutes(minutes(ms), short)

    /** Reprise where space is tight: "4,380 min". */
    fun repriseShort(ms: Long): String = reprise(ms, short = true)

    fun repriseMinutes(mins: Long, short: Boolean = false): String {
        val n = group(mins)
        return when {
            short -> "$n min"
            mins == 1L -> "1 minute"
            else -> "$n minutes"
        }
    }

    /** "4,380": thousands separators (always commas, like the rest of Minim's English text). */
    fun group(n: Long): String = NumberFormat.getIntegerInstance(Locale.US).format(n)
}
