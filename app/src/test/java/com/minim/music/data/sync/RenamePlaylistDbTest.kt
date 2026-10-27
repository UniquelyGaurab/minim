package com.minim.music.data.sync

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.minim.music.data.local.LibraryDatabase
import com.minim.music.data.local.LibraryDatabase.RenameResult
import com.minim.music.data.model.Track
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 5.3.2: renaming a playlist (tap its title), on the phone and across cloud sync. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class RenamePlaylistDbTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private var lib: LibraryDatabase? = null

    @Before fun clean() { context.deleteDatabase(LibraryDatabase.DB_NAME) }

    @After fun close() {
        lib?.close()
        context.deleteDatabase(LibraryDatabase.DB_NAME)
    }

    private fun open(): LibraryDatabase = LibraryDatabase(context).also { lib = it }

    private fun track(id: String) =
        Track(id, "Song $id", "Artist", 200L, "https://img/$id", "https://www.youtube.com/watch?v=$id")

    private fun uidOf(l: LibraryDatabase, id: Long): String =
        l.readableDatabase.rawQuery("SELECT uid FROM playlists WHERE id = ?", arrayOf(id.toString())).use {
            it.moveToFirst(); it.getString(0)
        }

    private fun outbox(l: LibraryDatabase): List<String> =
        l.readableDatabase.rawQuery("SELECT k FROM sync_outbox ORDER BY id", null).use { c ->
            val out = ArrayList<String>()
            while (c.moveToNext()) out.add(c.getString(0))
            out
        }

    private fun playlistDoc(uid: String, s: PlState) = LibrarySync.RemoteDoc(
        uid,
        HashMap<String, Any?>(SyncCodec.playlistHeader(s)).apply {
            put("t", s.tracks.mapValues { SyncCodec.encodeEntry(it.value) })
        }
    )

    @Test
    fun renameKeepsSongsSourceAndSyncIdAndGoesUp() = runBlocking {
        val l = open()
        l.syncTracking = true
        val pid = l.createPlaylist("Chill Mix", "spotify")
        l.addTrackToPlaylist(pid, track("songAAAAAAA"))
        val uid = uidOf(l, pid)
        l.readableDatabase.execSQL("DELETE FROM sync_outbox")

        assertEquals(RenameResult.RENAMED, l.renamePlaylist(pid, "  Evening  "))
        val p = l.getPlaylists().single()
        assertEquals("Evening", p.name)
        assertEquals("spotify", p.source)
        assertEquals(1, p.trackCount)
        assertEquals(uid, uidOf(l, pid))
        assertTrue(outbox(l).contains("P|$uid"))
        // the header that goes up carries the new name, newer than before
        val state = LibrarySync(l).readLocalPlaylist(l.readableDatabase, uid)!!
        assertEquals("Evening", state.name)
    }

    @Test
    fun takenBlankAndSameNamesChangeNothing() = runBlocking {
        val l = open()
        val a = l.createPlaylist("Gym")
        l.createPlaylist("Focus")
        assertEquals(RenameResult.TAKEN, l.renamePlaylist(a, "Focus"))
        assertEquals(RenameResult.BLANK, l.renamePlaylist(a, "   "))
        assertEquals(RenameResult.SAME, l.renamePlaylist(a, "Gym "))
        assertEquals(RenameResult.MISSING, l.renamePlaylist(9999L, "New"))
        assertEquals(setOf("Gym", "Focus"), l.getPlaylists().map { it.name }.toSet())
    }

    @Test
    fun oldNameIsFreeAgainForANewPlaylist() = runBlocking {
        val l = open()
        val a = l.createPlaylist("Gym")
        l.addTrackToPlaylist(a, track("songAAAAAAA"))
        assertEquals(RenameResult.RENAMED, l.renamePlaylist(a, "Workout"))
        val b = l.createPlaylist("Gym")
        assertNotEquals(-1L, b)
        assertNotEquals(uidOf(l, a), uidOf(l, b))
        assertTrue(l.getPlaylistTracks(b).isEmpty())
        assertEquals(1, l.getPlaylistTracks(a).size)
    }

    @Test
    fun aNewerRenameFromAnotherPhoneArrivesAndAnOlderNameDoesNot() = runBlocking {
        val l = open()
        l.syncTracking = true
        val pid = l.createPlaylist("Gym")
        val uid = uidOf(l, pid)
        val sync = LibrarySync(l)
        val local = sync.readLocalPlaylist(l.readableDatabase, uid)!!

        // renamed on the other phone, later than this phone's header
        val renamed = local.copy(name = "Workout", changed = local.changed + 1L, tracks = emptyMap())
        sync.applyPlaylists(listOf(playlistDoc(uid, renamed)))
        assertEquals("Workout", l.getPlaylists().single().name)

        // renamed here after that: the other phone's older name doesn't put it back
        Thread.sleep(5)
        assertEquals(RenameResult.RENAMED, l.renamePlaylist(pid, "Run"))
        sync.applyPlaylists(listOf(playlistDoc(uid, renamed)))
        assertEquals("Run", l.getPlaylists().single().name)
    }
}
