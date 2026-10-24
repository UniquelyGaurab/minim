package com.minim.music.ui

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** v4.9.8 part 3: the rules behind the full screen lyrics. */
class FullscreenLyricsLookTest {

    private val L = FullscreenLyricsLook

    private fun rgb(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r shl 16) or (g shl 8) or b

    @Test
    fun ink_followsTheAppsBrightnessRule() {
        // Same 0.40 line as the Artist page and the Home takeover
        assertFalse(L.inkIsDark(0.10))
        assertFalse(L.inkIsDark(0.40))
        assertTrue(L.inkIsDark(0.41))
        assertEquals(L.WHITE_INK, L.ink(false))
        assertEquals(L.DARK_INK, L.ink(true))
        assertEquals(0xFFFFFFFF.toInt(), L.WHITE_INK)
        assertEquals(0xFF121212.toInt(), L.DARK_INK)
    }

    @Test
    fun scrim_isSubtleOnDarkCovers_andStrongerOnBrightOnes() {
        val nearBlack = rgb(20, 20, 30)
        assertEquals(L.SCRIM_MIN, L.scrimAlpha(nearBlack, inkDark = false), 0.001f)
        val brightGrey = rgb(200, 200, 200)
        val a = L.scrimAlpha(brightGrey, inkDark = false)
        assertTrue(a > L.SCRIM_MIN)
        assertTrue(a <= L.SCRIM_MAX)
        // and with that wash, white text reaches the target contrast
        assertTrue(L.contrast(L.WHITE_INK, L.blend(brightGrey, L.scrimBase(false), a)) >= L.TARGET_CONTRAST)
    }

    @Test
    fun scrim_underDarkInk_isLight_andGivesContrast() {
        val midLight = rgb(170, 160, 150)
        val a = L.scrimAlpha(midLight, inkDark = true)
        val behind = L.blend(midLight, L.scrimBase(true), a)
        assertTrue(L.contrast(L.DARK_INK, behind) >= L.TARGET_CONTRAST)
        assertEquals(0xFFFFFF, L.scrimBase(true) and 0xFFFFFF)
        assertEquals(0x000000, L.scrimBase(false) and 0xFFFFFF)
    }

    @Test
    fun scrim_isCapped_soTheCoverColourSurvives() {
        // Even pure white under white text needs about half, well under the cap
        val white = L.scrimAlpha(rgb(255, 255, 255), inkDark = false)
        assertTrue(white in 0.5f..L.SCRIM_MAX)
        // Never past the cap, whatever the cover
        for (v in 0..255 step 15) for (dark in listOf(true, false)) {
            assertTrue(L.scrimAlpha(rgb(v, v, v), dark) in L.SCRIM_MIN..L.SCRIM_MAX)
        }
        val c = L.scrimColor(false, 0.5f)
        assertEquals(128, c ushr 24)
        assertEquals(0, c and 0xFFFFFF)
    }

    @Test
    fun worstPixel_takesAPercentile_notOneSpeck() {
        val px = IntArray(100) { rgb(30, 30, 30) }
        px[0] = rgb(255, 255, 255) // one speck of glare
        assertEquals(rgb(30, 30, 30), L.worstPixel(px, inkDark = false))
        val bright = IntArray(100) { rgb(230, 230, 230) }
        for (i in 0 until 20) bright[i] = rgb(90, 90, 90)
        // dark ink looks at the darker end
        assertEquals(rgb(90, 90, 90), L.worstPixel(bright, inkDark = true))
    }

    @Test
    fun luminanceAndContrast_matchWcag() {
        assertEquals(1.0, L.relativeLuminance(rgb(255, 255, 255)), 1e-6)
        assertEquals(0.0, L.relativeLuminance(rgb(0, 0, 0)), 1e-6)
        assertEquals(21.0, L.contrast(rgb(0, 0, 0), rgb(255, 255, 255)), 1e-6)
    }

