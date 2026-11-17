package com.minim.music.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.time.LocalDate
import java.time.YearMonth

/** 76: the Weekly / Monthly Reprise open from what was built ahead, and show a loading state otherwise. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = android.app.Application::class)
class RepriseOpenCacheTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var now = 0L
    private val cache = RepriseOpenCache<String>(clock = { now }, liveTtlMs = 1_000L, doneTtlMs = 10_000L)
    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    @After fun tearDown() = scope.cancel()

    @Test fun openingUsesWhatWasBuiltAheadWithoutBuildingAgain() {
        var builds = 0
        val gate = CompletableDeferred<String>()
        val key = RepriseOpenCache.weekKey(LocalDate.of(2026, 11, 8))
        // Built ahead when the week drops (or Stats / the Previews open)
        val ahead = cache.obtain(scope, key, done = true) { builds++; gate.await() }
        // A tap while it's still building joins it
        val tap1 = cache.obtain(scope, key, done = true) { builds++; "again" }
        assertSame(ahead, tap1)
        gate.complete("week")
        idle()
        // A later tap gets it at once, with no build
        assertEquals("week", cache.get(key))
        val tap2 = cache.obtain(scope, key, done = true) { builds++; "again" }
        assertTrue(tap2.isCompleted)
        assertEquals("week", kotlinx.coroutines.runBlocking { tap2.await() })
        assertEquals(1, builds)
    }

    @Test fun aWeekStillGoingIsRebuiltOnceStaleButAnEndedOneIsKept() {
        val live = RepriseOpenCache.monthKey(YearMonth.of(2026, 11))
        val done = RepriseOpenCache.monthKey(YearMonth.of(2026, 10))
        cache.put(live, "nov", done = false)
        cache.put(done, "oct", done = true)
        now = 2_000L
        assertNull(cache.get(live))
        assertEquals("oct", cache.get(done))
    }

    @Test fun nothingToShowIsNotKept() {
        val key = RepriseOpenCache.weekKey(LocalDate.of(2026, 11, 15))
        cache.obtain(scope, key, done = false) { null }
        idle()
        assertNull(cache.get(key))
    }

    @Test fun aFailedBuildCanBeTriedAgain() {
        val key = "W:x"
        cache.obtain(scope, key, done = true) { throw IllegalStateException("db") }
        idle()
        assertNull(cache.building(key))
        cache.obtain(scope, key, done = true) { "ok" }
        idle()
        assertEquals("ok", cache.get(key))
    }

    @Test fun theActivityOpensFromTheCacheAndPrewarms() {
        val main = File("src/main/java/com/minim/music/ui/MainActivity.kt").readText()
        val open = main.substring(main.indexOf("private fun openPeriodReprise("), main.indexOf("private fun openBuiltPeriod("))
        assertTrue("a tap checks the kept one first", open.indexOf("periodCache.get(") in 0 until open.indexOf("showRepriseLoading("))
        assertFalse("nothing is worked out on the tap itself", open.contains("RepriseLoader.scope("))
        assertTrue(main.contains("prewarmPeriod(true, week)"))
        assertTrue(main.contains("prewarmPeriod(false, lm.atDay(1))"))
        val previews = main.substring(main.indexOf("private fun showHiddenPreviews()"))
        assertTrue(previews.indexOf("prewarmPeriod(") < previews.indexOf("openSheet"))
        val loader = File("src/main/java/com/minim/music/data/local/RepriseLoader.kt").readText()
        assertTrue("the reads run side by side", loader.contains("async(Dispatchers.IO) { RepriseLog.rows(bridge, toMs) }"))
    }

    @Test fun theLoadingStateBreathesInEveryThemeAndHasNoSpinner() {
        val ctx: Context = ApplicationProvider.getApplicationContext()
        val cover = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(200, 60, 40)) }
        for (dark in listOf(false, true)) {
            val (base, ink) = RepriseLoadingView.colors(dark, null)
            val v = RepriseLoadingView(ctx, isWeek = !dark, cover = if (dark) cover else null, base = base, ink = ink)
            assertEquals(if (dark) "Gathering your month\u2026" else "Spinning up your week\u2026", v.line.text.toString())
            assertTrue(contrast(ink, base) >= 4.5)
            v.appear(0L)
            assertTrue(v.breathing)
            for (i in 0 until v.childCount) assertFalse(v.getChildAt(i) is android.widget.ProgressBar)
        }
        assertNotNull(RepriseLoadingView.blurSmall(cover))
    }

    private fun contrast(a: Int, b: Int): Double {
        val la = androidx.core.graphics.ColorUtils.calculateLuminance(a) + 0.05
        val lb = androidx.core.graphics.ColorUtils.calculateLuminance(b) + 0.05
        return maxOf(la, lb) / minOf(la, lb)
    }
}
