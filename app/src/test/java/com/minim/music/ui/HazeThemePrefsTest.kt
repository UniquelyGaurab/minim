package com.minim.music.ui

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.minim.music.data.local.AppPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 6.8.0 (71): Haze is stored with the other themes, and build 70's choice carries over. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class HazeThemePrefsTest {

    private val context get() = ApplicationProvider.getApplicationContext<android.app.Application>()
    private val raw get() = context.getSharedPreferences("minim_desk_prefs", Context.MODE_PRIVATE)

    @Before
    fun clean() {
        raw.edit().clear().commit()
    }

    @Test
    fun hazeIsRememberedAcrossRestarts() {
        AppPreferences(context).themeMode = AppPreferences.THEME_HAZE
        val p = AppPreferences(context)
        assertEquals(AppPreferences.THEME_HAZE, p.themeMode)
        assertTrue(p.isHazeTheme)
        // Anything Haze doesn't colour (the recap story) keeps the dark look
        assertTrue(p.isDarkTheme)
    }

    @Test
    fun build70DarkStaysDark() {
        raw.edit().putInt("theme_mode_v4", AppPreferences.THEME_DARK).putInt("colour_mode_v494", AppPreferences.COLOURS_DEFAULT).commit()
        val p = AppPreferences(context)
        assertEquals(AppPreferences.THEME_DARK, p.themeMode)
        assertFalse(p.isHazeTheme)
        assertEquals(AppPreferences.COLOURS_DEFAULT, p.colourMode)
    }

    @Test
    fun build70LightStaysLightAndFreshInstallsStartLight() {
        assertEquals(AppPreferences.THEME_LIGHT, AppPreferences(context).themeMode)
        raw.edit().putInt("theme_mode_v4", AppPreferences.THEME_LIGHT).commit()
        assertEquals(AppPreferences.THEME_LIGHT, AppPreferences(context).themeMode)
        assertFalse(AppPreferences(context).isDarkTheme)
    }

    @Test
    fun unknownValuesReadAsLight() {
        raw.edit().putInt("theme_mode_v4", 99).commit()
        assertEquals(AppPreferences.THEME_LIGHT, AppPreferences(context).themeMode)
    }

    @Test
    fun leavingHazeKeepsTheColoursChoice() {
        val p = AppPreferences(context)
        p.colourMode = AppPreferences.COLOURS_DEFAULT
        p.themeMode = AppPreferences.THEME_HAZE
        p.themeMode = AppPreferences.THEME_DARK
        assertEquals(AppPreferences.COLOURS_DEFAULT, AppPreferences(context).colourMode)
        val row = HazeLook.coloursRow(p.themeMode, p.colourMode)
        assertTrue(row.enabled)
        assertFalse(row.dynamicSelected)
    }
}
