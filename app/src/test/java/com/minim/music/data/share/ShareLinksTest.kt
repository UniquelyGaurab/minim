package com.minim.music.data.share

import com.minim.music.data.share.ShareLinks.Decoded
import com.minim.music.data.share.ShareLinks.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Base64
import java.util.zip.Deflater

/** 6.6.0: Minim share links: the payload, its limits, and reading a link back. */
class ShareLinksTest {

    private fun id(n: Int): String {
        // 11 chars from the YouTube id alphabet, different for each n
        val abc = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
        var x = n.toLong() * 2654435761L + 12345L
        val sb = StringBuilder()
        repeat(11) { sb.append(abc[((x ushr 3) and 63).toInt()]); x = x * 6364136223846793005L + 1442695040888963407L }
        return sb.toString()
    }

    private val suroor = Song("RA4jeB5zk9A", "Suroor", "Neha Kakkar & Bilal Saeed")

    private val words = listOf(
        "Tum", "Hi", "Ho", "Dil", "Ishq", "Raat", "Kesariya", "Suroor", "Jaana", "Tera", "Mera", "Pyaar", "Baarish",
        "Saiyaan", "Kaavaalaa", "Hukum", "Naa", "Ready", "Chuttamalle", "Arabic", "Kuthu", "Vaathi", "Coming",
        "Love", "Night", "Drive", "Heart", "Summer", "Lights", "Fire", "Rain", "Home", "Again", "Forever",
        "Zara", "Sa", "Khwab", "Safar", "Manzil", "Yaadein", "Aankhein", "Chand", "Sitare", "Hawa", "Mausam",
        "Kannaana", "Kanne", "Rowdy", "Baby", "Why", "This", "Kolaveri", "Di", "Enjoy", "Enjaami", "Sooraj"
    )
    private val artists = listOf(
        "Arijit Singh", "Anirudh Ravichander", "Shreya Ghoshal", "A.R. Rahman", "Pritam", "Sid Sriram",
        "Neha Kakkar", "Bilal Saeed", "Shilpa Rao", "Dhvani Bhanushali", "Jubin Nautiyal", "Darshan Raval",
        "Atif Aslam", "Vishal-Shekhar", "Sachin-Jigar", "Armaan Malik", "Thalapathy Vijay", "Dhee",
        "Yuvan Shankar Raja", "Harris Jayaraj", "Sunidhi Chauhan", "Badshah", "KK", "Shankar Mahadevan"
    )

    /** Distinct, realistic-looking songs (random ids don't compress, like real ones). */
    private fun realisticSongs(n: Int): List<Song> {
        val r = java.util.Random(n.toLong() * 31 + 7)
        return List(n) { i ->
            val title = (0 until 1 + r.nextInt(4)).joinToString(" ") { words[r.nextInt(words.size)] } +
                (if (r.nextInt(4) == 0) " (From \"" + words[r.nextInt(words.size)] + "\")" else "")
            val artist = (0 until 1 + r.nextInt(3)).map { artists[r.nextInt(artists.size)] }.distinct().joinToString(", ")
            Song(id(i), title, artist)
        }
    }

    // ---- round trips ----------------------------------------------------------------------------

    @Test fun songRoundTrip() {
        val link = ShareLinks.songLink(suroor, "Gaurab")
        assertTrue(link, link.startsWith("https://uniquelygaurab.github.io/m/s#1."))
        val d = ShareLinks.decodeLink(link) as Decoded.SharedSong
        assertEquals(suroor, d.song)
        assertEquals("Gaurab", d.sharer)
    }

