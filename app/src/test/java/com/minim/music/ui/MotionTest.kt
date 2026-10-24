package com.minim.music.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** v4.9.9 part 2: the song change colour fade and the tab switch motion. */
class MotionTest {

    private val darkBw = Pal(0xFF000000.toInt(), 0xFFFFFFFF.toInt(), 0xFF8A8A8A.toInt())
    private val lightBw = Pal(0xFFFFFFFF.toInt(), 0xFF000000.toInt(), 0xFF6E6E6E.toInt())
    private val darkRed = Pal(0xFF2A0E0E.toInt(), 0xFFF4C7C3.toInt(), 0xFFB08A86.toInt())
    private val darkBlue = Pal(0xFF0B1A2E.toInt(), 0xFFC6DAF6.toInt(), 0xFF8394AC.toInt())
    private val lightTeal = Pal(0xFFDDF3EF.toInt(), 0xFF0F3B35.toInt(), 0xFF51736D.toInt())

    private fun channel(c: Int, shift: Int) = (c shr shift) and 0xFF

    @Test fun fadeStartsAndEndsOnThePalettes() {
        assertEquals(darkRed, PaletteFade.at(darkRed, darkBlue, 0f))
        assertEquals(darkBlue, PaletteFade.at(darkRed, darkBlue, 1f))
        assertEquals(darkBlue, PaletteFade.at(darkRed, darkBlue, 1.2f))
    }

    @Test fun midpointBlendsEveryColour() {
        val mid = PaletteFade.at(darkRed, darkBlue, 0.5f)
        for (pick in listOf<(Pal) -> Int>({ it.bg }, { it.fg }, { it.muted })) {
            for (shift in listOf(16, 8, 0)) {
                val a = channel(pick(darkRed), shift)
                val b = channel(pick(darkBlue), shift)
                val m = channel(pick(mid), shift)
                assertTrue("channel $shift: $m between $a and $b", m in minOf(a, b)..maxOf(a, b))
                assertTrue(kotlin.math.abs(m - (a + b) / 2) <= 1)
            }
        }
    }

    @Test fun fadeMovesSteadilyWithoutOvershoot() {
        var last = PaletteFade.at(darkRed, darkBlue, 0f)
        for (i in 1..20) {
            val now = PaletteFade.at(darkRed, darkBlue, i / 20f)
            // Blue channel of the background only rises from red's to blue's
            assertTrue(channel(now.bg, 0) >= channel(last.bg, 0))
            last = now
        }
    }

    @Test fun retargetStartsFromTheInBetweenColours() {
        // A quick skip: the second fade begins exactly where the first one was, so nothing jumps
        val between = PaletteFade.at(darkRed, darkBlue, 0.37f)
        assertEquals(between, PaletteFade.at(between, lightTeal, 0f))
        val next = PaletteFade.at(between, lightTeal, 0.05f)
        for (shift in listOf(16, 8, 0)) {
            assertTrue(kotlin.math.abs(channel(next.bg, shift) - channel(between.bg, shift)) <= 13)
        }
    }

    @Test fun textFlipIsDetected() {
        assertTrue(PaletteFade.textFlips(darkBw, lightBw))
        assertTrue(PaletteFade.textFlips(darkRed, lightTeal))
        assertFalse(PaletteFade.textFlips(darkRed, darkBlue))
        assertFalse(PaletteFade.textFlips(lightBw, lightTeal))
    }

    @Test fun flippingTextCrossesOverAndStaysReadable() {
        for ((from, to, minimum) in listOf(
            Triple(darkBw, lightBw, 4.5), Triple(lightBw, darkBw, 4.5),
            Triple(darkRed, lightTeal, 4.0), Triple(lightTeal, darkBlue, 4.0)
        )) {
            var switched = false
            for (i in 0..100) {
                val p = PaletteFade.at(from, to, i / 100f)
                // Text is always light or dark, never a grey passing through the background
                val onNewSide = PaletteFade.isLight(p.fg) == PaletteFade.isLight(to.fg)
                if (onNewSide) switched = true else assertFalse("switches only once", switched)
                assertTrue("contrast at ${i}%", FullscreenLyricsLook.contrast(p.fg, p.bg) >= minimum)
            }
            assertTrue(switched)
            // It ends on the palette's own text colour
            assertEquals(to.fg, PaletteFade.at(from, to, 1f).fg)
            assertTrue(FullscreenLyricsLook.contrast(PaletteFade.at(from, to, 0.02f).fg, from.fg) < 1.3)
        }
    }

