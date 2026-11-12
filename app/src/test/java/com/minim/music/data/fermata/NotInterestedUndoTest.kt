package com.minim.music.data.fermata

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.minim.fermata.log.EventBody
import com.minim.fermata.log.TrackRef
import com.minim.fermata.recs.DropReason
import com.minim.music.data.local.LibraryDatabase
import com.minim.music.data.local.TestLibraryFiles
import com.minim.music.data.model.Track
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Clock

/**
 * 6.1.1 (the dev's 6.1 review): "not interested" can be undone. The song comes back on this
 * phone's screens, in Fermata (so the radio takes it again) and, after a merge, on the other phone.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NotInterestedUndoTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val opened = mutableListOf<LibraryDatabase>()

    @Before fun clean() = wipe()

    @After fun close() {
        opened.forEach { runCatching { it.close() } }
        wipe()
    }

    private fun wipe() {
        TestLibraryFiles.wipe(context)
        context.deleteDatabase(PHONE_B)
        context.getSharedPreferences(FermataStore.STATE_PREFS, Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences(FermataStore.DEVICE_PREFS, Context.MODE_PRIVATE).edit().clear().commit()
    }

    private fun phone(name: String? = null, device: String): Pair<LibraryDatabase, FermataBridge> {
        val lib = (if (name == null) LibraryDatabase(context) else LibraryDatabase(context, name)).also { opened += it }
        val b = FermataBridge(context, lib, "6.1.1-test", deviceId = device, clock = Clock.systemDefaultZone())
        b.start()
        return lib to b
    }

    private fun song(id: String) =
        Track(id, "Song $id", "Arijit Singh", 200L, "https://img/$id", "https://www.youtube.com/watch?v=$id")

    private fun ref(t: Track) = TrackRef(t.id, t.title, t.artist, t.durationSec * 1000)

    /** Past cold start: 30 full plays across 10 songs (none of them the song under test). */
    private fun FermataBridge.warm() {
        repeat(30) { fermata.record(EventBody.Play(ref(song("w${it % 10}")), 180_000)) }
    }

    private fun hidden(lib: LibraryDatabase) = lib.notInterestedIds.value

    private fun inRadio(b: FermataBridge, t: Track): Boolean {
        b.fermata.recs.warmUp()
        val out = b.fermata.recs.radio(listOf(ref(t), ref(song("w0"))))
        assertNotNull("past cold start", out)
        out!!
        return out.tracks.any { it.id == t.id }
    }

    private fun droppedAsNotInterested(b: FermataBridge, t: Track): Boolean {
        b.fermata.recs.warmUp()
        return b.fermata.recs.radio(listOf(ref(t)))!!.dropped.any { it.track.id == t.id && it.reason == DropReason.NOT_INTERESTED }
    }

    @Test
    fun undoBringsTheSongBackOnScreenAndInTheRadio() {
        val (lib, b) = phone(device = "phoneA")
        b.warm()
        val s = song("S")
        runBlocking { lib.markNotInterested(s) }
        assertEquals(setOf("S"), hidden(lib))
        assertTrue("hidden songs leave the radio", droppedAsNotInterested(b, s))

        val restored = runBlocking { lib.unmarkNotInterested("S") }
        assertTrue(restored)
        assertEquals(emptySet<String>(), hidden(lib))
        assertTrue("Undo puts it back in the radio", inRadio(b, s))
        assertFalse(droppedAsNotInterested(b, s))
        // Exactly one "interested" went to the log, after the one "not interested".
        val kinds = b.fermata.events().map { it.body }.filter { it is EventBody.NotInterested || it is EventBody.Interested }
        assertEquals(listOf("NotInterested", "Interested"), kinds.map { it::class.simpleName })

        // A second undo does nothing, and hiding again works again.
        assertFalse(runBlocking { lib.unmarkNotInterested("S") })
        runBlocking { lib.markNotInterested(s) }
        assertTrue(droppedAsNotInterested(b, s))
    }

    @Test
    fun undoSurvivesARebuildOfTheModel() {
        val (lib, b) = phone(device = "phoneA")
        b.warm()
        val s = song("S")
        runBlocking { lib.markNotInterested(s) }
        runBlocking { lib.unmarkNotInterested("S") }
        assertTrue(inRadio(b, s))
        // A new start (the app killed and opened again) builds the model from the log alone.
        val again = FermataBridge(context, lib, "6.1.1-test", deviceId = "phoneA", clock = Clock.systemDefaultZone())
        assertTrue(inRadio(again, s))
    }

    @Test
    fun aSongHiddenOnOnePhoneCanBeUndoneOnTheOther() {
        val (libA, a) = phone(device = "phoneA")
        val (libB, b) = phone(PHONE_B, device = "phoneB")
        a.warm()
        val s = song("S")
        runBlocking { libA.markNotInterested(s) }
        b.fermata.merge(a.fermata.events())
        assertEquals("B got A's hide", setOf("S"), hidden(libB))
        assertTrue(droppedAsNotInterested(b, s))

        Thread.sleep(5)
        assertTrue("B can undo a hide it only merged", runBlocking { libB.unmarkNotInterested("S") })
        assertTrue(inRadio(b, s))
        a.fermata.merge(b.fermata.events())
        assertEquals("A: B's undo wins", emptySet<String>(), hidden(libA))
        assertEquals(emptySet<String>(), hidden(libB))
        assertTrue("A's radio takes it back", inRadio(a, s))

        // And a later hide on A wins over B's older undo, on both phones.
        Thread.sleep(5)
        runBlocking { libA.markNotInterested(s) }
        b.fermata.merge(a.fermata.events())
        assertEquals(setOf("S"), hidden(libA))
        assertEquals(setOf("S"), hidden(libB))
        assertTrue(droppedAsNotInterested(b, s))
    }

    @Test
    fun anUndoComesBackWithARestore() {
        val (libA, a) = phone(device = "phoneA")
        a.warm()
        val s = song("S")
        runBlocking { libA.markNotInterested(s) }
        runBlocking { libA.unmarkNotInterested("S") }
        val events = a.fermata.events()
        val (libB, b) = phone(PHONE_B, device = "phoneB")
        b.fermata.restore(events)
        assertEquals(emptySet<String>(), hidden(libB))
        assertTrue(inRadio(b, s))
    }

    private companion object {
        const val PHONE_B = "phone_b_undo.db"
    }
}
