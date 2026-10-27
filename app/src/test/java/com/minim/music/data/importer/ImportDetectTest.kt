package com.minim.music.data.importer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ImportDetectTest {

    private fun detect(s: String) = ImportDetect.detect(s)

    @Test fun `youtube music playlist link`() {
        assertEquals(
            ImportInput.YouTube("PLabc_123-XY", music = true),
            detect("https://music.youtube.com/playlist?list=PLabc_123-XY&si=zz9")
        )
    }

    @Test fun `youtube playlist and watch-with-list and mobile links`() {
        assertEquals(ImportInput.YouTube("PL1"), detect("https://www.youtube.com/playlist?list=PL1"))
        assertEquals(ImportInput.YouTube("PL2"), detect("https://m.youtube.com/watch?v=abc&list=PL2&index=3"))
        assertEquals(ImportInput.YouTube("OLAK5uy_x"), detect("youtube.com/playlist?list=OLAK5uy_x"))
        assertEquals(ImportInput.YouTube("PL3"), detect("https://youtu.be/abc?list=PL3"))
    }

    @Test fun `youtube single video and private lists are refused`() {
        assertEquals(
            ImportInput.Unsupported(ImportInput.Why.YOUTUBE_NOT_PLAYLIST),
            detect("https://www.youtube.com/watch?v=dQw4w9WgXcQ")
        )
        assertEquals(
            ImportInput.Unsupported(ImportInput.Why.YOUTUBE_PRIVATE_LIST),
            detect("https://music.youtube.com/playlist?list=LM")
        )
    }

    @Test fun `spotify playlist link with share text around it`() {
        assertEquals(
            ImportInput.Spotify("playlist", "37i9dQZF1DX4o1oenSJRJd"),
            detect("Check out All Out 2000s on Spotify! https://open.spotify.com/playlist/37i9dQZF1DX4o1oenSJRJd?si=abc123")
        )
    }

    @Test fun `spotify intl path, album, uri and embed`() {
        assertEquals(ImportInput.Spotify("playlist", "abc"), detect("https://open.spotify.com/intl-de/playlist/abc"))
        assertEquals(ImportInput.Spotify("album", "xyz"), detect("https://open.spotify.com/album/xyz?si=1"))
        assertEquals(ImportInput.Spotify("playlist", "q1"), detect("spotify:playlist:q1"))
        assertEquals(ImportInput.Spotify("playlist", "e1"), detect("https://open.spotify.com/embed/playlist/e1"))
    }

    @Test fun `spotify track link is refused and short link is followed`() {
        assertEquals(
            ImportInput.Unsupported(ImportInput.Why.SPOTIFY_NOT_PLAYLIST),
            detect("https://open.spotify.com/track/4cOdK2wGLETKBW3PvgPWqT")
        )
        assertEquals(ImportInput.SpotifyShort("https://spotify.link/AbCd"), detect("https://spotify.link/AbCd"))
    }

    @Test fun `other links are refused`() {
        assertEquals(ImportInput.Unsupported(ImportInput.Why.UNKNOWN_LINK), detect("https://example.com/my-songs"))
    }

    @Test fun `empty input`() {
        assertEquals(ImportInput.Empty, detect("   \n  "))
    }

    @Test fun `plain lines with en dash, hyphen, by, numbering and times`() {
        val r = detect(
            """
            1. Kesariya – Arijit Singh
            2) Tum Hi Ho - Arijit Singh 4:22
            • Blinding Lights by The Weeknd

            Shape of You
            """.trimIndent()
        ) as ImportInput.Lines
        assertEquals(4, r.songs.size)
        assertEquals("Kesariya", r.songs[0].title)
        assertEquals(listOf("Arijit Singh"), r.songs[0].artists)
        assertEquals("Tum Hi Ho", r.songs[1].title)
        assertEquals("Blinding Lights", r.songs[2].title)
        assertEquals(listOf("The Weeknd"), r.songs[2].artists)
        assertEquals("Shape of You", r.songs[3].title)
        assertTrue(r.songs[3].artists.isEmpty())
        assertEquals("Shape of You", r.songs[3].query)
        assertEquals("Kesariya Arijit Singh", r.songs[0].query)
    }

    @Test fun `hyphen inside a word is not a split`() {
        val s = ImportDetect.parseLine("Nee-yum Naanum")!!
        assertEquals("Nee-yum Naanum", s.title)
        assertTrue(s.artists.isEmpty())
    }

    @Test fun `several artists are split`() {
        val s = ImportDetect.parseLine("Senorita – Shawn Mendes, Camila Cabello")!!
        assertEquals(listOf("Shawn Mendes", "Camila Cabello"), s.artists)
    }

    @Test fun `repeated lines inside the list are removed`() {
        val r = detect("Kesariya – Arijit Singh\nkesariya - arijit singh\nKesariya – Arijit Singh (Official Video)") as ImportInput.Lines
        assertEquals(1, r.songs.size)
    }

    @Test fun `plain list stops at the line limit`() {
        val text = (1..1200).joinToString("\n") { "Song number $it – Artist" }
        val r = detect(text) as ImportInput.Lines
        assertEquals(ImportDetect.MAX_LINES, r.songs.size)
    }
}
