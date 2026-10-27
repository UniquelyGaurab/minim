package com.minim.music.data.offline

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.Collections
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

/** 5.4.0: Save offline pulls each file in 4 parts at once, in ranged pieces, and recovers. */
class ParallelFetcherTest {

    private lateinit var server: MockWebServer
    private val data = Random(7).nextBytes(1_300_000)
    private val ranges: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private val inFlight = AtomicInteger(0)
    private val maxInFlight = AtomicInteger(0)
    private val client = OkHttpClient.Builder().readTimeout(5, TimeUnit.SECONDS).build()
    private lateinit var target: File

    /** Serves Range requests on [paths]; [special] can answer a request its own way first. */
    private fun serve(paths: Set<String>, special: (RecordedRequest) -> MockResponse? = { null }) {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val now = inFlight.incrementAndGet()
                maxInFlight.accumulateAndGet(now) { a, b -> maxOf(a, b) }
                try {
                    Thread.sleep(15) // so the parts really overlap
                    special(request)?.let { return it }
                    if (request.path !in paths) return MockResponse().setResponseCode(403)
                    val range = request.getHeader("Range") ?: return MockResponse().setBody(Buffer().write(data))
                    ranges += range
                    val m = Regex("bytes=(\\d+)-(\\d+)").find(range)!!
                    val from = m.groupValues[1].toInt()
                    val to = minOf(m.groupValues[2].toInt(), data.size - 1)
                    return MockResponse().setResponseCode(206)
                        .setHeader("Content-Range", "bytes $from-$to/${data.size}")
                        .setBody(Buffer().write(data, from, to - from + 1))
                } finally {
                    inFlight.decrementAndGet()
                }
            }
        }
    }

    private fun fetcher() = ParallelFetcher(client, segments = 4, pieceBytes = 64 * 1024L, retryWaitsMs = listOf(1L, 1L, 1L, 1L))

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        target = File.createTempFile("fetch", ".part")
    }

    @After
    fun tearDown() {
        server.shutdown()
        target.delete()
    }

    @Test
    fun savesTheExactFileInFourPartsAtOnce() = runBlocking {
        serve(setOf("/a"))
        var last = 0L
        fetcher().fetch(FetchSource(server.url("/a").toString(), data.size.toLong()), target, refresh = { null }) { last = it }
        assertArrayEquals(data, target.readBytes())
        assertEquals(data.size.toLong(), last)
        // 4 parts, each starting where the one before ended
        val starts = ranges.map { Regex("bytes=(\\d+)-").find(it)!!.groupValues[1].toLong() }.toSet()
        val quarter = data.size / 4L
        for (i in 0 until 4) assertTrue("part $i starts at ${i * quarter}", (i * quarter) in starts)
        // Pieces, never the whole file in one go
        assertTrue(ranges.size >= 16)
        assertTrue("parts ran at the same time (max ${maxInFlight.get()})", maxInFlight.get() >= 2)
    }

    @Test
    fun expiredLinkIsRefreshedAndCarriesOn() = runBlocking {
        // /old works for the first few pieces, then YouTube says 403 (expired link)
        val oldServed = AtomicInteger(0)
        serve(setOf("/new")) { r ->
            if (r.path == "/old" && oldServed.incrementAndGet() > 6) MockResponse().setResponseCode(403) else null
        }
        // /old serves like /new until it expires
        val refreshes = AtomicInteger(0)
        val baseDispatcher = server.dispatcher
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.path == "/old" && oldServed.get() < 6) {
                    oldServed.incrementAndGet()
                    val range = request.getHeader("Range")!!
                    val m = Regex("bytes=(\\d+)-(\\d+)").find(range)!!
                    val from = m.groupValues[1].toInt()
                    val to = minOf(m.groupValues[2].toInt(), data.size - 1)
                    return MockResponse().setResponseCode(206).setBody(Buffer().write(data, from, to - from + 1))
                }
                return baseDispatcher.dispatch(request)
            }
        }
        fetcher().fetch(
            FetchSource(server.url("/old").toString(), data.size.toLong()), target,
            refresh = { refreshes.incrementAndGet(); FetchSource(server.url("/new").toString(), data.size.toLong()) }
        )
        assertArrayEquals(data, target.readBytes())
        assertTrue(refreshes.get() >= 1)
        // The 4 parts share one fresh link instead of each asking for their own
        assertTrue("refreshed ${refreshes.get()} times", refreshes.get() <= 2)
    }

    @Test
    fun droppedConnectionPicksUpWhereItStopped() = runBlocking {
        val dropped = AtomicInteger(0)
        serve(setOf("/a")) { r ->
            val range = r.getHeader("Range") ?: ""
            // The first piece of the second part drops half-way through
            if (range.startsWith("bytes=${data.size / 4}-") && dropped.getAndIncrement() == 0) {
                val from = data.size / 4
                MockResponse().setResponseCode(206)
                    .setBody(Buffer().write(data, from, 64 * 1024))
                    .setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY)
            } else null
        }
        fetcher().fetch(FetchSource(server.url("/a").toString(), data.size.toLong()), target, refresh = { null })
        assertArrayEquals(data, target.readBytes())
        assertTrue(dropped.get() >= 1)
    }

    @Test
    fun goneForGoodFailsInsteadOfSavingAHalfFile() = runBlocking {
        serve(emptySet()) { MockResponse().setResponseCode(404) }
        try {
            fetcher().fetch(FetchSource(server.url("/a").toString(), data.size.toLong()), target, refresh = { null })
            fail("should have failed")
        } catch (e: java.io.IOException) {
            // expected: the song is reported as not saved
        }
    }

    @Test
    fun expiredWithNoFreshLinkFails() = runBlocking {
        serve(emptySet()) // everything 403
        try {
            fetcher().fetch(FetchSource(server.url("/a").toString(), data.size.toLong()), target, refresh = { null })
            fail("should have failed")
        } catch (e: java.io.IOException) {
            // expected
        }
    }
}
