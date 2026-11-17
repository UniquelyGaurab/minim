package com.minim.music.data.stats

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import java.util.Locale

/**
 * 6.9.0: Weekly and Monthly Reprise, and the Stats tab's periods. Pure logic (unit tested).
 *
 * - A week runs Sunday to Saturday. Its Reprise is ready on the Sunday after, from 8 AM local.
 * - A month runs from the 1st to the last day at 6 PM local, and its Reprise drops at that moment.
 *   The rest of the last day counts towards nothing (approved).
 * - Too little listening: a week needs [WEEK_MIN_MS] and a month [MONTH_MIN_MS] (songs and tapes).
 */
object RepriseClock {

    const val WEEK_HOUR = 8
    const val MONTH_HOUR = 18
    /** The Monthly teaser shows in the month's last 7 days. */
    const val TEASER_DAYS = 7
    /** After the drop, the "is here" card stays until the end of the next month's 3rd. */
    const val HERE_DAYS = 3

    // ---------------------------------------------------------------- weeks

    /** The Sunday that starts the week [date] is in. */
    fun weekStart(date: LocalDate): LocalDate = date.with(TemporalAdjusters.previousOrSame(DayOfWeek.SUNDAY))

    fun weekEnd(start: LocalDate): LocalDate = weekStart(start).plusDays(6)

    /** The 7 day keys of the week starting [start], Sunday first. */
    fun weekDays(start: LocalDate): List<Int> = (0L..6L).map { Days.key(start.plusDays(it)) }

    /** When the week starting [start] gets its Reprise: the next Sunday at 8 AM. */
    fun weeklyReadyAt(start: LocalDate, zone: ZoneId = ZoneId.systemDefault()): Long =
        weekStart(start).plusDays(7).atTime(WEEK_HOUR, 0).atZone(zone).toInstant().toEpochMilli()

    /** The newest week whose Reprise is out at [now]: last week from Sunday 8 AM on, else the one before. */
    fun latestWeek(now: Long, zone: ZoneId = ZoneId.systemDefault()): LocalDate {
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val last = weekStart(today).minusDays(7)
        return if (now >= weeklyReadyAt(last, zone)) last else last.minusDays(7)
    }

    /** "Nov 15–21", or "Nov 29 – Dec 5" across months. */
    fun weekLabel(start: LocalDate): String {
        val end = weekEnd(start)
        val m1 = start.month.getDisplayName(TextStyle.SHORT, Locale.ENGLISH)
        val m2 = end.month.getDisplayName(TextStyle.SHORT, Locale.ENGLISH)
        return if (m1 == m2) "$m1 ${start.dayOfMonth}–${end.dayOfMonth}" else "$m1 ${start.dayOfMonth} – $m2 ${end.dayOfMonth}"
    }

    /** Saturday only: the quiet line in Stats. */
    fun weeklyTeaser(today: LocalDate): String? =
        if (today.dayOfWeek == DayOfWeek.SATURDAY) "Your weekly Reprise lands tomorrow morning." else null

    // ---------------------------------------------------------------- months

    fun monthDropAt(ym: YearMonth, zone: ZoneId = ZoneId.systemDefault()): Long =
        ym.atEndOfMonth().atTime(MONTH_HOUR, 0).atZone(zone).toInstant().toEpochMilli()

    fun monthStartMs(ym: YearMonth, zone: ZoneId = ZoneId.systemDefault()): Long =
        ym.atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()

    /** Every day key the month covers (the last one only up to 6 PM). */
    fun monthDays(ym: YearMonth): List<Int> = (1..ym.lengthOfMonth()).map { Days.key(ym.atDay(it)) }

    /** The newest month whose Reprise is out at [now]. */
    fun latestMonth(now: Long, zone: ZoneId = ZoneId.systemDefault()): YearMonth {
        val ym = YearMonth.from(Instant.ofEpochMilli(now).atZone(zone))
        return if (now >= monthDropAt(ym, zone)) ym else ym.minusMonths(1)
    }

    fun monthName(ym: YearMonth): String = ym.month.getDisplayName(TextStyle.FULL, Locale.ENGLISH)

