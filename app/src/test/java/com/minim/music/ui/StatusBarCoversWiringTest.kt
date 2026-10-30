package com.minim.music.ui

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 5.5.2 (update 40): Settings, Now Playing and every other screen opened over Home paint their
 * own colour behind the status bar and the nav bar, never the festival wallpaper.
 */
class StatusBarCoversWiringTest {
    private fun file(path: String): String {
        val candidates = listOf(path, "app/$path")
        return File(candidates.first { File(it).exists() }).readText()
    }

    private val main get() = file("src/main/java/com/minim/music/ui/MainActivity.kt")

    @Test fun stripsSitAboveTheWallpaperAndTakeoverButUnderEveryScreen() {
        val xml = file("src/main/res/layout/activity_main.xml")
        val theme = xml.indexOf("@+id/home_theme\"")
        val takeover = xml.indexOf("@+id/home_takeover\"")
        val covers = xml.indexOf("@+id/home_bar_covers\"")
        val home = xml.indexOf("@layout/view_home")
        assertTrue(theme in 0 until takeover)
        assertTrue(takeover < covers)
        assertTrue(covers < home)
        assertTrue(xml.contains("@+id/home_bar_cover_top") && xml.contains("@+id/home_bar_cover_bottom"))
    }

    @Test fun stripsAreEdgeToEdgeAndSizedToTheBars() {
        val insets = main.substringAfter("private fun setupWindowInsets()").substringBefore("\n    /**")
        assertTrue(insets.contains("barCovers.layoutParams = lp"))
        assertTrue(insets.contains("stripHeight(barCoverTop, bars.top)"))
        assertTrue(insets.contains("stripHeight(barCoverBottom, bars.bottom)"))
        assertTrue(insets.contains("syncBarCovers()"))
    }

    @Test fun coverageFollowsEachScreensOwnAnimation() {
        val cov = main.substringAfter("private fun overlayCoverage()").substringBefore("\n    }")
        assertTrue(cov.contains("npMorph.progress"))
        assertTrue(cov.contains("settingsReveal.coverage"))
        assertTrue(cov.contains("screenSearch.background"))
        for (s in listOf("screenQueue", "screenArtist", "screenShelf", "screenList", "screenImport")) {
            assertTrue(s, cov.contains("$s.alpha"))
        }
        assertTrue(main.contains("settingsReveal.onCoverage = { syncBarCovers() }"))
        val reveal = file("src/main/java/com/minim/music/ui/SettingsReveal.kt")
        assertTrue(reveal.contains("coverageAnimator(0f, 1f, OPEN_MS"))
        assertTrue(reveal.contains("coverageAnimator(coverage, 0f, CLOSE_MS"))
    }

    @Test fun barIconsSwitchHalfWayWithTheStrips() {
        val onTop = main.substringAfter("private fun themeWallpaperOnTop(): Boolean =").substringBefore("\n\n")
        assertTrue(onTop.contains("overlayCoverage() < 0.5f"))
        val sync = main.substringAfter("private fun syncBarCovers()").substringBefore("\n    }\n")
        assertTrue(sync.contains("themeLayer.visibility == View.VISIBLE || takeoverRoot.visibility == View.VISIBLE"))
        assertTrue(sync.contains("barCovers.alpha = cover"))
        assertTrue(sync.contains("pal.bg"))
        assertTrue(sync.contains("updateSystemBars()"))
    }

    @Test fun bassBoostRowSitsUnderVolumeBoost() {
        val xml = file("src/main/res/layout/view_settings.xml")
        val volume = xml.indexOf("@string/settings_boost\"")
        val loud = xml.indexOf("@+id/set_boost_loud")
        val bass = xml.indexOf("@string/settings_bass\"")
        assertTrue(volume in 0 until loud && loud < bass)
        val ids = listOf("set_bass_off", "set_bass_warm", "set_bass_punchy", "set_bass_heavy")
        var last = bass
        for (id in ids) {
            val at = xml.indexOf("@+id/$id")
            assertTrue(id, at > last)
            last = at
            assertTrue(main.contains("option(R.id.$id)"))
            assertTrue(main.contains("sel(R.id.$id,"))
        }
        val strings = file("src/main/res/values/strings.xml")
        for (label in listOf(">Warm<", ">Punchy<", ">Heavy<", ">Bass boost<")) assertTrue(label, strings.contains(label))
    }

    @Test fun bassBoostIsMinimsOwnDspNotAndroidsEffect() {
        val service = file("src/main/java/com/minim/music/playback/PlaybackService.kt")
        assertTrue(service.contains(".setAudioProcessors(arrayOf<AudioProcessor>(bass))"))
        assertTrue(service.contains("ExoPlayer.Builder(this, renderersFactory)"))
        assertTrue(service.contains("bassBoost?.setStage(MinimApp.instance.preferences.bassBoost)"))
        val all = File(listOf("src/main/java", "app/src/main/java").first { File(it).exists() })
            .walkTopDown().filter { it.extension == "kt" }.map { it.readText() }.joinToString("\n")
        assertTrue(!all.contains("android.media.audiofx.BassBoost"))
        assertTrue(!all.contains("android.media.audiofx.Equalizer"))
    }
}
