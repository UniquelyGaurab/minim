package com.minim.music.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import com.minim.music.data.model.LyricsLine
import com.minim.music.data.share.LyricsCardOptions
import com.minim.music.data.share.LyricsVideoPlan
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
        assertEquals(listOf(hook[0].text to null, hook[1].text to null), LyricsCard.blocks(hook, LyricsCardOptions.Lyrics.ORIGINAL))
        assertEquals(hook[0].translation, LyricsCard.blocks(hook, LyricsCardOptions.Lyrics.TRANSLATION)[0].first)
        assertEquals(hook[1].text to hook[1].translation, LyricsCard.blocks(hook, LyricsCardOptions.Lyrics.BOTH)[1])
        // a line already in English (translation the same) or with none stays as it is
        val mixed = listOf(LyricsCard.Line("Hello there", "hello there"), LyricsCard.Line("Tum ho", null))
        assertFalse(LyricsCard.hasTranslation(mixed))
        assertEquals("Tum ho" to null, LyricsCard.blocks(mixed, LyricsCardOptions.Lyrics.BOTH)[1])
        assertEquals("Hello there", LyricsCard.blocks(mixed, LyricsCardOptions.Lyrics.TRANSLATION)[0].first)
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
        for (mode in LyricsCardOptions.Lyrics.values()) {
            val bmp = LyricsCard.render(ctx, pal, hook, mode, "Suroor", "Neha Kakkar & Bilal Saeed", "Gaurab", cover)
            assertEquals(1080, bmp.width)
            assertEquals(1920, bmp.height)
            assertNotEquals(bmp.getPixel(540, 40), bmp.getPixel(540, 960))
            save(bmp, "lyrics-card-" + mode.name.lowercase())
        }
    }

    @Test fun fiveLongLinesNoCoverNoName() {
        val five = List(5) { LyricsCard.Line("Kesariya tera ishq hai piya, rang jaaun jo main haath lagaaun $it") }
        val bmp = LyricsCard.render(ctx, ShareCard.palette(0), five, LyricsCardOptions.Lyrics.ORIGINAL, "Kesariya", "Arijit Singh", " ", null)
        assertEquals(1920, bmp.height)
        save(bmp, "lyrics-card-five")
        val english = listOf(LyricsCard.Line("I'm on the run, on and on"))
        save(LyricsCard.render(ctx, ShareCard.palette(Color.rgb(30, 140, 90)), english, LyricsCardOptions.Lyrics.ORIGINAL, "On & On", "Cartoon", null, null), "lyrics-card-one")
    }

    // ---- 6.7.0 ----

    @Test fun blurredCoverBackground() {
        val cover = realCover("RA4jeB5zk9A") ?: Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply {
            for (y in 0 until 64) for (x in 0 until 64) setPixel(x, y, Color.rgb(x * 4, 80, y * 4))
        }
        val pal = ShareCard.palette(seedOf(cover))
        val colours = LyricsCard.render(ctx, pal, hook, LyricsCardOptions.Lyrics.BOTH, "Suroor", "Neha Kakkar & Bilal Saeed", "Gaurab", cover)
        val blur = LyricsCard.render(ctx, pal, hook, LyricsCardOptions.Lyrics.BOTH, "Suroor", "Neha Kakkar & Bilal Saeed", "Gaurab", cover,
            LyricsCardOptions.Background.BLUR)
        assertEquals(1080, blur.width)
        assertNotEquals(colours.getPixel(30, 30), blur.getPixel(30, 30))
        save(blur, "lyrics-card-both-blur")
        // no cover: Blurred falls back to the cover colours
        val a = LyricsCard.render(ctx, pal, hook, LyricsCardOptions.Lyrics.ORIGINAL, "S", "A", null, null)
        val b = LyricsCard.render(ctx, pal, hook, LyricsCardOptions.Lyrics.ORIGINAL, "S", "A", null, null, LyricsCardOptions.Background.BLUR)
        assertEquals(a.getPixel(30, 30), b.getPixel(30, 30))
    }

    private val timed = listOf(
        LyricsLine(51_000, 0, "Peeti vi nahin, peeti vi nahin"),
        LyricsLine(54_200, 0, "Haaye, peeti vi nahin, phir vi ik suroor jeha ae"),
        LyricsLine(59_000, 0, "Next line")
    )

    private fun scene(sharer: String?, video: Boolean, mode: LyricsCardOptions.Lyrics = LyricsCardOptions.Lyrics.ORIGINAL,
                      lines: List<LyricsCard.Line> = timed.take(2).map { LyricsCard.Line(it.text) }) =
        LyricsCard.Scene(ctx, ShareCard.palette(Color.rgb(200, 40, 90)), lines, mode, "Suroor", "Neha Kakkar & Bilal Saeed",
            sharer, null, LyricsCardOptions.Background.COLOURS, video)

    @Test fun videoShowsSharedByOnlyInTheEnding() {
        val plan = LyricsVideoPlan.plan(timed, 0, 1, synced = true)!!
        val timing = LyricsCard.timings(plan, timed.subList(0, 2))
        fun frame(s: LyricsCard.Scene, ms: Long) = Bitmap.createBitmap(1080, 1920, Bitmap.Config.ARGB_8888).also {
            s.drawFrame(android.graphics.Canvas(it), plan, timing, ms)
        }
        val named = scene("Gaurab", video = true)
        val blank = scene(null, video = true)
        // during the lyrics a video frame is the same with or without a name: no "Shared by"
        val a = frame(named, 1_000)
        val b = frame(blank, 1_000)
        assertTrue(a.sameAs(b))
        // the ending differs: "Shared by Gaurab" under minim, just minim when blank
        val endA = frame(named, plan.lyricsMs + 900)
        val endB = frame(blank, plan.lyricsMs + 900)
        assertFalse(endA.sameAs(endB))
        assertFalse(endA.sameAs(a))
        save(a, "video-frame-lyrics")
        save(endA, "video-frame-ending")
        save(endB, "video-frame-ending-noname")
        // a still card keeps "Shared by" at the bottom
        val stillA = Bitmap.createBitmap(1080, 1920, Bitmap.Config.ARGB_8888).also { scene("Gaurab", false).drawBase(android.graphics.Canvas(it)) }
        val stillB = Bitmap.createBitmap(1080, 1920, Bitmap.Config.ARGB_8888).also { scene(null, false).drawBase(android.graphics.Canvas(it)) }
        assertFalse(stillA.sameAs(stillB))
    }

    @Test fun wordsFillAsTheSongGoes() {
        val plan = LyricsVideoPlan.plan(timed, 0, 1, synced = true)!!
        val timing = LyricsCard.timings(plan, timed.subList(0, 2))
        val s = scene("Gaurab", video = true)
        fun frame(ms: Long) = Bitmap.createBitmap(1080, 1920, Bitmap.Config.ARGB_8888).also {
            s.drawFrame(android.graphics.Canvas(it), plan, timing, ms)
        }
        val start = frame(0)
        val mid = frame(2_500)
        val late = frame(plan.lyricsMs - 1)
        assertFalse(start.sameAs(mid))
        assertFalse(mid.sameAs(late))
        s.recycle()
    }

    /** Writes every frame of a sample card for the server-made preview video (only when asked). */
    @Test fun sampleVideoFrames() {
        val dir = System.getenv("MINIM_VIDEO_FRAMES") ?: return
        val cover = realCover("RA4jeB5zk9A")
        val pal = ShareCard.palette(cover?.let { seedOf(it) } ?: Color.rgb(200, 40, 90))
        val mode = LyricsCardOptions.Lyrics.valueOf(System.getenv("MINIM_VIDEO_MODE") ?: "ORIGINAL")
        val bg = LyricsCardOptions.Background.valueOf(System.getenv("MINIM_VIDEO_BG") ?: "COLOURS")
        val lines = listOf(
            LyricsCard.Line(timed[0].text, "I haven't even had a drink, not a drink"),
            LyricsCard.Line(timed[1].text, "Oh, I haven't had a drink, and still I feel this high")
        )
        val plan = LyricsVideoPlan.plan(timed, 0, 1, synced = true)!!
        val s = LyricsCard.Scene(ctx, pal, lines, mode, "Suroor", "Neha Kakkar & Bilal Saeed", "Gaurab", cover, bg, video = true)
        val timing = LyricsCard.timings(plan, timed.subList(0, 2))
        val out = File(dir).apply { mkdirs() }
        val bmp = Bitmap.createBitmap(1080, 1920, Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(bmp)
        for (i in 0 until plan.frames) {
            s.drawFrame(c, plan, timing, plan.frameMs(i))
            File(out, String.format("f%04d.png", i)).outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 0, it) }
        }
        File(out, "plan.txt").writeText("frames=${plan.frames} fps=${LyricsVideoPlan.FPS} lyricsMs=${plan.lyricsMs}\n")
    }
}
