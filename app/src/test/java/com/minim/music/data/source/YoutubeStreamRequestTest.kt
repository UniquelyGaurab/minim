package com.minim.music.data.source

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class YoutubeStreamRequestTest {

    private val vision =
        "https://rr3---sn-abc.googlevideo.com/videoplayback?expire=1793000000&itag=140&clen=3456789&c=VISIONOS&n=abc&cpn=xyz"
    private val web =
        "https://rr3---sn-abc.googlevideo.com/videoplayback?itag=251&c=WEB_REMIX&n=abc"

    @Test
    fun detectsVideoPlaybackUrls() {
        assertTrue(YoutubeStreamRequest.isVideoPlaybackUrl(vision))
        assertFalse(YoutubeStreamRequest.isVideoPlaybackUrl("https://manifest.googlevideo.com/api/manifest/hls_variant/x"))
    }

    @Test
    fun visionOsLinkGetsVisionOsUserAgentAndNoBrowserHeaders() {
        val h = YoutubeStreamRequest.headersFor(vision)
        assertTrue(h["User-Agent"]!!.startsWith("com.google.visionos.youtube/"))
        assertNull(h["Origin"])
        assertNull(h["Referer"])
        assertEquals("identity", h["Accept-Encoding"])
    }

    @Test
    fun webLinkGetsFirefoxAndYoutubeOriginReferer() {
        val h = YoutubeStreamRequest.headersFor(web)
        assertEquals(YoutubeStreamRequest.DESKTOP_USER_AGENT, h["User-Agent"])
        assertEquals("https://www.youtube.com", h["Origin"])
        assertEquals("https://www.youtube.com", h["Referer"])
        assertEquals("cors", h["Sec-Fetch-Mode"])
    }

    @Test
    fun chunkUrlReplacesOldRangeAndRn() {
        val url = YoutubeStreamRequest.chunkUrl("$vision&range=0-99&rn=7", 1048576, 2097151, 2)
        assertTrue(url.endsWith("&rn=2&range=1048576-2097151"))
        assertEquals(1, Regex("[?&]range=").findAll(url).count())
        assertEquals(1, Regex("[?&]rn=").findAll(url).count())
        assertTrue(url.contains("cpn=xyz"))
    }

    @Test
    fun chunkEndStopsAtTheSongEnd() {
        assertEquals(1048575L, YoutubeStreamRequest.chunkEnd(0, 1048576, 3456789))
        assertEquals(3456788L, YoutubeStreamRequest.chunkEnd(3145728, 1048576, 3456789))
        assertEquals(1048575L + 10, YoutubeStreamRequest.chunkEnd(10, 1048576, -1))
    }

    @Test
    fun readsClenExpireAndItag() {
        assertEquals(3456789L, YoutubeStreamRequest.contentLength(vision))
        assertEquals(1793000000000L, YoutubeStreamRequest.expiresAtMs(vision))
        assertEquals(140, YoutubeStreamRequest.itagOf(vision))
        assertEquals(-1L, YoutubeStreamRequest.contentLength(web))
        assertEquals("VISIONOS", YoutubeStreamRequest.clientOf(vision))
    }

    @Test
    fun postBodyMatchesNewPipe() {
        assertEquals(listOf<Byte>(0x78, 0), YoutubeStreamRequest.POST_BODY.toList())
    }
}
