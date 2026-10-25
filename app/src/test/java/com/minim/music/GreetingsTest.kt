package com.minim.music

import com.minim.music.data.greetings.GreetingRule
import com.minim.music.data.greetings.GreetingTokens
import com.minim.music.data.greetings.RemoteGreeting
import com.minim.music.data.greetings.RemoteGreetingFile
import com.minim.music.data.greetings.RemoteGreetingsParser
import com.minim.music.ui.GreetingFacts
import com.minim.music.ui.Greetings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Calendar
import kotlin.random.Random

/** 5.1.0: stat greetings from the greetings file, and how they mix with the time-based ones. */
class GreetingsTest {

    private fun at(hour: Int, dow: Int = Calendar.WEDNESDAY): Calendar = Calendar.getInstance().apply {
        set(2026, Calendar.OCTOBER, 21, hour, 30)
        while (get(Calendar.DAY_OF_WEEK) != dow) add(Calendar.DAY_OF_MONTH, 1)
    }

    private val full = GreetingFacts(
        nowId = "a", nowTitle = "Cartoon, Jéja - On & On (feat. Daniel Levi) [NCS Release]",
        nowArtist = "Cartoon, Jéja, Daniel Levi", nowPlaying = true, nowLiked = false,
        lastId = "b", lastTitle = "Blinding Lights", lastArtist = "The Weeknd",
        playsToday = 7, topTrackWeekTitle = "On & On", topTrackWeekPlays = 6,
        topArtistWeek = "Cartoon - Topic", topArtistWeekPlays = 9, artistsWeek = 8,
        likedCount = 142, recentLikedTitle = "Levitating", topLikedArtist = "Dua Lipa", topLikedArtistCount = 5,
        playlistCount = 3, newestPlaylist = "Gym", biggestPlaylist = "Chill", biggestPlaylistSongs = 24,
        weekPlays = mapOf("a" to 6, "b" to 2)
    )

    /** The copy of greetings.json shipped in the app (the same file as greetings/greetings.json on GitHub). */
    private val shipped: RemoteGreetingFile by lazy {
        RemoteGreetingsParser.parse(File("src/main/assets/greetings_seed.json").readText())!!
    }

    @Test
    fun shortening() {
        assertEquals("On & On", Greetings.shortTitle("Cartoon, Jéja - On & On (feat. Daniel Levi) [NCS Release]", "Cartoon, Jéja, Daniel Levi"))
        assertEquals("Cartoon", Greetings.shortArtist("Cartoon, Jéja, Daniel Levi"))
        assertEquals("Cartoon", Greetings.shortArtist("Cartoon - Topic"))
        assertEquals("Simon", Greetings.shortArtist("Simon & Garfunkel"))
        val long = Greetings.shortTitle("A Really Very Extremely Long Song Title That Goes On", null)
        assertTrue(long, long.length <= 22 && long.endsWith("…"))
        assertEquals("(Intro)", Greetings.shortTitle("(Intro)", null))
    }

    @Test
    fun factsBecomePlaceholders() {
        val d = full.toData(at(20, Calendar.FRIDAY))
        assertEquals("On & On", d.value("song"))
        assertEquals("Cartoon", d.value("artist"))
        assertEquals(6, d.value("plays"))
        assertEquals(true, d.value(GreetingTokens.PLAYING))
        assertEquals(false, d.value(GreetingTokens.SONG_LIKED))
        assertEquals("Blinding Lights", d.value("last"))
        assertEquals("The Weeknd", d.value("lastartist"))
        assertEquals(7, d.value("today"))
        assertEquals(142, d.value("liked"))
        assertEquals("Friday", d.value("weekday"))
        assertEquals("Gym", d.value("playlist"))
        assertEquals(24, d.value("bigplaylistsongs"))
        // Every placeholder the parser knows has an answer here
        for (t in GreetingTokens.STATS) assertNotNull(t, d.value(t))
    }

    @Test
    fun missingDataIsNull() {
        val empty = GreetingFacts().toData(at(12))
        for (t in GreetingTokens.WORDS - "weekday") assertNull(t, empty.value(t))
        assertNull(empty.value("plays"))
        assertNull(empty.value(GreetingTokens.PLAYING))
        // The last song is the one in the player: no {last}
        val same = full.copy(lastId = "a").toData(at(12))
        assertNull(same.value("last"))
        assertNull(same.value("lastartist"))
        // "Unknown Artist" is never named
        val unknown = full.copy(nowArtist = "Unknown Artist").toData(at(12))
        assertNull(unknown.value("artist"))
        // A song with no plays this week is 0, so a {plays} line doesn't fit
        assertEquals(0, full.copy(nowId = "zzz").toData(at(12)).value("plays"))
    }

    @Test
    fun shippedFileIsValidAndSafeForOlderApps() {
        val f = shipped
        assertEquals(0, f.skipped)
        assertEquals(2, f.version)
        assertTrue(f.stats.size >= 40)
        assertTrue(f.anytime.size >= 40)
        // Nothing in the "greetings" list (which 4.9.6-5.0.0 also read) uses a stat placeholder
        for (g in f.anytime + f.occasions) assertFalse("$g", g.usesStats)
        assertTrue(File("src/main/assets/greetings_seed.json").length() < 64 * 1024)
    }

