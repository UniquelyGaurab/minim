package com.minim.music.playback

import com.minim.music.data.source.ResolvedStream
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.Collections

/**
 * Runs the chunked reader against a fake googlevideo server on localhost: pieces, request
 * numbers, POST + headers, a mid-song 403 with a fresh link, and the retry limits.
 */
class ChunkedStreamReaderTest {

    private data class Hit(val method: String, val query: String, val userAgent: String?, val body: Int)

    private val song = ByteArray(2_500) { (it * 31 % 251).toByte() }
    private val hits = Collections.synchronizedList(mutableListOf<Hit>())
    @Volatile private var refuseSig: String? = null
    @Volatile private var refuseAll = false
    private lateinit var server: MockWebServer
    private lateinit var base: String
    private val client = OkHttpClient()

    @Before
    fun start() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val query = request.requestUrl?.encodedQuery.orEmpty()
                hits.add(Hit(request.method.orEmpty(), query, request.getHeader("User-Agent"), request.bodySize.toInt()))
                val params = query.split('&').associate { it.substringBefore('=') to it.substringAfter('=', "") }
                val refused = refuseAll || (refuseSig != null && params["sig"] == refuseSig)
                val range = params["range"]
                if (refused) return MockResponse().setResponseCode(403)
                if (range == null) return MockResponse().setResponseCode(400)
                val begin = range.substringBefore('-').toInt()
                val end = minOf(range.substringAfter('-').toInt(), song.size - 1)
                if (begin >= song.size) return MockResponse().setResponseCode(416)
                return MockResponse()
                    .setResponseCode(200)
                    .addHeader("Content-Type", "audio/mp4")
                    .setBody(Buffer().write(song.copyOfRange(begin, end + 1)))
            }
        }
        server.start()
        base = server.url("/videoplayback").toString() + "?itag=140&c=VISIONOS"
    }

    @After
    fun stop() {
        server.shutdown()
    }

    private fun stream(sig: String, clen: Long = song.size.toLong(), itag: Int = 140) =
        ResolvedStream("vid", "$base&sig=$sig&clen=$clen", itag, clen, chunked = true, otherOptions = 2)

    private fun readAll(reader: ChunkedStreamReader): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(300)
        while (true) {
            val n = reader.read(buf, 0, buf.size)
            if (n == -1) break
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    @Test
    fun readsTheWholeSongInPiecesWithRnAndRange() {
        val reader = ChunkedStreamReader(client, { _, _, _, _ -> fail(); stream("x") }, 1_000, { emptySet() })
        assertEquals(song.size.toLong(), reader.open(stream("a"), 0, -1))
        assertArrayEquals(song, readAll(reader))
        assertEquals(3, hits.size)
        assertTrue(hits[0].query.endsWith("rn=0&range=0-999"))
        assertTrue(hits[1].query.endsWith("rn=1&range=1000-1999"))
        assertTrue(hits[2].query.endsWith("rn=2&range=2000-2499"))
        hits.forEach {
            assertEquals("POST", it.method)
            assertEquals(2, it.body)
            assertTrue(it.userAgent!!.startsWith("com.google.visionos.youtube/"))
        }
    }

    @Test
    fun startsFromASeekPosition() {
        val reader = ChunkedStreamReader(client, { _, _, _, _ -> stream("x") }, 1_000, { emptySet() })
        assertEquals(1_300L, reader.open(stream("a"), 1_200, -1))
        assertArrayEquals(song.copyOfRange(1_200, song.size), readAll(reader))
        assertTrue(hits[0].query.endsWith("range=1200-2199"))
    }

    @Test
    fun midSong403GetsFreshLinkAndContinuesFromSameByte() {
        var freshCalls = 0
        val reader = ChunkedStreamReader(client, { _, _, prefer, fresh ->
            assertEquals(140, prefer)
            assertTrue(fresh)
            freshCalls++
            stream("new")
        }, 1_000, { emptySet() })
        reader.open(stream("old"), 0, -1)
        val buf = ByteArray(1_000)
        var got = 0
        while (got < 1_000) got += reader.read(buf, got, 1_000 - got)
        refuseSig = "old" // the old link dies after the first piece, like the reported bug
        val rest = readAll(reader)
        assertArrayEquals(song, buf + rest)
        assertEquals(1, freshCalls)
        assertEquals(1, reader.refreshCount)
        assertTrue(hits.any { it.query.contains("sig=new") && it.query.contains("range=1000-1999") })
    }

    @Test
    fun unknownLengthEndsOnShortPiece() {
        val reader = ChunkedStreamReader(client, { _, _, _, _ -> stream("x") }, 1_000, { emptySet() })
        val unknown = ResolvedStream("vid", "$base&sig=a", 140, -1L, chunked = true, otherOptions = 0)
        assertEquals(-1L, reader.open(unknown, 0, -1))
        assertArrayEquals(song, readAll(reader))
    }

    @Test
    fun refreshesAreCappedThenItGivesUpWith403() {
        refuseAll = true
        var freshCalls = 0
        val reader = ChunkedStreamReader(client, { _, _, _, _ -> freshCalls++; stream("again$freshCalls") }, 1_000, { emptySet() })
        try {
            reader.open(stream("a"), 0, -1)
            fail("should give up")
        } catch (e: YoutubeStreamException) {
            assertEquals(403, e.responseCode)
            assertEquals(140, e.itag)
            assertEquals("vid", e.videoId)
        }
        assertEquals(StreamRecovery.MAX_CONSECUTIVE_REFRESHES, freshCalls)
        assertEquals(StreamRecovery.MAX_CONSECUTIVE_REFRESHES + 1, hits.size)
    }

    @Test
    fun formatGoneAfterRefreshHandsOverToPlayer() {
        refuseSig = "a"
        val reader = ChunkedStreamReader(client, { _, _, _, _ -> stream("b", itag = 251) }, 1_000, { emptySet() })
        try {
            reader.open(stream("a"), 0, -1)
            fail("should hand over")
        } catch (e: YoutubeStreamException) {
            assertEquals(140, e.itag)
            assertEquals(2, e.otherOptions)
        }
    }
}