    // ---------------------------------------------------------------- the Monthly teaser

    enum class Phase { HIDDEN, DAYS, TOMORROW, HOURS, FINAL_HOUR, HERE, LOCKED }

    class Teaser(val phase: Phase, val month: YearMonth, val title: String, val countdown: String, val progress: Float, val msLeft: Long)

    /**
     * The Monthly teaser at [now]: hidden outside the last 7 days of the month (and the few days
     * after the drop); [unlocked] false turns it into "play a bit more" and nothing drops.
     */
    fun teaser(now: Long, unlocked: Boolean, zone: ZoneId = ZoneId.systemDefault()): Teaser {
        val at = Instant.ofEpochMilli(now).atZone(zone)
        val today = at.toLocalDate()
        val ym = YearMonth.from(today)
        val name = monthName(ym)
        val drop = monthDropAt(ym, zone)
        val last = ym.atEndOfMonth()
        // Just after a drop: this month's 1st to 3rd still shows last month's "is here"
        if (today.dayOfMonth <= HERE_DAYS) {
            val prev = ym.minusMonths(1)
            return if (unlocked) Teaser(Phase.HERE, prev, "Your ${monthName(prev)} Reprise is here", "", 1f, 0L)
            else Teaser(Phase.HIDDEN, prev, "", "", 0f, 0L)
        }
        if (now >= drop) {
            return if (unlocked) Teaser(Phase.HERE, ym, "Your $name Reprise is here", "", 1f, 0L)
            else Teaser(Phase.HIDDEN, ym, "", "", 0f, 0L)
        }
        val daysAway = ChronoUnit.DAYS.between(today, last)
        if (daysAway >= TEASER_DAYS) return Teaser(Phase.HIDDEN, ym, "", "", 0f, 0L)
        val start = monthStartMs(ym, zone)
        val progress = ((now - start).toFloat() / (drop - start).toFloat()).coerceIn(0f, 1f)
        val left = drop - now
        if (!unlocked) return Teaser(Phase.LOCKED, ym, "$name Reprise", "Play a bit more to unlock your Reprise", progress, left)
        val title = "$name Reprise"
        return when {
            daysAway >= 2 -> Teaser(Phase.DAYS, ym, title, "in $daysAway days", progress, left)
            daysAway == 1L -> Teaser(Phase.TOMORROW, ym, title, "tomorrow at 6 PM", progress, left)
            left > 3_600_000L -> Teaser(Phase.HOURS, ym, title, "in " + hoursMinutes(left), progress, left)
            else -> Teaser(Phase.FINAL_HOUR, ym, title, "in " + mmss(left), progress, left)
        }
    }

    /** "7 hr 12 min" (minutes rounded up, so it never says 0 min early). */
    fun hoursMinutes(ms: Long): String {
        val mins = ((ms + 59_999L) / 60_000L).coerceAtLeast(0L)
        val h = mins / 60
        val m = mins % 60
        return when {
            h == 0L -> "$m min"
            m == 0L -> "$h hr"
            else -> "$h hr $m min"
        }
    }

    /** "42:07" (seconds rounded up). */
    fun mmss(ms: Long): String {
        val secs = ((ms + 999L) / 1000L).coerceAtLeast(0L)
        val m = secs / 60
        val s = secs % 60
        return "${if (m < 10) "0$m" else "$m"}:${if (s < 10) "0$s" else "$s"}"
    }

    /** How often the teaser text has to update: every second in the final hour, else every minute. */
    fun tickMs(t: Teaser): Long = if (t.phase == Phase.FINAL_HOUR) 1_000L else 60_000L

    /** The Home greeting line on the last day, before 6 PM. */
    fun greetingLine(now: Long, unlocked: Boolean, zone: ZoneId = ZoneId.systemDefault()): String? {
        val at = Instant.ofEpochMilli(now).atZone(zone)
        val ym = YearMonth.from(at)
        if (!unlocked || at.toLocalDate() != ym.atEndOfMonth() || now >= monthDropAt(ym, zone)) return null
        return "Your ${monthName(ym)} Reprise drops at 6."
    }