    @Test
    fun blendAndLerp() {
        assertEquals(rgb(128, 128, 128), L.blend(rgb(0, 0, 0), rgb(255, 255, 255), 0.5f))
        assertEquals(rgb(10, 20, 30), L.blend(rgb(10, 20, 30), rgb(255, 255, 255), 0f))
        assertEquals(0x80FF0000.toInt(), L.lerpColor(0x00FF0000, 0xFFFF0000.toInt(), 0.5f))
        assertEquals(0xFF00FF00.toInt(), L.lerpColor(0xFF000000.toInt(), 0xFF00FF00.toInt(), 1f))
    }

    @Test
    fun centerCrop_ofASquareCoverOnATallPhone_isTheMiddleStrip() {
        val r = L.centerCropRect(96, 96, 1080, 2400)
        // 1080/2400 of the width, centred
        assertEquals(0, r[1])
        assertEquals(96, r[3])
        val w = r[2] - r[0]
        assertTrue(w in 42..44)
        assertTrue(kotlin.math.abs((96 - r[2]) - r[0]) <= 1)
        // A wide view crops top and bottom instead
        val wide = L.centerCropRect(96, 96, 2400, 1080)
        assertEquals(0, wide[0])
        assertEquals(96, wide[2])
        assertTrue(wide[3] - wide[1] in 42..44)
        // Degenerate sizes don't crash
        assertArrayEquals(intArrayOf(0, 0, 10, 10), L.centerCropRect(10, 10, 0, 0))
    }

    @Test
    fun crop_returnsOnlyTheRect() {
        val px = IntArray(16) { it }
        assertArrayEquals(intArrayOf(5, 6, 9, 10), L.crop(px, 4, intArrayOf(1, 1, 3, 3)))
    }

    @Test
    fun lines_activeIsFull_othersDimmed_untimedEven() {
        assertEquals(1f, L.lineAlpha(0, synced = true), 0f)
        assertTrue(L.lineAlpha(1, true) < 0.5f)
        assertTrue(L.lineAlpha(2, true) <= L.lineAlpha(1, true))
        assertTrue(L.lineAlpha(9, true) <= L.lineAlpha(2, true))
        assertTrue(L.lineAlpha(9, true) > 0.2f) // still readable when scrolling back
        // Untimed: the same for every line, nothing highlighted
        assertEquals(L.lineAlpha(0, false), L.lineAlpha(5, false), 0f)
        assertEquals(1f, L.lineScale(4, false), 0f)
        assertEquals(1f, L.lineScale(0, true), 0f)
        assertTrue(L.lineScale(1, true) < 1f)
        // Before the first line, line 0 is one away (dimmed, not active)
        assertEquals(1, L.distance(0, -1))
    }

    @Test
    fun scrollTarget_andCentreDelta() {
        assertEquals(0, L.scrollTarget(-1, 10))
        assertEquals(4, L.scrollTarget(4, 10))
        assertEquals(9, L.scrollTarget(20, 10))
        assertEquals(-1, L.scrollTarget(0, 0))
        // child centre 150, area centre 500 -> scroll up by 350
        assertEquals(-350, L.centreDelta(100, 200, 0, 1000))
        assertEquals(0, L.centreDelta(450, 550, 0, 1000))
    }

    @Test
    fun sidePadding_isGenerous_andCapsLineWidthOnBigScreens() {
        assertEquals(100, L.sidePadding(1080, 100, 1500))
        assertEquals(450, L.sidePadding(2400, 100, 1500))
    }

    @Test
    fun transition_phases() {
        assertEquals(1f, L.nowPlayingAlpha(0f), 0f)
        assertEquals(0f, L.nowPlayingAlpha(0.6f), 0f)
        assertEquals(0f, L.backdropAlpha(0f), 0f)
        assertEquals(1f, L.backdropAlpha(1f), 0f)
        // The lines wait until Now Playing has mostly gone, and land in place
        assertEquals(0f, L.linesAlpha(0.3f), 0f)
        assertEquals(1f, L.linesAlpha(1f), 0f)
        assertEquals(0f, L.linesLift(1f), 0f)
        assertFalse(L.chromeSwitched(0.49f))
        assertTrue(L.chromeSwitched(0.5f))
        // Monotonic, so reversing mid-way retraces the same path
        var prev = -1f
        for (i in 0..20) {
            val v = L.backdropAlpha(i / 20f)
            assertTrue(v >= prev)
            prev = v
        }
    }

