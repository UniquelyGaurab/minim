package com.minim.music.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import com.minim.music.util.ArtworkUrls
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * 6.6.0: the lyrics card: 1080 x 1920, the words sized to fit, Original / Translation / Both, with
 * and without a cover and a name. Writes the images to build/share-preview for a look.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class LyricsCardRenderTest {

    private val ctx get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    private val hook = listOf(
        LyricsCard.Line("ਪੀਤੀ ਵੀ ਨਹੀਂ, ਪੀਤੀ ਵੀ ਨਹੀਂ", "I haven't even had a drink, not even a drink"),
        LyricsCard.Line("ਹਾਏ, ਪੀਤੀ ਵੀ ਨਹੀਂ, ਫ਼ਿਰ ਵੀ ਇਕ ਸੁਰੂਰ ਜਿਹਾ ਏ", "Oh, I haven't had a drink, and still I feel this high")
    )

    private fun realCover(id: String): Bitmap? {
        val f = File("/tmp/share-covers/$id.jpg")
        if (!f.exists()) return null
        val raw = BitmapFactory.decodeFile(f.path) ?: return null
        val sq = ArtworkUrls.squareCrop(raw.width, raw.height, videoFrame = true)
        return Bitmap.createBitmap(raw, sq.left, sq.top, sq.side, sq.side)
    }

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

    @Test fun modesPickTheWords() {
        assertEquals(listOf(hook[0].text to null, hook[1].text to null), LyricsCard.blocks(hook, LyricsCard.Mode.ORIGINAL))
        assertEquals(hook[0].translation, LyricsCard.blocks(hook, LyricsCard.Mode.TRANSLATION)[0].first)
        assertEquals(hook[1].text to hook[1].translation, LyricsCard.blocks(hook, LyricsCard.Mode.BOTH)[1])
        // a line already in English (translation the same) or with none stays as it is
        val mixed = listOf(LyricsCard.Line("Hello there", "hello there"), LyricsCard.Line("Tum ho", null))
        assertFalse(LyricsCard.hasTranslation(mixed))
        assertEquals("Tum ho" to null, LyricsCard.blocks(mixed, LyricsCard.Mode.BOTH)[1])
        assertEquals("Hello there", LyricsCard.blocks(mixed, LyricsCard.Mode.TRANSLATION)[0].first)
        assertTrue(LyricsCard.hasTranslation(hook))
    }

    @Test fun fitSizeStaysLarge() {
        // fits at the biggest size when short; shrinks step by step; never below the floor
        assertEquals(LyricsCard.MAX_SIZE, LyricsCard.fitSize(1000f) { it * 4 }, 0f)
        val s = LyricsCard.fitSize(1000f) { it * 20 }
        assertTrue(s <= 50f && s * 20 <= 1000f)
        assertEquals(LyricsCard.MIN_SIZE, LyricsCard.fitSize(10f) { it * 100 }, 0f)
    }

    @Test fun drawsAllModes() {
        val cover = realCover("RA4jeB5zk9A")
        val pal = ShareCard.palette(cover?.let { seedOf(it) } ?: Color.rgb(200, 40, 90))
        for (mode in LyricsCard.Mode.values()) {
            val bmp = LyricsCard.render(ctx, pal, hook, mode, "Suroor", "Neha Kakkar & Bilal Saeed", "Gaurab", cover)
            assertEquals(1080, bmp.width)
            assertEquals(1920, bmp.height)
            assertNotEquals(bmp.getPixel(540, 40), bmp.getPixel(540, 960))
            save(bmp, "lyrics-card-" + mode.name.lowercase())
        }
    }

    @Test fun fiveLongLinesNoCoverNoName() {
        val five = List(5) { LyricsCard.Line("Kesariya tera ishq hai piya, rang jaaun jo main haath lagaaun $it") }
        val bmp = LyricsCard.render(ctx, ShareCard.palette(0), five, LyricsCard.Mode.ORIGINAL, "Kesariya", "Arijit Singh", " ", null)
        assertEquals(1920, bmp.height)
        save(bmp, "lyrics-card-five")
        val english = listOf(LyricsCard.Line("I'm on the run, on and on"))
        save(LyricsCard.render(ctx, ShareCard.palette(Color.rgb(30, 140, 90)), english, LyricsCard.Mode.ORIGINAL, "On & On", "Cartoon", null, null), "lyrics-card-one")
    }
}