    /** The teaser's one teasing line, a new one each day. No numbers, names or spoilers. */
    val TEASES = listOf(
        "Your top song has no idea what's coming.",
        "Someone's about to be very exposed.",
        "Fermata's been taking notes.",
        "We counted. Every single replay.",
        "A few songs are getting nervous.",
        "It's giving main character.",
        "Your month, minus the excuses."
    )

    fun tease(today: LocalDate): String = TEASES[Math.floorMod(today.dayOfMonth + today.monthValue * 3, TEASES.size)]

    // ---------------------------------------------------------------- gates

    const val WEEK_MIN_MS = 45L * 60_000L
    const val MONTH_MIN_MS = 2L * 3_600_000L

    fun weekUnlocked(totalMs: Long): Boolean = totalMs >= WEEK_MIN_MS
    fun monthUnlocked(totalMs: Long): Boolean = totalMs >= MONTH_MIN_MS

    // ---------------------------------------------------------------- helpers

    fun dateOf(ms: Long, zone: ZoneId = ZoneId.systemDefault()): LocalDate = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()

    fun at(date: LocalDate, hour: Int, minute: Int = 0, zone: ZoneId = ZoneId.systemDefault()): Long =
        LocalDateTime.of(date, java.time.LocalTime.of(hour, minute)).atZone(zone).toInstant().toEpochMilli()
}

/**
 * 6.9.0: the Stats tab's periods. Today, this week (Sunday to today), this month (the 1st to
 * today) and all time. Each is compared with the same stretch before it: yesterday, the same days
 * of last week, the same days of last month (cut to that month's length). All time has no compare.
 */
object StatsPeriods {

    enum class Period(val label: String, val vsWord: String?) {
        TODAY("Today", "yesterday"), WEEK("Week", "last week"), MONTH("Month", "last month"), ALL("All time", null)
    }

    /** First and last day key (both included) of [p] on [today]. */
    fun range(p: Period, today: LocalDate, firstDay: LocalDate? = null): Pair<Int, Int> = when (p) {
        Period.TODAY -> Days.key(today) to Days.key(today)
        Period.WEEK -> Days.key(RepriseClock.weekStart(today)) to Days.key(today)
        Period.MONTH -> Days.key(today.withDayOfMonth(1)) to Days.key(today)
        Period.ALL -> Days.key(firstDay ?: today.minusYears(20)) to Days.key(today)
    }

    /** The stretch [p] is compared with, or null for all time. */
    fun previousRange(p: Period, today: LocalDate): Pair<Int, Int>? = when (p) {
        Period.TODAY -> Days.key(today.minusDays(1)).let { it to it }
        Period.WEEK -> {
            val s = RepriseClock.weekStart(today).minusDays(7)
            Days.key(s) to Days.key(today.minusDays(7))
        }
        Period.MONTH -> {
            val prev = YearMonth.from(today).minusMonths(1)
            Days.key(prev.atDay(1)) to Days.key(prev.atDay(minOf(today.dayOfMonth, prev.lengthOfMonth())))
        }
        Period.ALL -> null
    }

    /** "+1 hr 12 min vs last week", "−20 min vs yesterday", "Same as last week". Null with nothing to compare. */
    fun compareLine(p: Period, nowMs: Long, prevMs: Long): String? {
        val word = p.vsWord ?: return null
        if (nowMs <= 0L && prevMs <= 0L) return null
        val diff = nowMs - prevMs
        if (Math.abs(diff) < 60_000L) return "Same as $word"
        val sign = if (diff > 0) "+" else "−"
        return "$sign${long(Math.abs(diff))} vs $word"
    }

    /** "1 hr 12 min", "45 min", "3 hr". */
    fun long(ms: Long): String {
        val mins = ms / 60_000L
        val h = mins / 60
        val m = mins % 60
        return when {
            h == 0L -> "$m min"
            m == 0L -> "$h hr"
            else -> "$h hr $m min"
        }
    }

    class Bar(val label: String, val ms: Long, val day: Int?)

