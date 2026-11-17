package com.minim.music.data.local

import com.minim.music.data.fermata.FermataBridge
import com.minim.music.data.stats.DayStreak
import com.minim.music.data.stats.Days
import com.minim.music.data.stats.StatsEngine
import com.minim.music.data.stats.StatsFormat
import com.minim.music.data.stats.StatsInsights
import com.minim.music.data.stats.StatsPeriods
import com.minim.music.data.stats.TapeStats
import com.minim.music.data.stats.TodayLists
import com.minim.music.ui.StatsPeriodView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.YearMonth

/**
 * 6.9.0: loads what one Stats pill shows (Today, Week, Month, All time) and one heatmap month.
 * Read only. [assemble] and [numbers] are pure, so the tests cover them without a database.
 */
object StatsPeriodLoad {

    const val TOP = 5

    /** One pill's rows, its compare total and the day streak: everything but the insights. */
    class Loaded(
        val period: StatsPeriods.Period,
        val today: LocalDate,
        val from: Int,
        val to: Int,
        val data: PeriodData,
        val prevRange: Pair<Int, Int>?,
        val prevMs: Long?,
        val streak: Int
    )

    /**
     * Everything at once (tests, one-off loads). The Stats tab itself uses [load] and [insights]
     * separately, so the hero and Top 5s show before the slower insights are worked out.
     */
    suspend fun model(db: LibraryDatabase, bridge: FermataBridge?, p: StatsPeriods.Period, today: LocalDate): StatsPeriodView.Model {
        val l = load(db, p, today)
        return assemble(l, insights(db, bridge, l))
    }

    /** The fast part: the period's rows, the previous stretch's total and the day streak. */
    suspend fun load(db: LibraryDatabase, p: StatsPeriods.Period, today: LocalDate): Loaded {
        val first = if (p == StatsPeriods.Period.ALL) {
            try { RepriseQueries.firstDay(db)?.let { Days.date(it) } } catch (_: Exception) { null }
        } else null
        val (from, to) = StatsPeriods.range(p, today, first)
        val data = RepriseLoader.period(db, from, to)
        val prevRange = StatsPeriods.previousRange(p, today)
        val prevMs = prevRange?.let { try { RepriseLoader.total(db, it.first, it.second) } catch (_: Exception) { null } }
        val streak = try { streak(db, today) } catch (_: Exception) { 0 }
        return Loaded(p, today, from, to, data, prevRange, prevMs, streak)
    }

    /** The slow part: insight rows (reads Fermata's whole log). Empty when anything fails. */
    suspend fun insights(db: LibraryDatabase, bridge: FermataBridge?, l: Loaded): List<StatsInsights.Row> = try {
        val p = l.period
        val data = l.data
        val prevRange = l.prevRange
        val fromMs = Days.startMs(l.from)
        val toMs = Days.endMs(l.to)
        val top = StatsEngine.rankSongs(data.plays, data.listens).take(TOP)
        val all = p == StatsPeriods.Period.ALL
        val artistsBefore = if (all) null else try { RepriseQueries.artistKeysBefore(db, fromMs) } catch (_: Exception) { null }
        val songsBefore = if (all) null else try { db.getTracksPlayedBefore(top.map { it.trackId }, fromMs) } catch (_: Exception) { null }
        val prevPlays = prevRange?.let { try { RepriseQueries.plays(db, it.first, it.second) } catch (_: Exception) { null } }
        val prevListens = prevRange?.let { try { RepriseQueries.listens(db, it.first, it.second) } catch (_: Exception) { null } }
        val log = withContext(Dispatchers.IO) { try { RepriseLog.rows(bridge, toMs) } catch (_: Exception) { emptyList() } }
        withContext(Dispatchers.Default) {
            StatsInsights.build(
                data.plays, data.listens, data.hours, data.stats.totalMs, log, fromMs, toMs,
                artistsBefore, songsBefore, prevPlays, prevListens
            )
        }
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (_: Exception) {
        emptyList()
    }

    fun assemble(l: Loaded, insights: List<StatsInsights.Row>): StatsPeriodView.Model =
        assemble(l.period, l.today, l.data, l.prevMs, l.streak, insights)

    /** The pill's model from its rows: total, compare line, bars, top 5s and number boxes. */
    fun assemble(
        p: StatsPeriods.Period,
        today: LocalDate,
        data: PeriodData,
        prevMs: Long?,
        streak: Int,
        insights: List<StatsInsights.Row>
    ): StatsPeriodView.Model {
        val total = data.stats.totalMs
        return StatsPeriodView.Model(
            period = p,
            total = StatsPeriods.long(total),
            compare = prevMs?.let { StatsPeriods.compareLine(p, total, it) },
            bars = StatsPeriods.bars(p, today, data.dayTotals, data.hours),
            songs = StatsEngine.rankSongs(data.plays, data.listens).take(TOP),
            artists = TodayLists.artists(data.plays, data.listens).take(TOP),
            channels = TapeStats.channels(data.tapePlays, data.tapeListens).take(TOP),
            numbers = numbers(data, streak),
            insights = insights
        )
    }

    /** Songs, artists, tapes (only with tapes), day streak and peak hour. */
    fun numbers(data: PeriodData, streak: Int): List<Pair<String, String>> {
        val s = data.stats
        val out = ArrayList<Pair<String, String>>()
        out.add("Songs" to s.songs.toString())
        out.add("Artists" to s.artists.toString())
        if (s.tapes.tapes > 0) {
            out.add("Tapes" to s.tapes.tapes.toString())
            out.add("Channels" to s.tapes.channels.toString())
        }
        out.add("Day streak" to streak.toString())
        if (s.peakHour >= 0) out.add("Peak hour" to StatsFormat.hour(s.peakHour))
        return out
    }

    /** Day streak, the same rule as before (songs and tapes, 10+ min a day). */
    suspend fun streak(db: LibraryDatabase, today: LocalDate): Int {
        val t = Days.key(today)
        val from = Days.key(today.minusDays(DayStreak.MAX_DAYS.toLong()))
        val tapes = try { db.getTapeDayTotals(from, t) } catch (_: Exception) { emptyMap() }
        return DayStreak.count(t, DayStreak.merge(db.getDayTotals(from, t), tapes))
    }

    /** One heatmap month: songs and tapes per day. */
    suspend fun heat(db: LibraryDatabase, ym: YearMonth): Map<Int, Long> {
        val from = Days.key(ym.atDay(1))
        val to = Days.key(ym.atEndOfMonth())
        val a = try { RepriseQueries.dayTotals(db, from, to) } catch (_: Exception) { emptyMap() }
        val b = try { RepriseQueries.tapeDayTotals(db, from, to) } catch (_: Exception) { emptyMap() }
        return StatsPeriods.merge(a, b)
    }
}
