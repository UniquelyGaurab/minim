package com.minim.music.data.local

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.minim.music.data.model.Track
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException

/** The 5.9.1 file copy from the review: low storage, kills at each step, half-done renames. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LibraryFilesTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val target get() = context.getDatabasePath(LibraryFiles.FILE_NAME)
    private val legacyFile get() = context.getDatabasePath(LibraryDatabase.DB_NAME)
    private val prev get() = context.getDatabasePath(LibraryFiles.PREVIOUS_NAME)
    private val sfx = listOf("", "-wal", "-shm", "-journal")

    @Before fun clean() = TestLibraryFiles.wipe(context)
    @After fun done() = TestLibraryFiles.wipe(context)

    private fun song(id: String) = Track(id, "Song $id", "Arijit Singh", 200L, "https://img/$id", "https://www.youtube.com/watch?v=$id")

    private fun with591(vararg likes: String) {
        val db = TestLibraryFiles.legacy(context)
        runBlocking { likes.forEach { db.toggleLike(song(it)) } }
        db.close()
    }

    private fun likedIn(name: String): Set<String> {
        val db = LibraryDatabase(context, name)
        try {
            return runBlocking { db.getLikedTracks() }.map { it.id }.toSet()
        } finally {
            db.close()
        }
    }

    private fun leftovers() = context.getDatabasePath("x").parentFile!!.listFiles()!!.map { it.name }
        .filter { it.contains(".tmp") }

    private fun touch591() = legacyFile.setLastModified(System.currentTimeMillis() + 120_000L)

    @Test
    fun lowStorageIsCheckedFirstAndLeavesNoPartialFiles() {
        with591("A")
        LibraryFiles.freeSpace = { 1024L }
        var copied = false
        LibraryFiles.afterCopyingFile = { copied = true }
        assertEquals(LibraryDatabase.DB_NAME, LibraryFiles.prepare(context))
        assertFalse("nothing copied", copied)
        assertFalse(target.exists())
        assertEquals(emptyList<String>(), leftovers())
        // space again: the next start copies, with what was written meanwhile
        LibraryFiles.resetHooksForTest()
        val db = LibraryDatabase(context, LibraryDatabase.DB_NAME)
        runBlocking { db.toggleLike(song("B")) }
        db.close()
        assertEquals(LibraryFiles.FILE_NAME, LibraryFiles.prepare(context))
        assertEquals(setOf("A", "B"), likedIn(LibraryFiles.FILE_NAME))
    }

    @Test
    fun lowStorageOnTheFirstUpgradeShowsANoteOnceAndRetriesTheCopy() {
        with591("A")
        LibraryFiles.freeSpace = { 1024L }
        assertEquals(LibraryDatabase.DB_NAME, LibraryFiles.prepare(context))
        assertEquals(LibraryFiles.Note.LOW_SPACE, LibraryFiles.takeNote(context))
        assertEquals("shown once", null, LibraryFiles.takeNote(context))
        // still low on the next start: still the 5.9.1 file, no second note
        assertEquals(LibraryDatabase.DB_NAME, LibraryFiles.prepare(context))
        assertEquals(null, LibraryFiles.takeNote(context))
        // space again: copied, and the note is reset for a later episode
        LibraryFiles.freeSpace = { Long.MAX_VALUE }
        assertEquals(LibraryFiles.FILE_NAME, LibraryFiles.prepare(context))
        assertEquals(null, LibraryFiles.takeNote(context))
        assertEquals(setOf("A"), likedIn(LibraryFiles.FILE_NAME))
        assertFalse(context.getSharedPreferences(LibraryFiles.PREFS, 0).contains("note_shown"))
    }

    @Test
    fun aDiskFillingUpCountsAsLowSpaceAndOtherFailuresGetTheirOwnNote() {
        with591("A")
        LibraryFiles.afterCopyingFile = { throw IOException("ENOSPC (No space left on device)") }
        assertEquals(LibraryDatabase.DB_NAME, LibraryFiles.prepare(context))
        assertEquals(LibraryFiles.Note.LOW_SPACE, LibraryFiles.takeNote(context))
        LibraryFiles.afterCopyingFile = null
        assertEquals(LibraryFiles.FILE_NAME, LibraryFiles.prepare(context))
        TestLibraryFiles.wipe(context)
        with591("A")
        LibraryFiles.beforePlacingCopy = { error("could not move the library copy into place") }
        assertEquals(LibraryDatabase.DB_NAME, LibraryFiles.prepare(context))
        assertEquals(LibraryFiles.Note.COPY_FAILED, LibraryFiles.takeNote(context))
    }

    @Test
    fun noNoteWhenTheCopyWorksOrAfterARollbackKeepsTheOldCopy() {
        with591("A")
        assertEquals(LibraryFiles.FILE_NAME, LibraryFiles.prepare(context))
        assertEquals(null, LibraryFiles.takeNote(context))
        touch591()
        LibraryFiles.freeSpace = { 1024L }
        assertEquals(LibraryFiles.FILE_NAME, LibraryFiles.prepare(context))
        assertEquals("the 5.9.1 file is still untouched: going back is safe", null, LibraryFiles.takeNote(context))
    }

    @Test
    fun aDiskThatFillsUpDuringTheCopyLeavesNoPartialFiles() {
        with591("A")
        LibraryFiles.afterCopyingFile = { throw IOException("ENOSPC (No space left on device)") }
        assertEquals(LibraryDatabase.DB_NAME, LibraryFiles.prepare(context))
        assertFalse(target.exists())
        assertEquals(emptyList<String>(), leftovers())
        assertFalse("size and time not saved", context.getSharedPreferences(LibraryFiles.PREFS, 0).contains("time"))
        LibraryFiles.resetHooksForTest()
        assertEquals(LibraryFiles.FILE_NAME, LibraryFiles.prepare(context))
        assertEquals(setOf("A"), likedIn(LibraryFiles.FILE_NAME))
    }

    @Test
    fun lowStorageAfterARollbackKeepsUsingTheOldCopy() {
        with591("A")
        assertEquals(LibraryFiles.FILE_NAME, LibraryFiles.prepare(context))
        val db = LibraryDatabase(context, LibraryFiles.FILE_NAME)
        runBlocking { db.toggleLike(song("N")) } // made on 5.10.0
        db.close()
        touch591() // used 5.9.1 since
        LibraryFiles.afterCopyingFile = { throw IOException("ENOSPC") }
        assertEquals(LibraryFiles.FILE_NAME, LibraryFiles.prepare(context))
        assertEquals(setOf("A", "N"), likedIn(LibraryFiles.FILE_NAME))
        assertFalse(prev.exists())
        assertEquals(emptyList<String>(), leftovers())
        // and the change is still seen once there is space
        LibraryFiles.resetHooksForTest()
        assertEquals(LibraryFiles.FILE_NAME, LibraryFiles.prepare(context))
        assertTrue("old copy kept for its log", prev.exists())
        assertEquals(setOf("A", "N"), likedIn(LibraryFiles.PREVIOUS_NAME))
    }

    @Test
    fun aKillBeforeTheSizeAndTimeAreSavedCountsAsChanged() {
        with591("A")
        LibraryFiles.prepare(context)
        val db = LibraryDatabase(context, LibraryFiles.FILE_NAME)
        runBlocking { db.toggleLike(song("N")) }
        db.close()
        // an older build was killed after the rename and before saving the size and time
        context.getSharedPreferences(LibraryFiles.PREFS, 0).edit().clear().commit()
        // 5.9.1 is used later (its file's time may even end up the same)
        val back = TestLibraryFiles.legacy(context)
        runBlocking { back.toggleLike(song("Y")) }
        back.close()
        assertEquals(LibraryFiles.FILE_NAME, LibraryFiles.prepare(context))
        assertEquals("fresh copy of 5.9.1", setOf("A", "Y"), likedIn(LibraryFiles.FILE_NAME))
        assertEquals("old copy kept for Fermata to merge", setOf("A", "N"), likedIn(LibraryFiles.PREVIOUS_NAME))
        assertTrue(context.getSharedPreferences(LibraryFiles.PREFS, 0).contains("time"))
    }

    @Test
    fun aKillBetweenSavingTheSizeAndPlacingTheCopyCopiesAgain() {
        with591("A")
        LibraryFiles.beforePlacingCopy = { throw IllegalStateException("killed") }
        assertEquals(LibraryDatabase.DB_NAME, LibraryFiles.prepare(context))
        assertFalse(target.exists())
        LibraryFiles.resetHooksForTest()
        assertEquals(LibraryFiles.FILE_NAME, LibraryFiles.prepare(context))
        assertEquals(setOf("A"), likedIn(LibraryFiles.FILE_NAME))
        assertEquals(emptyList<String>(), leftovers())
    }

    private fun copyWithWal() {
        with591("A")
        LibraryFiles.prepare(context)
        // leave the copy with a WAL beside it, the way a running app does
        val db = LibraryDatabase(context, LibraryFiles.FILE_NAME)
        db.writableDatabase.enableWriteAheadLogging()
        runBlocking { db.toggleLike(song("N")) }
        File(target.path + "-wal").let { if (!it.exists()) it.writeBytes(ByteArray(0)) }
        db.close()
        File(target.path + "-wal").let { if (!it.exists()) it.writeBytes(ByteArray(0)) }
        touch591()
    }

    @Test
    fun aRenameThatFailsHalfwayIsUndone() {
        copyWithWal()
        val real = LibraryFiles.rename
        LibraryFiles.rename = { from, to -> if (from.name.endsWith("-wal")) false else real(from, to) }
        assertEquals(LibraryFiles.FILE_NAME, LibraryFiles.prepare(context))
        assertTrue(target.exists())
        assertFalse("nothing left half moved", prev.exists())
        assertEquals(setOf("A", "N"), likedIn(LibraryFiles.FILE_NAME))
    }

    @Test
    fun aKillHalfwayThroughTheRenameIsUndoneOnTheNextStart() {
        copyWithWal()
        val real = LibraryFiles.rename
        var moves = 0
        LibraryFiles.rename = { from, to ->
            if (to.name.startsWith(LibraryFiles.PREVIOUS_NAME) && ++moves == 2) throw IllegalStateException("killed")
            real(from, to)
        }
        try {
            LibraryFiles.prepare(context)
        } catch (_: IllegalStateException) {
        }
        assertFalse("the main file was moved, the WAL not yet", target.exists())
        // next start, with 5.9.1 untouched since: the old copy comes back and is used as it was
        LibraryFiles.rename = real
        context.getSharedPreferences(LibraryFiles.PREFS, 0).edit()
            .putLong("time", maxOf(legacyFile.lastModified(), File(legacyFile.path + "-wal").lastModified()))
            .putLong("size", sfx.take(2).sumOf { File(legacyFile.path + it).let { f -> if (f.exists()) f.length() else 0L } })
            .commit()
        assertEquals(LibraryFiles.FILE_NAME, LibraryFiles.prepare(context))
        assertEquals("not an empty library", setOf("A", "N"), likedIn(LibraryFiles.FILE_NAME))
        assertFalse(prev.exists())
    }

    @Test
    fun aKillHalfwayThroughTheRenameThenAFullMoveKeepsEverything() {
        copyWithWal()
        val real = LibraryFiles.rename
        var moves = 0
        LibraryFiles.rename = { from, to ->
            if (to.name.startsWith(LibraryFiles.PREVIOUS_NAME) && ++moves == 2) throw IllegalStateException("killed")
            real(from, to)
        }
        runCatching { LibraryFiles.prepare(context) }
        LibraryFiles.rename = real
        assertEquals(LibraryFiles.FILE_NAME, LibraryFiles.prepare(context))
        assertEquals(setOf("A"), likedIn(LibraryFiles.FILE_NAME))
        assertEquals("the old copy moved whole", setOf("A", "N"), likedIn(LibraryFiles.PREVIOUS_NAME))
    }
}