    /** The hero's bars: 24 hours, 7 days Sun–Sat, the month's days, or months (the last 12). */
    fun bars(p: Period, today: LocalDate, totals: Map<Int, Long>, hours: LongArray? = null): List<Bar> = when (p) {
        Period.TODAY -> (0..23).map { h -> Bar(if (h % 6 == 0) StatsFormat.hour(h) else "", hours?.getOrNull(h) ?: 0L, null) }
        Period.WEEK -> {
            val s = RepriseClock.weekStart(today)
            (0L..6L).map { i ->
                val d = s.plusDays(i)
                val k = Days.key(d)
                Bar(d.dayOfWeek.getDisplayName(TextStyle.NARROW, Locale.ENGLISH), totals[k] ?: 0L, if (d.isAfter(today)) null else k)
            }
        }
        Period.MONTH -> {
            val ym = YearMonth.from(today)
            (1..ym.lengthOfMonth()).map { n ->
                val d = ym.atDay(n)
                val k = Days.key(d)
                Bar(if (n == 1 || n % 7 == 0) "$n" else "", totals[k] ?: 0L, if (d.isAfter(today)) null else k)
            }
        }
        Period.ALL -> {
            val byMonth = HashMap<YearMonth, Long>()
            for ((k, ms) in totals) {
                val ym = YearMonth.from(Days.date(k))
                byMonth[ym] = (byMonth[ym] ?: 0L) + ms
            }
            val now = YearMonth.from(today)
            val first = byMonth.filterValues { it > 0L }.keys.minOrNull() ?: now
            val from = maxOf(first, now.minusMonths(11))
            val out = ArrayList<Bar>()
            var m = from
            while (!m.isAfter(now)) {
                out.add(Bar(m.month.getDisplayName(TextStyle.NARROW, Locale.ENGLISH), byMonth[m] ?: 0L, null))
                m = m.plusMonths(1)
            }
            out
        }
    }

    /** Heatmap level for one day: 0 (nothing, faint) to 4, by its share of the month's busiest day. */
    fun heatLevel(ms: Long, maxMs: Long): Int {
        if (ms <= 0L || maxMs <= 0L) return 0
        val f = ms.toDouble() / maxMs.toDouble()
        return when {
            f > 0.75 -> 4
            f > 0.5 -> 3
            f > 0.25 -> 2
            else -> 1
        }
    }

    /** Day keys of [ym] laid out in a Sunday-first month grid; null for the blanks. */
    fun monthGrid(ym: YearMonth): List<Int?> {
        val lead = ym.atDay(1).dayOfWeek.value % 7 // Sunday = 0
        val out = ArrayList<Int?>()
        repeat(lead) { out.add(null) }
        for (n in 1..ym.lengthOfMonth()) out.add(Days.key(ym.atDay(n)))
        while (out.size % 7 != 0) out.add(null)
        return out
    }

    /** Merges song and tape day totals. */
    fun merge(a: Map<Int, Long>, b: Map<Int, Long>): Map<Int, Long> {
        val out = HashMap(a)
        for ((k, v) in b) out[k] = (out[k] ?: 0L) + v
        return out
    }
}

/** 6.9.0: one row of Fermata's log, cut down to what Stats and Reprise read. */
data class LogRow(
    val type: String,
    val trackId: String,
    val title: String,
    val artist: String,
    val ts: Long,
    val source: String,
    val thumbnailUrl: String = ""
) {
    companion object {
        const val PLAY = "play"
        const val SKIP = "skip"
    }
}

/** 6.9.0: a song with a count, for insights and slides. */
data class CountedSong(val trackId: String, val title: String, val artist: String, val thumbnailUrl: String, val count: Int)

/** 6.9.0: an artist's listening in two periods, for rising and fading. */
data class ArtistMove(val name: String, val nowMs: Long, val beforeMs: Long)

/**
 * 6.9.0: the numbers behind the insights and the Reprise slides. Pure functions over rows.
 */
object RepriseMath {

