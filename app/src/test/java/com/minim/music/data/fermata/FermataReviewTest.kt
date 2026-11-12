package com.minim.music.data.fermata

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import com.minim.fermata.log.EventBody
import com.minim.music.data.local.LibraryDatabase
import com.minim.music.data.local.TestLibraryFiles
import com.minim.music.data.model.Track
import com.minim.music.data.stats.Days
import com.minim.music.data.stats.ListenSlice
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Clock
import java.time.Duration

/**
 * The findings from the dev's review of the 5.10.0 integration, each reproduced first: the like
 * in the attach/import gap, a kill between the log and the screen tables, and two phones whose
 * screens disagreed with their logs after a two-way merge.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FermataReviewTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val opened = mutableListOf<LibraryDatabase>()
    private val today = Days.keyOf(System.currentTimeMillis())

    @Before fun clean() = wipe()

    @After fun close() {
        opened.forEach { runCatching { it.close() } }
        wipe()
        context.deleteDatabase("phoneB.db")
    }

    private fun wipe() {
        TestLibraryFiles.wipe(context)
        context.getSharedPreferences(FermataStore.STATE_PREFS, Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences(FermataStore.DEVICE_PREFS, Context.MODE_PRIVATE).edit().clear().commit()
    }

    private fun <T : LibraryDatabase> keep(db: T): T = db.also { opened += it }
    private fun legacy() = keep(TestLibraryFiles.legacy(context))
    private fun current() = keep(LibraryDatabase(context))
    private fun bridge(lib: LibraryDatabase, device: String = "phoneA", clock: Clock = Clock.systemDefaultZone()) =
        FermataBridge(context, lib, "5.10.0-test", deviceId = device, clock = clock)

    private fun phoneB(clock: Clock = Clock.systemDefaultZone()): Pair<LibraryDatabase, FermataBridge> {
        val lib = keep(LibraryDatabase(context, "phoneB.db"))
        val b = bridge(lib, "phoneB", clock)
        b.start()
        return lib to b
    }

    private fun song(id: String) =
        Track(id, "Song $id", "Arijit Singh", 200L, "https://img/$id", "https://www.youtube.com/watch?v=$id")

    private fun liked(lib: LibraryDatabase) = runBlocking { lib.getLikedTracks() }.map { it.id }.toSet()

    /** What the screens show must be what the log says, for every like and playlist. */
    private fun assertScreensMatchLog(lib: LibraryDatabase, b: FermataBridge, phone: String) {
        val log = b.foldedDocs()
        val loggedLikes = log.filterKeys { it.startsWith("likes/") }
            .filterValues { it["l"] == true }.keys.map { it.removePrefix("likes/") }.toSet()
        assertEquals("$phone: liked songs on screen vs. in the log", loggedLikes, liked(lib))
        assertEquals("$phone: liked songs on screen vs. Fermata's library", b.fermata.library().likedTracks.map { it.track.id }.toSet(), liked(lib))
        val loggedPlaylists = log.filterKeys { it.startsWith("playlists/") }
            .filterValues { it["d"] != true && it["n"] != null }.values.map { it["n"] as String }.toSet()
        assertEquals("$phone: playlists on screen vs. in the log", loggedPlaylists, runBlocking { lib.getPlaylists() }.map { it.name }.toSet())
    }

    // ---- blocker 1: writes between attach() and the import ------------------------------------

    private fun oldFermataPlay(db: SQLiteDatabase, id: Long, track: String, device: String) {
        val now = System.currentTimeMillis() - 3_600_000L + id * 60_000L
        db.insertOrThrow("fermata_events", null, ContentValues().apply {
            put("id", id); put("type", EventBody.PLAY); put("track_id", track); put("title", "Song $track")
            put("artist", "Arijit Singh"); put("duration_ms", 200_000L); put("ts", now); put("hour", 10); put("dow", 3)
            put("session_id", "s1"); put("source", "search"); put("listened_ms", 120_000L); put("position_ms", 0L)
            put("device_id", device); put("speed_pct", 100)
        })
    }

    @Test
    fun aLikeBetweenAttachAndTheImportKeepsAllThreeOld590Plays() {
        val old = legacy()
        val db = old.writableDatabase
        for (i in 1L..3L) oldFermataPlay(db, i, "P$i", "phoneA")
        old.close(); opened -= old

        val lib = current()
        val b = bridge(lib)
        b.attach()
        runBlocking { lib.toggleLike(song("X")) } // the gap: the import has not run yet
        b.finishStart()

        val plays = b.fermata.events().filter { it.body is EventBody.Play && it.id.device == "phoneA" }
        assertEquals("all three 5.9.0 plays survive", setOf(1L, 2L, 3L), plays.map { it.id.counter }.toSet())
        assertTrue(b.fermata.library().isLiked("X"))
        val ids = b.fermata.events().map { it.id }
        assertEquals("no id used twice", ids.size, ids.toSet().size)
    }

    // ---- blocker 2: a kill between the log and the screen tables ------------------------------

    @Test
    fun aKillBetweenTheLogAndTheScreenTablesLeavesNeitherAndTheNextMergeAppliesIt() {
        val libA = current()
        val a = bridge(libA)
        a.start()
        val (libB, b) = phoneB()
        runBlocking { libB.toggleLike(song("Q")); libB.createPlaylist("Gym") }

        val before = a.fermata.eventCount()
        a.beforeApplyForTest = { throw IllegalStateException("process killed") }
        try {
            a.fermata.merge(b.fermata.events())
            fail("the merge should fail with the kill")
        } catch (_: IllegalStateException) {
        }
        assertEquals("nothing reached the log", before, a.fermata.eventCount())
        assertFalse("Q" in liked(libA))

        a.beforeApplyForTest = null
        a.fermata.merge(b.fermata.events())
        assertTrue("the next merge shows it", "Q" in liked(libA))
        assertEquals(listOf("Gym"), runBlocking { libA.getPlaylists() }.map { it.name })
        assertScreensMatchLog(libA, a, "A")
    }

    @Test
    fun aKillDuringARestoreLeavesNeitherTheLogNorTheTables() {
        val (libB, b) = phoneB()
        runBlocking { libB.toggleLike(song("Q")) }
        val libA = current()
        val a = bridge(libA)
        a.start()
        val before = a.fermata.eventCount()
        a.beforeApplyForTest = { throw IllegalStateException("process killed") }
        try {
            a.fermata.restore(b.fermata.events())
            fail("the restore should fail with the kill")
        } catch (_: IllegalStateException) {
        }
        assertEquals(before, a.fermata.eventCount())
        assertFalse("Q" in liked(libA))
        a.beforeApplyForTest = null
        a.fermata.restore(b.fermata.events())
        assertTrue("Q" in liked(libA))
        assertScreensMatchLog(libA, a, "A")
    }

    // ---- two phones showing different likes ---------------------------------------------------

    /**
     * Phone B's clock runs a minute fast. B unlikes Q; A merges B's backup, so A's clock rule now
     * stamps its events after B's. A then likes Q. Both logs end with A's like, but A used to put
     * its own wall-clock time (a minute "older" than B's unlike) inside the like, so B's screen
     * kept the unlike, and B's re-recorded unlike was dropped as a repeat of its last write.
     */
    @Test
    fun afterATwoWayMergeBothScreensMatchTheLogWhenOnePhonesClockIsAhead() {
        val libA = current()
        val a = bridge(libA)
        a.start()
        val (libB, b) = phoneB(Clock.offset(Clock.systemDefaultZone(), Duration.ofSeconds(60)))
        runBlocking { libB.toggleLike(song("Q")); libB.toggleLike(song("Q")) } // liked, then unliked
        assertFalse("Q" in liked(libB))

        a.fermata.merge(b.fermata.events())
        runBlocking { libA.toggleLike(song("Q")) } // A likes it after B's unlike
        assertTrue("Q" in liked(libA))

        // two-way: B merges A, A merges B
        b.fermata.merge(a.fermata.events())
        a.fermata.merge(b.fermata.events())
        b.fermata.merge(a.fermata.events())

        assertTrue("phone B shows the like", "Q" in liked(libB))
        assertTrue("phone A shows the like", "Q" in liked(libA))
        assertScreensMatchLog(libA, a, "A")
        assertScreensMatchLog(libB, b, "B")
    }

    /**
     * The other half of the mismatch: when the screen tables keep a newer state than the log's
     * latest write, that state is recorded again so the log catches up, even though it is the
     * same document this phone wrote before.
     */
    @Test
    fun whenTheTablesKeepANewerStateThanTheLogItIsRecordedAgain() {
        val libA = current()
        val a = bridge(libA)
        a.start()
        val (libB, b) = phoneB()
        runBlocking { libB.toggleLike(song("Q")) }
        a.fermata.merge(b.fermata.events())
        runBlocking { libA.toggleLike(song("Q")) } // A unlikes
        // B's like comes back to A with an older change time but a later place in the log
        // (a third phone passing B's old write on, for example): A keeps its newer unlike.
        val stale = b.fermata.events().first { (it.body as? EventBody.Unknown)?.json?.contains("likes/Q") == true }
        val replay = stale.copy(id = com.minim.fermata.log.EventId("phoneC", 1), ctx = stale.ctx.copy(timestamp = a.fermata.timestamp() + 10))
        a.fermata.merge(listOf(replay))
        assertFalse("Q" in liked(libA))
        assertScreensMatchLog(libA, a, "A")
    }

    // ---- playlists the same way --------------------------------------------------------------

    @Test
    fun afterATwoWayMergePlaylistsOnBothScreensMatchTheLog() {
        val libA = current()
        val a = bridge(libA)
        a.start()
        val (libB, b) = phoneB(Clock.offset(Clock.systemDefaultZone(), Duration.ofSeconds(60)))
        val p = runBlocking { libB.createPlaylist("Road trip") }
        runBlocking { libB.addTrackToPlaylist(p, song("A")) }
        a.fermata.merge(b.fermata.events())
        val pa = runBlocking { libA.getPlaylists() }.single()
        runBlocking { libA.renamePlaylist(pa.id, "Road trip 2026") }
        b.fermata.merge(a.fermata.events())
        a.fermata.merge(b.fermata.events())
        assertEquals(listOf("Road trip 2026"), runBlocking { libB.getPlaylists() }.map { it.name })
        assertScreensMatchLog(libA, a, "A")
        assertScreensMatchLog(libB, b, "B")
    }

    // ---- going back to 5.9.1 and upgrading again ---------------------------------------------

    private fun pause() = Thread.sleep(5)

    private fun play(lib: LibraryDatabase, id: String) = runBlocking {
        val row = lib.recordCountedPlay(song(id), 60_000L)
        lib.addListenTime(listOf(LibraryDatabase.ListenAdd(ListenSlice(today, 10, 60_000L), song(id))), row, 60_000L)
    }

    private fun playlistTracks(lib: LibraryDatabase) = runBlocking {
        lib.getPlaylists().associate { it.name to lib.getPlaylistTracks(it.id).map { t -> t.id } }
    }

    private fun playedSongs(lib: LibraryDatabase) = runBlocking { lib.getAllTrackStats() }
        .filter { it.playCount > 0 }.associate { it.track.id to it.playCount }

    @Test
    fun reUpgradeKeeps510UnlikesPlaylistRemovalsAndClears() {
        val old = legacy()
        runBlocking {
            old.toggleLike(song("A")); old.toggleLike(song("B"))
            val p = old.createPlaylist("Road trip")
            old.addTrackToPlaylist(p, song("A")); old.addTrackToPlaylist(p, song("C"))
            old.markNotInterested(song("H"))
        }
        play(old, "A"); play(old, "C")
        old.close(); opened -= old
        pause()

        // 5.10.0: unlike A, take C off the playlist, clear the history
        var lib = current()
        bridge(lib).start()
        pause()
        runBlocking {
            lib.toggleLike(song("A"))
            val p = lib.getPlaylists().single()
            lib.removeTrackFromPlaylist(p.id, "C")
            lib.clearHistory()
        }
        assertEquals(setOf("B"), liked(lib))
        lib.close(); opened -= lib
        pause()

        // back on 5.9.1 (adb install -r -d): it still has its own older file
        val back = legacy()
        runBlocking { back.toggleLike(song("Y")) }
        play(back, "D")
        back.close(); opened -= back
        context.getDatabasePath(LibraryDatabase.DB_NAME).setLastModified(System.currentTimeMillis() + 60_000L)

        // 5.10.0 again
        lib = current()
        val b = bridge(lib)
        b.start()
        assertEquals("A stays unliked, Y (liked on 5.9.1) comes in", setOf("B", "Y"), liked(lib))
        assertEquals("C stays off the playlist", mapOf("Road trip" to listOf("A")), playlistTracks(lib))
        assertEquals("only the play after the clear", mapOf("D" to 1), playedSongs(lib))
        assertEquals(1, runBlocking { lib.getDayPlays(today) }.size)
        runBlocking { lib.refreshNotInterestedCache() }
        assertTrue("not interested is kept", "H" in lib.notInterestedIds.value)
        assertScreensMatchLog(lib, b, "re-upgraded")
        assertEquals(setOf("D"), b.fermata.library().history.map { it.track.id }.toSet())
        val ids = b.fermata.events().map { it.id }
        assertEquals(ids.size, ids.toSet().size)
    }

    /** DESIGN.md's known limit: a song liked before the rollback and unliked on 5.9.1 must not come back liked. */
    @Test
    fun aLikeUndoneOn591StaysUndoneAfterReUpgrading() {
        val old = legacy()
        runBlocking { old.toggleLike(song("A")); old.toggleLike(song("B")) }
        old.close(); opened -= old
        pause()
        var lib = current()
        var b = bridge(lib)
        b.start()
        assertEquals(setOf("A", "B"), liked(lib))
        lib.close(); opened -= lib
        pause()
        // back on 5.9.1: unlike A there
        val back = legacy()
        runBlocking { back.toggleLike(song("A")) }
        back.close(); opened -= back
        context.getDatabasePath(LibraryDatabase.DB_NAME).setLastModified(System.currentTimeMillis() + 60_000L)
        lib = current()
        b = bridge(lib)
        b.start()
        assertEquals("A stays unliked", setOf("B"), liked(lib))
        assertEquals(setOf("B"), b.fermata.library().likedTracks.map { it.track.id }.toSet())
        assertScreensMatchLog(lib, b, "re-upgraded after an unlike on 5.9.1")
    }

    @Test
    fun reUpgradeWithoutChangesOn591AddsNoWrites() {
        val old = legacy()
        runBlocking { old.toggleLike(song("A")) }
        old.close(); opened -= old
        pause()
        var lib = current()
        var b = bridge(lib)
        b.start()
        runBlocking { lib.toggleLike(song("A")) } // unliked on 5.10.0
        val before = b.fermata.eventCount()
        lib.close(); opened -= lib
        context.getDatabasePath(LibraryDatabase.DB_NAME).setLastModified(System.currentTimeMillis() + 60_000L)
        lib = current()
        b = bridge(lib)
        b.start()
        assertEquals(emptySet<String>(), liked(lib))
        assertEquals("nothing recorded again", before, b.fermata.eventCount())
    }
}
