package com.minim.music.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import androidx.test.core.app.ApplicationProvider
import com.minim.music.util.ArtworkUrls
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * 6.6.0: the share card draws at 1080 x 1920 in the cover's colours, for a song and a playlist,
 * with and without covers and a name. Writes the images to build/share-preview for a look. Real
 * covers are used when /tmp/share-covers holds them (the build server downloads them first).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class ShareCardRenderTest {

    private val ctx get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    private fun fakeCover(a: Int, b: Int): Bitmap {
        val bmp = Bitmap.createBitmap(400, 400, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawRect(0f, 0f, 400f, 400f, Paint().apply { shader = LinearGradient(0f, 0f, 400f, 400f, a, b, Shader.TileMode.CLAMP) })
        c.drawCircle(260f, 150f, 90f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(250, 220, 120) })
        return bmp
    }

    /** A real video frame from /tmp/share-covers, its black bars cut off like the app does. */
    private fun realCover(id: String): Bitmap? {
        val f = File("/tmp/share-covers/$id.jpg")
        if (!f.exists()) return null
        val raw = BitmapFactory.decodeFile(f.path) ?: return null
        val sq = ArtworkUrls.squareCrop(raw.width, raw.height, videoFrame = true)
        return Bitmap.createBitmap(raw, sq.left, sq.top, sq.side, sq.side)
    }

    /** The cover's main colour, roughly like the app's palette pick: the most saturated average. */
    private fun seedOf(bmp: Bitmap): Int {
        val small = Bitmap.createScaledBitmap(bmp, 24, 24, true)
        var best = Color.GRAY
        var bestScore = -1f
        val hsv = FloatArray(3)
        for (y in 0 until 24) for (x in 0 until 24) {
            val p = small.getPixel(x, y)
            Color.colorToHSV(p, hsv)
            val score = hsv[1] * hsv[2]
            if (score > bestScore) { bestScore = score; best = p }
        }
        return best or 0xFF000000.toInt()
    }

    private fun save(bmp: Bitmap, name: String) {
        val dir = File("build/share-preview").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun songCard() {
        val cover = realCover("RA4jeB5zk9A") ?: fakeCover(Color.rgb(200, 40, 90), Color.rgb(40, 20, 80))
        val pal = ShareCard.palette(seedOf(cover))
        val bmp = ShareCard.render(ctx, pal, "Suroor", "Neha Kakkar & Bilal Saeed", "Gaurab", listOf(cover), playlist = false)
        assertEquals(1080, bmp.width)
        assertEquals(1920, bmp.height)
        // background is not the card colour, and the top and bottom of the gradient differ
        assertNotEquals(bmp.getPixel(540, 40), bmp.getPixel(540, 1880))
        assertNotEquals(bmp.getPixel(540, 40), bmp.getPixel(540, (ShareCard.CARD_TOP + 900).toInt()))
        save(bmp, "song-card")
    }

    @Test fun playlistCard() {
        val ids = listOf("GWNrPJyRTcA", "em9fQdHmKh4", "RA4jeB5zk9A", "6mr4cYJ7yew")
        val fakes = listOf(
            fakeCover(Color.rgb(20, 120, 140), Color.rgb(10, 30, 60)), fakeCover(Color.rgb(230, 120, 30), Color.rgb(90, 20, 10)),
            fakeCover(Color.rgb(200, 40, 90), Color.rgb(40, 20, 80)), fakeCover(Color.rgb(240, 200, 40), Color.rgb(120, 60, 0))
        )
        val covers = ids.mapIndexed { i, id -> realCover(id) ?: fakes[i] }
        val pal = ShareCard.palette(seedOf(covers[0]))
        val bmp = ShareCard.render(ctx, pal, "Gaurab's picks", "5 songs", "Gaurab", covers, playlist = true)
        assertEquals(1080 to 1920, bmp.width to bmp.height)
        // four different tiles in the collage
        val left = ShareCard.CARD_LEFT + 56f
        val top = ShareCard.CARD_TOP + 56f
        val q = (ShareCard.CARD_W - 112f) / 4f
        val tiles = listOf(left + q to top + q, left + 3 * q to top + q, left + q to top + 3 * q, left + 3 * q to top + 3 * q)
            .map { (x, y) -> bmp.getPixel(x.toInt(), y.toInt()) }
        assertTrue(tiles.toSet().size >= 3)
        save(bmp, "playlist-card")
    }

    @Test fun noCoversNoNameStillDraws() {
        for (playlist in listOf(false, true)) {
            val bmp = ShareCard.render(ctx, ShareCard.palette(0), "केसरिया 🔥 " + "very long title ".repeat(6), "அரிஜித் சிங்", null, emptyList(), playlist)
            assertEquals(1080, bmp.width)
            save(bmp, if (playlist) "playlist-card-empty" else "song-card-empty")
        }
        // two and three covers fill the 2x2 too
        val two = ShareCard.render(ctx, ShareCard.palette(Color.rgb(30, 140, 90)), "Two", "2 songs", "G",
            listOf(fakeCover(Color.RED, Color.BLUE), fakeCover(Color.GREEN, Color.BLACK)), playlist = true)
        val three = ShareCard.render(ctx, ShareCard.palette(Color.rgb(30, 140, 90)), "Three", "3 songs", "G",
            listOf(fakeCover(Color.RED, Color.BLUE), fakeCover(Color.GREEN, Color.BLACK), fakeCover(Color.YELLOW, Color.MAGENTA)), playlist = true)
        assertEquals(1920, two.height)
        assertEquals(1920, three.height)
    }

    @Test fun greyCoverGetsAGreyGlow() {
        val pal = ShareCard.palette(Color.rgb(128, 128, 128))
        val glow = ShareCard.glowColor(pal)
        assertEquals(Color.red(glow), Color.green(glow))
    }
}
