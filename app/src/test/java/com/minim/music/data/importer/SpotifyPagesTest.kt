package com.minim.music.data.importer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Real pages saved from Spotify in October 2026 (token replaced, market lists trimmed). */
class SpotifyPagesTest {

    private fun res(name: String): String =
        javaClass.classLoader!!.getResource("importer/$name")!!.readText()

    @Test fun `embed page gives the name, the first 100 songs and the page token`() {
        val e = SpotifyPages.parseEmbed(res("spotify_embed_all_out_2000s.html"))
        assertNotNull(e)
        e!!
        assertEquals("All Out 2000s", e.name)
        assertEquals("playlist", e.kind)
        assertEquals(SpotifyPages.EMBED_LIMIT, e.songs.size)
        assertEquals("TEST_TOKEN", e.token)
        val first = e.songs.first()
        assertTrue(first.title.isNotBlank())
        assertTrue(first.artists.isNotEmpty())
        assertTrue(first.durationMs > 60_000)
    }

    @Test fun `web player page gives the songs after 100 and the total`() {
        val p = SpotifyPages.parsePage(res("spotify_page_offset100.json"))
        assertNotNull(p)
        p!!
        assertEquals(150, p.total)
        assertEquals(50, p.songs.size)
        assertTrue(p.songs.all { it.title.isNotBlank() && it.artists.isNotEmpty() && it.durationMs > 0 })
    }

    @Test fun `total from the regular playlist page`() {
        assertEquals(150, SpotifyPages.parseTotalFromPage(res("spotify_page_initialstate.html")))
        assertEquals(0, SpotifyPages.parseTotalFromPage("<html></html>"))
    }

    @Test fun `private or broken pages give nothing`() {
        assertNull(SpotifyPages.parseEmbed("<html><body>Page not found</body></html>"))
        assertNull(
            SpotifyPages.parseEmbed(
                """<script id="__NEXT_DATA__" type="application/json">{"props":{"pageProps":{"status":404}}}</script>"""
            )
        )
        assertNull(SpotifyPages.parsePage("""{"errors":[{"message":"PersistedQueryNotFound"}]}"""))
        assertNull(SpotifyPages.parsePage("not json"))
    }

    @Test fun `podcast episodes in a playlist are skipped`() {
        val html = """<script id="__NEXT_DATA__" type="application/json">{"props":{"pageProps":{"state":{"data":{"entity":
            {"name":"Mix","type":"playlist","trackList":[
            {"title":"Song A","subtitle":"Artist A, Artist B","duration":200000,"entityType":"track"},
            {"title":"Episode 4","subtitle":"Some Podcast","duration":3600000,"entityType":"episode"}]}},
            "settings":{"session":{"accessToken":""}}}}}}</script>"""
        val e = SpotifyPages.parseEmbed(html)!!
        assertEquals(1, e.songs.size)
        assertEquals(listOf("Artist A", "Artist B"), e.songs[0].artists)
        assertNull(e.token)
    }
}
