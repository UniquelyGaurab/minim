package com.minim.music.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import android.os.Looper
import java.io.File

/** 76: Reprise story images load reliably (late arrival, fallbacks, prefetch) and are never clipped. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = android.app.Application::class)
class StoryArtTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val gates = HashMap<String, CompletableDeferred<Bitmap?>>()
    private val asked = mutableListOf<String>()
    private val memory = HashMap<String, Bitmap>()

    private fun gate(url: String) = gates.getOrPut(url) { CompletableDeferred() }

    private val art = StoryArt(
        scope,
        load = { u, _ -> asked += u; gate(u).await() },
        cached = { u, px -> memory["$u@$px"] },
        show = { v, b -> v.setImageBitmap(b) }
    )

    private fun bmp() = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
    private fun idle() = shadowOf(Looper.getMainLooper()).idle()
    private fun shown(v: ImageView): Bitmap? = (v.drawable as? BitmapDrawable)?.bitmap

    @After fun tearDown() = scope.cancel()

    @Test fun aSlideThatBindsBeforeItsImageIsUpdatedWhenItLands() {
        val img = ImageView(ctx)
        art.bind(img, listOf("https://a/cover.jpg"), 480)
        idle()
        assertNull("nothing yet, just the soft colour", img.drawable)
        assertTrue(art.pending(img))
        val b = bmp()
        gate("https://a/cover.jpg").complete(b)
        idle()
        assertSame(b, shown(img))
        assertFalse(art.pending(img))
    }

    @Test fun aBrokenCoverFallsBackToTheSongsVideoFrame() {
        val urls = StoryArt.coverCandidates("https://lh3.googleusercontent.com/x=w120-h120", "dQw4w9WgXcQ") { it.replace("w120-h120", "w720-h720") }
        assertEquals("https://i.ytimg.com/vi/dQw4w9WgXcQ/hqdefault.jpg", urls.last())
        val img = ImageView(ctx)
        art.bind(img, urls, 480)
        idle()
        gate(urls[0]).complete(null); idle()
        gate(urls[1]).complete(null); idle()
        val frame = bmp()
        gate(urls[2]).complete(frame); idle()
        assertEquals(urls, asked)
        assertSame(frame, shown(img))
    }

    @Test fun aSongWithNoCoverUrlStillGetsItsFrame() {
        assertEquals(listOf("https://i.ytimg.com/vi/abcdefghijk/hqdefault.jpg"), StoryArt.coverCandidates("", "abcdefghijk"))
        assertTrue(StoryArt.coverCandidates("", "local:123").isEmpty())
    }

    @Test fun theLetterOnlyShowsWhenThereTrulyIsNoImage() {
        val letter = ColorDrawable(0xFF00FF00.toInt())
        val none = ImageView(ctx)
        art.bind(none, emptyList(), 480, fallback = { letter })
        assertSame(letter, none.drawable)

        val failed = ImageView(ctx)
        art.bind(failed, listOf("https://a/photo.jpg"), 480, fallback = { letter })
        idle()
        assertFalse(failed.drawable === letter)
        gate("https://a/photo.jpg").complete(null); idle()
        assertSame(letter, failed.drawable)
    }

    @Test fun aNewerBindWinsOverALateOlderImage() {
        val img = ImageView(ctx)
        art.bind(img, listOf("https://a/old.jpg"), 320)
        art.bind(img, listOf("https://a/new.jpg"), 320)
        idle()
        val fresh = bmp()
        gate("https://a/new.jpg").complete(fresh); idle()
        gate("https://a/old.jpg").complete(bmp()); idle()
        assertSame(fresh, shown(img))
    }

    @Test fun aPrefetchedImageIsSharedWithTheSlidesBind() {
        art.prefetch(listOf("https://a/next.jpg"), 720)
        idle()
        val img = ImageView(ctx)
        art.bind(img, listOf("https://a/next.jpg"), 720)
        idle()
        assertEquals("fetched once", 1, asked.count { it == "https://a/next.jpg" })
        val b = bmp()
        gate("https://a/next.jpg").complete(b); idle()
        assertSame(b, shown(img))
    }

    @Test fun anImageAlreadyInMemoryShowsAtOnce() {
        val b = bmp()
        memory["https://a/hot.jpg@480"] = b
        val img = ImageView(ctx)
        art.bind(img, listOf("https://a/hot.jpg"), 480)
        assertSame(b, shown(img))
        assertTrue(asked.isEmpty())
    }

    @Test fun decodeSizeLeavesRoomForTheScaleUps() {
        for (px in listOf(80, 192, 250, 440, 500, 700)) {
            assertTrue("$px", StoryArt.decodePx(px) >= (px * 1.12f).toInt().coerceAtMost(720))
        }
    }

    @Test fun guessCoversFitAndStayWholeWhenTheyGrowOnSmallAndTallScreens() {
        // 720x1520 at 320dpi is 360dp wide; also a narrow 320dp phone and a wide 411dp one
        for (w in listOf(320f, 360f, 411f)) {
            val t = StoryArt.guessTileDp(w)
            val row = 3 * t + 2 * 12f
            assertTrue("row fits inside the padding at $w", row <= w - 72f + 0.01f)
            // The picked one grows on its centre: what spills out each side stays inside the gap and padding
            val spill = t * (StoryArt.GUESS_GROW - 1f) / 2f
            assertTrue("grow fits the gap at $w", spill * 2 <= 12f)
            assertTrue("grow stays on screen at $w", (w - row) / 2f >= spill)
        }
        assertEquals(96f, StoryArt.guessTileDp(480f), 0.01f)
    }

    @Test fun slidesLetGrowingImagesDrawPastTheirParents() {
        val outer = FrameLayout(ctx)
        val col = LinearLayout(ctx).apply { setPadding(36, 0, 36, 0) }
        val row = LinearLayout(ctx)
        row.addView(ImageView(ctx))
        col.addView(row)
        outer.addView(col)
        StoryArt.noClip(outer)
        for (g in listOf(outer, col, row)) {
            assertFalse(g.clipChildren)
            assertFalse(g.clipToPadding)
        }
    }

    private fun src(name: String) = File("src/main/java/com/minim/music/ui/$name").readText()

    @Test fun everyStoryImageGoesThroughStoryArt() {
        val story = src("RecapStory.kt")
        assertFalse("no direct loads left in the story", story.contains("artwork.loadInto("))
        assertTrue(story.contains("StoryArt.noClip(v)"))
        assertTrue(story.contains("prefetchAfter(i)"))
        assertTrue("the card uses the same fallbacks", src("RecapCard.kt").contains("StoryArt.coverCandidates"))
    }

    @Test fun lateArtistAndChannelPhotosReachTheStory() {
        val main = src("MainActivity.kt")
        assertTrue(main.contains("story.setArtistPhoto(lateArtist)"))
        assertTrue(main.contains("story.setChannelPhoto(lateChannel)"))
        assertEquals(2, Regex("resolveRecapPhotos\\(recap[,)]").findAll(main).count())
    }

    @Suppress("unused") private val keep = View.VISIBLE
}
