package com.minim.music.ui

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 5.5.2: the festival theme is wired where the user meets it. */
class V552WiringTest {
    private fun source(name: String): String {
        val candidates = listOf("src/main/java/com/minim/music/ui/$name", "app/src/main/java/com/minim/music/ui/$name")
        return File(candidates.first { File(it).exists() }).readText()
    }

    @Test fun sevenTapsOnTheVersionTogglesThePreviewAndLongPressTheBigDay() {
        val src = source("MainActivity.kt")
        assertTrue(src.contains("private const val VERSION_TAPS = 7"))
        assertTrue(src.contains("about.setOnClickListener { onVersionTapped() }"))
        assertTrue(src.contains("app.toggleThemePreview { showPreviewToast(it) }"))
        assertTrue(src.contains("app.toggleThemeBigDay { showPreviewToast(it) }"))
        assertTrue(src.contains("setupTheme()"))
    }

    @Test fun wallpaperFollowsTabsAndHomeTakesItsColours() {
        val src = source("MainActivity.kt")
        assertTrue(src.contains("syncThemeLayer(animate = slide)"))
        assertTrue(src.contains("takeoverRoot.visibility != View.VISIBLE && themeHomePal == null"))
        assertTrue(src.contains("if (isTakeoverActive || themeHomePal != null) applyHomeOverrides()"))
        val bars = src.substringAfter("private fun updateSystemBars").substringBefore("\n    }")
        assertTrue(bars.contains("themeWallpaperOnTop()"))
    }

    @Test fun festivalTakeoverAndOpenerAreUsed() {
        val src = source("MainActivity.kt")
        assertTrue(src.contains("ArtistTakeover.durationMs(Greetings.themeOn)"))
        assertTrue(src.contains("it.takeoverLine("))
        val open = src.substringAfter("private fun showTimeGreeting()").substringBefore("\n    }")
        assertTrue(open.contains("pickOpener()"))
        assertTrue(src.contains("app.syncThemes()"))
        assertTrue(src.contains("heartFx.burstStyle = s.theme?.burst"))
    }

    @Test fun homeLayoutHasTheWallpaperUnderTheScreens() {
        val candidates = listOf("src/main/res/layout/activity_main.xml", "app/src/main/res/layout/activity_main.xml")
        val xml = File(candidates.first { File(it).exists() }).readText()
        val theme = xml.indexOf("@+id/home_theme\"")
        val home = xml.indexOf("@layout/view_home")
        assertTrue(theme in 0 until home)
    }
}
