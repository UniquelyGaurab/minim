package com.minim.music.data.local

import com.minim.music.data.fermata.FermataBridge
import com.minim.music.data.stats.DayStats
import com.minim.music.data.stats.Days
import com.minim.music.data.stats.ListenRow
import com.minim.music.data.stats.PlayRow
import com.minim.music.data.stats.RepriseClock
import com.minim.music.data.stats.RepriseScope
import com.minim.music.data.stats.StatsEngine
import com.minim.music.data.stats.StatsPeriods
import com.minim.music.data.stats.TapeListenRow
import com.minim.music.data.stats.TapePlayRow
import com.minim.music.data.stats.TapeStats
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.YearMonth

/** 6.9.0: one period's rows and the daily-style stats built over all of them. */
class PeriodData(
    val fromDay: Int,
    val toDay: Int,
    val stats: DayStats,
    val plays: List<PlayRow>,
    val listens: List<ListenRow>,
    val tapeListens: List<TapeListenRow>,
    /** Songs and tapes per hour of the day. */
    val hours: LongArray,
    /** Songs and tapes per day. */
    val dayTotals: Map<Int, Long>,
    val tapePlays: List<TapePlayRow> = emptyList()
)

/** 6.9.0: loads Stats periods and Weekly/Monthly Reprise scopes. Read-only. */
object RepriseLoader {

    /** 6.9.0 b78: a stretch's raw rows. */
    private class Rows(
        val plays: List<PlayRow>,
        val listens: List<ListenRow>,
        val tapePlays: List<TapePlayRow>,
        val tapeListens: List<TapeListenRow>,
        val songDays: Map<Int, Long>,
        val tapeDays: Map<Int, Long>
    )

    private class Kept(val stamp: String, val rows: Rows)

    /** Past days' rows, by database, first day and last day. Past days don't change. */
    private val past = java.util.concurrent.ConcurrentHashMap<String, Kept>()

    /** How many times past rows were read from the database (for the tests). */
    @Volatile var pastReads = 0
        private set

    fun forget() = past.clear()

    private suspend fun rows(db: LibraryDatabase, fromDay: Int, toDay: Int, cutHour: Int?): Rows = coroutineScope {
        val plays = async { RepriseQueries.plays(db, fromDay, toDay, cutHour) }
        val listens = async { RepriseQueries.listens(db, fromDay, toDay, cutHour) }
        val tapePlays = async { try { RepriseQueries.tapePlays(db, fromDay, toDay, cutHour) } catch (_: Exception) { emptyList() } }
        val tapeListens = async { try { RepriseQueries.tapeListens(db, fromDay, toDay, cutHour) } catch (_: Exception) { emptyList() } }
        val songDays = async { try { RepriseQueries.dayTotals(db, fromDay, toDay, cutHour) } catch (_: Exception) { emptyMap() } }
        val tapeDays = async { try { RepriseQueries.tapeDayTotals(db, fromDay, toDay, cutHour) } catch (_: Exception) { emptyMap() } }
        Rows(plays.await(), listens.await(), tapePlays.await(), tapeListens.await(), songDays.await(), tapeDays.await())
    }

    /**
     * What the days before [todayKey] hold, cheaply: row counts, newest rows and sums, so a
     * restore, a clear or an import (which change past days) is noticed.
     */
    private suspend fun pastStamp(db: LibraryDatabase, todayKey: Int): String = withContext(Dispatchers.IO) {
        val r = db.readableDatabase
        val start = Days.startMs(todayKey).toString()
        val day = todayKey.toString()
        fun q(sql: String, arg: String): String = try {
            r.rawQuery(sql, arrayOf(arg)).use { c -> if (c.moveToFirst()) (0 until c.columnCount).joinToString(",") { c.getString(it) ?: "-" } else "-" }
        } catch (_: Exception) { "x" }
        q("SELECT COUNT(*), IFNULL(MAX(rowid), 0), IFNULL(SUM(listened_ms), 0) FROM plays WHERE played_at < ?", start) + "|" +
            q("SELECT COUNT(*), IFNULL(SUM(ms), 0) FROM listen_time WHERE day < ?", day) + "|" +
            q("SELECT COUNT(*), IFNULL(MAX(rowid), 0) FROM tape_plays WHERE played_at < ?", start) + "|" +
            q("SELECT COUNT(*), IFNULL(SUM(ms), 0) FROM tape_listen_all WHERE day < ?", day)
    }

