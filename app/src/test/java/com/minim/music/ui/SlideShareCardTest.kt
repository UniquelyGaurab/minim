package com.minim.music.ui

import android.content.Context
import android.graphics.Bitmap
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.minim.music.data.stats.DayStats
import com.minim.music.data.stats.Days
import com.minim.music.data.stats.ListenRow
import com.minim.music.data.stats.PlayRow
import com.minim.music.data.stats.RepriseClock
import com.minim.music.data.stats.RepriseScope
import com.minim.music.data.stats.StatsEngine
import com.minim.music.ui.RecapSlides.Kind
import com.minim.music.util.ArtworkPipeline
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.time.LocalDate
import java.time.ZoneId

/** 6.9.0 b77: every Reprise slide can be shared as a 9:16 card. Realme RMX1941-ish screen (720 x 1520). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = android.app.Application::class, qualifiers = "w360dp-h760dp-xhdpi")
class SlideShareCardTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val zone = ZoneId.systemDefault()
    private val min = 60_000L
    private val sunday = LocalDate.of(2026, 11, 8)
    private val monday = LocalDate.of(2026, 11, 16)

    private fun at(d: LocalDate, h: Int, m: Int = 0) = RepriseClock.at(d, h, m, zone)
    private fun play(id: String, d: LocalDate, h: Int, m: Int = 0) =
        PlayRow(id, "Song $id", "Artist ${id.take(1)}", "https://img/$id.jpg", 200L, at(d, h, m), 180_000L, true)
    private fun listens(plays: List<PlayRow>) = plays.groupBy { it.trackId }.map { (id, ps) ->
        ListenRow(id, "Song $id", ps.first().artist, "https://img/$id.jpg", 20, ps.size * 3 * min)
    }

    private val dayPlays = listOf(
        play("a", monday, 8, 5), play("b", monday, 9), play("a", monday, 10), play("c", monday, 12), play("b", monday, 13),
        play("a", monday, 18), play("d", monday, 19), play("e", monday, 20), play("c", monday, 21), play("b", monday, 21, 30),
        play("a", monday, 22), play("f", monday, 23, 10)
    )
    private val weekPlays = (0L..6L).flatMap { i ->
        val d = sunday.plusDays(i)
        listOf(play("a", d, 9), play("b", d, 13), play("a", d, 21), play("c$i", d, 22), play("d", d, 23))
    }
    private val monthPlays = (0L..29L).flatMap { i ->
        val d = LocalDate.of(2026, 11, 1).plusDays(i)
        listOf(play("a", d, 9), play("b", d, 13), play("e${i % 5}", d, 20), play("m$i", d, 22))
    }

    private class Built(val stats: DayStats, val day: RecapDay, val scope: RepriseScope?)

    private fun daily(plays: List<PlayRow>, d: LocalDate = monday): Built {
        val l = listens(plays)
        val s = StatsEngine.compute(Days.key(d), plays, l, zone)
        return Built(s, RecapDay.compute(s, plays, l, emptyList(), zone), null)
    }

    private fun period(p: RepriseScope.Period, start: LocalDate, plays: List<PlayRow>): Built {
        val l = listens(plays)
        val s = StatsEngine.compute(Days.key(start), plays, l, zone)
        val totals = plays.groupBy { Days.key(java.time.Instant.ofEpochMilli(it.playedAt).atZone(zone).toLocalDate()) }
            .mapValues { (_, ps) -> ps.size * 20 * min }
        val fromMs = start.atStartOfDay(zone).toInstant().toEpochMilli()
        val sc = RepriseScope.build(
            p, start, s, plays, l, totals, 0L, emptyList(), emptyList(), emptySet(), emptySet(),
            emptyList(), emptySet(), fromMs, fromMs + 31L * 24 * 3_600_000L, LongArray(24), zone
        )
        return Built(s, RecapDay.compute(s, plays, l, emptyList(), zone), sc)
    }

    private fun story(b: Built, shared: MutableList<Bitmap>? = null): RecapStory = RecapStory(
        context = ctx, stats = b.stats, day = b.day, pal = Pal.build(0xFF3366CC.toInt(), false), pals = emptyMap(),
        artwork = ArtworkPipeline(ctx, OkHttpClient()), artistPhotoUrl = null, channelPhotoUrl = null,
        roast = "Roasted" to "gently", persona = null, startAtCard = false, soundOn = null,
        tick = {}, onBars = {}, onShare = { shared?.add(it) }, onSave = {}, onSavePlaylist = {}, onPlayTop = {},
        onOpenArtist = {}, onSound = {}, onClosed = {}, scope = b.scope, sharedBy = "Gaurab"
    )

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun lay(v: View) {
        v.measure(View.MeasureSpec.makeMeasureSpec(720, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(1520, View.MeasureSpec.EXACTLY))
        v.layout(0, 0, 720, 1520)
    }

    private fun texts(v: View, out: MutableList<TextView> = mutableListOf()): List<TextView> {
        if (v is TextView) out.add(v)
        if (v is ViewGroup) for (i in 0 until v.childCount) texts(v.getChildAt(i), out)
        return out
    }

    /** Visible all the way up to [root] (not attached to a window, so isShown can't be used). */
    private fun seen(v: View, root: View): Boolean {
        var x: View? = v
        while (x != null && x !== root) {
            if (x.visibility != View.VISIBLE) return false
            x = x.parent as? View
        }
        return true
    }

    /** Every slide in [b]'s story makes a 9:16 card; returns the kinds it went through. */
    private fun everySlideMakesACard(b: Built): List<Kind> {
        val st = story(b)
        val kinds = st.kinds
        for (i in kinds.indices) {
            st.showForTest(i)
            lay(st)
            val still = st.slideStill()
            assertNotNull("no still for ${kinds[i]}", still)
            val card = runBlocking { st.slideCard(still) }
            assertEquals(SlideCard.W, card.width)
            assertEquals(SlideCard.H, card.height)
        }
        return kinds
    }

    @Test fun everyDailySlideMakesACard() {
        val kinds = everySlideMakesACard(daily(dayPlays))
        assertTrue(Kind.GUESS in kinds && Kind.TOP5 in kinds)
    }

    @Test fun everyWeeklySlideMakesACard() {
        val kinds = everySlideMakesACard(period(RepriseScope.Period.WEEK, sunday, weekPlays))
        assertEquals(Kind.CARD, kinds.last())
    }

    @Test fun everyMonthlySlideMakesACard() {
        val kinds = everySlideMakesACard(period(RepriseScope.Period.MONTH, LocalDate.of(2026, 11, 1), monthPlays))
        assertEquals(Kind.CARD, kinds.last())
    }

    @Test fun sparseDataDoesNotCrash() {
        everySlideMakesACard(daily(listOf(play("a", monday, 9))))
        everySlideMakesACard(period(RepriseScope.Period.WEEK, sunday, listOf(play("a", sunday, 9), play("a", sunday, 10))))
        everySlideMakesACard(period(RepriseScope.Period.MONTH, LocalDate.of(2026, 11, 1), listOf(play("a", sunday, 9))))
        // No still at all still makes a card (period, Shared by, minim)
        val card = SlideCard.render(null, 0xFF101010.toInt(), -1, 0xFF888888.toInt(), "November 2026", null,
            SlideCard.Faces(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.DEFAULT, android.graphics.Typeface.DEFAULT))
        assertEquals(SlideCard.H, card.height)
    }

    @Test fun theGuessSlideIsSharedWithItsAnswerShowing() {
        val st = story(daily(dayPlays))
        val i = st.kinds.indexOf(Kind.GUESS)
        assertTrue(i >= 0)
        st.showForTest(i)
        lay(st)
        st.slideStill()
        val all = texts(st).filter { seen(it, st) }.map { it.text.toString() }
        assertTrue("answer not shown: $all", "It was this one." in all)
        assertTrue("Song a" in all)
        // The hint is left out of the still but back on the slide after
        assertTrue(texts(st).any { it.text == "Tap the one you played most." && it.visibility == View.VISIBLE })
    }

    @Test fun animatedSlidesAreSettledAtTheirEnd() {
        val st = story(period(RepriseScope.Period.MONTH, LocalDate.of(2026, 11, 1), monthPlays))
        for (i in st.kinds.indices) {
            st.showForTest(i)
            lay(st)
            st.slideStill()
            // Nothing half-faded or still moving in on the card
            val slide = texts(st).filter { seen(it, st) && it.text.isNotEmpty() && it.tag == null }
            assertTrue("${st.kinds[i]} shows no text", slide.isNotEmpty() || st.kinds[i] == Kind.GUESS)
            val faded = slide.filter { v ->
                var x: View? = v
                var a = 1f
                while (x != null && x !== st) { a *= x.alpha; x = x.parent as? View }
                a > 0.01f && a < 0.99f && v.parent !== st // in between: half-faded
            }
            assertTrue("${st.kinds[i]} still fading: ${faded.map { it.text }}", faded.isEmpty())
        }
    }

    @Test fun periodLabels() {
        assertEquals("Monday, 16 Nov", SlideCard.dayLabel(monday))
        assertEquals("Nov 8 \u2013 14, 2026", SlideCard.weekLabel(sunday, sunday.plusDays(6)))
        assertEquals("Oct 25 \u2013 Nov 1, 2026", SlideCard.weekLabel(LocalDate.of(2026, 10, 25), LocalDate.of(2026, 11, 1)))
        assertEquals("Dec 27, 2026 \u2013 Jan 2, 2027", SlideCard.weekLabel(LocalDate.of(2026, 12, 27), LocalDate.of(2027, 1, 2)))
        assertEquals("November 2026", SlideCard.monthLabel(LocalDate.of(2026, 11, 1)))
        assertEquals("Monday, 16 Nov", story(daily(dayPlays)).periodLabel)
        assertEquals("Nov 8 \u2013 14, 2026", story(period(RepriseScope.Period.WEEK, sunday, weekPlays)).periodLabel)
        assertEquals("November 2026", story(period(RepriseScope.Period.MONTH, LocalDate.of(2026, 11, 1), monthPlays)).periodLabel)
    }

    @Test fun theSlideFitsTheCardWithNoCropping() {
        for ((w, h) in listOf(720 to 1340, 720 to 1100, 1080 to 2100, 480 to 900)) {
            val r = SlideCard.slideRect(w, h)
            assertTrue(r.left >= 0f && r.right <= SlideCard.W && r.top >= SlideCard.BOX_TOP && r.bottom <= SlideCard.BOX_BOTTOM)
            assertEquals(w.toFloat() / h, r.width() / r.height(), 0.01f)
        }
    }

    @Test fun shareButtonPausesAndTheSheetShowsTheCardThenResumes() {
        val st = story(period(RepriseScope.Period.WEEK, sunday, weekPlays))
        st.showForTest(1)
        lay(st)
        assertEquals(View.VISIBLE, st.shareButtonView.visibility)
        st.shareButtonView.performClick()
        assertTrue(st.isSharing)
        assertNotNull(st.shareSheetView)
        assertTrue(st.onBack())
        assertFalse(st.isSharing)
    }

    @Test fun theDailyCardKeepsItsOwnShareAndThePeriodCardsAreGone() {
        val st = story(daily(dayPlays))
        st.showForTest(st.kinds.lastIndex)
        assertEquals(Kind.CARD, st.currentKind)
        assertEquals(View.GONE, st.shareButtonView.visibility)
        assertFalse(File("src/main/java/com/minim/music/ui/RepriseCards.kt").exists())
        val main = File("src/main/java/com/minim/music/ui/MainActivity.kt").readText()
        assertFalse(main.contains("Receipt"))
        assertFalse(main.contains("Monthly card (Calendar)"))
        val story = File("src/main/java/com/minim/music/ui/RecapStory.kt").readText()
        assertTrue(story.contains("share this slide"))
        assertTrue(story.contains("save as playlist"))
    }
}
