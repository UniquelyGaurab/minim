package com.minim.music.ui

import com.minim.music.data.stats.RepriseClock
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId

/**
 * 6.9.0 part B: the hidden previews (Settings, tap the version 7 times). Each teaser state is shown
 * by moving the teaser's clock to a moment in this month that has it; the drop starts 8 seconds
 * before 6 PM on the last day, so the countdown runs out and it unblurs for real.
 */
object ReprisePreview {

    enum class Teaser(val label: String) {
        FIVE_DAYS("Teaser: 5 days out"),
        TOMORROW("Teaser: tomorrow at 6 PM"),
        LAST_DAY("Teaser: last day"),
        FINAL_HOUR("Teaser: final hour"),
        DROP("Teaser: the drop"),
        LOCKED("Teaser: below the minimum")
    }

    /** The moment [kind] shows at, in the month of [now]. */
    fun at(kind: Teaser, now: Long, zone: ZoneId = ZoneId.systemDefault()): Long {
        val ym = YearMonth.from(Instant.ofEpochMilli(now).atZone(zone))
        val last = ym.atEndOfMonth()
        val drop = RepriseClock.monthDropAt(ym, zone)
        return when (kind) {
            Teaser.FIVE_DAYS -> RepriseClock.at(last.minusDays(5), 12, 0, zone)
            Teaser.TOMORROW -> RepriseClock.at(last.minusDays(1), 12, 0, zone)
            Teaser.LAST_DAY -> RepriseClock.at(last, 10, 48, zone)
            Teaser.FINAL_HOUR -> drop - 12L * 60_000L
            Teaser.DROP -> drop - 8_000L
            Teaser.LOCKED -> RepriseClock.at(last.minusDays(3), 12, 0, zone)
        }
    }

    fun unlocked(kind: Teaser): Boolean = kind != Teaser.LOCKED

    /** The month's playlist: its top songs, best first (the top song always leads), no repeats. */
    fun monthPlaylistIds(ranked: List<String>, topSong: String?, max: Int = MONTH_PLAYLIST_MAX): List<String> {
        val out = LinkedHashSet<String>()
        topSong?.takeIf { it.isNotBlank() }?.let { out.add(it) }
        for (id in ranked) if (id.isNotBlank()) out.add(id)
        return out.take(max)
    }

    const val MONTH_PLAYLIST_MAX = 50
}
