package com.minim.music.perf

import android.content.ContentValues
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.minim.music.data.fermata.FermataBridge
import com.minim.music.data.fermata.FermataStore
import com.minim.music.data.local.LibraryDatabase
import com.minim.music.data.local.RepriseLoader
import com.minim.music.data.local.RepriseLog
import com.minim.music.data.local.StatsPeriodLoad
import com.minim.music.data.local.TestLibraryFiles
import com.minim.music.data.stats.Days
import com.minim.music.data.stats.RepriseScope
import com.minim.music.data.stats.StatsPeriods
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate

/**
 * 6.9.0 b78: time limits on the big fixture (a year of plays, 10k+ Fermata events, tapes), and the
 * caches that make them: past days are read once, Fermata's log is only read for what's new.
 * The limits are for this machine's JVM; the phone is slower, so they're kept well under the goal.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StatsPerfBudgetTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var db: LibraryDatabase
    private lateinit var bridge: FermataBridge
    private val today = LocalDate.now()

    @Before fun setUp() {
        TestLibraryFiles.wipe(context)
        context.getSharedPreferences(FermataStore.STATE_PREFS, Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences(FermataStore.DEVICE_PREFS, Context.MODE_PRIVATE).edit().clear().commit()
        RepriseLoader.forget()
        RepriseLog.forget()
        db = LibraryDatabase(context)
        bridge = FermataBridge(context, db, "perf", deviceId = "perfphone")
    }

    @After fun tearDown() {
        runCatching { db.close() }
        RepriseLoader.forget()
        RepriseLog.forget()
        TestLibraryFiles.wipe(context)
    }

    private inline fun <T> ms(block: () -> T): Pair<T, Long> {
        val t = System.nanoTime()
        val v = block()
        return v to (System.nanoTime() - t) / 1_000_000
    }

    @Test fun pillsAreUnderBudgetColdAndWarm() = runBlocking {
        val f = PerfFixture.fill(db, today)
        assertTrue(f.events >= 10_000)
        val cold = StatsPeriods.Period.values().associateWith { p -> ms { StatsPeriodLoad.load(db, p, today) }.second }
        val warm = StatsPeriods.Period.values().associateWith { p -> ms { StatsPeriodLoad.load(db, p, today) }.second }
        for (p in StatsPeriods.Period.values()) {
            assertTrue("$p cold ${cold[p]} ms", cold[p]!! < 500)
            assertTrue("$p warm ${warm[p]} ms", warm[p]!! < 100)
        }
    }

    @Test fun insightsAndTheRepriseBuildsAreUnderBudget() = runBlocking {
        PerfFixture.fill(db, today)
        val l = StatsPeriodLoad.load(db, StatsPeriods.Period.ALL, today)
        StatsPeriodLoad.insights(db, bridge, l) // first read of Fermata's log
        val (_, ins) = ms { StatsPeriodLoad.insights(db, bridge, l) }
        assertTrue("insights again $ins ms", ins < 300)
        val (_, day) = ms { db.getDayStats(Days.key(today)) }
        assertTrue("daily $day ms", day < 300)
        val wk = today.minusDays(7)
        val mo = today.minusMonths(1).withDayOfMonth(1)
        RepriseLoader.scope(db, bridge, RepriseScope.Period.WEEK, wk)
        RepriseLoader.scope(db, bridge, RepriseScope.Period.MONTH, mo)
        val (_, w) = ms { RepriseLoader.scope(db, bridge, RepriseScope.Period.WEEK, wk) }
        val (_, m) = ms { RepriseLoader.scope(db, bridge, RepriseScope.Period.MONTH, mo) }
        assertTrue("weekly again $w ms", w < 300)
        assertTrue("monthly again $m ms", m < 300)
    }

    @Test fun pastDaysAreReadOnceAndAChangeToThemIsNoticed() = runBlocking {
        PerfFixture.fill(db, today)
        StatsPeriodLoad.load(db, StatsPeriods.Period.ALL, today)
        val reads = RepriseLoader.pastReads
        repeat(3) { StatsPeriodLoad.load(db, StatsPeriods.Period.ALL, today) }
        assertEquals(reads, RepriseLoader.pastReads)
        // today's new play shows up at once, without reading the past again
        val before = StatsPeriodLoad.load(db, StatsPeriods.Period.ALL, today).data.stats.totalMs
        db.writableDatabase.execSQL(
            "INSERT INTO listen_time(day, hour, track_id, title, artist, thumbnail_url, ms) VALUES(?,?,?,?,?,?,?)",
            arrayOf(Days.key(today), 23, "newsong", "New", "Someone", "", 600_000L)
        )
        val after = StatsPeriodLoad.load(db, StatsPeriods.Period.ALL, today).data.stats.totalMs
        assertEquals(before + 600_000L, after)
        assertEquals(reads, RepriseLoader.pastReads)
        // a change to a past day (a restore, an import) reads the past again
        db.writableDatabase.insert("plays", null, ContentValues().apply {
            put("track_id", "t1"); put("played_at", today.minusDays(40).atTime(10, 0).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli())
            put("listened_ms", 60_000L); put("tracked", 1)
        })
        StatsPeriodLoad.load(db, StatsPeriods.Period.ALL, today)
        assertEquals(reads + 1, RepriseLoader.pastReads)
    }

    @Test fun fermatasLogIsOnlyReadForWhatIsNew() = runBlocking {
        PerfFixture.fill(db, today)
        val first = RepriseLog.rows(bridge)
        val reads = RepriseLog.fullReads
        assertTrue(first.size >= 10_000)
        val (again, t) = ms { RepriseLog.rows(bridge) }
        assertEquals(first.size, again.size)
        assertTrue("again $t ms", t < 50)
        assertEquals(reads, RepriseLog.fullReads)
        // clearing history deletes rows: seen, and read again from the start
        val at = System.currentTimeMillis()
        bridge.fermata.clearHistory()
        val cleared = RepriseLog.rows(bridge)
        // (the fixture has a few of today's plays later than now; only those may stay)
        assertTrue("${cleared.size} rows after a clear", cleared.all { it.ts >= at - 1_000 } && cleared.size < 40)
    }
}