    @Test fun blackAndWhiteFlipCrossesNearTheMiddle() {
        val at = (0..100).first { PaletteFade.isLight(PaletteFade.at(darkBw, lightBw, it / 100f).fg) == PaletteFade.isLight(lightBw.fg) }
        assertTrue("crossed at $at%", at in 35..65)
    }

    @Test fun tabDirections() {
        val shift = 50f
        // Home (0) to Stats (2): new content from the right, old content out to the left
        assertEquals(50f, TabMotion.incomingStart(0, 2, shift))
        assertEquals(-50f, TabMotion.outgoingEnd(0, 2, shift))
        // Library (3) to Discover (1): the other way round
        assertEquals(-50f, TabMotion.incomingStart(3, 1, shift))
        assertEquals(50f, TabMotion.outgoingEnd(3, 1, shift))
        assertTrue(TabMotion.SHIFT_DP in 16f..24f)
        assertTrue(TabMotion.IN_MS in 200L..250L && TabMotion.OUT_MS in 200L..250L)
    }

    @Test fun underlineStartsFromWhereTheOldOneIs() {
        val (dx, scale) = TabMotion.lineStart(fromCenter = 100f, fromWidth = 40f, toCenter = 220f, toWidth = 80f)
        assertEquals(-120f, dx)
        assertEquals(0.5f, scale)
        // Not laid out yet: no stretch
        assertEquals(1f, TabMotion.lineStart(0f, 0f, 10f, 30f).second)
    }

    @Test fun quickSecondTapUsesOnlyWhatIsLeft() {
        assertEquals(TabMotion.IN_MS, TabMotion.remainingMs(TabMotion.IN_MS, 0f, 1f))
        assertEquals(TabMotion.IN_MS / 2, TabMotion.remainingMs(TabMotion.IN_MS, 0.5f, 1f))
        assertEquals(0L, TabMotion.remainingMs(TabMotion.IN_MS, 1f, 1f))
        // A nearly finished slide still takes a short moment, never a jump
        assertEquals(60L, TabMotion.remainingMs(TabMotion.IN_MS, 0.99f, 1f))
    }

    private fun mainSource(): String {
        val candidates = listOf("src/main/java/com/minim/music/ui/MainActivity.kt", "app/src/main/java/com/minim/music/ui/MainActivity.kt")
        return File(candidates.first { File(it).exists() }).readText()
    }

    @Test fun paletteFadeIsEasedAndSharedAndRespectsRemoveAnimations() {
        val src = mainSource()
        val body = src.substringAfter("private fun animatePaletteTo(target: Pal) {").substringBefore("private fun applyPalette(")
        assertTrue(body.contains("PaletteFade.at(from, target, t)"))
        assertTrue(body.contains("DecelerateInterpolator"))
        assertTrue(body.contains("!animationsOn()"))
        // Retargets from the colours on screen
        assertTrue(body.contains("val from = pal"))
        assertFalse(body.contains("OvershootInterpolator"))
    }

    @Test fun songTextFadesOnNowPlayingAndHomePill() {
        val src = mainSource()
        assertTrue(src.contains("textFade.set(npTitle, track.title, fadeText)"))
        assertTrue(src.contains("textFade.set(npArtist, track.artist, fadeText)"))
        assertTrue(src.contains("textFade.set(mini.title, track.title, fadeText)"))
        // The pill measures the incoming text so its resize runs alongside the fade
        assertTrue(src.contains("measureText(textFade.target(m.title).toString())"))
        assertTrue(TextCrossfade.OUT_MS + TextCrossfade.IN_MS in 200L..250L)
    }

    @Test fun tabSwitchUsesOneAnimatorAndTheUnderlineResizes() {
        val src = mainSource()
        val anim = src.substringAfter("private fun animateTabChange(from: Tab, to: Tab) {").substringBefore("private fun stopTabTransition()")
        assertTrue(anim.contains("glideTabLine(from, to)"))
        assertTrue(anim.contains("TabMotion.incomingStart"))
        assertTrue(anim.contains("TabMotion.outgoingEnd"))
        val glide = src.substringAfter("private fun glideTabLine(from: Tab, to: Tab) {").substringBefore("/** Shows the floating mini player")
        assertTrue(glide.contains("scaleX(1f)"))
        assertTrue(src.contains("private fun fitTabLines()"))
    }
}