    @Test fun unicodeTitlesRoundTrip() {
        val songs = listOf(
            Song(id(1), "तुम ही हो", "अरिजीत सिंह"),
            Song(id(2), "கண்ணான கண்ணே", "சித் ஸ்ரீராம்"),
            Song(id(3), "Vibe 🔥🎧 (Remix) 💯", "DJ 🦋"),
            Song(id(4), "ছবি", "অনুপম রায়"),
            Song(id(5), "Mixed हिंदी தமிழ் 😀 text", "Ä Ö Ü ß é")
        )
        for (s in songs) {
            val d = ShareLinks.decodeLink(ShareLinks.songLink(s, "गौरव 🎵")) as Decoded.SharedSong
            assertEquals(s, d.song)
            assertEquals("गौरव 🎵", d.sharer)
        }
        val p = ShareLinks.decodeLink(ShareLinks.playlistLink("मेरी 🎶 பாடல்கள்", "Gaurab", songs)) as Decoded.SharedPlaylist
        assertEquals("मेरी 🎶 பாடல்கள்", p.name)
        assertEquals(songs, p.songs)
        assertEquals(0, p.skipped)
    }

    @Test fun playlistRoundTripKeepsOrder() {
        val songs = realisticSongs(37)
        val d = ShareLinks.decodeLink(ShareLinks.playlistLink("Late night", "Gaurab", songs)) as Decoded.SharedPlaylist
        assertEquals("Late night", d.name)
        assertEquals("Gaurab", d.sharer)
        assertEquals(songs, d.songs)
    }

    @Test fun payloadIsUrlSafeAndAfterTheHash() {
        val link = ShareLinks.playlistLink("Mix", "Gaurab", realisticSongs(100))
        val hash = link.indexOf('#')
        assertEquals("https://uniquelygaurab.github.io/m/p", link.substring(0, hash))
        assertTrue(link.substring(hash + 1).matches(Regex("^1\\.[A-Za-z0-9_-]+$")))
    }

    // ---- length and the cap -----------------------------------------------------------------------

    @Test fun linkLengths() {
        val l1 = ShareLinks.songLink(suroor, "Gaurab").length
        val l50 = ShareLinks.playlistLink("Late night drives", "Gaurab", realisticSongs(50)).length
        val l100 = ShareLinks.playlistLink("Late night drives", "Gaurab", realisticSongs(100)).length
        File("build/share-preview").mkdirs()
        File("build/share-preview/link-lengths.txt").writeText("song=$l1\nplaylist50=$l50\nplaylist100=$l100\n")
        println("SHARE LINK LENGTHS song=$l1 playlist50=$l50 playlist100=$l100")
        assertTrue("song link $l1", l1 < 200)
        assertTrue("50 songs $l50", l50 < 4000)
        assertTrue("100 songs $l100", l100 < 8000)
        assertTrue(l100 > l50)
    }

    @Test fun capAtOneHundred() {
        assertFalse(ShareLinks.needsCap(100))
        assertTrue(ShareLinks.needsCap(101))
        val songs = realisticSongs(150)
        val d = ShareLinks.decodeLink(ShareLinks.playlistLink("Big", null, songs)) as Decoded.SharedPlaylist
        assertEquals(100, d.songs.size)
        assertEquals(songs.take(100), d.songs)
    }

    @Test fun decoderRefusesMoreThanOneHundred() {
        // a hand-made link with 120 songs: only the first 100 come in, the rest count as skipped
        val rows = (0 until 120).joinToString(",") { "[\"${id(it)}\",\"T$it\",\"A\"]" }
        val d = ShareLinks.decodeLink("https://uniquelygaurab.github.io/m/p#" + raw("{\"l\":\"x\",\"s\":[$rows]}")) as Decoded.SharedPlaylist
        assertEquals(100, d.songs.size)
        assertEquals(20, d.skipped)
    }

    @Test fun songsWithoutVideoIdsStayOut() {
        val songs = listOf(Song("local:123", "Tape", "Me"), suroor, Song("", "x", "y"), Song("tooShort", "z", "z"))
        val d = ShareLinks.decodeLink(ShareLinks.playlistLink("Mix", null, songs)) as Decoded.SharedPlaylist
        assertEquals(listOf(suroor), d.songs)
    }

