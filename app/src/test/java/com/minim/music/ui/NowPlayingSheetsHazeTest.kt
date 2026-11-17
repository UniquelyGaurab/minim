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
import com.minim.music.data.model.Track
import com.minim.music.util.CrashReporter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
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
 * 6.8.0 (72): every sheet that opens from Now Playing (song menu, sleep timer, speed, the lyrics
 * card) goes through the one shared sheet, so under Haze it must be the frosted haze with the Haze
 * ink, and under Light/Dark the plain sheet it always was.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
@Config(sdk = [34], application = LaunchTestApp::class, qualifiers = "w411dp-h891dp-xxhdpi")
class NowPlayingSheetsHazeTest {

    private val app get() = ApplicationProvider.getApplicationContext<MinimApp>()
    private val track = Track("dQw4w9WgXcQ", "Song", "Artist", 200L, "", "https://www.youtube.com/watch?v=dQw4w9WgXcQ")

    @Before
    fun noOldReport() {
        CrashReporter.clear(app.filesDir)
    }

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

    private fun launch(theme: Int): MainActivity {
        app.preferences.themeMode = theme
        app.preferences.welcomeDone = true
        com.minim.music.data.fermata.RestoreOfferGate(app).markAnswered()
        val c = Robolectric.buildActivity(MainActivity::class.java)
        c.setup()
        idle(3)
        return c.get()
    }

    private fun texts(v: View, out: MutableList<TextView> = mutableListOf()): List<TextView> {
        if (v.visibility != View.VISIBLE) return out
        if (v is TextView && v.text.isNotBlank()) out += v
        if (v is ViewGroup) for (i in 0 until v.childCount) texts(v.getChildAt(i), out)
        return out
    }

    private val sheets: List<Pair<String, (MainActivity) -> Unit>> = listOf(
        "song menu" to { a -> call(a, "showTrackOptions", null, track, null, false, null, true) },
        "sleep timer" to { a -> call(a, "showSleepTimerSheet") },
        "speed" to { a -> call(a, "showSpeedSheet", track) },
    )

    private fun checkSheets(a: MainActivity, haze: Boolean) {
        val panel = a.findViewById<View>(R.id.sheet_panel)
        val content = a.findViewById<ViewGroup>(R.id.sheet_content)
        for ((name, open) in sheets) {
            open(a)
            frame(a)
            assertEquals("$name opens", View.VISIBLE, a.findViewById<View>(R.id.sheet_root).visibility)
            val bg = panel.background
            if (haze) {
                assertTrue("$name is frosted haze", bg is HazeDrawable && bg.sheet)
                val ink = pal(a).fg
                val inked = texts(content).count { it.currentTextColor == ink }
                assertTrue("$name text uses the Haze ink", inked > 0)
            } else {
                assertFalse("$name stays a plain sheet", bg is HazeDrawable)
            }
            call(a, "closeSheet", null)
            idle(2)
        }
        assertTrue(CrashReporter.pending(app.filesDir) == null)
    }

    @Test fun nowPlayingSheetsAreFrostedHazeUnderHaze() = checkSheets(launch(AppPreferences.THEME_HAZE), haze = true)

    @Test fun nowPlayingSheetsStayPlainOnDark() = checkSheets(launch(AppPreferences.THEME_DARK), haze = false)

    /** The lyrics card sheet and full-screen lyrics need a playing song; they use the same paths. */
    @Test fun lyricsCardAndFullscreenLyricsUseTheHazePaths() {
        val main = listOf(File("src/main"), File("app/src/main")).first { it.isDirectory }
        val activity = File(main, "java/com/minim/music/ui/MainActivity.kt").readText()
        val card = activity.indexOf("R.layout.sheet_lyrics_card")
        assertTrue(card > 0)
        assertTrue("lyrics card goes through openSheet", activity.lastIndexOf("openSheet {", card) > activity.indexOf("private fun showLyricsShare"))
        assertTrue(File(main, "res/layout/view_sheet.xml").readText().contains("haze:sheet"))
        val fs = File(main, "java/com/minim/music/ui/FullscreenLyrics.kt").readText()
        assertTrue("full-screen lyrics backdrop is painted by paintBg", fs.contains("Painter.paintBg(backdrop"))
    }
}
