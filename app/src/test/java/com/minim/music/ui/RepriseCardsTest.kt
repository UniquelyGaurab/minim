package com.minim.music.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import com.minim.music.data.stats.Days
import com.minim.music.data.stats.TopSong
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.time.LocalDate
import java.time.YearMonth

/** 76: the Weekly "Receipt" and Monthly "Calendar" cards render at 9:16 with the right data, and with little data. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = android.app.Application::class)
class RepriseCardsTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private fun song(i: Int, min: Long) = TopSong("id$i", "Song number $i with a rather long title", "Artist $i", "", 3, min * 60_000L)
    private fun cover() = Bitmap.createBitmap(120, 120, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(30, 90, 160)) }

    private val fullWeek = RepriseCards.Week(
        LocalDate.of(2026, 11, 8), LocalDate.of(2026, 11, 14),
        (1..7).map { song(it, 60L - it) }, songs = 84, tapes = 6, topArtist = "Alan Walker", streak = 5, totalMs = 14 * 3_600_000L
    )

    @Test fun receiptRendersAt9by16() {
        val b = RepriseCards.receipt(ctx, fullWeek, cover(), "Gaurab")
        assertEquals(1080, b.width)
        assertEquals(1920, b.height)
        assertEquals(1080 * 16, 1920 * 9)
    }

    @Test fun receiptHeaderAndLines() {
        assertEquals("Nov 8 \u2013 14, 2026", RepriseCards.weekDates(fullWeek.start, fullWeek.end))
        assertEquals("Nov 29 \u2013 Dec 5, 2026", RepriseCards.weekDates(LocalDate.of(2026, 11, 29), LocalDate.of(2026, 12, 5)))
        assertEquals("Dec 27, 2026 \u2013 Jan 2, 2027", RepriseCards.weekDates(LocalDate.of(2026, 12, 27), LocalDate.of(2027, 1, 2)))
        assertEquals("ORDER #46", RepriseCards.orderNo(fullWeek.start))
        val subs = RepriseCards.subtotals(fullWeek).toMap()
        assertEquals("84", subs["SONGS"])
        assertEquals("6", subs["TAPES"])
        assertEquals("Alan Walker", subs["TOP ARTIST"])
        assertEquals("5 days", subs["DAY STREAK"])
        // The same week always gets the same footer and barcode
        assertEquals(RepriseCards.footer(fullWeek), RepriseCards.footer(fullWeek))
        assertTrue(RepriseCards.barcode(fullWeek).contentEquals(RepriseCards.barcode(fullWeek)))
        assertTrue(RepriseCards.barcode(fullWeek).all { it in 1..4 })
    }

    @Test fun receiptWithFewSongsNoTapesNoArtistNoCover() {
        val sparse = RepriseCards.Week(LocalDate.of(2026, 11, 8), LocalDate.of(2026, 11, 14), listOf(song(1, 12)), 1, 0, null, 1, 12 * 60_000L)
        val subs = RepriseCards.subtotals(sparse).toMap()
        assertNull("no tapes row", subs["TAPES"])
        assertNull(subs["TOP ARTIST"])
        assertEquals("1 day", subs["DAY STREAK"])
        assertFalse(RepriseCards.footer(sparse).contains("null"))
        val b = RepriseCards.receipt(ctx, sparse, null, null)
        assertEquals(1080, b.width); assertEquals(1920, b.height)
        val empty = RepriseCards.Week(LocalDate.of(2026, 11, 8), LocalDate.of(2026, 11, 14), emptyList(), 0, 0, null, 0, 0L)
        assertEquals(1920, RepriseCards.receipt(ctx, empty, null, "").height)
    }

    private fun month(today: Int?, totals: Map<Int, Long>) = RepriseCards.Month(
        YearMonth.of(2026, 11), totals, today, totals.values.sum(), song(1, 300), "JEET"
    )

    @Test fun calendarRendersAt9by16WithHeatmapAndHighlight() {
        val totals = (1..17).associate { Days.key(LocalDate.of(2026, 11, it)) to it * 600_000L }
        val m = month(Days.key(LocalDate.of(2026, 11, 17)), totals)
        val b = RepriseCards.calendar(ctx, m, Pal.build(Color.rgb(30, 90, 160), true), cover(), cover(), "Gaurab")
        assertEquals(1080, b.width); assertEquals(1920, b.height)
        assertEquals("today while the month is going", Days.key(LocalDate.of(2026, 11, 17)), RepriseCards.highlightKey(m))
        val ended = month(null, totals + (Days.key(LocalDate.of(2026, 11, 3)) to 99_000_000L))
        assertEquals("busiest day once it's over", Days.key(LocalDate.of(2026, 11, 3)), RepriseCards.highlightKey(ended))
        assertEquals("2 hours listened", RepriseCards.hoursLine(2 * 3_600_000L + 5 * 60_000L))
        assertEquals("1 hour listened", RepriseCards.hoursLine(3_600_000L))
    }

    @Test fun calendarWithSparseData() {
        val m = RepriseCards.Month(YearMonth.of(2026, 9), mapOf(Days.key(LocalDate.of(2026, 9, 2)) to 40 * 60_000L), null, 40 * 60_000L, null, null)
        val b = RepriseCards.calendar(ctx, m, Pal.build(0, false), null, null, null)
        assertEquals(1920, b.height)
        assertTrue(RepriseCards.hoursLine(m.totalMs).endsWith("listened"))
    }

    @Test fun dayNumbersReadOnEveryBoxLevel() {
        for (seed in listOf(Color.rgb(30, 90, 160), Color.rgb(240, 200, 40), 0)) for (dark in listOf(true, false)) {
            val pal = Pal.build(seed, dark)
            for (lv in 0..4) {
                val box = androidx.core.graphics.ColorUtils.compositeColors(
                    androidx.core.graphics.ColorUtils.setAlphaComponent(pal.fg, RepriseCards.boxAlpha(lv)), pal.bg or 0xFF000000.toInt())
                val ink = androidx.core.graphics.ColorUtils.compositeColors(RepriseCards.numberInk(box, false), box)
                val la = androidx.core.graphics.ColorUtils.calculateLuminance(ink) + 0.05
                val lb = androidx.core.graphics.ColorUtils.calculateLuminance(box) + 0.05
                assertTrue("level $lv", maxOf(la, lb) / minOf(la, lb) >= 4.0)
            }
        }
    }

    @Test fun squareCoversAndPhotosAreDrawnWhole() {
        // Square covers and round photos fit their slot whole at the card's final size: nothing cut
        for ((sw, sh) in listOf(720 to 720, 544 to 544, 160 to 158)) for (slot in listOf(120f, 170f)) {
            val k = RepriseCards.fitScale(sw, sh, slot, slot)
            assertTrue(sw * k <= slot + 0.01f && sh * k <= slot + 0.01f)
            assertTrue("fills it", maxOf(sw, sh) * k >= slot - 0.01f)
        }
        // A 16:9 video frame fills from the middle (its letterbox bars are what's cut)
        val k = RepriseCards.fitScale(480, 360, 170f, 170f)
        assertEquals(170f, 360 * k, 0.01f)
    }

    @Test fun theStoryUsesTheNewCardsAndKeepsTheDailyOne() {
        val main = File("src/main/java/com/minim/music/ui/MainActivity.kt").readText()
        assertTrue(main.contains("RepriseCards.receipt("))
        assertTrue(main.contains("RepriseCards.calendar("))
        assertTrue("the Daily card is unchanged", main.contains("RecapCard.build("))
    }
}
