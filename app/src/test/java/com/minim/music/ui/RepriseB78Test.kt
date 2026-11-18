package com.minim.music.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.minim.music.data.stats.ListenRow
import com.minim.music.data.stats.PlayRow
import com.minim.music.data.stats.DayStats
import com.minim.music.data.stats.Days
import com.minim.music.data.stats.RepriseClock
import com.minim.music.data.stats.RepriseScope
import com.minim.music.data.stats.StatsEngine
import com.minim.music.util.ArtworkPipeline
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicInteger

/**
 * 6.9.0 b78: card pictures keep the slide's shapes, the share button sits at thumb height and owns
 * its taps, long minute counts fit, and opening a Reprise can't wait for good.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = android.app.Application::class, qualifiers = "w360dp-h760dp-xhdpi")
class RepriseB78Test {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val zone = ZoneId.systemDefault()
    private val min = 60_000L
    private val sunday = LocalDate.of(2026, 11, 8)
    private val density get() = ctx.resources.displayMetrics.density

    private fun play(id: String, d: LocalDate, h: Int) =
        PlayRow(id, "Song $id", "Artist ${id.take(1)}", "https://img/$id.jpg", 200L, RepriseClock.at(d, h, 0, zone), 180_000L, true)

    private fun week(minutesEach: Long = 3): Pair<DayStats, RepriseScope> {
        val plays = (0L..6L).flatMap { i -> val d = sunday.plusDays(i); listOf(play("a", d, 9), play("b", d, 13), play("c$i", d, 22)) }
        val l = plays.groupBy { it.trackId }.map { (id, ps) -> ListenRow(id, "Song $id", ps.first().artist, "https://img/$id.jpg", 20, ps.size * minutesEach * min) }
        val s = StatsEngine.compute(Days.key(sunday), plays, l, zone)
        val totals = plays.groupBy { Days.key(java.time.Instant.ofEpochMilli(it.playedAt).atZone(zone).toLocalDate()) }.mapValues { (_, ps) -> ps.size * minutesEach * min }
        val fromMs = sunday.atStartOfDay(zone).toInstant().toEpochMilli()
        val sc = RepriseScope.build(
            RepriseScope.Period.WEEK, sunday, s, plays, l, totals, 0L, emptyList(), emptyList(), emptySet(), emptySet(),
            emptyList(), emptySet(), fromMs, fromMs + 7L * 24 * 3_600_000L, LongArray(24), zone
        )
        return s to sc
    }

    private fun dayOf(s: DayStats): RecapDay {
        val plays = (0L..6L).flatMap { i -> val d = sunday.plusDays(i); listOf(play("a", d, 9), play("b", d, 13), play("c$i", d, 22)) }
        val l = plays.groupBy { it.trackId }.map { (id, ps) -> ListenRow(id, "Song $id", ps.first().artist, "https://img/$id.jpg", 20, ps.size * 3 * min) }
        return RecapDay.compute(s, plays, l, emptyList(), zone)
    }

    private fun story(s: DayStats, sc: RepriseScope?): RecapStory = RecapStory(
        context = ctx, stats = s, day = dayOf(s), pal = Pal.build(0xFF3366CC.toInt(), false), pals = emptyMap(),
        artwork = ArtworkPipeline(ctx, OkHttpClient()), artistPhotoUrl = null, channelPhotoUrl = null,
        roast = "Roasted" to "gently", persona = null, startAtCard = false, soundOn = null,
        tick = {}, onBars = {}, onShare = {}, onSave = {}, onSavePlaylist = {}, onPlayTop = {},
        onOpenArtist = {}, onSound = {}, onClosed = {}, scope = sc, sharedBy = "Gaurab"
    )

    private fun lay(v: View) {
        v.measure(View.MeasureSpec.makeMeasureSpec(720, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(1520, View.MeasureSpec.EXACTLY))
        v.layout(0, 0, 720, 1520)
    }

    private fun <T : View> all(v: View, type: Class<T>, out: MutableList<T> = mutableListOf()): List<T> {
        if (type.isInstance(v)) out.add(type.cast(v)!!)
        if (v is ViewGroup) for (i in 0 until v.childCount) all(v.getChildAt(i), type, out)
        return out
    }

    private fun onScreen(v: View, root: View): IntArray {
        var x = 0; var y = 0
        var p: View? = v
        while (p != null && p !== root) { x += p.left - p.scrollX + p.translationX.toInt(); y += p.top - p.scrollY + p.translationY.toInt(); p = p.parent as? View }
        return intArrayOf(x, y, x + v.width, y + v.height)
    }

    private fun seen(v: View, root: View): Boolean {
        var x: View? = v
        while (x != null && x !== root) { if (x.visibility != View.VISIBLE || x.alpha < 0.01f) return false; x = x.parent as? View }
        return true
    }

    // ---- card pictures keep the slide's shapes ----

    @Test fun everySlidePictureCarriesItsShape() {
        val (s, sc) = week()
        val st = story(s, sc)
        for (i in st.kinds.indices) {
            st.showForTest(i)
            lay(st)
            val plain = all(st, ImageView::class.java).filter { seen(it, st) && it !is ShapedImageView && it.width > 24 * density && it !== st.shareButtonView }
                .filter { it.background != null || it.drawable is android.graphics.drawable.BitmapDrawable }
            assertTrue("${st.kinds[i]} has unshaped pictures: ${plain.size}", plain.isEmpty())
        }
        val src = File("src/main/java/com/minim/music/ui/RecapStory.kt").readText()
        assertTrue(src.contains("ShapedImageView(context)"))
    }

    // ---- the share button sits at thumb height ----

    @Test fun theShareButtonSitsAtThumbHeightAndCoversNothing() {
        val (s, sc) = week()
        val st = story(s, sc)
        for (i in st.kinds.indices) {
            st.showForTest(i)
            lay(st)
            val b = st.shareButtonView
            if (b.visibility != View.VISIBLE) continue
            val r = onScreen(b, st)
            val centreFromBottom = (1520 - (r[1] + r[3]) / 2f) / density
            assertTrue("centre $centreFromBottom dp above the bottom", centreFromBottom in 96f..112f)
            assertEquals(40f, b.width / density, 0.5f)
            for (v in all(st, View::class.java)) {
                if (v === b || v is ViewGroup || !seen(v, st) || v.width == 0 || v.height == 0) continue
                if (v !is TextView && v !is ImageView) continue
                if (v is TextView && v.text.isNullOrBlank()) continue
                if (v.parent === st) continue // the story's own chrome (bars, close, hints)
                val o = onScreen(v, st)
                val overlaps = o[0] < r[2] && o[2] > r[0] && o[1] < r[3] && o[3] > r[1]
                assertTrue("${st.kinds[i]}: ${v.javaClass.simpleName} '${(v as? TextView)?.text}' under the share button", !overlaps)
            }
        }
    }

    @Test fun theShareButtonsTapNeverMovesTheStory() {
        val (s, sc) = week()
        val st = story(s, sc)
        st.showForTest(1)
        lay(st)
        val r = onScreen(st.shareButtonView, st)
        val x = (r[0] + r[2]) / 2f
        val y = (r[1] + r[3]) / 2f
        val t = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, x, y, 0)
        val move = MotionEvent.obtain(t, t + 30, MotionEvent.ACTION_MOVE, x + 6, y - 40, 0)
        val up = MotionEvent.obtain(t, t + 60, MotionEvent.ACTION_UP, x + 6, y - 40, 0)
        assertTrue(st.onShareButton(x, y))
        st.dispatchTouchEvent(down); st.dispatchTouchEvent(move); st.dispatchTouchEvent(up)
        assertEquals(st.kinds[1], st.currentKind)
        assertEquals(0f, st.translationY, 0.01f) // no swipe-to-close drag either
        // a tap right beside it (left third) is still a tap zone, not the button
        assertTrue(!st.onShareButton(40f, y))
    }

    // ---- long minute counts fit ----

    @Test fun aLongMinuteCountFitsItsLine() {
        val tv = FitOneLineText(ctx).apply { textSize = 64f; maxLines = 1; text = "1,234,567 min" }
        tv.measure(View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.AT_MOST), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        val need = tv.paint.measureText(tv.text.toString())
        assertTrue("needs $need px, has 600", need <= 600f)
        val short = FitOneLineText(ctx).apply { textSize = 64f; maxLines = 1; text = "42 min" }
        short.measure(View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.AT_MOST), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        assertEquals(64f * density, short.textSize, 0.5f) // short text keeps its size
    }

    @Test fun aHugeWeekShowsMinutesAndNothingIsCutOff() {
        val (s, sc) = week(minutesEach = 120) // 8,400+ minutes
        val st = story(s, sc)
        for (i in st.kinds.indices) {
            st.showForTest(i)
            lay(st)
            st.settleSlide()
            lay(st)
            for (tv in all(st, TextView::class.java).filter { seen(it, st) && it.width > 0 }) {
                val t = tv.text.toString()
                assertTrue("${st.kinds[i]} says hours: $t", !Regex("\\d+ ?(hr|hrs|hours?)\\b").containsMatchIn(t))
                if (tv.maxLines == 1 && t.isNotEmpty()) {
                    val need = tv.paint.measureText(t)
                    val has = tv.width - tv.paddingLeft - tv.paddingRight
                    assertTrue("${st.kinds[i]}: '$t' needs $need px, has $has", need <= has + 2)
                }
            }
        }
    }

    // ---- opening never waits for good ----

    @Test fun openAndBuildAheadShareOneBuildWithNoDeadlock() = runBlocking {
        val cache = RepriseOpenCache<String>(clock = { 0L })
        val builds = AtomicInteger()
        val gate = CompletableDeferred<Unit>()
        val scope = kotlinx.coroutines.CoroutineScope(Dispatchers.Default)
        val ahead = cache.obtain(scope, "W:x", true) { builds.incrementAndGet(); gate.await(); "built" }
        val opens = (1..3).map { scope.async { cache.obtain(scope, "W:x", true) { builds.incrementAndGet(); "other" }.await() } }
        delay(50)
        gate.complete(Unit)
        val got = withTimeout(2_000) { opens.awaitAll() + ahead.await() }
        assertEquals(listOf("built", "built", "built", "built"), got)
        assertEquals(1, builds.get())
        // the next open is the kept copy, at once
        assertEquals("built", withTimeout(50) { cache.obtain(scope, "W:x", true) { "again" }.await() })
    }

    @Test fun theLiveRefreshNeverCancelsAReprise() {
        val main = File("src/main/java/com/minim/music/ui/MainActivity.kt").readText()
        // builds run in the activity's scope, never in the refresh's job
        assertTrue(main.contains("periodCache.obtain(lifecycleScope,"))
        // opening is bounded: it opens or says so, it never sits on the loading state
        assertTrue(main.contains("withRealTimeout(REPRISE_OPEN_TIMEOUT_MS) { prepareRecapStory(recap, dark) }"))
        assertTrue(main.contains("withRealTimeout(REPRISE_OPEN_TIMEOUT_MS) {\n                    obtainPeriod("))
        assertTrue(main.contains("Your Reprise is taking too long"))
        // pictures' colours are waited for together and only briefly
        assertTrue(main.contains("quickPals(urls, dark, palWaitMs)"))
        assertTrue(!main.contains("for (u in urls) {\n            if (u.isBlank()) continue\n            try {\n                val seed = artwork.extractAccentForUrl(u)"))
    }
}

/** 6.9.0 b78: drawn into a plain bitmap (like the share card), the pictures keep their shapes. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = android.app.Application::class)
@org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
class CardImageShapeTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()

    private fun drawn(v: ShapedImageView, w: Int, h: Int): Bitmap {
        val red = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
        v.setImageBitmap(red)
        v.scaleType = ImageView.ScaleType.CENTER_CROP
        v.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        v.layout(0, 0, w, h)
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        v.draw(c) // a plain bitmap canvas, like the share card's
        return out
    }

    @Test fun aRoundedCoverStaysRoundedOnTheCard() {
        val v = ShapedImageView(ctx).apply { shape(false, 40f) }
        val b = drawn(v, 200, 200)
        assertEquals(0, Color.alpha(b.getPixel(1, 1)))
        assertEquals(0, Color.alpha(b.getPixel(198, 198)))
        assertEquals(Color.RED, b.getPixel(100, 100))
        assertEquals(Color.RED, b.getPixel(100, 2)) // edges are kept: no cropping
        assertEquals(Color.RED, b.getPixel(2, 100))
    }

    @Test fun aRoundPhotoStaysRoundOnTheCard() {
        val v = ShapedImageView(ctx).apply { shape(true, 0f) }
        val b = drawn(v, 200, 200)
        assertEquals(0, Color.alpha(b.getPixel(20, 20)))
        assertEquals(0, Color.alpha(b.getPixel(180, 180)))
        assertEquals(Color.RED, b.getPixel(100, 100))
        assertEquals(Color.RED, b.getPixel(100, 3))
        assertEquals(Color.RED, b.getPixel(3, 100))
    }

}
