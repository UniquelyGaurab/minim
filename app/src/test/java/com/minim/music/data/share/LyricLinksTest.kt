package com.minim.music.data.share

import com.minim.music.data.share.ShareLinks.Decoded
import com.minim.music.data.share.ShareLinks.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Base64
import java.util.zip.Deflater

/** 6.6.0 lyrics card: song links that start at a line, and older links still opening. */
class LyricLinksTest {

    private val suroor = Song("RA4jeB5zk9A", "Suroor", "Neha Kakkar & Bilal Saeed")
    /** Suroor's lines 11 and 12 on LRCLIB (id 4855385); the first starts at 0:51.83. */
    private val hook = listOf(
        "ਪੀਤੀ ਵੀ ਨਹੀਂ, ਪੀਤੀ ਵੀ ਨਹੀਂ",
        "ਹਾਏ, ਪੀਤੀ ਵੀ ਨਹੀਂ, ਫ਼ਿਰ ਵੀ ਇਕ ਸੁਰੂਰ ਜਿਹਾ ਏ"
    )

    /** The JSON inside a v1 link. */
    private fun json(link: String): String {
        val data = link.substringAfter('#').substringAfter('.')
        val inf = java.util.zip.Inflater(true)
        inf.setInput(Base64.getUrlDecoder().decode(data))
        val out = ByteArrayOutputStream(); val buf = ByteArray(512)
        while (!inf.finished()) { val n = inf.inflate(buf); if (n == 0 && inf.needsInput()) break; out.write(buf, 0, n) }
        return out.toString("UTF-8")
    }

    private fun rawPayload(json: String): String {
        val d = Deflater(Deflater.BEST_COMPRESSION, true)
        d.setInput(json.toByteArray(Charsets.UTF_8)); d.finish()
        val out = ByteArrayOutputStream(); val buf = ByteArray(512)
        while (!d.finished()) out.write(buf, 0, d.deflate(buf))
        return "1." + Base64.getUrlEncoder().withoutPadding().encodeToString(out.toByteArray())
    }

    @Test fun roundTripWithStart() {
        val link = ShareLinks.lyricLink(suroor, "Gaurab", 51_830L, hook)
        assertTrue(link.startsWith("https://uniquelygaurab.github.io/m/s#1."))
        val d = ShareLinks.decodeLink(link) as Decoded.SharedSong
        assertEquals(suroor, d.song)
        assertEquals("Gaurab", d.sharer)
        assertEquals(51_830L, d.startMs)
    }

    @Test fun roundTripWithoutStart() {
        // unsynced lyrics: no start time, but the lines' checksum still goes
        val link = ShareLinks.lyricLink(suroor, "Gaurab", null, hook)
        val d = ShareLinks.decodeLink(link) as Decoded.SharedSong
        assertNull(d.startMs)
        assertEquals(suroor, d.song)
        assertTrue(!json(link).contains("\"s\""))
        assertTrue(json(link).contains("\"c\":2"))
    }

    @Test fun plainSongLinkUnchanged() {
        val plain = ShareLinks.songLink(suroor, "Gaurab")
        val d = ShareLinks.decodeLink(plain) as Decoded.SharedSong
        assertNull(d.startMs)
        assertEquals(suroor, d.song)
        // a lyric link with no start and no lines is exactly the plain song link
        assertEquals(plain, ShareLinks.lyricLink(suroor, "Gaurab", null, emptyList()))
        assertEquals(137, plain.length)
    }

    @Test fun oldLinksStillOpen() {
        // the 6.6.0 sample song link sent before the lyrics card
        val old = "1.q1bKVLJSCnI0yUp1Mq3KtnRU0lEqAYoElxbl5xcBOYlAjl9qRqKCd2J2dmKRgpqCU2ZOYo5CcGJqagpQPg8o755YWpSYpFQLAA"
        val d = ShareLinks.decode(ShareLinks.Parsed(ShareLinks.Kind.SONG, old)) as Decoded.SharedSong
        assertEquals("RA4jeB5zk9A", d.song.id)
        assertNull(d.startMs)
    }

    @Test fun blankNameLeftOut() {
        for (name in listOf(null, "", "   ")) {
            val link = ShareLinks.lyricLink(suroor, name, 83_000L, hook)
            val d = ShareLinks.decodeLink(link) as Decoded.SharedSong
            assertNull(d.sharer)
            assertEquals(83_000L, d.startMs)
            assertTrue(!json(link).contains("\"n\""))
        }
        assertEquals("A line from Suroor on Minim", ShareLinks.lyricShareLine(" ", "Suroor", 1))
        assertEquals("Lines from Suroor on Minim", ShareLinks.lyricShareLine(null, "Suroor", 3))
    }

    @Test fun shareText() {
        assertEquals("Gaurab shared a line from Suroor on Minim", ShareLinks.lyricShareLine("Gaurab", "Suroor", 1))
        assertEquals("Gaurab shared lines from Suroor on Minim", ShareLinks.lyricShareLine("Gaurab", "Suroor", 2))
        assertEquals("Gaurab shared a line from Suroor on Minim\nhttps://x", ShareLinks.lyricShareText("Gaurab", "Suroor", 1, "https://x"))
    }

    @Test fun badStartTimesAreIgnored() {
        for (bad in listOf("0", "-5", "1.5", "\"83\"", "99999999999", "true")) {
            val p = rawPayload("""{"i":"RA4jeB5zk9A","t":"Suroor","a":"Neha","s":$bad}""")
            val d = ShareLinks.decode(ShareLinks.Parsed(ShareLinks.Kind.SONG, p)) as Decoded.SharedSong
            assertNull("s=$bad", d.startMs)
            assertEquals("RA4jeB5zk9A", d.song.id)
        }
        val ok = rawPayload("""{"i":"RA4jeB5zk9A","t":"Suroor","a":"Neha","s":83000,"c":2,"h":123,"z":"future"}""")
        assertEquals(83_000L, (ShareLinks.decode(ShareLinks.Parsed(ShareLinks.Kind.SONG, ok)) as Decoded.SharedSong).startMs)
        assertNull(ShareLinks.cleanStart(0L))
        assertNull(ShareLinks.cleanStart(ShareLinks.MAX_START_MS + 1))
    }

    @Test fun quoteHashMatchesTheWebPage() {
        // the same CRC-32 the web page works out (Python's zlib.crc32 of the lines joined by \n)
        assertEquals(2120728657L, ShareLinks.quoteHash(hook))
        // spaces don't matter, so a line with doubled spaces still matches
        assertEquals(ShareLinks.quoteHash(hook), ShareLinks.quoteHash(hook.map { "  " + it.replace(" ", "   ") + " " }))
        assertEquals("0:51", ShareLinks.formatStart(51_830L))
        assertEquals("1:23", ShareLinks.formatStart(83_000L))
        assertEquals("1:02:03", ShareLinks.formatStart(3_723_000L))
    }

    @Test fun linesNeverInTheLink() {
        val link = ShareLinks.lyricLink(suroor, "Gaurab", 51_830L, hook)
        val json = json(link)
        assertTrue(!json.contains("ਪੀਤੀ"))
        assertTrue(link.length < 200)
        File("build/share-preview").mkdirs()
        File("build/share-preview/lyric-link.txt").writeText(link + "\n" + json + "\n")
    }
}
