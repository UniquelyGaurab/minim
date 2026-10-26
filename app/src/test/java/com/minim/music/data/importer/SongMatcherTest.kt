package com.minim.music.data.importer

import com.minim.music.data.model.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SongMatcherTest {

    private fun t(id: String, title: String, artist: String, sec: Long = 0) =
        Track(id = id, title = title, artist = artist, durationSec = sec, thumbnailUrl = "", sourceUrl = "")

    private val kesariya = SourceSong("Kesariya (From \"Brahmastra\")", listOf("Pritam", "Arijit Singh"), 268_000)

    @Test fun `core drops brackets, feat, remaster endings and video words`() {
        assertEquals("kesariya", SongMatcher.core("Kesariya (From \"Brahmastra\")"))
        assertEquals("hey jude", SongMatcher.core("Hey Jude - Remastered 2015"))
        assertEquals("senorita", SongMatcher.core("Señorita feat. Camila Cabello"))
        assertEquals("believer", SongMatcher.core("Believer (Official Music Video)"))
    }

    @Test fun `exact song with artist and length is sure`() {
        val r = SongMatcher.match(kesariya, listOf(t("a", "Kesariya (From \"Brahmastra\")", "Pritam, Arijit Singh", 268)))
        assertEquals(Confidence.SURE, r.confidence)
        assertEquals("a", r.best!!.track.id)
    }

    @Test fun `official video titled artist - song is sure`() {
        val r = SongMatcher.match(kesariya, listOf(t("v", "Arijit Singh - Kesariya | Brahmastra | Ranbir | Alia", "Sony Music India", 270)))
        assertEquals(Confidence.SURE, r.confidence)
    }

    @Test fun `remix is flagged when the source isn't one`() {
        val r = SongMatcher.match(kesariya, listOf(t("r", "Kesariya Remix", "DJ Someone, Arijit Singh", 250)))
        assertEquals(Confidence.DOUBTFUL, r.confidence)
        assertTrue(MatchFlag.REMIX in r.best!!.flags)
    }

    @Test fun `cover, live and slowed are flagged`() {
        val src = SourceSong("Blinding Lights", listOf("The Weeknd"), 200_000)
        assertTrue(MatchFlag.COVER in SongMatcher.score(src, t("c", "Blinding Lights (Cover) The Weeknd", "Some Guy", 200)).flags)
        assertTrue(MatchFlag.LIVE in SongMatcher.score(src, t("l", "Blinding Lights (Live at SoFi)", "The Weeknd", 210)).flags)
        assertTrue(MatchFlag.VERSION in SongMatcher.score(src, t("s", "Blinding Lights slowed + reverb", "The Weeknd", 260)).flags)
    }

    @Test fun `a remix in the source is not flagged as a remix`() {
        val src = SourceSong("Lean On - Remix", listOf("Major Lazer"), 0)
        val r = SongMatcher.match(src, listOf(t("x", "Lean On (Remix)", "Major Lazer")))
        assertFalse(MatchFlag.REMIX in r.best!!.flags)
        assertEquals(Confidence.SURE, r.confidence)
    }

    @Test fun `the original wins over a remix that comes first in search`() {
        val r = SongMatcher.match(
            kesariya,
            listOf(
                t("remix", "Kesariya (Remix)", "Pritam, Arijit Singh", 240),
                t("orig", "Kesariya", "Pritam, Arijit Singh", 268)
            )
        )
        assertEquals("orig", r.best!!.track.id)
        assertEquals(Confidence.SURE, r.confidence)
        assertEquals(2, r.options.size)
    }

    @Test fun `other artist is doubtful`() {
        val src = SourceSong("Hello", listOf("Adele"), 295_000)
        val r = SongMatcher.match(src, listOf(t("h", "Hello", "Lionel Richie", 250)))
        assertEquals(Confidence.DOUBTFUL, r.confidence)
        assertTrue(MatchFlag.OTHER_ARTIST in r.best!!.flags)
    }

    @Test fun `very different length is flagged`() {
        val src = SourceSong("Kesariya", listOf("Arijit Singh"), 268_000)
        val s = SongMatcher.score(src, t("long", "Kesariya", "Arijit Singh", 3600))
        assertTrue(MatchFlag.LENGTH in s.flags)
        assertFalse(s.sure)
    }

    @Test fun `nothing found and unrelated results give no match`() {
        assertEquals(Confidence.NONE, SongMatcher.match(kesariya, emptyList()).confidence)
        assertNull(SongMatcher.match(kesariya, emptyList()).best)
        val r = SongMatcher.match(kesariya, listOf(t("z", "Cooking pasta at home", "Chef Channel", 600)))
        assertEquals(Confidence.NONE, r.confidence)
    }

    @Test fun `plain line without artist is sure when every word is found`() {
        val line = ImportDetect.parseLine("Shape of You")!!
        val r = SongMatcher.match(line, listOf(t("s", "Shape of You", "Ed Sheeran", 234)))
        assertEquals(Confidence.SURE, r.confidence)
    }

    @Test fun `plain line written artist - song is matched both ways round`() {
        val line = ImportDetect.parseLine("Arijit Singh - Tum Hi Ho")!!
        val r = SongMatcher.match(line, listOf(t("th", "Tum Hi Ho", "Arijit Singh", 262)))
        assertEquals(Confidence.SURE, r.confidence)
    }

    @Test fun `topic channel names still match the artist`() {
        val src = SourceSong("Believer", listOf("Imagine Dragons"), 204_000)
        assertTrue(SongMatcher.artistMatches(src.artists, t("b", "Believer", "Imagine Dragons - Topic")))
        assertTrue(SongMatcher.artistMatches(src.artists, t("b", "Believer", "ImagineDragonsVEVO")))
    }

    @Test fun `options keep at most five, best first, each once`() {
        val list = (1..8).map { t("id$it", "Kesariya version $it", "Arijit Singh") } + t("id1", "dup", "x")
        val r = SongMatcher.match(kesariya, list)
        assertEquals(SongMatcher.MAX_OPTIONS, r.options.size)
        assertEquals(r.options.map { it.track.id }.distinct().size, r.options.size)
    }
}