    @Test fun longTextIsClippedNotBroken() {
        val long = "🎵".repeat(300)
        val d = ShareLinks.decodeLink(ShareLinks.songLink(Song(suroor.id, long, long), "x".repeat(500))) as Decoded.SharedSong
        assertTrue(d.song.title.length <= ShareLinks.MAX_TITLE)
        assertEquals(0, d.song.title.length % 2) // no half emoji
        assertEquals(ShareLinks.MAX_NAME, d.sharer!!.length)
    }

    // ---- blank names -------------------------------------------------------------------------------

    @Test fun blankNameIsLeftOut() {
        for (name in listOf(null, "", "   ", "\n\t")) {
            val link = ShareLinks.songLink(suroor, name)
            val json = inflate(link.substringAfter("#1."))
            assertFalse(json, json.contains("\"n\""))
            assertNull((ShareLinks.decodeLink(link) as Decoded.SharedSong).sharer)
            val p = ShareLinks.decodeLink(ShareLinks.playlistLink("Mix", name, listOf(suroor))) as Decoded.SharedPlaylist
            assertNull(p.sharer)
        }
        assertTrue(inflate(ShareLinks.songLink(suroor, " Gaurab ").substringAfter("#1.")).contains("\"n\":\"Gaurab\""))
    }

    @Test fun shareText() {
        assertEquals("Gaurab shared Suroor on Minim", ShareLinks.shareLine("Gaurab", "Suroor"))
        assertEquals("Listen to Suroor on Minim", ShareLinks.shareLine("  ", "Suroor"))
        assertEquals("Listen to Suroor on Minim", ShareLinks.shareLine(null, "Suroor"))
        assertEquals("Gaurab shared Suroor on Minim\nhttps://x", ShareLinks.shareText("Gaurab", "Suroor", "https://x"))
    }

    // ---- broken, cut short, newer ----------------------------------------------------------------

    @Test fun truncatedLinksAreBrokenNeverCrash() {
        val link = ShareLinks.playlistLink("Mix", "Gaurab", realisticSongs(40))
        val start = link.indexOf('#') + 3
        var broken = 0
        for (cut in start until link.length) {
            val d = ShareLinks.decodeLink(link.substring(0, cut))
            // a cut never gives back a different, wrong playlist
            if (d is Decoded.SharedPlaylist) assertEquals(realisticSongs(40), d.songs) else { assertTrue(d is Decoded.Broken); broken++ }
        }
        assertTrue(broken > 0)
        val s = ShareLinks.songLink(suroor, "Gaurab")
        var songBroken = 0
        for (cut in s.indexOf('#') until s.length) {
            val d = ShareLinks.decodeLink(s.substring(0, cut))
            // the last char or two can be only padding bits: then it's the same song, never a wrong one
            if (d is Decoded.SharedSong) assertEquals("cut $cut", suroor, d.song) else { assertTrue("cut $cut: $d", d is Decoded.Broken); songBroken++ }
        }
        assertTrue(songBroken >= s.length - s.indexOf('#') - 3)
    }

    @Test fun garbledLinksAreBroken() {
        val base = "https://uniquelygaurab.github.io/m/s#"
        val bad = listOf(
            "", "1", "1.", ".abc", "abc", "x.abc", "1.!!!!", "1.AAAA", "1.____", "0." + raw("{}").substringAfter("."),
            "-1.abc", "1." + "A".repeat(40_000),
            raw("[1,2,3]"), raw("{\"i\":\"short\"}"), raw("{\"i\":42}"), raw("not json"), raw("{\"t\":\"no id\"}")
        )
        for (b in bad) assertTrue(b.take(40), ShareLinks.decodeLink(base + b) is Decoded.Broken)
        // flipped bytes: never a crash, never an exception
        val good = ShareLinks.songLink(suroor, "Gaurab")
        val start = good.indexOf('#') + 3
        val chars = "ABCxyz019-_"
        for (i in start until good.length) for (c in chars) {
            val flipped = good.substring(0, i) + c + good.substring(i + 1)
            val d = ShareLinks.decodeLink(flipped)
            assertTrue(d is Decoded.Broken || d is Decoded.SharedSong)
        }
        // a playlist with no usable song is broken too
        assertTrue(ShareLinks.decodeLink("https://uniquelygaurab.github.io/m/p#" + raw("{\"l\":\"x\",\"s\":[[\"bad\",\"a\",\"b\"]]}")) is Decoded.Broken)
        assertTrue(ShareLinks.decodeLink("https://uniquelygaurab.github.io/m/p#" + raw("{\"l\":\"x\",\"s\":[]}")) is Decoded.Broken)
    }

