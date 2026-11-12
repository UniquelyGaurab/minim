package com.minim.music.data.sync

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import com.minim.music.data.local.LibraryDatabase
import com.minim.music.data.model.Track
import com.minim.music.data.stats.Days
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

/**
 * 5.10.0: Clear listening history reaches Firestore. A signed-in phone writes history/cleared and
 * deletes the day documents before it; another phone that pulls history/cleared clears too, and
 * cleared plays never come back from a pull or a full merge.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class HistoryClearSyncTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private var lib: LibraryDatabase? = null

    @Before fun clean() = com.minim.music.data.local.TestLibraryFiles.wipe(context)

    @After fun close() {
        lib?.close()
        com.minim.music.data.local.TestLibraryFiles.wipe(context)
    }

    private fun open(): LibraryDatabase = LibraryDatabase(context).also { lib = it }
    private fun track(id: String) = Track(id, "Song $id", "Artist", 200L, "https://img/$id", "https://www.youtube.com/watch?v=$id")
    private fun count(db: SQLiteDatabase, sql: String): Int = db.rawQuery(sql, null).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }

    private val day = 86_400_000L
    private val now = System.currentTimeMillis()

    private fun historyDoc(at: Long, song: String): LibrarySync.RemoteDoc {
        val d = Days.keyOf(at)
        val ev = PlayEv(song, at, 60_000L, true)
        return LibrarySync.RemoteDoc(
            d.toString(),
            mapOf(
                "d" to d.toLong(),
                "p" to mapOf(SyncIds.eventId(at, song) to SyncCodec.encodePlay(ev)),
                "s" to mapOf(song to SyncCodec.encodeMeta(SongMeta("T", "Artist", "", 100L))),
                "l" to mapOf("dOTHER" to mapOf("${Days.hourOf(at)}|$song" to 60_000L)),
            )
        )
    }

    @Test
    fun clearingHistoryWritesHistoryClearedAndDeletesTheOlderDays() = runBlocking {
        val l = open()
        val sync = LibrarySync(l)
        sync.ensureDevice("dMINE")
        l.syncTracking = true
        // two days of history pulled from Firestore, liked songs too
        assertTrue(sync.applyHistory(listOf(historyDoc(now - 3 * day, "songAAAAAAA"), historyDoc(now - 2 * day, "songBBBBBBB"))))
        l.toggleLike(track("songLLLLLLL"))
        sync.clearOutbox()

        l.clearHistory()
        assertEquals(0, count(l.readableDatabase, "SELECT COUNT(*) FROM plays"))
        val writes = sync.buildWrites(sync.readOutbox(1000))
        val cleared = writes.single { it.path == LibrarySync.CLEARED_PATH }
        val point = cleared.data["c"] as Long
        assertTrue(point >= now)
        val deletes = writes.filter { it.delete }.map { it.path }.toSet()
        assertEquals(setOf("history/${Days.keyOf(now - 3 * day)}", "history/${Days.keyOf(now - 2 * day)}"), deletes)
        assertFalse("likes are not touched", writes.any { it.path.startsWith("likes/") && it.delete })
        assertEquals(point, sync.clearedBefore())
    }

    @Test
    fun aPullAfterTheClearNeverBringsClearedPlaysBack() = runBlocking {
        val l = open()
        val sync = LibrarySync(l)
        sync.ensureDevice("dMINE")
        l.clearHistory()
        sync.clearOutbox()
        // another phone (or 5.9.1) still has the old day documents in Firestore
        val old = historyDoc(now - 2 * day, "songAAAAAAA")
        val sameDayBefore = historyDoc(sync.clearedBefore()!! - 60_000L, "songBBBBBBB")
        sync.applyHistory(listOf(old, sameDayBefore))
        assertEquals(0, count(l.readableDatabase, "SELECT COUNT(*) FROM plays"))
        assertEquals(0, count(l.readableDatabase, "SELECT COUNT(*) FROM listen_time"))
        // and the old day found in Firestore is deleted there
        val writes = sync.buildWrites(sync.readOutbox(1000))
        assertTrue(writes.any { it.delete && it.path == "history/${Days.keyOf(now - 2 * day)}" })
        // a play after the clear is kept
        val later = historyDoc(sync.clearedBefore()!! + 60_000L, "songCCCCCCC")
        assertTrue(sync.applyHistory(listOf(later)))
        assertEquals(1, count(l.readableDatabase, "SELECT COUNT(*) FROM plays"))
    }

    @Test
    fun aClearFromAnotherPhoneInFirestoreClearsThisPhone() = runBlocking {
        val l = open()
        val sync = LibrarySync(l)
        sync.ensureDevice("dMINE")
        l.recordCountedPlay(track("songAAAAAAA"), 60_000L)
        assertEquals(1, count(l.readableDatabase, "SELECT COUNT(*) FROM plays"))
        var heard: Long? = null
        l.onRemoteHistoryClear = { heard = it }
        val point = System.currentTimeMillis() + 1_000L
        val clearedDoc = LibrarySync.RemoteDoc(LibrarySync.CLEARED_ID, mapOf("c" to point))
        assertTrue(sync.applyHistory(listOf(clearedDoc)))
        assertEquals(0, count(l.readableDatabase, "SELECT COUNT(*) FROM plays"))
        assertEquals(point, heard)
        assertEquals(point, sync.clearedBefore())
        // the same clear again changes nothing
        heard = null
        assertFalse(sync.applyHistory(listOf(clearedDoc)))
        assertEquals(null, heard)
    }

    @Test
    fun signingInAgainSendsTheClearWithTheFullMerge() = runBlocking {
        val l = open()
        val sync = LibrarySync(l)
        sync.ensureDevice("dMINE")
        l.clearHistory()
        sync.clearOutbox() // signing in clears the queue first
        sync.enqueueAll()
        val writes = sync.buildWrites(sync.readOutbox(1000))
        assertNotNull(writes.singleOrNull { it.path == LibrarySync.CLEARED_PATH })
    }
}
