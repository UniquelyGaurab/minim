package com.minim.music.data.greetings

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class OkHttpGreetingsFetcherTest {

    private lateinit var server: MockWebServer
    private lateinit var fetcher: OkHttpGreetingsFetcher

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        fetcher = OkHttpGreetingsFetcher(OkHttpClient(), server.url("/greetings/greetings.json").toString())
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun okReturnsBodyAndEtag() {
        server.enqueue(MockResponse().setBody("""{"greetings":[]}""").setHeader("ETag", "\"e1\""))
        val r = fetcher.fetch(null) as FetchResult.Ok
        assertEquals("""{"greetings":[]}""", r.body)
        assertEquals("\"e1\"", r.etag)
        assertNull(server.takeRequest().getHeader("If-None-Match"))
    }

    @Test
    fun sendsEtagAndHandles304() {
        server.enqueue(MockResponse().setResponseCode(304))
        assertEquals(FetchResult.NotModified, fetcher.fetch("\"e1\""))
        assertEquals("\"e1\"", server.takeRequest().getHeader("If-None-Match"))
    }

    @Test
    fun notFoundAndServerErrorsFail() {
        server.enqueue(MockResponse().setResponseCode(404))
        server.enqueue(MockResponse().setResponseCode(500))
        assertTrue(fetcher.fetch(null) is FetchResult.Failed)
        assertTrue(fetcher.fetch(null) is FetchResult.Failed)
    }

    @Test
    fun tooBigFails() {
        server.enqueue(MockResponse().setBody("x".repeat(RemoteGreetingsStore.MAX_BYTES + 10)))
        assertTrue(fetcher.fetch(null) is FetchResult.Failed)
    }

    @Test
    fun noServerFails() {
        server.shutdown()
        assertTrue(fetcher.fetch(null) is FetchResult.Failed)
    }
}
