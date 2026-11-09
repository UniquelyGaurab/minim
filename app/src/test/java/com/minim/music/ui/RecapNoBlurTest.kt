package com.minim.music.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.test.core.app.ApplicationProvider
import com.minim.music.data.stats.DayStats
import com.minim.music.data.stats.TopArtist
import com.minim.music.data.stats.TopSong
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** 5.8.3 (update 55): no blurred cover anywhere in the recap, and every label reads clearly. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class RecapNoBlurTest {

    private fun src(name: String) = File("src/main/java/com/minim/music/ui/$name").readText()

    @Test fun noBlurInTheStoryOrTheCard() {
        for (f in listOf("RecapStory.kt", "RecapCard.kt", "RecapSlides.kt", "RecapDay.kt")) {
            val code = src(f).lines().filterNot { it.trim().startsWith("*") || it.trim().startsWith("//") || it.trim().startsWith("/**") }
                .joinToString("\n")
            assertFalse("$f still blurs", code.contains("SoftwareBlur"))
            assertFalse("$f still blurs", code.contains("blurred("))
            assertFalse("$f still blurs", code.contains("RenderEffect"))
            assertFalse("$f still blurs", code.contains("topBlur"))
        }
        // the recap is opened without a blurred cover
        val main = src("MainActivity.kt")
        val prep = main.substring(main.indexOf("private suspend fun prepareRecapStory"), main.indexOf("private suspend fun recapMore"))
        assertFalse(prep.contains("blurred("))
        assertFalse(main.contains("topBlur"))
    }

    private val stats = DayStats(
        day = 20261108, timeMs = 2L * 3_600_000L, songs = 30, artists = 9,
        topSong = TopSong("t1", "Suroor", "Neha Kakkar", "", 16, 1_500_000L),
        topArtist = TopArtist("Neha Kakkar", 3_000_000L, 14), peakHour = 21, streak = null, estimated = false
    )

    /** A grey-ish cover, like the one that made 5.8.2's card muddy. */
    private fun greyCover(): Bitmap {
        val bmp = Bitmap.createBitmap(400, 400, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.rgb(120, 120, 124))
        c.drawCircle(200f, 200f, 120f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(40, 40, 44) })
        return bmp
    }

    @Test fun theCardTopIsOneSolidColourFromThePalette() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        for (dark in listOf(false, true)) {
            val pal = Pal.build(Color.rgb(128, 128, 132), dark)
            val card = RecapCard.render(ctx, stats, pal, greyCover(), null, null)
            val band = RecapCard.bandColor(pal)
            // left and right margins of the top band, above and beside the cover: all one colour
            for ((x, y) in listOf(4 to 4, 1076 to 4, 4 to 640, 1076 to 640, 4 to 300, 1076 to 400)) {
                assertEquals("band at $x,$y (dark=$dark)", band, card.getPixel(x, y))
            }
            // and the bottom is the plain bg
            assertEquals(pal.bg, card.getPixel(10, 1900))
            val dir = File("build/recap-preview").apply { mkdirs() }
            File(dir, if (dark) "card-noblur-dark.png" else "card-noblur-light.png").outputStream().use {
                card.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
        }
    }

    @Test fun faintLabelsReadOnEverySlideColourInLightAndDark() {
        val seeds = listOf(0, Color.rgb(128, 128, 132), Color.rgb(200, 40, 90), Color.rgb(30, 60, 140),
            Color.rgb(250, 220, 120), Color.rgb(20, 20, 20), Color.rgb(240, 240, 240), Color.rgb(60, 160, 80))
        for (seed in seeds) for (dark in listOf(false, true)) {
            val p = Pal.build(seed, dark)
            // the story's three looks: bg/fg, swapped, and a tint of bg
            val looks = listOf(
                Triple(p.bg, p.fg, p.muted),
                Triple(p.fg, p.bg, androidx.core.graphics.ColorUtils.blendARGB(p.fg, p.bg, 0.6f)),
                Triple(androidx.core.graphics.ColorUtils.blendARGB(p.bg, p.fg, 0.12f), p.fg, p.muted)
            )
            for ((bg, fgRaw, mutedRaw) in looks) {
                val fg = RecapInk.text(bg, fgRaw)
                val tone = RecapInk.tone(bg, fg)
                val muted = RecapInk.readableOn(listOf(bg, tone), mutedRaw, fg)
                // main text: 7:1 where the colour allows it, never under 4.5:1
                val best = maxOf(RecapInk.contrast(Color.BLACK, bg or 0xFF000000.toInt()), RecapInk.contrast(Color.WHITE, bg or 0xFF000000.toInt()))
                assertTrue("text seed=$seed dark=$dark", RecapInk.contrast(fg, bg) >= minOf(RecapInk.TEXT_MIN, best) - 0.01)
                assertTrue("text seed=$seed dark=$dark", RecapInk.contrast(fg, bg) >= RecapInk.LABEL_MIN)
                assertTrue("label seed=$seed dark=$dark", RecapInk.contrast(muted, bg) >= RecapInk.LABEL_MIN - 0.01)
                assertTrue("label on shape seed=$seed dark=$dark", RecapInk.contrast(muted, tone) >= RecapInk.LABEL_MIN - 0.01)
            }
            // the card: labels on the band and on the bg
            val band = RecapCard.bandColor(p)
            val fg = RecapInk.textOn(listOf(band, p.bg), p.fg)
            val muted = RecapInk.readableOn(listOf(band, p.bg), p.muted, fg)
            assertTrue(RecapInk.contrast(muted, band) >= RecapInk.LABEL_MIN - 0.01)
            assertTrue(RecapInk.contrast(muted, p.bg) >= RecapInk.LABEL_MIN - 0.01)
            assertTrue(RecapInk.contrast(fg, p.bg) >= RecapInk.LABEL_MIN)
        }
    }
}