    private suspend fun pastRows(db: LibraryDatabase, fromDay: Int, toDay: Int, todayKey: Int): Rows {
        val key = "${System.identityHashCode(db)}:$fromDay:$toDay"
        val stamp = pastStamp(db, todayKey)
        past[key]?.takeIf { it.stamp == stamp }?.let { return it.rows }
        val r = rows(db, fromDay, toDay, null)
        pastReads++
        if (past.size > 12) past.clear()
        past[key] = Kept(stamp, r)
        return r
    }

    /** Past rows + today's rows; listens that share a song and hour are added together. */
    private fun join(a: Rows, b: Rows): Rows {
        if (b.plays.isEmpty() && b.listens.isEmpty() && b.tapePlays.isEmpty() && b.tapeListens.isEmpty()) return a
        val listens = LinkedHashMap<String, ListenRow>()
        for (l in a.listens + b.listens) {
            val k = l.trackId + "\u0000" + l.hour
            val had = listens[k]
            listens[k] = if (had == null) l else had.copy(ms = had.ms + l.ms)
        }
        val tapes = LinkedHashMap<String, TapeListenRow>()
        for (l in a.tapeListens + b.tapeListens) {
            val k = l.videoId + "\u0000" + l.hour + "\u0000" + l.speedPct
            val had = tapes[k]
            tapes[k] = if (had == null) l else had.copy(ms = had.ms + l.ms)
        }
        return Rows(a.plays + b.plays, listens.values.toList(), a.tapePlays + b.tapePlays, tapes.values.toList(), a.songDays + b.songDays, a.tapeDays + b.tapeDays)
    }

    /**
     * 6.9.0 b78: the days before today come from memory (read once, checked with a cheap stamp);
     * only today is read from the database each time. All time used to read every play of the
     * year, every time.
     */
    suspend fun period(db: LibraryDatabase, fromDay: Int, toDay: Int, cutHour: Int? = null): PeriodData {
        val todayKey = Days.key(LocalDate.now())
        val rows = when {
            cutHour != null || fromDay >= todayKey -> rows(db, fromDay, toDay, cutHour)
            toDay < todayKey -> pastRows(db, fromDay, toDay, todayKey)
            else -> join(pastRows(db, fromDay, Days.key(LocalDate.now().minusDays(1)), todayKey), rows(db, todayKey, toDay, null))
        }
        val plays = rows.plays
        val listens = rows.listens
        val tapePlays = rows.tapePlays
        val tapeListens = rows.tapeListens
        return withContext(Dispatchers.Default) {
            val music = StatsEngine.compute(fromDay, plays, listens)
            val tapes = TapeStats.compute(tapePlays, tapeListens)
            val mh = StatsEngine.hourMs(plays, listens)
            val th = TapeStats.hourMs(tapeListens)
            val stats = TapeStats.combine(music, tapes, mh, th)
            val hours = LongArray(24) { mh[it] + th[it] }
            PeriodData(fromDay, toDay, stats, plays, listens, tapeListens, hours, StatsPeriods.merge(rows.songDays, rows.tapeDays), tapePlays)
        }
    }

    /** Just the total for a stretch (songs and tapes), for comparisons and the gates. */
    suspend fun total(db: LibraryDatabase, fromDay: Int, toDay: Int, cutHour: Int? = null): Long {
        val a = try { RepriseQueries.dayTotals(db, fromDay, toDay, cutHour) } catch (_: Exception) { emptyMap() }
        val b = try { RepriseQueries.tapeDayTotals(db, fromDay, toDay, cutHour) } catch (_: Exception) { emptyMap() }
        return a.values.sum() + b.values.sum()
    }

