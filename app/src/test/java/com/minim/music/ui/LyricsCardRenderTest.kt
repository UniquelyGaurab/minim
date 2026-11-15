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

    // ---- 6.7.0 revision: smaller card, smaller ending, scrolling 30/45/60 cards ----

    @Test fun cardIsSmallerWithMoreBackdrop() {
        assertEquals(1080f * 0.78f, LyricsCard.CARD_W, 0.01f)
        val side = (1080f - LyricsCard.CARD_W) / 2f
        assertTrue(side >= 118f)
        // everything inside scales with the card, text stays readable
        assertEquals(LyricsCard.CARD_W / 880f, LyricsCard.SCALE, 0.0001f)
        assertEquals(LyricsCardSpacing.MAX_SIZE * LyricsCard.SCALE, LyricsCard.MAX_SIZE, 0.01f)
        assertTrue(LyricsCard.MIN_SIZE >= 30f)
        assertTrue(LyricsCard.FOOTER_SCALE < LyricsCard.SCALE)
        // the tallest card leaves backdrop above and below: at most about two thirds of the height
        val tallest = LyricsCard.cardHeight(LyricsCard.MAX_TEXT_H)
        assertTrue(tallest <= 1920f * 0.67f)
        val r = LyricsCard.cardRect(tallest)
        assertTrue(r.top >= 300f && 1920f - r.bottom >= 300f)
        assertEquals(540f, r.centerX(), 0.5f)
        // a five-line card really draws inside those bounds
        val five = List(5) { LyricsCard.Line("Kesariya tera ishq hai piya, rang jaaun jo main haath lagaaun $it") }
        val s = LyricsCard.Scene(ctx, ShareCard.palette(Color.rgb(200, 40, 90)), five, LyricsCardOptions.Lyrics.ORIGINAL,
            "Kesariya", "Arijit Singh", "Gaurab", null, LyricsCardOptions.Background.COLOURS, false)
        assertTrue(s.card.height() <= tallest + 0.5f)
        assertTrue(s.card.width() < 1080f * 0.79f)
        s.recycle()
    }

    @Test fun quietEnding() {
        assertTrue(LyricsCard.END_MARK_SIZE in 70f..80f)   // about half of 150
        assertTrue(LyricsCard.END_NAME_SIZE < 46f * 0.7f)
        assertTrue(LyricsCard.END_NAME_ALPHA < 205)
    }

    private val longSong = (0 until 30).map { LyricsLine(20_000L + it * 3_500L, 0, "Lyric line number $it goes here") }

    @Test fun longerCardScrollsPastThePickedLines() {
        val plan = LyricsVideoPlan.plan(longSong, 2, 3, synced = true, length = LyricsCardOptions.Length.S30, songDurationMs = 200_000)!!
        val lines = plan.lineIndices.map { LyricsCard.Line(longSong[it].text) }
        val s = LyricsCard.Scene(ctx, ShareCard.palette(Color.rgb(200, 40, 90)), lines, LyricsCardOptions.Lyrics.ORIGINAL,
            "Suroor", "Neha Kakkar", "Gaurab", null, LyricsCardOptions.Background.COLOURS, video = true,
            pickedCount = plan.pickedCount, scrolls = true)
        val timing = LyricsCard.timings(plan, plan.lineIndices.map { longSong[it] })
        val bmp = Bitmap.createBitmap(1080, 1920, Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(bmp)
        s.drawFrame(c, plan, timing, 1_000)
        assertEquals(0f, s.scrollOffset, 0f)
        val early = bmp.copy(Bitmap.Config.ARGB_8888, false)
        s.drawFrame(c, plan, timing, 20_000)
        assertTrue(s.scrollOffset > 0f)
        assertFalse(early.sameAs(bmp))
        save(bmp, "lyrics-scroll-mid")
        // the window holds about four or five lines, and the card stays inside the backdrop
        assertTrue(s.windowH <= LyricsCard.MAX_TEXT_H)
        assertTrue(s.textSize <= LyricsCard.MAX_SIZE * LyricsCard.SCROLL_SIZE + 0.01f)
        assertTrue(s.textSize >= LyricsCard.MIN_SIZE)
        assertTrue(s.card.top >= 300f)
        // the ending covers the card
        s.drawFrame(c, plan, timing, plan.totalMs - 100)
        save(bmp, "lyrics-scroll-ending")
        s.recycle()
    }
}
