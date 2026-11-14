package com.minim.music.data.share

import com.minim.music.data.share.SharedLookup.One
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

class SharedLookupTest {

    private val ids = listOf("RA4jeB5zk9A", "GWNrPJyRTcA", "em9fQdHmKh4", "6mr4cYJ7yew", "szvt1vD0Uug")
    private fun found(id: String) = One.Found(ShareLinks.Song(id, "t$id", "a$id"))

    @Test fun allFoundInOrderAndReady() = runBlocking {
        val r = arrayOfNulls<One>(ids.size)
        val seen = ArrayList<Int>()
        SharedLookup.fill(ids, r, lookup = { delay((it.hashCode() and 7).toLong()); found(it) }, onEach = { i, _ -> seen.add(i) })
        val s = SharedLookup.summary(r)
        assertTrue(s.ready)
        assertEquals(ids, s.found.map { it.id })
        assertEquals(ids.indices.toSet(), seen.toSet())
    }

    @Test fun unavailableSongsAreSkipped() = runBlocking {
        val r = arrayOfNulls<One>(ids.size)
        SharedLookup.fill(ids, r, lookup = { if (it == ids[1] || it == ids[3]) One.Unavailable else found(it) })
        val s = SharedLookup.summary(r)
        assertTrue(s.ready)
        assertEquals(listOf(ids[0], ids[2], ids[4]), s.found.map { it.id })
        assertEquals(2, s.unavailable)
    }

    @Test fun offlineIsFailedNotACrashAndRetryFillsOnlyTheGaps() = runBlocking {
        val r = arrayOfNulls<One>(ids.size)
        SharedLookup.fill(ids, r, lookup = { throw IOException("offline") })
        var s = SharedLookup.summary(r)
        assertTrue(s.done)
        assertFalse(s.ready)
        assertEquals(5, s.failed)
        // back online: Try again fills them, and a song found before isn't looked up again
        r[0] = found(ids[0])
        val calls = AtomicInteger()
        SharedLookup.fill(ids, r, lookup = { calls.incrementAndGet(); found(it) })
        assertEquals(4, calls.get())
        s = SharedLookup.summary(r)
        assertTrue(s.ready)
    }

    @Test fun aSlowLookupTimesOut() = runBlocking {
        val r = arrayOfNulls<One>(ids.size)
        SharedLookup.fill(ids, r, timeoutMs = 50, lookup = { if (it == ids[2]) { delay(10_000); found(it) } else found(it) })
        val s = SharedLookup.summary(r)
        assertEquals(One.Failed, r[2])
        assertEquals(1, s.failed)
        assertFalse(s.ready)
    }

    @Test fun atMostAFewAtOnce() = runBlocking {
        val now = AtomicInteger()
        var peak = 0
        val many = (0 until 40).map { "id$it".padEnd(11, 'A') }
        SharedLookup.fill(many, arrayOfNulls(many.size), parallel = 4, lookup = {
            val n = now.incrementAndGet()
            synchronized(this@SharedLookupTest) { peak = maxOf(peak, n) }
            delay(5)
            now.decrementAndGet()
            found(it)
        })
        assertTrue("peak $peak", peak in 1..4)
    }

    @Test fun nothingAvailable() {
        val s = SharedLookup.summary(arrayOf(One.Unavailable, One.Unavailable))
        assertTrue(s.done)
        assertFalse(s.ready)
        assertTrue(s.found.isEmpty())
    }
}