    @Test
    fun everyStatGreetingFitsWithTheLongestNames() {
        val longest = GreetingFacts(
            nowId = "a", nowTitle = "W".repeat(60), nowArtist = "Abcdefghijkl", nowPlaying = true, nowLiked = true,
            lastId = "b", lastTitle = "W".repeat(60), lastArtist = "Abcdefghijkl",
            playsToday = 9999, topTrackWeekTitle = "W".repeat(60), topTrackWeekPlays = 9999,
            topArtistWeek = "The Abcdefghijklmnopq", topArtistWeekPlays = 9999, artistsWeek = 9999,
            likedCount = 9999, recentLikedTitle = "W".repeat(60), topLikedArtist = "The Abcdefghijklmnopq",
            topLikedArtistCount = 9999, playlistCount = 9999, newestPlaylist = "W".repeat(60),
            biggestPlaylist = "W".repeat(60), biggestPlaylistSongs = 9999, weekPlays = mapOf("a" to 9999)
        )
        for ((i, g) in shipped.stats.withIndex()) {
            // Each line under the conditions it was written for
            val rule = g.rule
            val facts = longest.copy(
                nowPlaying = rule.playing ?: true,
                nowLiked = rule.liked ?: true,
                playsToday = rule.max["today"] ?: 9999,
                likedCount = rule.max["liked"] ?: 9999,
                artistsWeek = rule.max["artists"] ?: 9999,
                playlistCount = rule.max["playlists"] ?: 9999
            )
            val hour = if (rule.fromHour >= 0) rule.fromHour else 12
            val dow = rule.days.firstOrNull() ?: Calendar.WEDNESDAY
            val lines = g.render("Abcdefghijklmnopqrst", facts.toData(at(hour, dow)), at(hour, dow).apply { set(Calendar.HOUR_OF_DAY, hour) })
            assertNotNull("stat $i: $g", lines)
            assertTrue("stat $i: $lines", lines!!.first.length <= 44 && lines.second.length <= 44)
            assertFalse("stat $i: $lines", (lines.first + lines.second).contains("{"))
        }
    }

    @Test
    fun noDataNoStatGreeting() {
        val empty = GreetingFacts()
        for (hour in 0..23) for (i in shipped.stats.indices) {
            val id = Greetings.STAT_BASE + i
            val l = Greetings.remoteLines(id, "", at(hour, Calendar.FRIDAY), shipped, empty)
            assertNull("$id at $hour: $l", l)
        }
    }

    @Test
    fun statGreetingsRenderWithRealData() {
        var shown = 0
        for (hour in listOf(2, 9, 14, 20)) for (dow in listOf(Calendar.WEDNESDAY, Calendar.FRIDAY)) {
            for (i in shipped.stats.indices) {
                val l = Greetings.remoteLines(Greetings.STAT_BASE + i, "Gaurab", at(hour, dow), shipped, full) ?: continue
                shown++
                assertTrue(l.first.isNotBlank() && l.second.isNotBlank())
                assertFalse(l.toString(), (l.first + l.second).contains("{"))
            }
        }
        assertTrue("$shown", shown > 100)
        // Examples the user saw
        val rent = shipped.stats.indexOfFirst { it.line2 == "At this point it pays rent." }
        assertEquals("On & On, again?" to "At this point it pays rent.",
            Greetings.remoteLines(Greetings.STAT_BASE + rent, "", at(14), shipped, full))
        assertNull(Greetings.remoteLines(Greetings.STAT_BASE + rent, "", at(14), shipped, full.copy(weekPlays = mapOf("a" to 2))))
        val talk = shipped.stats.indexOfFirst { it.line2 == "this week. We need to talk." }
        assertEquals("6 plays of On & On" to "this week. We need to talk.",
            Greetings.remoteLines(Greetings.STAT_BASE + talk, "", at(14), shipped, full))
        val couch = shipped.stats.indexOfFirst { it.line2 == "The couch is proud of you." }
        assertEquals("23 songs today." to "The couch is proud of you.",
            Greetings.remoteLines(Greetings.STAT_BASE + couch, "", at(14), shipped, full.copy(playsToday = 23)))
        assertNull(Greetings.remoteLines(Greetings.STAT_BASE + couch, "", at(14), shipped, full))
    }

    @Test
    fun rotationMixesStatsAndNeverRepeats() {
        val rot = Greetings.Rotation(Random(7)) { shipped }
        rot.markShown(Greetings.TIME_BASED)
        var prev = Greetings.TIME_BASED
        var stats = 0
        val now = at(20)
        repeat(400) {
            val g = rot.next(now) { Greetings.fits(it, full, "Gaurab", now, shipped) }
            assertNotEquals(prev, g)
            if (Greetings.isStat(g)) stats++
            prev = g
        }
        // Every second greeting is a stat one, apart from the slots festival lines take
        assertTrue("$stats", stats in 120..200)
        // Nothing played yet: no stat lines, the regular bag still never repeats
        val rot2 = Greetings.Rotation(Random(3)) { shipped }
        prev = -1
        repeat(100) {
            val g = rot2.next(now) { Greetings.fits(it, GreetingFacts(), "", now, shipped) }
            assertFalse(Greetings.isStat(g))
            assertNotEquals(prev, g)
            prev = g
        }
    }

    @Test
    fun newStatLinesJoinWithoutARestart() {
        var file = RemoteGreetingFile.EMPTY
        val rot = Greetings.Rotation(Random(1)) { file }
        rot.markShown(Greetings.TIME_BASED)
        val now = at(14)
        repeat(10) { assertFalse(Greetings.isStat(rot.next(now) { Greetings.fits(it, full, "G", now, file) })) }
        file = RemoteGreetingFile(2, emptyList(), emptyList(), 0, listOf(
            RemoteGreeting("{today} songs today.", "Nice.", null, GreetingRule.NONE)
        ))
        val ids = (1..10).map { rot.next(now) { Greetings.fits(it, full, "G", now, file) } }
        assertTrue(ids.contains(Greetings.STAT_BASE))
        assertEquals("7 songs today." to "Nice.", Greetings.remoteLines(Greetings.STAT_BASE, "G", now, file, full))
    }
}