    @Test fun zipBombIsRefused() {
        val huge = "{\"i\":\"RA4jeB5zk9A\",\"t\":\"" + "a".repeat(1_000_000) + "\"}"
        val link = "https://uniquelygaurab.github.io/m/s#" + raw(huge)
        assertTrue(link.length < ShareLinks.MAX_PAYLOAD_CHARS)
        assertTrue(ShareLinks.decodeLink(link) is Decoded.Broken)
    }

    @Test fun newerVersionSaysUpdate() {
        val payload = ShareLinks.encodeSong(suroor, "Gaurab").substringAfter('.')
        assertEquals(Decoded.TooNew(2), ShareLinks.decodeLink("https://uniquelygaurab.github.io/m/s#2.$payload"))
        assertEquals(Decoded.TooNew(17), ShareLinks.decodeLink("https://uniquelygaurab.github.io/m/p#17.whatever-it-is"))
    }

    @Test fun unavailableSongsAreSkippedAndDuplicatesDropped() {
        val json = "{\"l\":\"Mix\",\"s\":[[\"RA4jeB5zk9A\",\"Suroor\",\"N\"],[\"nope\",\"x\",\"y\"],7,[],[\"RA4jeB5zk9A\",\"Suroor\",\"N\"],[\"GWNrPJyRTcA\"]]}"
        val d = ShareLinks.decodeLink("https://uniquelygaurab.github.io/m/p#" + raw(json)) as Decoded.SharedPlaylist
        assertEquals(listOf("RA4jeB5zk9A", "GWNrPJyRTcA"), d.songs.map { it.id })
        assertEquals("Untitled", d.songs[1].title)
        assertEquals("Unknown Artist", d.songs[1].artist)
        assertEquals(4, d.skipped)
    }

    // ---- reading links ---------------------------------------------------------------------------

    @Test fun parsesLinksAsTheyArriveFromChats() {
        val link = ShareLinks.songLink(suroor, "Gaurab")
        val payload = link.substringAfter('#')
        val variants = listOf(
            link,
            "  $link  ",
            "$link.",
            "$link)",
            link.replace("https://", "http://"),
            link.replace("/m/s#", "/m/s/#"),
            link.replace("/m/s#", "/m/s.html#"),
            link.replace("/m/s#", "/m/s?utm=wa#"),
            link.replace("uniquelygaurab.github.io", "UniquelyGaurab.GitHub.io"),
            "https://uniquelygaurab.github.io/m/s#" + payload.replace(".", "%2E")
        )
        for (v in variants) {
            val d = ShareLinks.decodeLink(v)
            assertTrue(v, d is Decoded.SharedSong)
            assertEquals(suroor, (d as Decoded.SharedSong).song)
        }
        assertEquals(ShareLinks.Kind.PLAYLIST, ShareLinks.parse("https://uniquelygaurab.github.io/m/p#1.x")?.kind)
        for (notOurs in listOf(
            null, "", "Suroor", "https://example.com/m/s#1.x", "https://uniquelygaurab.github.io/minim/#x",
            "https://uniquelygaurab.github.io/m/x#1.x", "https://uniquelygaurab.github.io.evil.com/m/s#1.x",
            "ftp://uniquelygaurab.github.io/m/s#1.x", "https://music.youtube.com/watch?v=RA4jeB5zk9A"
        )) assertNull(notOurs, ShareLinks.parse(notOurs))
        // no fragment at all: a Minim link, but broken
        assertTrue(ShareLinks.decodeLink("https://uniquelygaurab.github.io/m/s") is Decoded.Broken)
        assertTrue(ShareLinks.decodeLink("https://uniquelygaurab.github.io/m/p#") is Decoded.Broken)
    }

