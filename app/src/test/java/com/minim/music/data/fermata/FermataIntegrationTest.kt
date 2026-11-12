package com.minim.music.data.fermata

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import com.minim.music.data.local.LibraryDatabase
import com.minim.music.data.local.LibraryFiles
import com.minim.music.data.local.TestLibraryFiles
import com.minim.music.data.model.Track
import com.minim.music.data.stats.Days
import com.minim.music.data.stats.ListenSlice
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

/**
 * 5.10.0: Minim writing through Fermata. The first-launch import, the rollback-safe copy of the
 * 5.9.1 database, the screens' numbers staying the same, clear history across phones, and other
 * phones' listening time while Firebase sign-in is on.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class FermataIntegrationTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val opened = mutableListOf<LibraryDatabase>()
    private val today = Days.keyOf(System.currentTimeMillis())

    @Before fun clean() {
        wipe()
    }

    @After fun close() {
        opened.forEach { runCatching { it.close() } }
        wipe()
        for (n in listOf("phoneB.db")) context.deleteDatabase(n)
    }

    private fun wipe() {
        TestLibraryFiles.wipe(context)
        context.getSharedPreferences(FermataStore.STATE_PREFS, Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences(FermataStore.DEVICE_PREFS, Context.MODE_PRIVATE).edit().clear().commit()
    }

    private fun <T : LibraryDatabase> keep(db: T): T = db.also { opened += it }
    private fun legacy() = keep(TestLibraryFiles.legacy(context))
    private fun current() = keep(LibraryDatabase(context))
    private fun bridge(lib: LibraryDatabase, device: String = "phoneA") =
        FermataBridge(context, lib, "5.10.0-test", deviceId = device)

    private fun song(id: String) =
        Track(id, "Song $id", "Arijit Singh", 200L, "https://img/$id", "https://www.youtube.com/watch?v=$id")

    private fun count(db: SQLiteDatabase, sql: String): Int =
        db.rawQuery(sql, null).use { if (it.moveToFirst()) it.getInt(0) else 0 }

    /** A 5.9.1 library with likes, a playlist, counted plays and listening time. */
    private fun seed591() = runBlocking {
        val old = legacy()
        old.toggleLike(song("A"))
        old.toggleLike(song("B"))
        val p = old.createPlaylist("Road trip")
        old.addTrackToPlaylist(p, song("A"))
        old.addTrackToPlaylist(p, song("C"))
        for (id in listOf("A", "A", "C")) {
            val row = old.recordCountedPlay(song(id), 60_000L)
            old.addListenTime(listOf(LibraryDatabase.ListenAdd(ListenSlice(today, 10, 60_000L), song(id))), row, 60_000L)
        }
        old.close()
        opened -= old
    }

    private data class Screens(
        val liked: List<String>,
        val playlists: List<Pair<String, List<String>>>,
        val stats: List<String>,
        val dayTotals: Map<Int, Long>,
        val dayPlays: Int,
    )

    private fun screens(lib: LibraryDatabase) = runBlocking {
        Screens(
            liked = lib.getLikedTracks().map { it.id }.sorted(),
            playlists = lib.getPlaylists().map { it.name to lib.getPlaylistTracks(it.id).map { t -> t.id } }.sortedBy { it.first },
            stats = lib.getAllTrackStats().map { it.toString() }.sorted(),
            dayTotals = lib.getDayTotals(today - 1, today + 1),
            dayPlays = lib.getDayPlays(today).size,
        )
    }

    // ---- first-launch import -------------------------------------------------------------------

    @Test
    fun importCopiesThe591LibraryOnceAndRunningItAgainAddsNothing() {
        seed591()
        val lib = current()
        val b = bridge(lib)
        b.start()
        assertTrue(b.imported())
        val liked = b.fermata.library().likedTracks.map { it.track.id }.toSet()
        assertEquals(setOf("A", "B"), liked)
        val events = b.fermata.eventCount()
        assertTrue("import recorded events", events > 0)
        assertFalse(b.importOnce())
        // a second app start: same database, a new bridge
        val again = bridge(lib)
        again.start()
        assertFalse(again.importOnce())
        assertEquals(events, again.fermata.eventCount())
    }

    @Test
    fun aKillDuringTheImportLeavesNothingAndTheNextStartImports() {
        seed591()
        val lib = current()
        val b = bridge(lib)
        b.afterImportForTest = { throw IllegalStateException("process killed") }
        b.start() // the import's failure is caught; the app keeps going
        assertFalse(b.imported())
        assertEquals(0L, b.fermata.eventCount())
        b.afterImportForTest = null
        val next = bridge(lib)
        next.start()
        assertTrue(next.imported())
        assertEquals(setOf("A", "B"), next.fermata.library().likedTracks.map { it.track.id }.toSet())
    }

    // ---- rollback safety -----------------------------------------------------------------------

    @Test
    fun the591FileIsNeverWrittenAndTheCopyIsUsed() {
        seed591()
        val legacyFile = context.getDatabasePath(LibraryDatabase.DB_NAME)
        val before = legacyFile.readBytes().contentHashCode()
        val lib = current()
        assertEquals(LibraryFiles.FILE_NAME, lib.databaseName)
        bridge(lib).start()
        runBlocking { lib.toggleLike(song("D")) }
        assertEquals(setOf("A", "B", "D"), runBlocking { lib.getLikedTracks() }.map { it.id }.toSet())
        lib.close()
        opened -= lib
        assertEquals(before, legacyFile.readBytes().contentHashCode())
        // 5.9.1 opening its own file still sees exactly what it left
        val old = legacy()
        assertEquals(setOf("A", "B"), runBlocking { old.getLikedTracks() }.map { it.id }.toSet())
    }

    @Test
    fun downgradeTo591ThenUpgradeAgainMergesBothHistories() {
        seed591()
        var lib = current()
        bridge(lib).start()
        runBlocking { lib.toggleLike(song("X")) } // liked on 5.10.0
        lib.close(); opened -= lib

        // back on 5.9.1: it writes its own file
        val old = legacy()
        runBlocking { old.toggleLike(song("Y")) }
        old.close(); opened -= old
        val legacyFile = context.getDatabasePath(LibraryDatabase.DB_NAME)
        legacyFile.setLastModified(System.currentTimeMillis() + 60_000L)

        // 5.10.0 again: copies the newer 5.9.1 file and folds in the first copy's log
        lib = current()
        assertTrue(context.getDatabasePath(LibraryFiles.PREVIOUS_NAME).exists())
        val b = bridge(lib)
        b.start()
        assertFalse("the older copy is folded in and removed", context.getDatabasePath(LibraryFiles.PREVIOUS_NAME).exists())
        // no id was used twice
        val ids = b.fermata.events().map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        val liked = runBlocking { lib.getLikedTracks() }.map { it.id }.toSet()
        assertEquals(setOf("A", "B", "X", "Y"), liked)
        assertEquals(setOf("A", "B", "X", "Y"), b.fermata.library().likedTracks.map { it.track.id }.toSet())
    }

    // ---- the screens' numbers ------------------------------------------------------------------

    @Test
    fun afterTheUpgradeTheScreensShowTheSameNumbersAs591() {
        seed591()
        val old = legacy()
        val was = screens(old)
        old.close(); opened -= old
        val lib = current()
        val b = bridge(lib)
        b.start()
        assertEquals(was, screens(lib))
        // and Fermata agrees with the tables on likes
        assertEquals(was.liked.toSet(), b.fermata.library().likedTracks.map { it.track.id }.toSet())
    }

    // ---- two phones ----------------------------------------------------------------------------

    private fun phoneB(): Pair<LibraryDatabase, FermataBridge> {
        val lib = keep(LibraryDatabase(context, "phoneB.db"))
        val b = bridge(lib, "phoneB")
        b.start()
        return lib to b
    }

    private fun play(lib: LibraryDatabase, id: String, ms: Long = 60_000L) = runBlocking {
        val row = lib.recordCountedPlay(song(id), ms)
        lib.addListenTime(listOf(LibraryDatabase.ListenAdd(ListenSlice(today, 9, ms), song(id))), row, ms)
    }

    @Test
    fun clearedHistoryDoesNotComeBackFromAnotherPhonesBackup() {
        val libA = current()
        val a = bridge(libA)
        a.start()
        val (libB, b) = phoneB()
        play(libB, "Q")
        play(libB, "R")
        runBlocking { libB.toggleLike(song("Q")) }

        a.fermata.merge(b.fermata.events())
        assertEquals(2, runBlocking { libA.getDayPlays(today) }.size)

        runBlocking { libA.clearHistory() }
        assertEquals(0, runBlocking { libA.getDayPlays(today) }.size)
        assertEquals(0L, runBlocking { libA.getDayTotals(today, today) }.values.sum())

        // the next merge (or restore) brings phone B's file again: the plays stay cleared
        a.fermata.merge(b.fermata.events())
        assertEquals(0, runBlocking { libA.getDayPlays(today) }.size)
        assertEquals(0L, runBlocking { libA.getDayTotals(today, today) }.values.sum())
        // likes are not history: they stay
        assertEquals(listOf("Q"), runBlocking { libA.getLikedTracks() }.map { it.id })
        // a play after the clear still merges
        play(libB, "S")
        a.fermata.merge(b.fermata.events())
        assertEquals(1, runBlocking { libA.getDayPlays(today) }.size)
    }

    @Test
    fun otherPhonesListeningTimeMerges() {
        val (libB, b) = phoneB()
        play(libB, "Q", 120_000L)
        val libA = current()
        val a = bridge(libA)
        a.start()
        a.fermata.merge(b.fermata.events())
        assertEquals(120_000L, runBlocking { libA.getDayTotals(today, today) }.values.sum())
    }

    @Test
    fun aMergeThatChangesTheTablesTellsTheScreens() {
        val libA = current()
        val a = bridge(libA)
        a.start()
        val before = a.dataVersion.value
        val (libB, b) = phoneB()
        runBlocking { libB.toggleLike(song("Q")) }
        a.fermata.merge(b.fermata.events())
        assertTrue(a.dataVersion.value > before)
        assertTrue("Q" in libA.likedIds.value)
    }
}
