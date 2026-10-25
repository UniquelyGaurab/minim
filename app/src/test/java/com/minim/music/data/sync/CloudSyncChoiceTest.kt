package com.minim.music.data.sync

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.minim.music.data.account.MinimAccount
import com.minim.music.data.discover.DiscoverEngine
import com.minim.music.data.local.LibraryDatabase
import com.minim.music.data.model.Track
import com.minim.music.data.source.MusicSourceAdapter
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Minim 5.0.0 part 2c: the different-account question (Cancel and Start fresh), the reload signal,
 * and Discover following a pull. Real SQLite; no Firebase in the test app, so nothing reaches the
 * network.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class CloudSyncChoiceTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private var lib: LibraryDatabase? = null

    @Before
    fun clean() {
        context.deleteDatabase(LibraryDatabase.DB_NAME)
        File(context.filesDir, "discover_cache_v1.json").delete()
    }

    @After
    fun close() {
        lib?.close()
        context.deleteDatabase(LibraryDatabase.DB_NAME)
        File(context.filesDir, "discover_cache_v1.json").delete()
        File(context.filesDir, "library_before_account_switch.db").delete()
    }

    private fun open(): LibraryDatabase = LibraryDatabase(context).also { lib = it }

    private fun track(id: String) =
        Track(id, "Song $id", "Artist", 200L, "https://img/$id", "https://www.youtube.com/watch?v=$id")

    private fun outboxCount(l: LibraryDatabase): Int =
        l.readableDatabase.rawQuery("SELECT COUNT(*) FROM sync_outbox", null).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }

    /** A phone whose music last synced with account "old", now signed in to "new". */
    private fun waitingPhone(l: LibraryDatabase): CloudSync {
        val store = LibrarySync(l)
        store.setState("uid", "old")
        store.setState("full", "old")
        store.setState("last", "123")
        val sync = CloudSync(context, l, MinimAccount(context))
        sync.onUser("new", "Ana Lee")
        return sync
    }

    @Test
    fun a_different_account_waits_for_a_choice() = runBlocking {
        val l = open()
        l.toggleLike(track("songAAAAAAA"))
        val sync = waitingPhone(l)
        assertEquals(CloudSync.Choice("new", "Ana Lee"), sync.pendingChoice.value)
        assertEquals(CloudSync.Phase.NEEDS_CHOICE, sync.status.value.phase)
        assertFalse(l.syncTracking)
    }

    @Test
    fun cancel_leaves_the_phone_exactly_as_it_was() = runBlocking {
        val l = open()
        l.toggleLike(track("songAAAAAAA"))
        l.markNotInterested(track("songBBBBBBB"))
        val sync = waitingPhone(l)
        val versionBefore = sync.dataVersion.value

        assertTrue(sync.cancelChoice())

        assertNull(sync.pendingChoice.value)
        assertEquals(CloudSync.Phase.OFF, sync.status.value.phase)
        assertFalse(l.syncTracking)
        // still tied to the account the music came from, so signing in with it later just carries on
        val store = LibrarySync(l)
        assertEquals("old", store.state("uid"))
        assertEquals("old", store.state("full"))
        assertEquals("123", store.state("last"))
        // the library is untouched and no screen reload is asked for
        assertEquals(listOf("songAAAAAAA"), l.getLikedTracks().map { it.id })
        assertTrue("songBBBBBBB" in l.notInterestedIds.value)
        assertEquals(versionBefore, sync.dataVersion.value)
        assertFalse(File(context.filesDir, "library_before_account_switch.db").exists())
        // nothing is queued for the cloud afterwards
        l.toggleLike(track("songCCCCCCC"))
        assertEquals(0, outboxCount(l))
        // a second Cancel (a double tap) does nothing
        assertFalse(sync.cancelChoice())
    }

    @Test
    fun start_fresh_wipes_keeps_a_backup_and_signals_a_full_reload() = runBlocking {
        val l = open()
        l.toggleLike(track("songAAAAAAA"))
        val sync = waitingPhone(l)
        assertEquals(0L, sync.dataVersion.value)

        sync.choose(merge = false)
        val until = System.currentTimeMillis() + 10_000L
        while (sync.dataVersion.value == 0L && System.currentTimeMillis() < until) Thread.sleep(20)

        assertEquals(1L, sync.dataVersion.value)
        assertTrue(sync.lastChangeWasFull)
        assertNull(sync.pendingChoice.value)
        assertTrue(l.getLikedTracks().isEmpty())
        assertTrue(l.likedIds.value.isEmpty())
        assertTrue(File(context.filesDir, "library_before_account_switch.db").exists())
        // the next sign-in to that account starts with a full merge
        val store = LibrarySync(l)
        assertNull(store.state("full"))
        assertNull(store.state("last"))
    }

    @Test
    fun discover_drops_songs_hidden_on_another_phone_and_can_be_marked_stale() = runBlocking {
        val l = open()
        val today = java.time.LocalDate.now().toEpochDay()
        val bucket = DiscoverEngine.bucketOf(java.time.LocalTime.now().hour)
        fun shelf(key: String, ids: List<String>) = JSONObject().apply {
            put("key", key); put("title", key)
            put("tracks", JSONArray().apply {
                for (id in ids) put(JSONObject().apply {
                    put("id", id); put("title", "Song $id"); put("artist", "A"); put("d", 200)
                    put("th", ""); put("src", "")
                })
            })
        }
        File(context.filesDir, "discover_cache_v1.json").writeText(JSONObject().apply {
            put("day", today); put("bucket", bucket); put("builtAt", 1L); put("hasHistory", true)
            put("shelves", JSONArray().apply {
                put(shelf("liked", listOf("a1", "a2", "a3", "a4", "a5")))
                put(shelf("repeat", listOf("b1", "b2", "b3", "b4")))
            })
        }.toString())
        val engine = DiscoverEngine(context, l, MusicSourceAdapter(OkHttpClient()))
        assertFalse(engine.isStale(engine.cached()))

        assertNull(engine.dropHidden(setOf("zz")))
        val after = engine.dropHidden(setOf("a2", "b1"))
        assertNotNull(after)
        // the first shelf loses one song; the second gets too small and goes
        assertEquals(listOf("liked"), after!!.shelves.map { it.key })
        assertEquals(listOf("a1", "a3", "a4", "a5"), after.shelves[0].tracks.map { it.id })

        engine.markStale()
        val fresh = DiscoverEngine(context, l, MusicSourceAdapter(OkHttpClient()))
        val saved = fresh.cached()
        // still shows until then, and stays stale after the app closes
        assertEquals(listOf("liked"), saved!!.shelves.map { it.key })
        assertTrue(fresh.isStale(saved))
    }
}