    @Test fun fromParts() {
        assertEquals(ShareLinks.Parsed(ShareLinks.Kind.SONG, "1.abc"), ShareLinks.fromParts("/m/s", "1.abc"))
        assertEquals(ShareLinks.Parsed(ShareLinks.Kind.PLAYLIST, ""), ShareLinks.fromParts("/m/p/", null))
        assertNull(ShareLinks.fromParts("/m/", "1.abc"))
        assertNull(ShareLinks.fromParts(null, "1.abc"))
        assertNull(ShareLinks.fromParts("/s", "1.abc"))
    }

    /** Writes Kotlin-made links for the web page test (scripts/web-check.py reads them). */
    @Test fun writesSamplesForTheWebPage() {
        val dir = File("build/share-preview").apply { mkdirs() }
        val unicode = listOf(
            Song("RA4jeB5zk9A", "Suroor", "Neha Kakkar & Bilal Saeed"),
            Song("GWNrPJyRTcA", "Chuttamalle", "Anirudh Ravichander, Shilpa Rao"),
            Song("em9fQdHmKh4", "Hukum - Thalaivar Alappara", "Anirudh Ravichander"),
            Song("6mr4cYJ7yew", "Kesariya", "Pritam, Arijit Singh"),
            Song("szvt1vD0Uug", "Naa Ready", "Anirudh Ravichander, Thalapathy Vijay")
        )
        val lines = listOf(
            "song\t" + ShareLinks.songLink(unicode[0], "Gaurab"),
            "song_noname\t" + ShareLinks.songLink(unicode[0], ""),
            "song_unicode\t" + ShareLinks.songLink(Song("6mr4cYJ7yew", "केसरिया 🔥", "அரிஜித் சிங்"), "गौरव"),
            "playlist\t" + ShareLinks.playlistLink("Gaurab's picks", "Gaurab", unicode),
            "playlist_100\t" + ShareLinks.playlistLink("Late night drives", "Gaurab", realisticSongs(100)),
            "too_new\thttps://uniquelygaurab.github.io/m/s#2." + ShareLinks.encodeSong(unicode[0], "G").substringAfter('.'),
            "broken\t" + ShareLinks.songLink(unicode[0], "Gaurab").dropLast(9)
        )
        File(dir, "links.tsv").writeText(lines.joinToString("\n") + "\n")
        assertEquals(7, File(dir, "links.tsv").readLines().size)
    }

    // ---- helpers -----------------------------------------------------------------------------------

    private fun raw(json: String): String {
        val d = Deflater(9, true)
        d.setInput(json.toByteArray(Charsets.UTF_8)); d.finish()
        val out = ByteArrayOutputStream(); val buf = ByteArray(4096)
        while (!d.finished()) out.write(buf, 0, d.deflate(buf))
        d.end()
        return "1." + Base64.getUrlEncoder().withoutPadding().encodeToString(out.toByteArray())
    }

    private fun inflate(b64: String): String {
        val inf = java.util.zip.Inflater(true)
        inf.setInput(Base64.getUrlDecoder().decode(b64) + 0)
        val out = ByteArrayOutputStream(); val buf = ByteArray(4096)
        while (!inf.finished()) { val n = inf.inflate(buf); if (n == 0 && inf.needsInput()) break; out.write(buf, 0, n) }
        inf.end()
        return out.toString("UTF-8")
    }
}
