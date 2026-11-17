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

    suspend fun period(db: LibraryDatabase, fromDay: Int, toDay: Int, cutHour: Int? = null): PeriodData {
        val plays = RepriseQueries.plays(db, fromDay, toDay, cutHour)
        val listens = RepriseQueries.listens(db, fromDay, toDay, cutHour)
        val tapePlays = try { RepriseQueries.tapePlays(db, fromDay, toDay, cutHour) } catch (_: Exception) { emptyList() }
        val tapeListens = try { RepriseQueries.tapeListens(db, fromDay, toDay, cutHour) } catch (_: Exception) { emptyList() }
        return withContext(Dispatchers.Default) {
            val music = StatsEngine.compute(fromDay, plays, listens)
            val tapes = TapeStats.compute(tapePlays, tapeListens)
            val mh = StatsEngine.hourMs(plays, listens)
            val th = TapeStats.hourMs(tapeListens)
            val stats = TapeStats.combine(music, tapes, mh, th)
            val hours = LongArray(24) { mh[it] + th[it] }
            val songDays = try { RepriseQueries.dayTotals(db, fromDay, toDay, cutHour) } catch (_: Exception) { emptyMap() }
            val tapeDays = try { RepriseQueries.tapeDayTotals(db, fromDay, toDay, cutHour) } catch (_: Exception) { emptyMap() }
            PeriodData(fromDay, toDay, stats, plays, listens, tapeListens, hours, StatsPeriods.merge(songDays, tapeDays), tapePlays)
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
        val data = period(db, from, to, cut)
        val fromMs = Days.startMs(from)
        val toMs = if (isWeek) Days.endMs(to) else RepriseClock.monthDropAt(ym)
        val prevMs = total(db, pFrom, pTo, pCut)
        val prevPlays = if (isWeek) emptyList() else try { RepriseQueries.plays(db, pFrom, pTo, pCut) } catch (_: Exception) { emptyList() }
        val prevListens = if (isWeek) emptyList() else try { RepriseQueries.listens(db, pFrom, pTo, pCut) } catch (_: Exception) { emptyList() }
        val artistsBefore = try { RepriseQueries.artistKeysBefore(db, fromMs) } catch (_: Exception) { emptySet() }
        val ranked = StatsEngine.rankSongs(data.plays, data.listens)
        val songsBefore = try { db.getTracksPlayedBefore(ranked.take(5).map { it.trackId }, fromMs) } catch (_: Exception) { emptySet() }
        val log = withContext(Dispatchers.IO) { RepriseLog.rows(bridge, toMs) }
        val kept = try { RepriseQueries.keptIds(db) } catch (_: Exception) { emptySet() }
        val sc = withContext(Dispatchers.Default) {
            RepriseScope.build(
                period, if (isWeek) RepriseClock.weekStart(start) else ym.atDay(1), data.stats, data.plays, data.listens,
                data.dayTotals, prevMs, prevPlays, prevListens, artistsBefore, songsBefore, log, kept, fromMs, toMs, data.hours
            )
        }
        return data to sc
    }
}
