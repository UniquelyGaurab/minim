package com.minim.music.ui

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 5.5.2 (update 39): the festival wallpaper behind the status bar and the navigation bar. */
class ThemeBarsTest {

    @Test fun wallpaperLayerPushesOutPastEveryBar() {
        assertArrayEquals(intArrayOf(0, -63, 0, -126), ThemeBars.edgeToEdgeMargins(0, 63, 0, 126))
        // Landscape 3-button nav on the side
        assertArrayEquals(intArrayOf(-10, -63, -126, 0), ThemeBars.edgeToEdgeMargins(10, 63, 126, 0))
    }

    @Test fun stripsMatchTheBarHeights() {
        val (s0, s1) = ThemeBars.statusStrip(120, 2400)
        assertEquals(0f, s0, 0f)
        assertEquals(0.05f, s1, 1e-6f)
        val (n0, n1) = ThemeBars.navStrip(48, 2400)
        assertEquals(0.98f, n0, 1e-6f)
        assertEquals(1f, n1, 0f)
        // No nav bar: still reads a thin strip at the bottom
        assertEquals(1f - ThemeBars.FALLBACK_STRIP, ThemeBars.navStrip(0, 2400).first, 1e-6f)
    }

    @Test fun cropBoxFollowsCentreCrop() {
        // 1080x2400 picture on a 1080x2400 screen: rows map one to one
        assertArrayEquals(intArrayOf(0, 0, 1080, 120), ThemeBars.cropBox(1080, 2400, 1080, 2400, 0f, 0.05f))
        // Taller screen (1080x2520): the picture is scaled up and its sides cut off
        val box = ThemeBars.cropBox(1080, 2400, 1080, 2520, 0.95f, 1f)
        assertTrue(box[0] > 0 && box[2] < 1080)
        assertEquals(2400, box[3])
        assertTrue(box[1] in 2270..2290)
        // Always at least one pixel
        val tiny = ThemeBars.cropBox(1080, 2400, 1080, 2400, 0f, 0f)
        assertTrue(tiny[3] > tiny[1] && tiny[2] > tiny[0])
    }

    @Test fun overTheWallpaperBarsAreClearAndFollowThePicture() {
        // Dark Diwali wallpaper: light icons (not "light bars"), clear nav, no grey scrim
        val p = ThemeBars.plan(
            wallOnTop = true, statusOnLight = false, navOnLight = false,
            baseLightStatus = true, baseLightNav = true, baseNavContrast = true
        )
        assertFalse(p.lightStatus)
        assertFalse(p.lightNav)
        assertTrue(p.navTransparent)
        assertFalse(p.navContrastEnforced)
        assertFalse(ThemeBars.isLight(0.1))
        assertTrue(ThemeBars.isLight(0.8))
    }

    @Test fun offTheWallpaperBarsAreExactlyTheUsualOnes() {
        for (ls in listOf(true, false)) for (ln in listOf(true, false)) for (c in listOf(true, false)) {
            val p = ThemeBars.plan(false, statusOnLight = !ls, navOnLight = !ln, baseLightStatus = ls, baseLightNav = ln, baseNavContrast = c)
            assertEquals(ThemeBars.Plan(ls, ln, navTransparent = false, navContrastEnforced = c), p)
        }
    }

    @Test fun activityWiresTheLayerBehindTheBars() {
        val candidates = listOf("src/main/java/com/minim/music/ui/MainActivity.kt", "app/src/main/java/com/minim/music/ui/MainActivity.kt")
        val src = File(candidates.first { File(it).exists() }).readText()
        val insets = src.substringAfter("private fun setupWindowInsets()").substringBefore("\n    }\n")
        assertTrue(insets.contains("findViewById<View>(R.id.home_theme)"))
        assertTrue(insets.contains("ThemeBars.edgeToEdgeMargins(bars.left, bars.top, bars.right, bars.bottom)"))
        assertTrue(insets.contains("readThemeBarTones()"))
        val bars = src.substringAfter("private fun updateSystemBars").substringBefore("\n    }\n")
        assertTrue(bars.contains("ThemeBars.plan("))
        assertTrue(bars.contains("isNavigationBarContrastEnforced"))
    }
}