    /** The most-skipped song in [from, to) from Fermata's skips (2 or more skips), or null. */
    fun mostSkipped(log: List<LogRow>, from: Long, to: Long): CountedSong? {
        val skips = log.filter { it.type == LogRow.SKIP && it.ts in from until to }
        if (skips.isEmpty()) return null
        val best = skips.groupBy { it.trackId }.maxWithOrNull(compareBy<Map.Entry<String, List<LogRow>>> { it.value.size }.thenBy { -it.value.minOf { r -> r.ts } }) ?: return null
        if (best.value.size < 2) return null
        val r = best.value.last()
        return CountedSong(r.trackId, r.title, r.artist, r.thumbnailUrl, best.value.size)
    }

    /**
     * The skip slide: a song skipped often in the period and never removed: still in the library
     * ([kept] = saved, liked or in a playlist). Needs 2+ skips and at least one play too.
     */
    fun skippedButKept(log: List<LogRow>, from: Long, to: Long, kept: Set<String>): CountedSong? {
        val inRange = log.filter { it.ts in from until to }
        val played = inRange.filter { it.type == LogRow.PLAY }.map { it.trackId }.toSet()
        val skips = inRange.filter { it.type == LogRow.SKIP && it.trackId in kept && it.trackId in played }
        val best = skips.groupBy { it.trackId }.maxByOrNull { it.value.size } ?: return null
        if (best.value.size < 2) return null
        val r = best.value.last()
        return CountedSong(r.trackId, r.title, r.artist, r.thumbnailUrl, best.value.size)
    }

    /** Share of [hours] listening that's late at night, 11 PM to 4 AM. */
    fun lateNightShare(hours: LongArray): Float {
        val total = hours.sum()
        if (total <= 0L) return 0f
        var late = 0L
        for (h in hours.indices) if (h >= 23 || h <= 3) late += hours[h]
        return late.toFloat() / total
    }

    /** "23%" of a share. */
    fun percent(f: Float): String = "${Math.round(f * 100f)}%"

    /** Artist keys in [plays] that were never played before (not in [before]), as display names, by time. */
    fun newArtists(plays: List<PlayRow>, before: Set<String>): List<String> {
        val seen = LinkedHashMap<String, String>()
        for (p in plays) {
            val name = StatsEngine.primaryArtist(p.artist)
            if (name.isBlank()) continue
            val k = StatsEngine.artistKey(name)
            if (k !in before) seen.putIfAbsent(k, name)
        }
        return seen.values.toList()
    }

    /** Share of the period's listening that went to [newNames]' artists. */
    fun newShare(plays: List<PlayRow>, listens: List<ListenRow>, newNames: List<String>): Float {
        val keys = newNames.map { StatsEngine.artistKey(it) }.toSet()
        val by = artistMs(plays, listens)
        val all = by.values.sumOf { it.second }
        val fresh = by.filterKeys { it in keys }.values.sumOf { it.second }
        return if (all <= 0L) 0f else fresh.toFloat() / all
    }

    /** On repeat: the most plays of one song in a single day of [plays]. Needs 3+. */
    fun onRepeat(plays: List<PlayRow>): Pair<CountedSong, Int>? {
        var best: Pair<CountedSong, Int>? = null
        for ((key, list) in plays.groupBy { Days.keyOf(it.playedAt) to it.trackId }) {
            val n = list.size
            if (n >= 3 && (best == null || n > best.first.count)) {
                val p = list.first()
                best = CountedSong(p.trackId, p.title, p.artist, p.thumbnailUrl, n) to key.first
            }
        }
        return best
    }

    /** Biggest new obsession: of the period's top 5, the best song never played before it started. */
    fun obsession(top: List<TopSong>, playedBefore: Set<String>): TopSong? =
        top.take(5).firstOrNull { it.trackId !in playedBefore }

    /** Per-artist listening (by key, with a display name): real listening, else the song's length. */
    fun artistMs(plays: List<PlayRow>, listens: List<ListenRow>): Map<String, Pair<String, Long>> {
        val out = HashMap<String, Pair<String, Long>>()
        fun add(line: String, ms: Long) {
            val name = StatsEngine.primaryArtist(line)
            if (name.isBlank() || ms <= 0L) return
            val k = StatsEngine.artistKey(name)
            val cur = out[k]
            out[k] = (cur?.first ?: name) to ((cur?.second ?: 0L) + ms)
        }
        for (l in listens) add(l.artist, l.ms)
        for (p in plays) if (!p.tracked) add(p.artist, StatsEngine.estimateMs(p))
        return out
    }

