package com.minim.music.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.minim.music.MinimApp
import com.minim.music.R
import com.minim.music.data.local.AppPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import java.io.File
import java.time.Duration

/**
 * 5.8.4 u56: the Settings revamp. Every setting from 5.8.3 is still there (same ids, same saved
 * values), the page is in eight groups, every choice is a SegmentRow whose thumb sits on the saved
 * option, colours come from the palette only, and the long privacy notes fold away.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
@Config(sdk = [34], application = LaunchTestApp::class, qualifiers = "w411dp-h891dp-xxhdpi")
class SettingsRevampTest {

    private val app get() = ApplicationProvider.getApplicationContext<MinimApp>()

    private fun idle(seconds: Long = 1) {
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(seconds))
    }

    private fun frame(a: android.app.Activity) {
        val root = a.window.decorView
        root.measure(
            View.MeasureSpec.makeMeasureSpec(1233, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(2673, View.MeasureSpec.EXACTLY)
        )
        root.layout(0, 0, 1233, 2673)
        root.draw(Canvas(Bitmap.createBitmap(1233, 2673, Bitmap.Config.ARGB_8888)))
        idle()
    }

    private fun call(a: MainActivity, name: String, vararg args: Any?) {
        val m = MainActivity::class.java.declaredMethods.first { it.name == name && it.parameterCount == args.size }
        m.isAccessible = true
        m.invoke(a, *args)
        idle()
    }

    private fun pal(a: MainActivity): Pal {
        val f = MainActivity::class.java.getDeclaredField("pal")
        f.isAccessible = true
        return f.get(a) as Pal
    }

    private fun openSettings(): MainActivity {
        app.preferences.welcomeDone = true
        val c = Robolectric.buildActivity(MainActivity::class.java)
        c.setup()
        idle(3)
        val a = c.get()
        call(a, "openSettings", null)
        idle(2)
        frame(a)
        return a
    }

    private fun segmentRows(v: View, out: MutableList<SegmentRow> = mutableListOf()): List<SegmentRow> {
        if (v is SegmentRow) out += v
        if (v is ViewGroup) for (i in 0 until v.childCount) segmentRows(v.getChildAt(i), out)
        return out
    }

    private fun file(path: String): String {
        val f = listOf(File(path), File("app/$path")).first { it.exists() }
        return f.readText()
    }

    /** Every id the 5.8.3 Settings page had (bar the version label beside the update check). */
    private val oldIds = listOf(
        "screen_settings", "settings_content", "settings_close", "settings_account", "account_signed_out",
        "account_sign_in", "account_signed_in", "account_avatar", "account_name", "account_status",
        "account_sync_problem", "account_sign_out", "account_delete", "account_message", "account_privacy",
        "settings_name", "set_theme_light", "set_theme_dark", "set_colours_dynamic", "set_colours_default",
        "set_boost_off", "set_boost_normal", "set_boost_loud", "set_bass_off", "set_bass_warm",
        "set_bass_punchy", "set_bass_heavy", "set_fade_off", "set_fade_2", "set_fade_4",
        "set_lyrics_flow", "set_lyrics_line", "set_lyrics_minus", "set_lyrics_value", "set_lyrics_plus",
        "set_swipe_skip", "set_swipe_off", "set_dtap_play", "set_dtap_like", "set_dtap_off",
        "set_lpress_lyrics", "set_lpress_off", "set_haptics_on", "set_haptics_off", "set_gemini_header",
        "set_gemini_model_pick", "set_gemini_key_status", "set_gemini_key_add", "set_gemini_key_remove",
        "set_gemini_token_box", "set_gemini_token_value", "set_gemini_token_copy", "set_offline_storage",
        "set_offline_remove_all", "set_clear_cache", "set_about", "set_check_updates", "set_credits"
    )

    @Test
    fun everyOldSettingIsStillOnThePage() {
        val xml = file("src/main/res/layout/view_settings.xml")
        for (id in oldIds) assertTrue(id, xml.contains("@+id/$id\""))
        val main = file("src/main/java/com/minim/music/ui/MainActivity.kt")
        for (id in oldIds.filter { it.startsWith("set_") && it.count { c -> c == '_' } >= 2 && !it.startsWith("set_gemini") }) {
            assertTrue(id, main.contains("R.id.$id"))
        }
    }

    @Test
    fun groupsComeInOrder_andTheCreditsAreUnchanged() {
        val xml = file("src/main/res/layout/view_settings.xml")
        val order = listOf("you", "look", "sound", "lyrics", "gestures", "gemini", "storage", "about")
            .map { xml.indexOf("@+id/settings_group_$it\"") }
        assertTrue(order.toString(), order.all { it > 0 } && order == order.sorted())
        // account and name are in You; the update check and credits are in About
        assertTrue(xml.indexOf("@+id/settings_account") in order[0] until order[1])
        assertTrue(xml.indexOf("@+id/settings_name") in order[0] until order[1])
        assertTrue(xml.indexOf("@+id/set_check_updates\"") > order[7])
        assertTrue(xml.indexOf("@+id/set_credits") > order[7])
        val strings = file("src/main/res/values/strings.xml")
        assertTrue(strings.contains("<string name=\"about_credits\">Designed by Gaurab\\nBuilt by Charlie (Sesame AI)</string>"))
        assertFalse(strings.contains("Early code by Google Gemini"))
    }

    @Test
    fun noFixedColours_andNoOldOutlinePills() {
        val xml = file("src/main/res/layout/view_settings.xml")
        assertFalse(Regex("\"#[0-9A-Fa-f]{3,8}\"").containsMatchIn(xml))
        assertFalse(xml.contains("@color/"))
        assertFalse(xml.contains("MinimText.Option"))
        assertFalse(xml.contains("android:tag=\"opt\""))
        val seg = file("src/main/java/com/minim/music/ui/SegmentRow.kt")
        assertFalse(Regex("Color\\.(RED|GREEN|BLUE|parseColor)").containsMatchIn(seg))
    }

    @Test
    fun versionIs586Build58() {
        val gradle = file("build.gradle.kts")
        assertTrue(gradle.contains("versionCode = 58"))
        assertTrue(gradle.contains("versionName = \"5.8.6\""))
    }

    @Test
    fun everyChoiceRowShowsTheSavedOption_andIsEasyToTap() {
        val p = app.preferences
        p.volumeBoost = AppPreferences.BOOST_NORMAL
        p.bassBoost = AppPreferences.BASS_PUNCHY
        p.crossfadeSeconds = 4
        val a = openSettings()
        val rows = segmentRows(a.findViewById(R.id.screen_settings))
        assertEquals(10, rows.size)
        val density = a.resources.displayMetrics.density
        for (row in rows) {
            assertEquals(1, (0 until row.childCount).count { row.getChildAt(it).isSelected })
            assertTrue(row.height >= (44 * density).toInt() - 1)
            val sel = row.getChildAt(row.selectedIndex())
            val thumb = row.thumbRect()
            assertTrue("thumb over the chosen option", thumb.centerX() in sel.left.toFloat()..sel.right.toFloat())
            // chosen text in the background colour (on the thumb), the rest in the main colour
            assertEquals(pal(a).bg, (sel as TextView).currentTextColor)
            for (i in 0 until row.childCount) if (i != row.selectedIndex()) {
                assertEquals(pal(a).fg, (row.getChildAt(i) as TextView).currentTextColor)
            }
        }
        assertTrue(a.findViewById<View>(R.id.set_boost_normal).isSelected)
        assertTrue(a.findViewById<View>(R.id.set_bass_punchy).isSelected)
        assertTrue(a.findViewById<View>(R.id.set_fade_4).isSelected)
    }

    @Test
    fun tappingAChoiceSavesItAndSlidesTheThumb() {
        app.preferences.volumeBoost = AppPreferences.BOOST_OFF
        app.preferences.gestureDoubleTapAction = AppPreferences.GESTURE_DTAP_PLAY_PAUSE
        val a = openSettings()
        val loud = a.findViewById<View>(R.id.set_boost_loud)
        loud.performClick()
        idle(1)
        frame(a)
        assertEquals(AppPreferences.BOOST_LOUD, app.preferences.volumeBoost)
        val row = loud.parent as SegmentRow
        assertEquals(2, row.selectedIndex())
        assertTrue(row.thumbRect().centerX() in loud.left.toFloat()..loud.right.toFloat())
        a.findViewById<View>(R.id.set_dtap_like).performClick()
        idle(1)
        assertEquals(AppPreferences.GESTURE_DTAP_LIKE, app.preferences.gestureDoubleTapAction)
        a.findViewById<View>(R.id.set_boost_off).performClick()
        idle(1)
        assertEquals(AppPreferences.BOOST_OFF, app.preferences.volumeBoost)
    }

    @Test
    fun privacyNotesFoldOpenAndShut() {
        val a = openSettings()
        for ((toggle, body) in listOf(
            R.id.account_privacy_toggle to R.id.account_privacy,
            R.id.set_gemini_privacy_toggle to R.id.set_gemini_privacy
        )) {
            assertEquals(View.GONE, a.findViewById<View>(body).visibility)
            a.findViewById<View>(toggle).performClick()
            idle(1)
            assertEquals(View.VISIBLE, a.findViewById<View>(body).visibility)
            a.findViewById<View>(toggle).performClick()
            idle(1)
            assertEquals(View.GONE, a.findViewById<View>(body).visibility)
        }
    }

    @Test
    fun aboutShowsTheVersion_andTheModelRowOpensThePicker() {
        val a = openSettings()
        assertEquals("Minim 5.8.6", a.findViewById<TextView>(R.id.set_about).text.toString())
        assertTrue(a.findViewById<TextView>(R.id.set_about_build).text.startsWith("Build 58"))
        assertFalse(a.findViewById<TextView>(R.id.set_gemini_model_pick).text.contains("▾"))
        assertTrue(a.findViewById<View>(R.id.set_gemini_model_row).contentDescription.startsWith("Gemini model: "))
        a.findViewById<View>(R.id.set_gemini_model_row).performClick()
        idle(2)
        assertEquals(View.VISIBLE, a.findViewById<View>(R.id.sheet_root).visibility)
    }

    @Test
    fun scrollsDownAndBackWithoutCrashing() {
        val a = openSettings()
        val scroll = a.findViewById<android.widget.ScrollView>(R.id.screen_settings)
        for (y in listOf(800, 2000, 4000, 0)) {
            scroll.scrollTo(0, y)
            frame(a)
        }
        a.onBackPressedDispatcher.onBackPressed()
        idle(2)
        assertFalse(a.isFinishing)
    }
}
