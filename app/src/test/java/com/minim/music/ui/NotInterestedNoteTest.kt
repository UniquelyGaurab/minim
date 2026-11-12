package com.minim.music.ui

import android.os.Looper
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.minim.music.MinimApp
import com.minim.music.R
import com.minim.music.data.fermata.RestoreOfferGate
import com.minim.music.data.model.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import java.time.Duration

/** 6.1.1 (dev's review): the "not interested" note says the radio drops the song too, and has Undo. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = LaunchTestApp::class, qualifiers = "w411dp-h891dp-xxhdpi")
class NotInterestedNoteTest {

    private val app get() = ApplicationProvider.getApplicationContext<MinimApp>()

    private fun idle(seconds: Long = 1) {
        repeat(seconds.toInt() * 4) {
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(250))
            Thread.sleep(20)
        }
    }

    private fun waitFor(check: () -> Boolean) {
        repeat(20) { if (check()) return; idle(1) }
    }

    private fun launch(): MainActivity {
        app.preferences.welcomeDone = true
        RestoreOfferGate(app).markAnswered()
        val c = Robolectric.buildActivity(MainActivity::class.java)
        c.setup()
        idle(3)
        return c.get()
    }

    private fun track(id: String) = Track(id, "Song $id", "Artist", 200L, "", "https://www.youtube.com/watch?v=$id")

    private fun hide(a: MainActivity, t: Track) {
        val m = MainActivity::class.java.getDeclaredMethod("markNotInterested", Track::class.java)
        m.isAccessible = true
        m.invoke(a, t)
    }

    private fun note(a: MainActivity) = a.findViewById<TextView>(R.id.minim_note)

    @Test
    fun theNoteSaysTheRadioToo() {
        val a = launch()
        val text = a.getString(R.string.not_interested_done)
        assertTrue("says the song leaves the radio: $text", text.contains("radio", ignoreCase = true))
        assertEquals("Undo", a.getString(R.string.not_interested_undo))
        // one line: short enough that Undo is never cut off
        assertTrue((text + "   Undo").length <= 34)
    }

    @Test
    fun undoOnTheNoteBringsTheSongBack() {
        val a = launch()
        val t = track("songUUUUUUU")
        hide(a, t)
        waitFor { app.database.notInterestedIds.value.contains(t.id) && note(a).text.contains(a.getString(R.string.not_interested_undo)) }
        assertEquals(setOf(t.id), app.database.notInterestedIds.value)
        val shown = note(a).text.toString()
        assertEquals(a.getString(R.string.not_interested_done) + "   " + a.getString(R.string.not_interested_undo), shown)
        assertTrue("the note can be tapped", note(a).isClickable)

        note(a).performClick()
        waitFor { note(a).text.toString() == a.getString(R.string.not_interested_undone) }
        assertEquals(a.getString(R.string.not_interested_undone), note(a).text.toString())
        assertEquals(emptySet<String>(), app.database.notInterestedIds.value)
        assertFalse("the plain note can't be tapped again", note(a).isClickable)
        // Fermata recorded the undo: the last taste event for the song is "interested".
        val last = app.fermata.events().map { it.body }
            .filter { it is com.minim.fermata.log.EventBody.NotInterested || it is com.minim.fermata.log.EventBody.Interested }
        assertEquals(listOf("NotInterested", "Interested"), last.map { it::class.simpleName })
    }

    @Test
    fun tappingUndoTwiceUndoesOnce() {
        val a = launch()
        val t = track("songVVVVVVV")
        hide(a, t)
        waitFor { note(a).isClickable }
        note(a).performClick()
        note(a).performClick()
        waitFor { note(a).text.toString() == a.getString(R.string.not_interested_undone) }
        val undos = app.fermata.events().count { it.body is com.minim.fermata.log.EventBody.Interested }
        assertEquals(1, undos)
    }
}
