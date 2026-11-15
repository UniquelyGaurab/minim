package com.minim.music.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.RectF
import androidx.test.core.app.ApplicationProvider
import com.minim.music.data.model.LyricsLine
import com.minim.music.data.share.LyricsCardOptions
import com.minim.music.data.share.LyricsVideoPlan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * 6.7.0 revision 3: the 4:5 card, the footer rows pinned to the bottom, the wider-card fallback,
 * and finished lines dimming back down on animated cards.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class LyricsCardLayoutTest {

    private val ctx get() = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val pal = ShareCard.palette(Color.rgb(200, 40, 90))

    private val sample = listOf(
        LyricsCard.Line("Kesariya tera ishq hai piya", "Your love is saffron, beloved"),
        LyricsCard.Line("Rang jaaun jo main haath lagaaun", "I'd take its colour if I touched it"),
        LyricsCard.Line("Din beete saara teri fikr mein", "My whole day goes by worrying about you"),
        LyricsCard.Line("Rain saari teri khair manaaun", "All night I pray that you're well"),
        LyricsCard.Line("Pyaar ki bhatti mein pak gaya", "Baked in the furnace of love")
    )
    /** Lines after the picked ones on a scrolling card. */
    private val after = (0 until 8).map { LyricsCard.Line("Then this line comes along $it", "And this is what it means $it") }

    private fun scene(
        lines: List<LyricsCard.Line>, mode: LyricsCardOptions.Lyrics, video: Boolean, sharer: String? = "Gaurab",
        title: String = "Kesariya", artist: String = "Arijit Singh", scrolls: Boolean = false, picked: Int = lines.size
    ) = LyricsCard.Scene(ctx, pal, lines, mode, title, artist, sharer, null, LyricsCardOptions.Background.COLOURS,
        video, pickedCount = picked, scrolls = scrolls)

    private fun assertFourFive(s: LyricsCard.Scene, what: String) {
        assertEquals("4:5 $what", s.card.width() * 1.25f, s.card.height(), 1f)
        assertEquals("centred $what", 540f, s.card.centerX(), 0.5f)
        assertEquals("centred $what", 960f, s.card.centerY(), 0.5f)
        assertTrue("width $what", s.card.width() / 1080f in 0.679f..0.801f)
    }

    @Test fun cardIsAlwaysFourFive() {
        var checked = 0
        for (n in 1..5) for (mode in LyricsCardOptions.Lyrics.values()) {
            val lines = sample.take(n)
            // static, animated Fit
            for (video in listOf(false, true)) {
                val s = scene(lines, mode, video)
                assertFourFive(s, "n=$n $mode video=$video")
                assertEquals(0.68f, s.cardShare, 0.0001f)
                s.recycle(); checked++
            }
            // static without a name
            scene(lines, mode, false, sharer = null).also { assertFourFive(it, "blank n=$n $mode"); it.recycle(); checked++ }
            // animated 30s / 45s / 60s: the picked lines, then more that scroll in
            for (len in listOf(LyricsCardOptions.Length.S30, LyricsCardOptions.Length.S45, LyricsCardOptions.Length.S60)) {
                val s = scene(lines + after, mode, true, scrolls = true, picked = n)
                assertFourFive(s, "n=$n $mode $len")
                // the scrolling window fills the lyric space
                assertEquals(s.lyricSpace, s.windowH, 0.01f)
                s.recycle(); checked++
            }
        }
        assertEquals(5 * 3 * 6, checked)
        // blurred background: same card
        val cover = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(40, 90, 160)) }
        val blur = LyricsCard.Scene(ctx, pal, sample.take(3), LyricsCardOptions.Lyrics.BOTH, "K", "A", "Gaurab", cover,
            LyricsCardOptions.Background.BLUR, false)
        assertFourFive(blur, "blur")
    }

    @Test fun lyricsAreCentredAboveTheDivider() {
        for (n in listOf(1, 3, 5)) for (video in listOf(false, true)) {
            val s = scene(sample.take(n), LyricsCardOptions.Lyrics.ORIGINAL, video)
            val above = s.textTop - s.lyricTop
            val below = s.lyricTop + s.lyricSpace - (s.textTop + s.textHeight)
            assertEquals("n=$n", above, below, 1f)
            assertTrue(s.lyricTop + s.lyricSpace < s.footer.divider)
            s.recycle()
        }
        // one short line keeps the biggest size, it doesn't blow up
        val one = scene(listOf(LyricsCard.Line("On and on")), LyricsCardOptions.Lyrics.ORIGINAL, false)
        assertEquals(LyricsCard.MAX_SIZE, one.textSize, 0.01f)
    }

    @Test fun footerIsPinnedToTheBottom() {
        val animated = scene(sample.take(2), LyricsCardOptions.Lyrics.ORIGINAL, true)
        val static = scene(sample.take(2), LyricsCardOptions.Lyrics.ORIGINAL, false)
        val blank = scene(sample.take(2), LyricsCardOptions.Lyrics.ORIGINAL, false, sharer = "  ")
        val five = scene(sample, LyricsCardOptions.Lyrics.BOTH, false)
        val scroll = scene(sample.take(2) + after, LyricsCardOptions.Lyrics.ORIGINAL, true, scrolls = true, picked = 2)
        // minim: the same spot and size on every card, in the bottom-right corner
        val ref = animated
        for (s in listOf(animated, static, blank, five, scroll)) {
            assertEquals(s.card.bottom - LyricsCard.PAD, s.footer.markBaseline, 0.01f)
            assertEquals(s.card.right - ref.card.right, s.footer.mark.right - ref.footer.mark.right, 0.01f)
            assertEquals(ref.footer.mark.width(), s.footer.mark.width(), 0.01f)
            assertEquals(ref.footer.mark.height(), s.footer.mark.height(), 0.01f)
            assertEquals(s.card.bottom - ref.card.bottom, s.footer.mark.bottom - ref.footer.mark.bottom, 0.01f)
            assertTrue(s.footer.cover.bottom < s.card.bottom && s.footer.divider < s.footer.cover.top)
        }
        // animated and blank-name static: one row, the cover centred on minim's row
        for (s in listOf(animated, blank, scroll)) {
            assertTrue(s.oneRowFooter && s.footer.oneRow)
            assertEquals(s.footer.mark.centerY(), s.footer.cover.centerY(), 1f)
            assertNull(s.footer.sharedBy)
            assertTrue(s.footer.titleRight <= s.footer.mark.left)
        }
        // static with a name: cover row above, then "Shared by" and minim on the bottom row
        for (s in listOf(static, five)) {
            assertFalse(s.oneRowFooter)
            assertEquals("Shared by Gaurab", s.footer.sharedBy)
            assertTrue(s.footer.cover.bottom < s.footer.mark.top)
            assertTrue(s.footer.sharedByRight < s.footer.mark.left)
        }
        // all the same distance from the card's bottom: pinned, whatever the lines
        assertEquals(static.card.bottom - static.footer.divider, five.card.bottom - five.footer.divider, 0.01f)
        assertEquals(animated.card.bottom - animated.footer.divider, scroll.card.bottom - scroll.footer.divider, 0.01f)
        // the video frame during the lyrics shows no name
        assertTrue(animated.footer.sharedBy == null)
    }

    @Test fun longTitlesNeverReachMinim() {
        val longTitle = "Kesariya (From Brahmastra) Extended Unplugged Version With A Very Long Name Indeed"
        val longArtist = "Pritam, Arijit Singh, Amitabh Bhattacharya, Shreya Ghoshal and many many more"
        for (video in listOf(true, false)) for (sharer in listOf("Gaurab", null)) {
            val s = scene(sample.take(2), LyricsCardOptions.Lyrics.ORIGINAL, video, sharer = sharer, title = longTitle, artist = longArtist)
            val f = s.footer
            assertTrue("title [${f.shownTitle}] ${f.shownTitle.map { it.code }.takeLast(3)}", f.shownTitle.endsWith("…"))
            assertTrue(f.shownArtist.endsWith("…"))
            assertTrue(f.titleRight <= f.textLimit + 0.5f && f.artistRight <= f.textLimit + 0.5f)
            assertTrue(f.titleRight <= s.card.right - LyricsCard.PAD)
            if (f.oneRow) assertTrue("title ends before minim", f.titleRight < f.mark.left && f.artistRight < f.mark.left)
            val bmp = Bitmap.createBitmap(1080, 1920, Bitmap.Config.ARGB_8888)
            s.drawBase(Canvas(bmp))
            s.recycle()
        }
        val longName = scene(sample.take(1), LyricsCardOptions.Lyrics.ORIGINAL, false, sharer = "Gaurab the Extraordinarily Long Named Person")
        assertTrue(longName.footer.sharedByRight < longName.footer.mark.left)
    }

    @Test fun widerCardOnlyWhenNeeded() {
        // pure maths: fits at the floor on the 68% card -> no widening
        val roomy = LyricsCard.fitCard(false) { s, _ -> s * 4 }
        assertEquals(LyricsCard.CARD_SHARE, roomy.share, 0f)
        // text that needs a bit more room than the 68% card has at 28 px: widens just enough
        val need = LyricsCard.lyricSpace(0.72f, true)
        val tight = LyricsCard.fitCard(true) { s, w -> need * LyricsCard.textWidth(0.72f) / w * s / LyricsCard.MIN_SIZE }
        assertTrue(tight.share > LyricsCard.CARD_SHARE && tight.share <= 0.725f)
        assertEquals(LyricsCard.MIN_SIZE, tight.size, 0.01f)
        val smaller = tight.share - 0.005f
        assertTrue(need * LyricsCard.textWidth(0.72f) / LyricsCard.textWidth(smaller) > LyricsCard.lyricSpace(smaller, true))
        // never past 80%; past it the text gets smaller rather than cut
        val huge = LyricsCard.fitCard(false) { s, _ -> s * 100 }
        assertEquals(LyricsCard.MAX_CARD_SHARE, huge.share, 0.0001f)
        assertTrue(huge.size >= LyricsCard.LAST_RESORT_PX)

        // real cards: five normal lines in Both stay at 68%
        val five = scene(sample, LyricsCardOptions.Lyrics.BOTH, false)
        assertEquals(LyricsCard.CARD_SHARE, five.cardShare, 0f)
        assertTrue(five.textSize >= LyricsCard.MIN_SIZE)
        // five long lines with long translations need the wider card, still 4:5, text not below 28 px
        val long = (0 until 5).map {
            LyricsCard.Line(
                "Tujhe dekha to ye jaana sanam, pyaar hota hai deewana sanam, ab yahan se kahan jaayen hum $it",
                "When I saw you I knew, my love, that love is crazy, my love, where do we go from here now $it"
            )
        }
        val wide = scene(long, LyricsCardOptions.Lyrics.BOTH, false)
        assertTrue("share ${wide.cardShare}", wide.cardShare > LyricsCard.CARD_SHARE && wide.cardShare <= LyricsCard.MAX_CARD_SHARE)
        assertEquals(wide.card.width() * 1.25f, wide.card.height(), 1f)
        if (wide.cardShare < LyricsCard.MAX_CARD_SHARE) {
            assertEquals(LyricsCard.MIN_SIZE, wide.textSize, 0.01f)
            assertTrue(wide.textHeight <= wide.lyricSpace)
        }
        // the same lines in Original fit without widening as much
        val orig = scene(long, LyricsCardOptions.Lyrics.ORIGINAL, false)
        assertTrue(orig.cardShare <= wide.cardShare)
        save(LyricsCard.render(ctx, pal, long, LyricsCardOptions.Lyrics.BOTH, "Tujhe Dekha", "Kumar Sanu", "Gaurab", null), "rev3-wide")
    }

    // ---- only the active line is lit ----

    @Test fun pastFadeMaths() {
        assertEquals(0f, LyricsCard.pastFade(5_000, null), 0f)
        assertEquals(0f, LyricsCard.pastFade(4_000, 5_000), 0f)
        assertEquals(0f, LyricsCard.pastFade(5_000, 5_000), 0f)
        assertEquals(0.5f, LyricsCard.pastFade(5_150, 5_000), 0.01f)
        assertEquals(1f, LyricsCard.pastFade(5_300, 5_000), 0f)
        val t = listOf(LyricsCard.Timing(0, 1_800, null), LyricsCard.Timing(2_000, 1_500, null), LyricsCard.Timing(3_200, 900, null))
        assertEquals(1_800L, LyricsCard.lineEndMs(t, 0))
        assertEquals(3_200L, LyricsCard.lineEndMs(t, 1))   // never after the next line starts
        assertNull(LyricsCard.lineEndMs(t, 2))              // the last line stays lit into the ending
    }

    private val same = (0 until 3).map { LyricsLine(10_000L + it * 2_500L, 0, "La la la la la") }

    private fun avgDark(bmp: Bitmap, r: RectF): Double {
        var sum = 0.0
        var n = 0
        for (y in r.top.toInt() until r.bottom.toInt()) for (x in r.left.toInt() until r.right.toInt()) {
            val p = bmp.getPixel(x, y)
            sum += 255 - (Color.red(p) + Color.green(p) + Color.blue(p)) / 3.0
            n++
        }
        return sum / n
    }

    @Test fun finishedLinesDimBackDown() {
        for (mode in listOf(LyricsCardOptions.Lyrics.ORIGINAL, LyricsCardOptions.Lyrics.BOTH, LyricsCardOptions.Lyrics.TRANSLATION)) {
            val lines = same.map { LyricsCard.Line(it.text, "Tra la la la la") }
            val plan = LyricsVideoPlan.plan(same, 0, 2, synced = true)!!
            val timing = LyricsCard.timings(plan, same)
            val s = scene(lines, mode, true)
            val bmp = Bitmap.createBitmap(1080, 1920, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            val r0 = s.lineRect(0)
            val r2 = s.lineRect(2)
            // before line 1 starts it has the dim look (line 0 may already be lighting up at 0 ms)
            s.drawFrame(c, plan, timing, 0)
            val dim = avgDark(bmp, s.lineRect(1))
            // line 0 sung through: lit
            val end0 = LyricsCard.lineEndMs(timing, 0)!!
            s.drawFrame(c, plan, timing, end0 - plan.lyricStartMs - 10)
            val lit0 = avgDark(bmp, r0)
            assertTrue("$mode lit $lit0 vs $dim", kotlin.math.abs(lit0 - dim) > 3.0)
            // halfway through dimming
            s.drawFrame(c, plan, timing, end0 - plan.lyricStartMs + LyricsCard.PAST_FADE_MS / 2)
            val half0 = avgDark(bmp, r0)
            assertTrue("$mode half", kotlin.math.abs(half0 - dim) < kotlin.math.abs(lit0 - dim) - 0.5)
            // after the fade: line 0 looks like line 2, which isn't sung yet
            s.drawFrame(c, plan, timing, end0 - plan.lyricStartMs + LyricsCard.PAST_FADE_MS + 50)
            val after0 = avgDark(bmp, r0)
            assertEquals("$mode dim again", avgDark(bmp, r2), after0, 0.5)
            assertEquals("$mode dim again", dim, after0, 0.5)
            if (mode == LyricsCardOptions.Lyrics.BOTH) save(bmp, "rev3-dim-both")
            s.recycle()
        }
    }

    @Test fun scrollingCardsDimToo() {
        val song = (0 until 20).map { LyricsLine(20_000L + it * 3_000L, 0, "Lyric line number $it") }
        val plan = LyricsVideoPlan.plan(song, 1, 2, synced = true, length = LyricsCardOptions.Length.S30, songDurationMs = 200_000)!!
        val lines = plan.lineIndices.map { LyricsCard.Line(song[it].text) }
        val timing = LyricsCard.timings(plan, plan.lineIndices.map { song[it] })
        // the first picked line is done once the second has started
        val end = LyricsCard.lineEndMs(timing, 0)!!
        assertTrue(LyricsCard.pastFade(timing[1].startMs + LyricsCard.PAST_FADE_MS, end) == 1f)
        val s = scene(lines, LyricsCardOptions.Lyrics.ORIGINAL, true, scrolls = true, picked = plan.pickedCount)
        val bmp = Bitmap.createBitmap(1080, 1920, Bitmap.Config.ARGB_8888)
        s.drawFrame(Canvas(bmp), plan, timing, 12_000)
        save(bmp, "rev3-scroll-60")
        assertFourFive(s, "scroll")
        s.recycle()
    }

    /** Pixel check images (only when asked). */
    @Test fun pixelCheckCards() {
        val dir = System.getenv("MINIM_REV3_OUT") ?: return
        val out = File(dir).apply { mkdirs() }
        fun write(b: Bitmap, n: String) = File(out, "$n.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
        write(LyricsCard.render(ctx, pal, listOf(LyricsCard.Line("I'm on the run, on and on")), LyricsCardOptions.Lyrics.ORIGINAL,
            "On & On", "Cartoon", "Gaurab", null), "static-1")
        write(LyricsCard.render(ctx, pal, sample, LyricsCardOptions.Lyrics.ORIGINAL, "Kesariya", "Arijit Singh", "Gaurab", null), "static-5")
        write(LyricsCard.render(ctx, pal, sample, LyricsCardOptions.Lyrics.BOTH, "Kesariya", "Arijit Singh", "Gaurab", null), "static-5-both")
        write(LyricsCard.render(ctx, pal, sample.take(1), LyricsCardOptions.Lyrics.ORIGINAL, "Kesariya", "Arijit Singh", null, null), "static-1-noname")
        val plan = LyricsVideoPlan.plan(same, 0, 2, synced = true)!!
        val s = scene(same.map { LyricsCard.Line(it.text) }, LyricsCardOptions.Lyrics.ORIGINAL, true)
        val bmp = Bitmap.createBitmap(1080, 1920, Bitmap.Config.ARGB_8888)
        s.drawFrame(Canvas(bmp), plan, LyricsCard.timings(plan, same), 3_200)
        write(bmp, "animated-3")
        File(out, "geom.txt").writeText(listOf("static-1" to scene(listOf(LyricsCard.Line("I'm on the run, on and on")), LyricsCardOptions.Lyrics.ORIGINAL, false),
            "static-5" to scene(sample, LyricsCardOptions.Lyrics.ORIGINAL, false), "animated" to s).joinToString("\n") { (n, x) ->
            "$n card=${x.card} size=${x.textSize} share=${x.cardShare} textTop=${x.textTop} textH=${x.textHeight} space=${x.lyricSpace} divider=${x.footer.divider} cover=${x.footer.cover} mark=${x.footer.mark} base=${x.footer.markBaseline}"
        })
    }

    private fun save(bmp: Bitmap, name: String) {
        val dir = File("build/share-preview").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
