package com.minim.music.ui

import com.minim.music.data.local.AppPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 6.8.0 (71): the Haze theme's ink, scrim, fallback and Settings rule. */
class HazeLookTest {

    private fun grey(v: Int) = (0xFF shl 24) or (v shl 16) or (v shl 8) or v

    private fun lookFor(c: Int, was: Boolean? = null) =
        HazeLook.look(HazeLook.luminance(c), c, c, c, was)

    // ---- ink ----

    @Test
    fun darkCoverGetsWhiteInk() {
        assertTrue(HazeLook.lightInk(HazeLook.luminance(grey(20)), null))
        assertEquals(HazeLook.LIGHT_INK, lookFor(0xFF101A30.toInt()).ink)
    }

    @Test
    fun lightCoverGetsDarkInk() {
        assertFalse(HazeLook.lightInk(HazeLook.luminance(grey(235)), null))
        assertEquals(HazeLook.DARK_INK, lookFor(0xFFF4E9D0.toInt()).ink)
    }

    @Test
    fun firstSongSplitsAtTheEvenPoint() {
        assertTrue(HazeLook.lightInk(HazeLook.EVEN_LUMINANCE - 0.001, null))
        assertFalse(HazeLook.lightInk(HazeLook.EVEN_LUMINANCE + 0.001, null))
        // The even point really is where white and #111 read the same
        val ratioWhite = 1.05 / (HazeLook.EVEN_LUMINANCE + 0.05)
        val ratioDark = (HazeLook.EVEN_LUMINANCE + 0.05) / (HazeLook.luminance(HazeLook.DARK_INK) + 0.05)
        assertEquals(ratioWhite, ratioDark, 0.05)
    }

    @Test
    fun borderlineCoversKeepTheLastInk() {
        val mid = 0.20 // a little above the even point
        assertTrue(HazeLook.lightInk(mid, wasLight = true))
        assertFalse(HazeLook.lightInk(mid, wasLight = false))
        val low = 0.17 // a little below it
        assertTrue(HazeLook.lightInk(low, wasLight = true))
        assertFalse(HazeLook.lightInk(low, wasLight = false))
    }

    @Test
    fun clearCoversStillFlip() {
        assertFalse(HazeLook.lightInk(HazeLook.TO_DARK + 0.01, wasLight = true))
        assertTrue(HazeLook.lightInk(HazeLook.TO_LIGHT - 0.01, wasLight = false))
        assertTrue(HazeLook.TO_LIGHT < HazeLook.EVEN_LUMINANCE && HazeLook.EVEN_LUMINANCE < HazeLook.TO_DARK)
    }

    // ---- scrim ----

    @Test
    fun scrimIsFaintOnEasyCovers() {
        assertEquals(HazeLook.SCRIM_MIN, HazeLook.scrimAlpha(HazeLook.LIGHT_INK, grey(10)), 0.001f)
        assertEquals(HazeLook.SCRIM_MIN, HazeLook.scrimAlpha(HazeLook.DARK_INK, grey(250)), 0.001f)
    }

    @Test
    fun scrimGrowsOnMidToneCoversUntilReadable() {
        val a = HazeLook.scrimAlpha(HazeLook.LIGHT_INK, grey(140))
        assertTrue(a > HazeLook.SCRIM_MIN)
        assertTrue(a < HazeLook.SCRIM_MAX)
        val under = HazeLook.over(0xFF000000.toInt(), a, grey(140))
        assertTrue(HazeLook.contrast(HazeLook.LIGHT_INK, under) >= HazeLook.TARGET_CONTRAST)
        // One step weaker would not be enough
        val weaker = HazeLook.over(0xFF000000.toInt(), a - HazeLook.SCRIM_STEP, grey(140))
        assertTrue(HazeLook.contrast(HazeLook.LIGHT_INK, weaker) < HazeLook.TARGET_CONTRAST)
    }

    @Test
    fun scrimIsDarkUnderWhiteInkAndLightUnderDarkInk() {
        assertEquals(0xFF000000.toInt(), HazeLook.scrimFor(HazeLook.LIGHT_INK))
        assertEquals(0xFFFFFFFF.toInt(), HazeLook.scrimFor(HazeLook.DARK_INK))
    }

    @Test
    fun scrimNeverPassesItsCap() {
        assertTrue(HazeLook.scrimAlpha(HazeLook.LIGHT_INK, grey(255)) <= HazeLook.SCRIM_MAX)
        assertTrue(HazeLook.scrimAlpha(HazeLook.DARK_INK, grey(0)) <= HazeLook.SCRIM_MAX)
    }

