package com.minim.music.ui

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.core.graphics.ColorUtils
import androidx.test.core.app.ApplicationProvider
import com.minim.music.R
import com.minim.music.data.local.LibraryDatabase
import com.minim.music.data.local.StatsPeriodLoad
import com.minim.music.data.local.TestLibraryFiles
import com.minim.music.data.model.Track
import com.minim.music.data.stats.Days
import com.minim.music.data.stats.ListenSlice
import com.minim.music.data.stats.StatsPeriods
import com.minim.music.util.ArtworkPipeline
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.YearMonth
import java.util.concurrent.atomic.AtomicInteger

/**
 * 6.9.0 (74): build 73 showed a blank gap under the Stats pills. These load real history on
 * Android 9 for every pill and check the hero, Top 5s and boxes are on screen, visible and bound;
 * that live refreshes no longer cancel a pill's load forever; and the heatmap's day numbers.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = android.app.Application::class)
class StatsPeriodShowsTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext<Context>().apply {
        setTheme(R.style.Theme_Minim)
    }
    private var lib: LibraryDatabase? = null
    private val taps = mutableListOf<Int>()

    @Before fun clean() { TestLibraryFiles.wipe(ctx) }
    @After fun close() { lib?.close(); TestLibraryFiles.wipe(ctx) }

    private fun view(pal: Pal): StatsPeriodView {
        val host = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val v = StatsPeriodView(
            host, ArtworkPipeline(ctx, OkHttpClient()),
            StatsPeriodView.Callbacks(
                onPeriod = {}, onPlaySong = { _, _ -> }, onArtist = {}, onChannel = {},
                onDay = { taps += it }, onHeatMonth = {}, onTeaserDive = {}, onTeaserCard = {},
                tick = {}, softHaptic = {}
            )
        )
        v.restyle(pal)
        return v
    }

    private suspend fun history(): LibraryDatabase {
        val db = LibraryDatabase(ctx).also { lib = it }
        val today = LocalDate.now()
        val tracks = listOf(
            Track("aaaaaaaaaa1", "Kesariya", "Arijit Singh", 200L, "https://img/1", "https://music.youtube.com/watch?v=aaaaaaaaaa1"),
            Track("aaaaaaaaaa2", "Apna Bana Le", "Arijit Singh", 210L, "https://img/2", "https://music.youtube.com/watch?v=aaaaaaaaaa2"),
            Track("aaaaaaaaaa3", "Husn", "Anuv Jain", 220L, "https://img/3", "https://music.youtube.com/watch?v=aaaaaaaaaa3")
        )
        // today and a few earlier days (inside this week, month and all time)
        for ((i, t) in tracks.withIndex()) {
            val row = db.recordCountedPlay(t, 150_000L)
            db.addListenTime(listOf(LibraryDatabase.ListenAdd(ListenSlice(Days.key(today), 9 + i, 150_000L), t)), row, 150_000L)
        }
        return db
    }

    private fun visibleOnScreen(v: View): Boolean {
        var cur: View? = v
        while (cur != null) {
            if (cur.visibility != View.VISIBLE || cur.alpha <= 0f) return false
            cur = cur.parent as? View
        }
        return true
    }

    private fun hasBoundText(g: ViewGroup): Boolean {
        for (i in 0 until g.childCount) {
            val c = g.getChildAt(i)
            if (c is android.widget.TextView && c.text.isNotBlank() && c.visibility == View.VISIBLE && c.alpha > 0f) return true
            if (c is ViewGroup && hasBoundText(c)) return true
        }
        return false
    }

    @Test fun everyPillShowsItsHeroTopFiveAndBoxes() = runBlocking {
        val db = history()
        val today = LocalDate.now()
        val dark = Pal(0xFF000000.toInt(), 0xFFFFFFFF.toInt(), 0xFF8C8C8C.toInt())
        val v = view(dark)
        // before any data: no blank gap
        assertEquals(View.GONE, v.heroSection.visibility)
        assertEquals(View.GONE, v.numbersSection.visibility)
        for (p in StatsPeriods.Period.values()) {
            val m = StatsPeriodLoad.model(db, null, p, today)
            v.select(p)
            v.setModel(m)
            assertTrue("$p hero visible", visibleOnScreen(v.heroSection))
            assertTrue("$p hero text", v.heroText.text.isNotBlank())
            assertNotEquals("$p hero colour is not see-through", 0, android.graphics.Color.alpha(v.heroText.currentTextColor))
            assertTrue("$p top 5 visible", visibleOnScreen(v.listsSection) && hasBoundText(v.listsSection))
            assertTrue("$p boxes visible", visibleOnScreen(v.numbersSection) && hasBoundText(v.numbersSection))
            assertTrue("$p has songs", m.songs.isNotEmpty())
        }
    }

    @Test fun emptySectionsCollapse() {
        val v = view(Pal(0xFFFFFFFF.toInt(), 0xFF000000.toInt(), 0xFF8C8C8C.toInt()))
        v.setModel(StatsPeriodView.Model(StatsPeriods.Period.WEEK, "0 min", null, emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList()))
        assertEquals(View.VISIBLE, v.heroSection.visibility)
        assertEquals(View.GONE, v.listsSection.visibility)
        assertEquals(View.GONE, v.numbersSection.visibility)
        assertEquals(View.GONE, v.insightsSection.visibility)
        assertEquals(View.GONE, v.barsSection.visibility)
    }

    @Test fun liveRefreshesDoNotRestartAPillLoadForever() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val finished = AtomicInteger()
        val loads = LatestLoad<StatsPeriods.Period>(scope) { delay(200); finished.incrementAndGet() }
        // a refresh every 20 ms, far quicker than one load: build 73 never finished here
        loads.request(StatsPeriods.Period.TODAY)
        repeat(20) { delay(20); loads.request(StatsPeriods.Period.TODAY) }
        withTimeout(3_000) { while (loads.busy) delay(20) }
        assertTrue("a load landed", finished.get() >= 1)
        assertTrue("queued refreshes collapse into one more pass", finished.get() <= 4)
        // another pill does cancel the old one
        finished.set(0)
        loads.request(StatsPeriods.Period.WEEK)
        yield(); delay(20)
        loads.request(StatsPeriods.Period.MONTH)
        withTimeout(3_000) { while (loads.busy) delay(20) }
        assertEquals(1, finished.get())
        scope.cancel()
    }

    @Test fun heatmapMarksTodayAndFutureDaysCannotBeTapped() {
        val v = view(Pal(0xFF000000.toInt(), 0xFFFFFFFF.toInt(), 0xFF8C8C8C.toInt()))
        val ym = YearMonth.of(2026, 11)
        val today = 20261117
        v.todayOverride = today
        v.setHeat(ym, mapOf(20261116 to 3_600_000L, today to 600_000L, 20261120 to 900_000L))
        val grid = v.heatGridView!!
        val slots = v.heatSlots
        assertEquals(slots.indexOf(today), grid.todayIndex)
        assertEquals(17, grid.dayNumbers!![slots.indexOf(today)])
        assertEquals((1..30).toList(), grid.dayNumbers!!.filter { it > 0 })
        // a future day, even with a total, can't be tapped and draws faint
        val future = slots.indexOf(20261120)
        assertTrue(grid.future[future])
        assertFalse(v.tapHeat(future))
        assertEquals(HeatInk.FUTURE_ALPHA, grid.boxAlpha(future, 4, true))
        // past and today with listening open their Reprise
        assertTrue(v.tapHeat(slots.indexOf(20261116)))
        assertTrue(v.tapHeat(slots.indexOf(today)))
        assertFalse("an empty past day stays closed", v.tapHeat(slots.indexOf(20261101)))
        assertEquals(listOf(20261116, today), taps)
    }

    @Test fun dayNumbersStayReadableOnBrightAndFaintBoxes() {
        // Dark/Haze-dark (white text colour), Light/Haze-bright (black), and a Haze tint
        val fgs = listOf(0xFFFFFFFF.toInt(), 0xFF000000.toInt(), 0xFFF3E6D0.toInt(), 0xFF2A1F3D.toInt())
        for (fg in fgs) for (a in listOf(22, 70, 120, 180, 255)) {
            val box = HeatInk.box(fg, a)
            val ink = HeatInk.ink(fg, a)
            assertTrue("fg=${Integer.toHexString(fg)} a=$a", ColorUtils.calculateContrast(ink, box) >= 4.5)
        }
        // dark ink on a bright full box in dark contexts, light ink on its faint box
        assertEquals(HeatInk.DARK, HeatInk.ink(0xFFFFFFFF.toInt(), 255))
        assertEquals(HeatInk.LIGHT, HeatInk.ink(0xFFFFFFFF.toInt(), 22))
        assertEquals(HeatInk.LIGHT, HeatInk.ink(0xFF000000.toInt(), 255))
        assertEquals(HeatInk.DARK, HeatInk.ink(0xFF000000.toInt(), 22))
    }
}
