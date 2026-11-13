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
        // 5.10.1 (V6): the account rows are gone with sign-in; the profile replaces them
        "screen_settings", "settings_content", "settings_close", "profile_photo", "profile_hint",
        "settings_name", "set_theme_light", "set_theme_dark", "set_colours_dynamic", "set_colours_default",
        "set_boost_off", "set_boost_normal", "set_boost_loud", "set_bass_off", "set_bass_warm",
        "set_bass_punchy", "set_bass_heavy", "set_fade_off", "set_fade_2", "set_fade_4",
        "set_lyrics_flow", "set_lyrics_line", "set_lyrics_minus", "set_lyrics_value", "set_lyrics_plus",
        "set_swipe_skip", "set_swipe_off", "set_dtap_play", "set_dtap_like", "set_dtap_off",
        "set_lpress_lyrics", "set_lpress_off", "set_haptics_on", "set_haptics_off", "set_gemini_header",
        "set_gemini_model_pick", "set_gemini_key_status", "set_gemini_key_add", "set_gemini_key_remove",
        // 5.10.1: the App Check token box went with Firebase
        "set_offline_storage",
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
        // the profile photo and name are in You; the update check and credits are in About
        assertTrue(xml.indexOf("@+id/profile_photo") in order[0] until order[1])
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
    fun versionIs650Build67() {
        val gradle = file("build.gradle.kts")
        assertTrue(gradle.contains("versionCode = 67"))
        assertTrue(gradle.contains("versionName = \"6.5.0\""))
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
        assertEquals("Minim 6.5.0", a.findViewById<TextView>(R.id.set_about).text.toString())
        assertTrue(a.findViewById<TextView>(R.id.set_about_build).text.startsWith("Build 67"))
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

    /**
     * 5.10.1 (V6): Profile, Backup & restore, the old groups, Clear history last. 6.5.0: the notice
     * moved above Profile, so it's the first thing under the Settings title; the rest keep their order.
     */
    @Test
    fun v6SettingsOrder() {
        val a = openSettings()
        val you = a.findViewById<View>(R.id.settings_group_you)
        val list = you.parent as ViewGroup
        val order = listOf(
            R.id.settings_v6_notice, R.id.settings_group_you, R.id.settings_group_backup,
            R.id.settings_group_look, R.id.settings_group_sound, R.id.settings_group_lyrics,
            R.id.settings_group_gestures, R.id.settings_group_gemini, R.id.settings_group_storage,
            R.id.settings_group_about, R.id.settings_group_danger
        ).map { id -> list.indexOfChild(a.findViewById(id)) }
        assertTrue(order.toString(), order.all { it >= 0 })
        assertEquals(order.sorted(), order)
        assertEquals("the notice comes straight after the Settings title row", 1, order.first())
        assertEquals("Profile comes straight after the notice", order.first() + 1, order[1])
        val danger = a.findViewById<View>(R.id.settings_group_danger)
        val lastShown = (0 until list.childCount).map { list.getChildAt(it) }.last { it.visibility == View.VISIBLE }
        assertEquals("Clear history's group is the last thing in Settings", danger, lastShown)
        assertTrue(isInside(a.findViewById(R.id.set_clear_history), danger))
    }

    private fun isInside(v: View, group: View): Boolean {
        var p: android.view.ViewParent? = v.parent
        while (p != null) { if (p === group) return true; p = p.parent }
        return false
    }

    /** 6.5.0: the folded line, and the full note's facts, with no company or app named. */
    @Test
    fun v6NoticeTextTellsTheFacts() {
        val a = openSettings()
        val line = a.findViewById<TextView>(R.id.settings_v6_notice_line).text.toString()
        assertEquals("No accounts. No cloud. Nobody watching you skip the same song at 3am.", line)
        val body = a.findViewById<TextView>(R.id.settings_v6_notice_body).text.toString()
        for (fact in listOf(
            "Google sign-in and Firebase are gone", "there are no accounts", "Minim collects nothing",
            "The old cloud copy of your library has been deleted",
            "Minim Fermata", "a folder you pick", "syncs your phones through that same folder",
            "recommendations are here too, in the radio and Discover", "worked out on your phone",
            "Built-in Gemini left with Firebase", "free Google AI Studio key under Gemini",
            "lyrics, translation and chat", "big streaming apps", "other clients",
        )) assertTrue("missing \"$fact\": $body", body.contains(fact))
        assertFalse(body, body.contains("coming next"))
        assertFalse(body, body.contains("is being deleted"))
        // every Fermata is "Minim Fermata"
        assertFalse(body, Regex("\\bFermata\\b").findAll(body).any { m -> !body.startsWith("Minim ", m.range.first - 6) })
        // roast the category, never a name
        for (name in listOf(
            "Spotify", "YouTube", "Apple", "Amazon", "Deezer", "Tidal", "SoundCloud", "JioSaavn", "Gaana",
            "Wynk", "Metrolist", "ViMusic", "InnerTune", "OuterTune", "Harmony", "NewPipe", "Wrapped",
        )) assertFalse("names $name", (line + body).contains(name, ignoreCase = true))
        // a few short paragraphs
        val paragraphs = body.split("\n\n")
        assertTrue(paragraphs.size.toString(), paragraphs.size in 3..5)
        assertTrue(body.length.toString(), body.length < 1000)
    }

    /** 6.1.1: the notice's body text is painted with the readable body colour, not muted. */
    @Test
    fun v6NoticeBodyIsReadable() {
        val a = openSettings()
        val body = a.findViewById<TextView>(R.id.settings_v6_notice_body)
        assertEquals("text:body", body.tag)
        assertTrue(body.currentTextColor != a.findViewById<TextView>(R.id.set_credits).currentTextColor)
    }

    /** 6.5.0: folded to one line on open; a tap on the card opens it in place, another folds it. */
    @Test
    fun v6NoticeExpandsAndCollapsesInPlace() {
        val a = openSettings()
        val notice = a.findViewById<View>(R.id.settings_v6_notice)
        val body = a.findViewById<View>(R.id.settings_v6_notice_body)
        val chev = a.findViewById<View>(R.id.settings_v6_notice_chev)
        val list = notice.parent as ViewGroup
        val at = list.indexOfChild(notice)
        assertEquals(View.VISIBLE, notice.visibility)
        assertEquals(View.GONE, body.visibility)
        val folded = notice.height
        assertTrue(folded > 0)

        assertTrue(notice.performClick())
        idle(1)
        frame(a)
        assertEquals(View.VISIBLE, body.visibility)
        assertEquals(180f, chev.rotation, 0.5f)
        assertTrue("opened card is taller ($folded -> ${notice.height})", notice.height > folded * 2)
        assertEquals("it opens in place", at, list.indexOfChild(notice))

        // tapping the open note (its body included) folds it again
        assertTrue(notice.performClick())
        idle(1)
        frame(a)
        assertEquals(View.GONE, body.visibility)
        assertEquals(0f, chev.rotation, 0.5f)
        assertEquals(folded, notice.height)
        assertEquals(View.VISIBLE, notice.visibility)
    }

    /** 6.5.0: the notice can't be closed: no X, no About row to bring it back, no saved "closed" state. */
    @Test
    fun v6NoticeCannotBeDismissed() {
        // a phone that closed it on 6.0.0 to 6.2.0
        app.getSharedPreferences("minim_desk_prefs", android.content.Context.MODE_PRIVATE)
            .edit().putBoolean(AppPreferences.KEY_V6_NOTICE_DISMISSED, true).commit()
        AppPreferences(app)
        assertFalse(
            "the old closed flag is removed",
            app.getSharedPreferences("minim_desk_prefs", android.content.Context.MODE_PRIVATE)
                .contains(AppPreferences.KEY_V6_NOTICE_DISMISSED)
        )
        app.getSharedPreferences("minim_desk_prefs", android.content.Context.MODE_PRIVATE)
            .edit().putBoolean(AppPreferences.KEY_V6_NOTICE_DISMISSED, true).commit()
        val a = openSettings()
        val notice = a.findViewById<View>(R.id.settings_v6_notice)
        assertEquals("shown even with the old flag set", View.VISIBLE, notice.visibility)

        for (gone in listOf("settings_v6_notice_close", "set_v6_notice_read")) {
            assertEquals(gone, 0, a.resources.getIdentifier(gone, "id", a.packageName))
        }
        for (gone in listOf("v6_notice_close", "v6_notice_about_label", "v6_notice_read", "v6_notice_title")) {
            assertEquals(gone, 0, a.resources.getIdentifier(gone, "string", a.packageName))
        }
        // nothing inside the card takes a tap of its own (the whole card is the toggle)
        fun clickables(v: View, out: MutableList<View> = mutableListOf()): List<View> {
            if (v !== notice && (v.isClickable || v.hasOnClickListeners())) out += v
            if (v is ViewGroup) for (i in 0 until v.childCount) clickables(v.getChildAt(i), out)
            return out
        }
        assertEquals(emptyList<View>(), clickables(notice))
        // no "Why there's no account" row under About
        val about = a.findViewById<ViewGroup>(R.id.settings_group_about)
        fun texts(v: View, out: MutableList<String> = mutableListOf()): List<String> {
            if (v is TextView) out += v.text.toString()
            if (v is ViewGroup) for (i in 0 until v.childCount) texts(v.getChildAt(i), out)
            return out
        }
        assertFalse(texts(about).toString(), texts(about).any { it.contains("no account", ignoreCase = true) || it == "What changed in V6" })

        // open and fold it a few times, then reopen Settings: it's still there
        repeat(3) { notice.performClick(); idle(1) }
        val b = openSettings()
        assertEquals(View.VISIBLE, b.findViewById<View>(R.id.settings_v6_notice).visibility)
        assertEquals(View.GONE, b.findViewById<View>(R.id.settings_v6_notice_body).visibility)
    }

    /** 5.10.1: Take photo's file survives Android closing Minim while the camera is open. */
    @Test
    fun takePhotoFileSurvivesTheActivityBeingRecreated() {
        app.preferences.welcomeDone = true
        val c = Robolectric.buildActivity(MainActivity::class.java).setup()
        idle(3)
        val f = MainActivity::class.java.getDeclaredField("profileCameraFile").apply { isAccessible = true }
        val shot = File(app.cacheDir, "profile/camera.jpg")
        f.set(c.get(), shot)
        val state = android.os.Bundle()
        c.saveInstanceState(state)
        c.pause().stop().destroy()
        val again = Robolectric.buildActivity(MainActivity::class.java).create(state)
        idle(2)
        assertEquals(shot, f.get(again.get()))
    }
}
