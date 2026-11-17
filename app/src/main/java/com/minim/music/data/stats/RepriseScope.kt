package com.minim.music.data.stats

import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale

/**
 * 6.9.0: everything a Weekly or Monthly Reprise shows beyond what the daily story already has.
 * The daily pieces (DayStats, RecapDay) are built over the whole period and reused as they are.
 */
class RepriseScope(
    val period: Period,
    /** The first day of the period. */
    val start: LocalDate,
    /** Its days, in order (a month's last day only counts up to 6 PM). */
    val days: List<Int>,
    /** "Your week, Nov 15–21" or "November". */
    val title: String,
    /** The card's and story's top label: "Nov 15–21" or "November 2026". */
    val label: String,
    /** "week" or "month". */
    val word: String,
    val totalMs: Long,
    val previousMs: Long,
    /** Listening per day, songs and tapes. */
    val dayTotals: Map<Int, Long>,
    /** Each day's top song (null for a quiet day), in [days] order. */
    val dayTops: List<TopSong?>,
    /** Monthly: each week's top song, with the week's label. */
    val weekTops: List<Pair<String, TopSong>>,
    val artistsTotal: Int,
    val newArtists: List<String>,
    val newShare: Float,
    val onRepeat: Pair<CountedSong, Int>?,
    val skipped: CountedSong?,
    val obsession: TopSong?,
    val rising: List<ArtistMove>,
    val fading: List<ArtistMove>,
    val fermataHits: List<CountedSong>,
    val bestDay: Int?,
    val bestDayTop: TopSong?,
    val longestStreak: Int,
    val favouriteHour: Int,
    val weekendMs: Long,
    val weekdayMs: Long
) {
    enum class Period { WEEK, MONTH }

    val isWeek: Boolean get() = period == Period.WEEK
    val unlocked: Boolean get() = if (isWeek) RepriseClock.weekUnlocked(totalMs) else RepriseClock.monthUnlocked(totalMs)
    val compareLine: String? get() = StatsPeriods.compareLine(if (isWeek) StatsPeriods.Period.WEEK else StatsPeriods.Period.MONTH, totalMs, previousMs)
    val funLine: String? get() = if (isWeek) null else RepriseMath.funComparison(totalMs)
    val busiestQuietest: Pair<Int?, Int?> get() = RepriseMath.busiestQuietest(dayTotals, days)
    /** The soundtrack slide needs 3 or more days with a top song. */
    val hasSoundtrack: Boolean get() = dayTops.count { it != null } >= 3
    val hasAnthems: Boolean get() = weekTops.size >= 2
    val heatMax: Long get() = days.maxOfOrNull { dayTotals[it] ?: 0L } ?: 0L
    /** "November" for the month, the playlist Save as playlist makes and its cover. */
    val monthName: String get() = start.month.getDisplayName(TextStyle.FULL, Locale.ENGLISH)
    val cardTag: String get() = if (isWeek) "my week in music" else "my month in music"
    val key: String get() = if (isWeek) "w${Days.key(start)}" else "m${start.year * 100 + start.monthValue}"

    companion object {

        /**
         * Builds a period's scope from its rows. [stats] and [day] are the daily pieces computed over
         * the whole period; [prev*] are last week's or last month's rows; [log] is Fermata's log.
         */
        fun build(
            period: Period,
            start: LocalDate,
            stats: DayStats,
            plays: List<PlayRow>,
            listens: List<ListenRow>,
            dayTotals: Map<Int, Long>,
            previousMs: Long,
            prevPlays: List<PlayRow>,
            prevListens: List<ListenRow>,
            artistsBefore: Set<String>,
            songsBefore: Set<String>,
            log: List<LogRow>,
            kept: Set<String>,
            fromMs: Long,
            toMs: Long,
            hours: LongArray,
            zone: ZoneId = ZoneId.systemDefault()
        ): RepriseScope {
            val isWeek = period == Period.WEEK
            val ym = YearMonth.from(start)
            val days = if (isWeek) RepriseClock.weekDays(start) else RepriseClock.monthDays(ym)
            val byDay = plays.groupBy { Days.keyOf(it.playedAt, zone) }
            val dayTops = days.map { k ->
                val p = byDay[k].orEmpty()
                if (p.isEmpty()) null else StatsEngine.rankSongs(p, emptyList()).firstOrNull()
            }
            val weekTops = if (isWeek) emptyList() else {
                val out = ArrayList<Pair<String, TopSong>>()
                var ws = RepriseClock.weekStart(ym.atDay(1))
                while (!ws.isAfter(ym.atEndOfMonth())) {
                    val keys = RepriseClock.weekDays(ws).filter { it in days }.toSet()
                    val p = plays.filter { Days.keyOf(it.playedAt, zone) in keys }
                    StatsEngine.rankSongs(p, emptyList()).firstOrNull()?.let {
                        val from = maxOf(ws, ym.atDay(1))
                        val to = minOf(ws.plusDays(6), ym.atEndOfMonth())
                        out.add("${from.dayOfMonth}–${to.dayOfMonth}" to it)
                    }
                    ws = ws.plusDays(7)
                }
                out
            }
            val newArtists = RepriseMath.newArtists(plays, artistsBefore)
            val (busy, _) = RepriseMath.busiestQuietest(dayTotals, days)
            val bestTop = busy?.let { k -> byDay[k]?.takeIf { it.isNotEmpty() }?.let { StatsEngine.rankSongs(it, emptyList()).firstOrNull() } }
            val rf = if (isWeek) emptyList<ArtistMove>() to emptyList() else
                RepriseMath.risingFading(RepriseMath.artistMs(plays, listens), RepriseMath.artistMs(prevPlays, prevListens))
            val ranked = StatsEngine.rankSongs(plays, listens)
            val (weekend, weekday) = RepriseMath.weekendSplit(dayTotals)
            val label = if (isWeek) RepriseClock.weekLabel(start) else "${RepriseClock.monthName(ym)} ${ym.year}"
            return RepriseScope(
                period = period,
                start = start,
                days = days,
                title = if (isWeek) "Your week, ${RepriseClock.weekLabel(start)}" else RepriseClock.monthName(ym),
                label = label,
                word = if (isWeek) "week" else "month",
                totalMs = stats.totalMs,
                previousMs = previousMs,
                dayTotals = dayTotals,
                dayTops = dayTops,
                weekTops = weekTops,
                artistsTotal = stats.artists,
                newArtists = newArtists,
                newShare = RepriseMath.newShare(plays, listens, newArtists),
                onRepeat = RepriseMath.onRepeat(plays),
                skipped = RepriseMath.skippedButKept(log, fromMs, toMs, kept),
                obsession = if (isWeek) null else RepriseMath.obsession(ranked, songsBefore),
                rising = rf.first,
                fading = rf.second,
                fermataHits = if (isWeek) emptyList() else RepriseMath.fermataHits(log, fromMs, toMs),
                bestDay = busy,
                bestDayTop = bestTop,
                longestStreak = RepriseMath.longestRun(dayTotals, days),
                favouriteHour = RepriseMath.favouriteHour(hours),
                weekendMs = weekend,
                weekdayMs = weekday
            )
        }
    }
}