    @Test
    fun inkStaysReadableOnLightDarkAndMidCovers() {
        for (c in listOf(grey(250), 0xFFFFF4C2.toInt(), grey(8), 0xFF0B1A3A.toInt(), grey(128), 0xFF7A6A55.toInt(), 0xFF3C7F8F.toInt())) {
            val look = lookFor(c)
            assertTrue("cover ${Integer.toHexString(c)}", HazeLook.contrast(look.ink, look.average) >= HazeLook.TARGET_CONTRAST)
        }
    }

    @Test
    fun scrimCoversTheHardestPartOfTheCover() {
        // Mostly dark with a bright patch: white ink, and the scrim is sized for the bright patch
        val px = IntArray(100) { if (it < 80) grey(15) else grey(220) }
        val st = HazeLook.stats(px)
        val look = HazeLook.look(st.luma, st.average, st.brightest, st.darkest, null)
        assertTrue(look.lightInk)
        val overBright = HazeLook.over(look.scrim, look.scrimAlpha, grey(220))
        assertTrue(HazeLook.contrast(look.ink, overBright) >= HazeLook.TARGET_CONTRAST)
    }

    // ---- palette ----

    @Test
    fun paletteUsesTheInkAndBarsFollowIt() {
        for (c in listOf(grey(250), grey(8), grey(128), 0xFFB7C7A0.toInt(), 0xFF9A9A9A.toInt())) {
            val look = lookFor(c)
            assertEquals(look.ink, look.pal.fg)
            // Status and nav icons come from the base: they must match the ink
            assertEquals("cover ${Integer.toHexString(c)}", !look.lightInk, HazeLook.luminance(look.pal.bg) > 0.5)
            // Text in ink-filled pills (the base on the ink) reads clearly
            assertTrue(HazeLook.contrast(look.pal.bg, look.pal.fg) >= 4.5)
        }
    }

    @Test
    fun frostIsATranslucentInk() {
        val f = HazeLook.frost(HazeLook.LIGHT_INK)
        val alpha = (f ushr 24) / 255f
        assertTrue(alpha in 0.10f..0.14f)
        assertEquals(HazeLook.LIGHT_INK and 0xFFFFFF, f and 0xFFFFFF)
        assertEquals(HazeLook.DARK_INK and 0xFFFFFF, HazeLook.frost(HazeLook.DARK_INK) and 0xFFFFFF)
    }

    // ---- nothing playing ----

    @Test
    fun nothingPlayingUsesTheLastPlayedCover() {
        assertEquals(HazeLook.Source.Cover("now"), HazeLook.source("now", "last"))
        assertEquals(HazeLook.Source.Cover("last"), HazeLook.source(null, "last"))
        assertEquals(HazeLook.Source.Cover("last"), HazeLook.source("", "last"))
    }

    @Test
    fun noHistoryFallsBackToTheDarkLook() {
        assertEquals(HazeLook.Source.Dark, HazeLook.source(null, null))
        assertEquals(HazeLook.Source.Dark, HazeLook.source(" ", ""))
        assertEquals(Pal.build(0, darkMode = true), HazeLook.NO_COVER_PAL)
    }

    // ---- Settings ----

    @Test
    fun hazeHidesTheColoursRowAndKeepsTheChoice() {
        val row = HazeLook.coloursRow(AppPreferences.THEME_HAZE, AppPreferences.COLOURS_DEFAULT)
        assertFalse(row.enabled)
        assertTrue(row.showNote)
        assertFalse(row.dynamicSelected)
        assertTrue(HazeLook.coloursAlpha(row.enabled) < 0.5f)
    }

    @Test
    fun lightAndDarkBringTheColoursRowBackAsItWas() {
        for (theme in listOf(AppPreferences.THEME_LIGHT, AppPreferences.THEME_DARK)) {
            val def = HazeLook.coloursRow(theme, AppPreferences.COLOURS_DEFAULT)
            assertTrue(def.enabled)
            assertFalse(def.showNote)
            assertFalse(def.dynamicSelected)
            assertEquals(1f, HazeLook.coloursAlpha(def.enabled), 0f)
            assertTrue(HazeLook.coloursRow(theme, AppPreferences.COLOURS_DYNAMIC).dynamicSelected)
        }
    }

    // ---- the blur ----

    @Test
    fun statsOfAFlatCover() {
        val st = HazeLook.stats(IntArray(64) { grey(100) })
        assertEquals(grey(100), st.average)
        assertEquals(grey(100), st.brightest)
        assertEquals(grey(100), st.darkest)
        assertEquals(HazeLook.luminance(grey(100)), st.luma, 1e-9)
    }
}
