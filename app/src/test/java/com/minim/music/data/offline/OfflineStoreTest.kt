package com.minim.music.data.offline

import androidx.test.core.app.ApplicationProvider
import com.minim.music.data.model.Track
import com.minim.music.data.source.RawLyrics
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

/** 5.4.0: where saved songs live, and that removing them really frees the space. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class OfflineStoreTest {

    private lateinit var root: File
    private lateinit var store: OfflineStore

    private fun track(id: String) = Track(id, "Song $id", "Artist", 200, "https://i.ytimg.com/vi/$id/hq.jpg", "https://music.youtube.com/watch?v=$id")

    private fun download(bytes: Int): File = File.createTempFile("download", ".part").apply { writeBytes(ByteArray(bytes) { 1 }) }

    @Before
    fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<android.app.Application>()
        root = OfflineStore.folderIn(ctx.filesDir)
        root.deleteRecursively()
        store = OfflineStore(root)
    }

    @Test
    fun savedSongsLiveInPrivateFilesNotTheCache() {
        val ctx = ApplicationProvider.getApplicationContext<android.app.Application>()
        val path = root.canonicalPath
        assertTrue(path.startsWith(ctx.filesDir.canonicalPath))
        assertFalse(path.startsWith(ctx.cacheDir.canonicalPath))
        assertFalse(path.startsWith(ctx.codeCacheDir.canonicalPath))
        ctx.externalCacheDir?.let { assertFalse(path.startsWith(it.canonicalPath)) }
        // Even a download that's still going sits inside the store, not the cache
        assertTrue(store.tempAudioFile("abc").canonicalPath.startsWith(path))
    }

    @Test
    fun addKeepsAudioLyricsAndCoverAndSurvivesARestart() {
        val cover = download(500)
        store.add(track("a1"), download(4000), "webm", 160_000, 251, RawLyrics(true, "[00:01.00]Hello"), cover, false)
        assertTrue(store.isSaved("a1"))
        assertEquals(4000L, store.audioFor("a1")!!.length())
        assertTrue(store.audioFor("a1")!!.name.endsWith(".webm"))
        assertEquals("[00:01.00]Hello", store.lyricsFor("a1")!!.text)
        assertNotNull(store.artFor("https://i.ytimg.com/vi/a1/hq.jpg"))

        // A new store on the same folder (the app restarted) still has it
        val again = OfflineStore(root)
        assertTrue(again.isSaved("a1"))
        assertEquals("Song a1", again.saved.value.single().track.title)
        assertEquals(160_000, again.song("a1")!!.bitrate)
        assertEquals("[00:01.00]Hello", again.lyricsFor("a1")!!.text)
    }

    @Test
    fun newestSavedComesFirst() {
        store.add(track("a"), download(10), "webm", 1, 251, null, null, false)
        store.add(track("b"), download(10), "m4a", 1, 140, null, null, false)
        assertEquals(listOf("b", "a"), store.saved.value.map { it.track.id })
    }

    @Test
    fun removeDeletesItsFilesAndKeepsTheRest() {
        store.add(track("a"), download(1000), "webm", 1, 251, RawLyrics(false, "la"), download(50), false)
        store.add(track("b"), download(2000), "webm", 1, 251, null, null, false)
        val aFile = store.audioFor("a")!!
        store.remove(listOf("a"))
        assertFalse(store.isSaved("a"))
        assertFalse(aFile.exists())
        assertNull(store.lyricsFor("a"))
        assertTrue(store.isSaved("b"))
        assertEquals(2000L, store.bytesUsed())
        assertFalse(OfflineStore(root).isSaved("a"))
    }

    @Test
    fun removeAllFreesEverything() {
        store.add(track("a"), download(1000), "webm", 1, 251, RawLyrics(false, "la"), download(50), false)
        store.add(track("b"), download(2000), "webm", 1, 251, null, null, false)
        store.tempAudioFile("c").writeBytes(ByteArray(10))
        store.removeAll()
        store.cleanPartials()
        assertTrue(store.saved.value.isEmpty())
        assertEquals(0L, store.bytesUsed())
        assertTrue(OfflineStore(root).saved.value.isEmpty())
        assertFalse(store.tempAudioFile("c").exists())
    }

    @Test
    fun aMissingFileIsNotPlayedAsSaved() {
        store.add(track("a"), download(1000), "webm", 1, 251, null, null, false)
        store.audioFor("a")!!.delete()
        assertNull(store.audioFor("a"))
    }
}