    /**
     * Rising: up the most vs last month (at least +20 min, and doubled or new). Fading: down the
     * most (had 30+ min last month, now under half). Up to [n] each.
     */
    fun risingFading(now: Map<String, Pair<String, Long>>, before: Map<String, Pair<String, Long>>, n: Int = 3): Pair<List<ArtistMove>, List<ArtistMove>> {
        val rising = now.map { (k, v) -> ArtistMove(v.first, v.second, before[k]?.second ?: 0L) }
            .filter { it.nowMs - it.beforeMs >= 20 * 60_000L && it.nowMs >= it.beforeMs * 2 }
            .sortedByDescending { it.nowMs - it.beforeMs }.take(n)
        val fading = before.map { (k, v) -> ArtistMove(v.first, now[k]?.second ?: 0L, v.second) }
            .filter { it.beforeMs >= 30 * 60_000L && it.nowMs * 2 < it.beforeMs }
            .sortedByDescending { it.beforeMs - it.nowMs }.take(n)
        return rising to fading
    }

    /**
     * Fermata's hits: songs first heard through a Fermata recommendation (radio or Discover) in
     * [from, to), then played again since. Best first, by plays after.
     */
    fun fermataHits(log: List<LogRow>, from: Long, to: Long, n: Int = 5): List<CountedSong> {
        val plays = log.filter { it.type == LogRow.PLAY }.sortedBy { it.ts }
        val firstPlay = LinkedHashMap<String, LogRow>()
        for (r in plays) firstPlay.putIfAbsent(r.trackId, r)
        val out = ArrayList<CountedSong>()
        for ((id, first) in firstPlay) {
            if (first.ts !in from until to) continue
            if (first.source != "radio" && first.source != "discover") continue
            val again = plays.count { it.trackId == id && it.ts > first.ts && it.ts < to }
            if (again >= 1) out.add(CountedSong(id, first.title, first.artist, first.thumbnailUrl, again + 1))
        }
        return out.sortedByDescending { it.count }.take(n)
    }

    /** "that's 3 full days" or "that's 41 movies" (a 2-hour film), or null under 2 hours. */
    fun funComparison(ms: Long): String? {
        val hours = ms / 3_600_000L
        if (hours < 2) return null
        val days = ms / 86_400_000L
        return if (days >= 2) "that's $days full days" else "that's ${hours / 2} ${if (hours / 2 == 1L) "movie" else "movies"}"
    }

    /** Weekend (Sat, Sun) and weekday listening from day totals. */
    fun weekendSplit(totals: Map<Int, Long>): Pair<Long, Long> {
        var weekend = 0L
        var weekday = 0L
        for ((k, ms) in totals) {
            val d = Days.date(k).dayOfWeek
            if (d == DayOfWeek.SATURDAY || d == DayOfWeek.SUNDAY) weekend += ms else weekday += ms
        }
        return weekend to weekday
    }

    /** The busiest and quietest day with any listening, as keys. Quietest is null with under 2 days. */
    fun busiestQuietest(totals: Map<Int, Long>, days: List<Int>): Pair<Int?, Int?> {
        val heard = days.filter { (totals[it] ?: 0L) > 0L }
        val busy = heard.maxByOrNull { totals[it] ?: 0L }
        val quiet = if (heard.size >= 2) heard.minByOrNull { totals[it] ?: 0L } else null
        return busy to quiet
    }

    /** Favourite hour from per-hour listening, or -1. */
    fun favouriteHour(hours: LongArray): Int {
        var best = -1
        var ms = 0L
        for (h in hours.indices) if (hours[h] > ms) { ms = hours[h]; best = h }
        return best
    }

    /** Longest run of days in a row with 10+ minutes in [days] (in order). */
    fun longestRun(totals: Map<Int, Long>, days: List<Int>): Int {
        var best = 0
        var run = 0
        for (k in days) {
            if ((totals[k] ?: 0L) >= DayStreak.MIN_MS) { run++; best = maxOf(best, run) } else run = 0
        }
        return best
    }
}
