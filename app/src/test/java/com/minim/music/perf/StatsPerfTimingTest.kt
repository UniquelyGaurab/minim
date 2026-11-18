package com.minim.music.perf

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.minim.music.data.fermata.FermataBridge
import com.minim.music.data.fermata.FermataStore
import com.minim.music.data.local.LibraryDatabase
import com.minim.music.data.local.RepriseLoader
import com.minim.music.data.local.RepriseLog
import com.minim.music.data.local.StatsPeriodLoad
import com.minim.music.data.local.TestLibraryFiles
import com.minim.music.data.stats.RepriseScope
import com.minim.music.data.stats.StatsPeriods
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.time.LocalDate
import java.time.YearMonth

/**
 * 6.9.0 b78: times every Stats pill, the insights, the heatmap and each Reprise build on the big
 * fixture, cold (first time) and warm (again, as a re-open or a pill switch back). The numbers go
 * to build/perf-b78.txt; [StatsPerfBudgetTest] holds the limits.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StatsPerfTimingTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var db: LibraryDatabase
    private lateinit var bridge: FermataBridge
    private val today = LocalDate.now()

    @Before fun setUp() {
        TestLibraryFiles.wipe(context)
        context.getSharedPreferences(FermataStore.STATE_PREFS, Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences(FermataStore.DEVICE_PREFS, Context.MODE_PRIVATE).edit().clear().commit()
        db = LibraryDatabase(context)
        bridge = FermataBridge(context, db, "perf", deviceId = "perfphone")
    }

    @After fun tearDown() {
        runCatching { db.close() }
        TestLibraryFiles.wipe(context)
    }

    private inline fun <T> ms(block: () -> T): Pair<T, Long> {
        val t = System.nanoTime()
        val v = block()
        return v to (System.nanoTime() - t) / 1_000_000
    }

    @Test fun timings() = runBlocking {
        val f = PerfFixture.fill(db, today)
        val out = StringBuilder()
        out.append("fixture: ${f.plays} plays, ${f.events} Fermata events, ${f.tapes} tapes\n")
        for (round in listOf("cold", "warm")) {
            for (p in StatsPeriods.Period.values()) {
                val (l, loadMs) = ms { StatsPeriodLoad.load(db, p, today) }
                val (_, insMs) = ms { StatsPeriodLoad.insights(db, bridge, l) }
                out.append("$round pill ${p.name}: load ${loadMs} ms, insights ${insMs} ms\n")
            }
            val (_, heatMs) = ms { StatsPeriodLoad.heat(db, YearMonth.from(today)) }
            val (logRows, logMs) = ms { RepriseLog.rows(bridge) }
            val (_, dayMs) = ms { db.getDayStats(com.minim.music.data.stats.Days.key(today)) }
            val (_, weekMs) = ms { RepriseLoader.scope(db, bridge, RepriseScope.Period.WEEK, today.minusDays(7)) }
            val (_, monthMs) = ms { RepriseLoader.scope(db, bridge, RepriseScope.Period.MONTH, today.minusMonths(1).withDayOfMonth(1)) }
            out.append("$round heat ${heatMs} ms, fermata log ${logMs} ms (${logRows.size} rows), daily stats ${dayMs} ms, weekly ${weekMs} ms, monthly ${monthMs} ms\n")
        }
        run {
            val from = com.minim.music.data.local.RepriseQueries.firstDay(db)!!
            val to = com.minim.music.data.stats.Days.key(today)
            val (pl, a) = ms { com.minim.music.data.local.RepriseQueries.plays(db, from, to) }
            val (li, b) = ms { com.minim.music.data.local.RepriseQueries.listens(db, from, to) }
            val (_, c) = ms { com.minim.music.data.local.RepriseQueries.tapePlays(db, from, to) }
            val (_, d) = ms { com.minim.music.data.local.RepriseQueries.tapeListens(db, from, to) }
            val (_, e) = ms { com.minim.music.data.stats.StatsEngine.compute(from, pl, li) }
            val (_, f) = ms { com.minim.music.data.local.RepriseQueries.dayTotals(db, from, to, null) }
            val (_, g) = ms { com.minim.music.data.stats.StatsEngine.rankSongs(pl, li) }
            out.append("ALL parts: plays ${a} ms (${pl.size}), listens ${b} ms (${li.size}), tapePlays $c, tapeListens $d, compute $e, dayTotals $f, rankSongs $g\n")
        }
        File("build/perf-b78.txt").writeText(out.toString())
        println(out)
    }
}