    /** The month so far, up to now or the drop (for the teaser's gate and mosaic). */
    suspend fun monthSoFarMs(db: LibraryDatabase, ym: YearMonth): Long =
        total(db, Days.key(ym.atDay(1)), Days.key(ym.atEndOfMonth()), RepriseClock.MONTH_HOUR)

    /** The month's top covers so far, for the teaser's blurred mosaic. */
    suspend fun monthTopCovers(db: LibraryDatabase, ym: YearMonth, n: Int = 4): List<String> {
        val from = Days.key(ym.atDay(1))
        val to = Days.key(ym.atEndOfMonth())
        val plays = RepriseQueries.plays(db, from, to, RepriseClock.MONTH_HOUR)
        val listens = RepriseQueries.listens(db, from, to, RepriseClock.MONTH_HOUR)
        return StatsEngine.rankSongs(plays, listens).map { it.thumbnailUrl }.filter { it.isNotBlank() }.distinct().take(n)
    }

    fun weekRange(start: LocalDate): Triple<Int, Int, Int?> =
        Triple(Days.key(start), Days.key(start.plusDays(6)), null)

    fun monthRange(ym: YearMonth): Triple<Int, Int, Int?> =
        Triple(Days.key(ym.atDay(1)), Days.key(ym.atEndOfMonth()), RepriseClock.MONTH_HOUR)

    /** A Weekly or Monthly Reprise: its rows and its scope. */
    suspend fun scope(db: LibraryDatabase, bridge: FermataBridge?, period: RepriseScope.Period, start: LocalDate): Pair<PeriodData, RepriseScope> {
        val isWeek = period == RepriseScope.Period.WEEK
        val ym = YearMonth.from(start)
        val (from, to, cut) = if (isWeek) weekRange(RepriseClock.weekStart(start)) else monthRange(ym)
        val (pFrom, pTo, pCut) = if (isWeek) weekRange(RepriseClock.weekStart(start).minusDays(7)) else monthRange(ym.minusMonths(1))
        // 6.9.0 b76: the independent reads run side by side (they used to run one after another,
        // with Fermata's whole history last), which was most of the wait when a Reprise opened
        return coroutineScope {
        val dataD = async { period(db, from, to, cut) }
        val prevMsD = async { total(db, pFrom, pTo, pCut) }
        val prevPlaysD = async { if (isWeek) emptyList() else try { RepriseQueries.plays(db, pFrom, pTo, pCut) } catch (_: Exception) { emptyList() } }
        val prevListensD = async { if (isWeek) emptyList() else try { RepriseQueries.listens(db, pFrom, pTo, pCut) } catch (_: Exception) { emptyList() } }
        val fromMs = Days.startMs(from)
        val toMs = if (isWeek) Days.endMs(to) else RepriseClock.monthDropAt(ym)
        val artistsBeforeD = async { try { RepriseQueries.artistKeysBefore(db, fromMs) } catch (_: Exception) { emptySet() } }
        val logD = async(Dispatchers.IO) { RepriseLog.rows(bridge, toMs) }
        val keptD = async { try { RepriseQueries.keptIds(db) } catch (_: Exception) { emptySet() } }
        val data = dataD.await()
        val ranked = withContext(Dispatchers.Default) { StatsEngine.rankSongs(data.plays, data.listens) }
        val songsBefore = try { db.getTracksPlayedBefore(ranked.take(5).map { it.trackId }, fromMs) } catch (_: Exception) { emptySet() }
        val prevMs = prevMsD.await()
        val prevPlays = prevPlaysD.await()
        val prevListens = prevListensD.await()
        val artistsBefore = artistsBeforeD.await()
        val log = logD.await()
        val kept = keptD.await()
        val sc = withContext(Dispatchers.Default) {
            RepriseScope.build(
                period, if (isWeek) RepriseClock.weekStart(start) else ym.atDay(1), data.stats, data.plays, data.listens,
                data.dayTotals, prevMs, prevPlays, prevListens, artistsBefore, songsBefore, log, kept, fromMs, toMs, data.hours
            )
        }
        data to sc
        }
    }
}
