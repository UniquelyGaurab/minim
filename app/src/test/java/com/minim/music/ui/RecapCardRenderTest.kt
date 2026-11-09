package com.minim.music.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import androidx.test.core.app.ApplicationProvider
import com.minim.music.data.stats.DayStats
import com.minim.music.data.stats.Streak
import com.minim.music.data.stats.TopArtist
import com.minim.music.data.stats.TopSong
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * 5.8.2: the redesigned card draws at 9:16 in the cover's palette, with and without a cover,
 * a photo or a personality. Also writes the images to build/recap-preview for a look.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class RecapCardRenderTest {

    private fun fakeCover(a: Int, b: Int): Bitmap {
        val bmp = Bitmap.createBitmap(400, 400, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawRect(0f, 0f, 400f, 400f, Paint().apply {
            shader = LinearGradient(0f, 0f, 400f, 400f, a, b, Shader.TileMode.CLAMP)
        })
        c.drawCircle(260f, 150f, 90f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(250, 220, 120) })
        return bmp
    }

    private val stats = DayStats(
        day = 20261108, timeMs = 2L * 3_600_000L + 14L * 60_000L, songs = 38, artists = 12,
        topSong = TopSong("t1", "Chuttamalle (From \"Devara Part 1\")", "Anirudh Ravichander, Shilpa Rao", "", 7, 1_500_000L),
        topArtist = TopArtist("Anirudh Ravichander", 3_000_000L, 14), peakHour = 21,
        streak = Streak("Anirudh Ravichander", 6), estimated = false
    )

    private fun save(bmp: Bitmap, name: String) {
        val dir = File("build/recap-preview").apply { mkdirs() }
        File(dir, name).outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test
    fun drawsStoryShapedCardInBothModes() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val persona = RecapPersonality.Result(RecapPersonality.Type.LOYALIST, "Most of your day belonged to Anirudh.", emptyList())
        val cover = fakeCover(Color.rgb(30, 60, 140), Color.rgb(200, 40, 90))
        val light = RecapCard.render(ctx, stats, Pal.build(Color.rgb(200, 40, 90), false), cover, null, persona)
        val dark = RecapCard.render(ctx, stats, Pal.build(Color.rgb(30, 60, 140), true), cover, null, persona)
        for (b in listOf(light, dark)) {
            assertEquals(RecapCard.W, b.width)
            assertEquals(RecapCard.H, b.height)
            assertEquals(9f / 16f, b.width.toFloat() / b.height, 0.001f)
        }
        // the bottom is the palette's own background
        assertEquals(Pal.build(Color.rgb(200, 40, 90), false).bg, light.getPixel(10, 1900))
        assertNotEquals(light.getPixel(10, 1900), dark.getPixel(10, 1900))
        save(light, "card-light.png")
        save(dark, "card-dark.png")
    }

    @Test
    fun drawsWithoutCoverPhotoOrPersonality() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val bare = RecapCard.render(ctx, stats.copy(topArtist = null, streak = null), Pal.build(0, false), null, null, null)
        assertEquals(RecapCard.H, bare.height)
        save(bare, "card-bare.png")
    }
}
