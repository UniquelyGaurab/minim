package com.minim.music.data.greetings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

/** 5.1.0: placeholders, conditions and the "stats" list in greetings.json. */
class RemoteGreetingStatsTest {

    private fun at(hour: Int, dow: Int = Calendar.WEDNESDAY): Calendar = Calendar.getInstance().apply {
        set(2026, Calendar.OCTOBER, 21, hour, 0)
        while (get(Calendar.DAY_OF_WEEK) != dow) add(Calendar.DAY_OF_MONTH, 1)
        set(Calendar.HOUR_OF_DAY, hour)
    }

    private fun data(vararg pairs: Pair<String, Any?>): GreetingData {
        val m = mapOf(*pairs)
        return GreetingData { m[it] }
    }

    private fun one(json: String): RemoteGreeting? {
        val f = RemoteGreetingsParser.parse("""{"version":2,"greetings":[],"stats":[$json]}""") ?: return null
        return f.stats.singleOrNull()
    }

    @Test
    fun placeholdersAreFilledIn() {
        val g = one("""{"text":"{plays} plays of {song}\nthis week. We need to talk."}""")!!
        assertEquals("9 plays of Hukum" to "this week. We need to talk.",
            g.render("", data("plays" to 9, "song" to "Hukum"), at(12)))
    }

    @Test
    fun aLineNeedsAllItsData() {
        val g = one("""{"text":"Back to {artist}?\nThey should be paying you."}""")!!
        assertNull(g.render("", data(), at(12)))
        assertNull(g.render("", data("artist" to "  "), at(12)))
        assertNotNull(g.render("", data("artist" to "Pritam"), at(12)))
        // A count of 0 is no data unless "min" says so
        val c = one("""{"text":"{today} songs today.\nOkay."}""")!!
        assertNull(c.render("", data("today" to 0), at(12)))
        assertNotNull(c.render("", data("today" to 1), at(12)))
        // {name} still needs a saved name
        val n = one("""{"text":"{name}, {today} songs.\nOn fire."}""")!!
        assertNull(n.render("", data("today" to 3), at(12)))
        assertEquals("Gaurab, 3 songs." to "On fire.", n.render("Gaurab", data("today" to 3), at(12)))
    }

    @Test
    fun minMaxAndNeeds() {
        val g = one("""{"text":"{today} songs so far.\nWarming up.","min":{"today":3},"max":{"today":19}}""")!!
        assertNull(g.render("", data("today" to 2), at(12)))
        assertNotNull(g.render("", data("today" to 3), at(12)))
        assertNotNull(g.render("", data("today" to 19), at(12)))
        assertNull(g.render("", data("today" to 20), at(12)))
        val z = one("""{"text":"Nothing played today.\nSo bored.","max":{"today":0},"needs":["last"]}""")!!
        assertNull(z.render("", data("today" to 0), at(12)))
        assertNotNull(z.render("", data("today" to 0, "last" to "Hukum"), at(12)))
        assertNull(z.render("", data("today" to 1, "last" to "Hukum"), at(12)))
    }

    @Test
    fun hoursDaysPlayingLiked() {
        val late = one("""{"text":"{song} at this hour?\nBold.","hours":"22-3"}""")!!
        val d = data("song" to "Hukum")
        assertNotNull(late.render("", d, at(23)))
        assertNotNull(late.render("", d, at(2)))
        assertNull(late.render("", d, at(12)))
        val fri = one("""{"text":"Friday.\nLoud.","days":["Friday","sat"]}""")!!
        assertNotNull(fri.render("", d, at(12, Calendar.FRIDAY)))
        assertNotNull(fri.render("", d, at(12, Calendar.SATURDAY)))
        assertNull(fri.render("", d, at(12, Calendar.MONDAY)))
        val paused = one("""{"text":"Paused {song}?\nIt waits.","playing":false}""")!!
        assertNotNull(paused.render("", data("song" to "X", GreetingTokens.PLAYING to false), at(12)))
        assertNull(paused.render("", data("song" to "X", GreetingTokens.PLAYING to true), at(12)))
        assertNull(paused.render("", data("song" to "X"), at(12)))
        val liked = one("""{"text":"You liked {song}.\nKeeper.","liked":true}""")!!
        assertNotNull(liked.render("", data("song" to "X", GreetingTokens.SONG_LIKED to true), at(12)))
        assertNull(liked.render("", data("song" to "X", GreetingTokens.SONG_LIKED to false), at(12)))
    }

    @Test
    fun brokenOrUnknownEntriesAreSkipped() {
        val f = RemoteGreetingsParser.parse(
            """{"version":2,"greetings":[{"text":"Hi.\nThere."}],"stats":[
                {"text":"{songg} again?\nTypo."},
                {"text":"{Song} again?\nCase."},
                {"text":"{song}\nfine","min":{"song":2}},
                {"text":"{song}\nfine","min":{"plays":"two"}},
                {"text":"{song}\nfine","hours":"25-3"},
                {"text":"{song}\nfine","days":"someday"},
                {"text":"{song}\nfine","playing":"yes"},
                {"text":"{song}\nfine","needs":["bogus"]},
                {"text":"{song}, again?\nGood one."}
            ]}"""
        )!!
        assertEquals(1, f.anytime.size)
        assertEquals(1, f.stats.size)
        assertEquals(8, f.skipped)
        assertEquals("{song}, again?", f.stats[0].line1)
    }

    @Test
    fun statLinesInTheGreetingsListMoveToStats() {
        val f = RemoteGreetingsParser.parse(
            """{"greetings":[{"text":"{name} here.\nHi."},{"text":"{song}, again?\nHm."},{"text":"Late.\nSleep?","hours":"0-3"}]}"""
        )!!
        assertEquals(1, f.version)
        assertEquals(1, f.anytime.size)
        assertEquals(2, f.stats.size)
    }

    @Test
    fun aStatsKeyThatIsNotAListDoesNotLoseTheFile() {
        val f = RemoteGreetingsParser.parse("""{"version":2,"greetings":[{"text":"Hi.\nThere."}],"stats":"oops"}""")!!
        assertEquals(1, f.anytime.size)
        assertEquals(0, f.stats.size)
    }

    @Test
    fun filledLinesThatGetTooLongDontShow() {
        val g = RemoteGreeting("{song} {song}", "x")
        assertTrue(g.usesStats)
        assertNull(g.render("", data("song" to "A".repeat(30)), at(12)))
        assertNotNull(g.render("", data("song" to "A".repeat(20)), at(12)))
    }

    @Test
    fun linesForStillWorksForPlainGreetings() {
        assertEquals("Hi Gaurab" to "x", RemoteGreeting("Hi {name}", "x").linesFor("Gaurab"))
        assertNull(RemoteGreeting("{song}", "x").linesFor("Gaurab"))
    }

    @Test
    fun configGreetingsAreSortedTheSameWay() {
        val f = RemoteGreetingFile.of(1, listOf(
            RemoteGreeting("Hi.", "x"),
            RemoteGreeting("{today} songs.", "x"),
            RemoteGreeting("Diwali.", "x", DateRange(11, 1, 11, 9))
        ))
        assertEquals(1, f.anytime.size)
        assertEquals(1, f.stats.size)
        assertEquals(1, f.occasions.size)
        val merged = RemoteGreetingFile.merge(f, f)
        assertEquals(2, merged.stats.size)
    }

    @Test
    fun checksEveryTenMinutes() {
        assertEquals(10L * 60 * 1000, RemoteGreetingsStore.CHECK_EVERY_MS)
        assertEquals(10L * 60 * 1000, RemoteGreetingsStore.RETRY_AFTER_MS)
    }
}