    @Test
    fun blurRadius_isStrong_andBounded() {
        assertEquals(97.2f, L.blurRadiusPx(1080), 0.01f)
        assertEquals(150f, L.blurRadiusPx(4000), 0f)
        assertEquals(48f, L.blurRadiusPx(200), 0f)
    }

    // ------------------------------------------------------------------------------------------

    @Test
    fun softwareBlur_keepsAFlatImageFlat() {
        val px = IntArray(20 * 10) { rgb(40, 80, 120) }
        assertArrayEquals(px, SoftwareBlur.blur(px, 20, 10, 3))
    }

    @Test
    fun softwareBlur_spreadsADot_andKeepsAlpha() {
        val w = 21
        val h = 21
        val px = IntArray(w * h) { rgb(0, 0, 0) }
        px[10 * w + 10] = rgb(255, 255, 255)
        val out = SoftwareBlur.blur(px, w, h, 2)
        val centre = out[10 * w + 10]
        val near = out[10 * w + 12]
        assertTrue((centre and 0xFF) < 255)
        assertTrue((near and 0xFF) > 0 || (centre and 0xFF) > 0)
        assertTrue((centre and 0xFF) >= (near and 0xFF))
        assertTrue(out.all { it ushr 24 == 0xFF })
        // symmetric
        assertEquals(out[10 * w + 8], out[10 * w + 12])
        assertEquals(out[8 * w + 10], out[12 * w + 10])
    }

    @Test
    fun softwareBlur_radiusZeroIsACopy_andTinyImagesWork() {
        val px = intArrayOf(rgb(1, 2, 3), rgb(4, 5, 6))
        assertArrayEquals(px, SoftwareBlur.blur(px, 2, 1, 0))
        assertEquals(2, SoftwareBlur.blur(px, 2, 1, 5).size)
    }

    // ------------------------------------------------------------------------------------------
    // Source checks: wiring that only runs on a phone
    // ------------------------------------------------------------------------------------------

    private fun src(name: String): String {
        val rel = "src/main/java/com/minim/music/ui/$name"
        return listOf(File(rel), File("app/$rel")).first { it.isFile }.readText()
    }

    @Test
    fun oldLyricsScreenIsGone_andTheHookOpensTheNewMode() {
        val main = src("MainActivity.kt")
        assertFalse(main.contains("R.id.screen_lyrics"))
        assertFalse(main.contains("lyControls"))
        assertFalse(main.contains("LyricsView"))
        val hook = main.substringAfter("private fun openFullscreenLyrics()").substringBefore("\n    }")
        assertTrue(hook.contains("fsLyrics.open()"))
        assertFalse(File("src/main/java/com/minim/music/ui/LyricsView.kt").exists() ||
            File("app/src/main/java/com/minim/music/ui/LyricsView.kt").exists())
    }

    @Test
    fun fullscreenMode_usesNowPlayingsCoverSize_andRenderEffectWithAFallback() {
        val fs = src("FullscreenLyrics.kt")
        // Same request as Now Playing (screen width, 720..1440) so the cached copy is reused
        assertTrue(fs.contains("widthPixels.coerceIn(720, 1440)"))
        assertTrue(fs.contains("RenderEffect.createBlurEffect"))
        assertTrue(fs.contains("SoftwareBlur.blur"))
        // Tapping a line doesn't seek: no click listeners on lines
        val views = src("FullscreenLyricsViews.kt")
        assertFalse(views.contains("setOnClickListener"))
    }

    @Test
    fun activeLine_respectsTheTimingOffset_andHasAPerWordHook() {
        val main = src("MainActivity.kt")
        val fn = main.substringAfter("private fun updateLyricLine(").substringBefore("\n    }")
        assertTrue(fn.contains("lyricsOffsetMs") || fn.contains("shifted"))
        assertTrue(fn.contains("fsLyrics.onLinePosition(shifted,"))
        assertTrue(fn.contains("fsLyrics.setActive(index"))
        val views = src("FullscreenLyricsViews.kt")
        assertTrue(views.contains("open fun onLinePosition(positionMs: Long)"))
    }
}
