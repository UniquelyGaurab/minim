package com.minim.music.data.greetings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class RemoteGreetingsStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val good = """{ "version": 1, "greetings": [ { "text": "Volume up.\nWorries down." } ] }"""
    private val good2 = """{ "version": 1, "greetings": [ { "text": "One.\nTwo." }, { "text": "Three.\nFour." } ] }"""

    private var now = 1_000_000_000_000L
    private val hour = 60L * 60 * 1000

    private fun store(dir: File = tmp.root) = RemoteGreetingsStore(dir) { now }

    private class FakeFetcher(var result: FetchResult) : GreetingsFetcher {
        var calls = 0
        var lastEtag: String? = null
        override fun fetch(etag: String?): FetchResult {
            calls++
            lastEtag = etag
            return result
        }
    }

    @Test
    fun noCacheMeansNothing() {
        assertNull(store().loadCached())
    }

    @Test
    fun goodDownloadIsCachedAndReadBackOffline() {
        val f = FakeFetcher(FetchResult.Ok(good, "\"abc\""))
        val r = store().refresh(f)
        assertEquals(RemoteGreetingsStore.Outcome.UPDATED, r.outcome)
        assertEquals(1, r.file!!.anytime.size)
        // A new store (like after a restart) reads the same file without the network
        val cached = store().loadCached()
        assertNotNull(cached)
        assertEquals("Volume up.", cached!!.anytime[0].line1)
        assertEquals("\"abc\"", store().readMeta().etag)
    }

    @Test
    fun onlyAboutOnceADay() {
        val f = FakeFetcher(FetchResult.Ok(good, null))
        store().refresh(f)
        now += 2 * hour
        assertEquals(RemoteGreetingsStore.Outcome.NOT_DUE, store().refresh(f).outcome)
        now += 10 * hour
        assertFalse(store().isDue())
        now += 9 * hour // 21 hours after the last good check
        assertTrue(store().isDue())
        assertEquals(RemoteGreetingsStore.Outcome.UPDATED, store().refresh(f).outcome)
        assertEquals(2, f.calls)
    }

    @Test
    fun failedDownloadKeepsTheCacheAndBacksOff() {
        store().refresh(FakeFetcher(FetchResult.Ok(good, null)))
        now += 21 * hour
        val failing = FakeFetcher(FetchResult.Failed("HTTP 500"))
        assertEquals(RemoteGreetingsStore.Outcome.FAILED, store().refresh(failing).outcome)
        assertEquals("Volume up.", store().loadCached()!!.anytime[0].line1)
        // Not again straight away...
        now += 1 * hour
        assertEquals(RemoteGreetingsStore.Outcome.NOT_DUE, store().refresh(failing).outcome)
        // ...but after the retry gap
        now += 3 * hour
        assertEquals(RemoteGreetingsStore.Outcome.FAILED, store().refresh(failing).outcome)
        assertEquals(2, failing.calls)
    }

    @Test
    fun fetcherThatThrowsCountsAsFailed() {
        val boom = GreetingsFetcher { throw IllegalStateException("boom") }
        assertEquals(RemoteGreetingsStore.Outcome.FAILED, store().refresh(boom).outcome)
        assertNull(store().loadCached())
    }

    @Test
    fun badDownloadNeverReplacesAGoodCache() {
        store().refresh(FakeFetcher(FetchResult.Ok(good, "\"v1\"")))
        val r = store().refresh(FakeFetcher(FetchResult.Ok("{ this is not json", "\"v2\"")), force = true)
        assertEquals(RemoteGreetingsStore.Outcome.BAD_FILE, r.outcome)
        assertNull(r.file)
        assertEquals(1, store().loadCached()!!.anytime.size)
        assertEquals("\"v1\"", store().readMeta().etag)
        // A too-big download is a bad file too
        val huge = """{ "greetings": [] }""" + " ".repeat(RemoteGreetingsStore.MAX_BYTES)
        assertEquals(RemoteGreetingsStore.Outcome.BAD_FILE, store().refresh(FakeFetcher(FetchResult.Ok(huge, null)), force = true).outcome)
        assertEquals(1, store().loadCached()!!.anytime.size)
    }

    @Test
    fun newFileReplacesTheOldOne() {
        store().refresh(FakeFetcher(FetchResult.Ok(good, null)))
        val r = store().refresh(FakeFetcher(FetchResult.Ok(good2, null)), force = true)
        assertEquals(2, r.file!!.anytime.size)
        assertEquals(2, store().loadCached()!!.anytime.size)
    }

    @Test
    fun etagIsSentAndNotModifiedKeepsTheCache() {
        store().refresh(FakeFetcher(FetchResult.Ok(good, "\"abc\"")))
        now += 21 * hour
        val f = FakeFetcher(FetchResult.NotModified)
        val r = store().refresh(f)
        assertEquals(RemoteGreetingsStore.Outcome.NOT_MODIFIED, r.outcome)
        assertEquals("\"abc\"", f.lastEtag)
        assertNotNull(store().loadCached())
        assertFalse(store().isDue())
    }

    @Test
    fun noEtagIsSentWithoutACache() {
        store().refresh(FakeFetcher(FetchResult.Ok(good, "\"abc\"")))
        File(tmp.root, RemoteGreetingsStore.CACHE_NAME).delete()
        val f = FakeFetcher(FetchResult.Ok(good2, null))
        store().refresh(f, force = true)
        assertNull(f.lastEtag)
        assertEquals(2, store().loadCached()!!.anytime.size)
    }

    @Test
    fun brokenCacheOnDiskIsIgnoredAndDeleted() {
        tmp.root.mkdirs()
        val cache = File(tmp.root, RemoteGreetingsStore.CACHE_NAME)
        cache.writeText("\u0000garbage{{{")
        assertNull(store().loadCached())
        assertFalse(cache.exists())
        File(tmp.root, RemoteGreetingsStore.META_NAME).writeText("lastAttempt=banana\n=\nnonsense")
        assertEquals(RemoteGreetingsStore.Meta(), store().readMeta())
        assertTrue(store().isDue())
    }

    @Test
    fun clockGoingBackwardsMakesItDue() {
        store().refresh(FakeFetcher(FetchResult.Ok(good, null)))
        now -= 5 * hour
        assertTrue(store().isDue())
    }

    @Test
    fun syncPublishesCacheThenFreshFile() {
        store().refresh(FakeFetcher(FetchResult.Ok(good, null)))
        now += 21 * hour
        val published = ArrayList<RemoteGreetingFile>()
        // RemoteGreetingsSync uses the real clock; the last check above was in 2001, so it is due
        RemoteGreetingsSync.run(tmp.root, FakeFetcher(FetchResult.Ok(good2, null))) { published.add(it) }
        assertTrue(published.isNotEmpty())
        assertEquals(2, published.last().anytime.size)
    }
}
