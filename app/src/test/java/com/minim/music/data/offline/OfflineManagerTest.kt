package com.minim.music.data.offline

import androidx.test.core.app.ApplicationProvider
import com.minim.music.data.model.Track
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

/** 5.4.0: the Save offline queue: 3 at a time, playlist rings and messages, remove. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class OfflineManagerTest {

    private lateinit var store: OfflineStore
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val running = AtomicInteger(0)
    private val maxRunning = AtomicInteger(0)
    private val failIds: MutableSet<String> = Collections.synchronizedSet(mutableSetOf())
    private var gate: CompletableDeferred<Unit>? = null

    private fun track(id: String) = Track(id, "Song $id", "Artist", 200, "", "")

    /** A pretend saver: reports progress, waits a little, then adds a small file to the store. */
    private val saver = SongSaver { t, onProgress ->
        val now = running.incrementAndGet()
        maxRunning.accumulateAndGet(now) { a, b -> maxOf(a, b) }
        try {
            gate?.await()
            onProgress(0.5f)
            delay(40)
            if (t.id in failIds) throw IOException("no file")
            val f = File.createTempFile("download", ".part").apply { writeBytes(ByteArray(100)) }
            store.add(t, f, "webm", 160_000, 251, null, null, false)
            onProgress(1f)
        } finally {
            running.decrementAndGet()
        }
    }

    private lateinit var manager: OfflineManager

    @Before
    fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<android.app.Application>()
        val root = OfflineStore.folderIn(ctx.filesDir)
        root.deleteRecursively()
        store = OfflineStore(root)
        manager = OfflineManager(store, saver, scope)
    }

    @After
    fun tearDown() = scope.cancel()

    private suspend fun idle() = withTimeout(10_000) { manager.progress.first { it.isEmpty() } }

    @Test
    fun savesThreeSongsAtATime() = runBlocking {
        val tracks = (1..9).map { track("s$it") }
        assertEquals(9, manager.save(tracks))
        assertTrue(manager.busy)
        idle()
        assertEquals(3, maxRunning.get())
        assertEquals(9, store.saved.value.size)
        assertEquals(9 to 9, manager.session.value)
    }

    @Test
    fun alreadySavedOrSavingSongsAreNotQueuedTwice() = runBlocking {
        gate = CompletableDeferred()
        assertEquals(2, manager.save(listOf(track("a"), track("b"))))
        assertEquals(1, manager.save(listOf(track("a"), track("c"), track("c"))))
        gate!!.complete(Unit)
        idle()
        assertEquals(0, manager.save(listOf(track("a"), track("b"))))
        assertEquals(3, store.saved.value.size)
    }

    @Test
    fun playlistRingFillsAndOneMessageWhenItFinishes() = runBlocking {
        failIds += "p3"
        val events = Collections.synchronizedList(mutableListOf<OfflineEvent>())
        val collector = scope.launch { manager.events.collect { events += it } }
        delay(50)
        gate = CompletableDeferred()
        manager.save(listOf(track("p1"), track("p2"), track("p3")), "pl:7", "Chill Mix")
        val g = manager.groups.value["pl:7"]!!
        assertEquals(0f, OfflineRings.group(g, manager.progress.value), 0.001f)
        gate!!.complete(Unit)
        idle()
        delay(50)
        assertTrue(manager.groups.value.isEmpty())
        val done = events.filterIsInstance<OfflineEvent.GroupFinished>().single()
        assertEquals("Chill Mix", done.name)
        assertEquals(2, done.saved)
        assertEquals(1, done.failed)
        // The failed song belongs to the playlist, so the playlist message covers it
        assertTrue(events.filterIsInstance<OfflineEvent.SongFailed>().single().inGroup)
        collector.cancel()
    }

    @Test
    fun aSongSavedOnItsOwnThatFailsGetsItsOwnMessage() = runBlocking {
        failIds += "x"
        val events = Collections.synchronizedList(mutableListOf<OfflineEvent>())
        val collector = scope.launch { manager.events.collect { events += it } }
        delay(50)
        manager.save(listOf(track("x")))
        idle()
        delay(50)
        val e = events.filterIsInstance<OfflineEvent.SongFailed>().single()
        assertFalse(e.inGroup)
        assertFalse(store.isSaved("x"))
        collector.cancel()
    }

    @Test
    fun ringMath() {
        val g = GroupProgress("P", listOf("a", "b", "c", "d"), done = 1, failed = 1)
        assertEquals(0.625f, OfflineRings.group(g, mapOf("c" to 0.5f)), 0.001f)
        assertEquals(0.5f, OfflineRings.session(2 to 5, mapOf("x" to 0.25f, "y" to 0.25f))!!, 0.001f)
        assertEquals(null, OfflineRings.session(5 to 5, emptyMap()))
    }

    @Test
    fun removeStopsWaitingSongsAndFreesTheRing() = runBlocking {
        gate = CompletableDeferred()
        val ids = (1..6).map { "r$it" }
        manager.save(ids.map { track(it) }, "liked", "Liked Songs")
        delay(50)
        manager.remove(ids)
        // Nothing left saving, no ring stuck on the tile
        assertTrue(manager.progress.value.isEmpty())
        assertTrue(manager.groups.value.isEmpty())
        assertFalse(manager.busy)
        gate!!.complete(Unit)
        delay(200)
        assertTrue(store.saved.value.isEmpty())
    }

    @Test
    fun removeDeletesSavedSongs() = runBlocking {
        manager.save(listOf(track("a"), track("b")))
        idle()
        manager.remove(listOf("a"))
        assertFalse(store.isSaved("a"))
        assertTrue(store.isSaved("b"))
    }

    @Test
    fun removeAllStopsEverythingAndDeletesEverything() = runBlocking {
        manager.save(listOf(track("a")))
        idle()
        gate = CompletableDeferred()
        manager.save((1..5).map { track("w$it") }, "pl:1", "P")
        delay(50)
        manager.removeAll()
        assertTrue(manager.progress.value.isEmpty())
        assertTrue(manager.groups.value.isEmpty())
        gate!!.complete(Unit)
        delay(200)
        assertTrue(store.saved.value.isEmpty())
        assertEquals(0L, store.bytesUsed())
    }
}
