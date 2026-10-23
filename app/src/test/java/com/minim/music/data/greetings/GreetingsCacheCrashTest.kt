package com.minim.music.data.greetings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** v4.9.6 part 4 crash checks: no offline copy, or a bad one, never breaks the greetings. */
class GreetingsCacheCrashTest {

    @get:Rule val tmp = TemporaryFolder()

    private val failing = GreetingsFetcher { FetchResult.Failed("offline") }
    private val throwing = GreetingsFetcher { throw IllegalStateException("boom") }

    @Test
    fun noFolderAtAll() {
        val dir = File(tmp.root, "never-made")
        val store = RemoteGreetingsStore(dir)
        assertNull(store.loadCached())
        assertTrue(store.isDue())
        assertEquals(RemoteGreetingsStore.Outcome.FAILED, store.refresh(failing).outcome)
        assertNull(store.loadCached())
    }

    @Test
    fun badCachedFilesAreDroppedQuietly() {
        val bad = listOf(
            "",
            "null",
            "[]",
            """{"version":1,"greetings":"nope"}""",
            """{"version":1,"greetings":[{"text":""}]""",
            "[".repeat(50_000),
            "\uFEFF\u0000\u0007"
        )
        for (text in bad) {
            val dir = tmp.newFolder()
            val cache = File(dir, RemoteGreetingsStore.CACHE_NAME)
            cache.writeText(text)
            assertNull("<${text.take(20)}>", RemoteGreetingsStore(dir).loadCached())
            assertFalse(cache.exists())
        }
        // Too big for the cap
        val dir = tmp.newFolder()
        File(dir, RemoteGreetingsStore.CACHE_NAME).writeText("x".repeat(RemoteGreetingsStore.MAX_BYTES + 10))
        assertNull(RemoteGreetingsStore(dir).loadCached())
        // The cache path is a folder, not a file
        val dir2 = tmp.newFolder()
        File(dir2, RemoteGreetingsStore.CACHE_NAME).mkdirs()
        assertNull(RemoteGreetingsStore(dir2).loadCached())
    }

    @Test
    fun syncWithABadCacheAndNoInternetPublishesNothingAndDoesNotThrow() {
        val dir = tmp.newFolder()
        File(dir, RemoteGreetingsStore.CACHE_NAME).writeText("{{{ not json")
        File(dir, RemoteGreetingsStore.META_NAME).writeText("\u0000\u0000")
        val published = ArrayList<RemoteGreetingFile>()
        RemoteGreetingsSync.run(dir, failing) { published.add(it) }
        RemoteGreetingsSync.run(dir, throwing) { published.add(it) }
        assertTrue(published.isEmpty())
    }
}
