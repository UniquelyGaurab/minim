package com.minim.music.data.config

import com.minim.music.data.greetings.MiniJson
import com.minim.music.data.greetings.RemoteGreetingFile
import com.minim.music.data.greetings.RemoteGreeting
import com.minim.music.data.local.SeenIds
import com.minim.music.ui.ColourMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.GregorianCalendar

class VibeConfigTest {

    private fun cfg(json: String): VibeConfig = VibeConfigParser.parse(MiniJson.parse(json) as Map<*, *>)
    private fun day(y: Int, m: Int, d: Int): Calendar = GregorianCalendar(y, m - 1, d, 12, 0)
    private val v499 = InstalledVersion.of("4.9.9-debug", 26)
    private val v500 = InstalledVersion.of("5.0.0", 27)

    private val sample = """
    {
      "switches": { "festivals": true, "sync": "off", "weird": 0 },
      "events": [
        { "id": "bihu", "name": "Bohag Bihu", "from": "04-13", "to": "04-15", "day": "04-14",
          "accent": "#E8A33D", "banner": "Happy Bihu! Dhol on.", "link": "https://example.com/bihu",
          "greetings": ["Happy Bihu!\nDhol on, volume up.", { "text": "Bihu mood\nPepa ready?" }] },
        { "id": "ny", "name": "New Year", "from": "2026-12-31", "to": "2027-01-01", "banner": "Happy New Year" }
      ],
      "announcements": [
        { "id": "welcome5", "title": "Minim 5.0 is here", "text": "Sign in to sync.\n\nThat's it.", "min_version": "5.0.0" },
        { "id": "old", "title": "Please update", "max_version": 26, "link": "https://example.com", "button": "Get it" }
      ],
      "greetings": [ "Hey there\nPress play", { "text": "Diwali\nLights on", "from": "11-08", "to": "11-09" } ],
      "update_lock": { "min_version": "5.0.0", "message": "This version stopped working." }
    }
    """

    @Test
    fun readsTheWholeDocument() {
        val c = cfg(sample)
        assertEquals(0, c.skipped)
        assertEquals(2, c.events.size)
        val bihu = c.events[0]
        assertEquals("Bohag Bihu", bihu.name)
        assertEquals(0xFFE8A33D.toInt(), bihu.accent)
        assertEquals("Happy Bihu! Dhol on.", bihu.banner)
        assertEquals("https://example.com/bihu", bihu.link)
        assertEquals(2, bihu.greetings.size)
        assertEquals(RemoteGreeting("Happy Bihu!", "Dhol on, volume up.", bihu.range), bihu.greetings[0])
        assertEquals(4, bihu.range.mainMonth); assertEquals(14, bihu.range.mainDay)
        assertEquals(2, c.announcements.size)
        assertEquals("Sign in to sync.\n\nThat's it.", c.announcements[0].text)
        assertEquals(2, c.greetings.size)
        assertFalse(c.isOn(VibeConfig.SWITCH_SYNC))
        assertFalse(c.isOn("weird"))
        assertTrue(c.isOn(VibeConfig.SWITCH_ANNOUNCEMENTS)) // missing = on
    }

    @Test
    fun festivalOnlyInsideItsDates() {
        val c = cfg(sample)
        assertEquals("bihu", c.activeEvent(day(2027, 4, 13))?.id)
        assertEquals("bihu", c.activeEvent(day(2030, 4, 15))?.id) // every year
        assertNull(c.activeEvent(day(2027, 4, 16)))
        // A dated range across the new year: that year only
        assertEquals("ny", c.activeEvent(day(2026, 12, 31))?.id)
        assertEquals("ny", c.activeEvent(day(2027, 1, 1))?.id)
        assertNull(c.activeEvent(day(2027, 12, 31)))
        assertNull(c.activeEvent(day(2028, 1, 1)))
    }

    @Test
    fun festivalsSwitchTurnsEverythingFestiveOff() {
        val c = cfg(sample.replace("\"festivals\": true", "\"festivals\": false"))
        assertNull(c.activeEvent(day(2027, 4, 14)))
        // the plain greetings stay, the festival's own lines go
        assertEquals(2, c.greetingFile().size)
        assertEquals(4, cfg(sample).greetingFile().size)
    }

    @Test
    fun greetingFileSplitsAnytimeAndOccasions() {
        val f = cfg(sample).greetingFile()
        assertEquals(1, f.anytime.size)
        assertEquals(3, f.occasions.size) // Diwali + two Bihu lines
        assertSame(RemoteGreetingFile.EMPTY, VibeConfig.DEFAULT.greetingFile())
        val off = cfg("""{ "switches": { "greetings": false }, "greetings": ["a\nb"] }""")
        assertSame(RemoteGreetingFile.EMPTY, off.greetingFile())
    }

    @Test
    fun announcementsRespectVersionsDatesAndDismissals() {
        val c = cfg(sample)
        val now = day(2027, 2, 1)
        assertEquals("old", c.nextAnnouncement(v499, now, emptySet())?.id)
        assertEquals("welcome5", c.nextAnnouncement(v500, now, emptySet())?.id)
        assertNull(c.nextAnnouncement(v500, now, setOf("welcome5")))
        assertNull(cfg(sample.replace("\"festivals\": true", "\"announcements\": false")).nextAnnouncement(v500, now, emptySet()))
        val dated = cfg("""{ "announcements": [ { "id": "a", "title": "T", "from": "2027-03-01", "to": "2027-03-05" } ] }""")
        assertNull(dated.nextAnnouncement(v500, day(2027, 2, 28), emptySet()))
        assertNotNull(dated.nextAnnouncement(v500, day(2027, 3, 3), emptySet()))
        assertNull(dated.nextAnnouncement(v500, day(2028, 3, 3), emptySet()))
    }

    @Test
    fun announcementButtonOnlyWithAHttpsLink() {
        val c = cfg(sample)
        assertNull(c.announcements[0].button)
        assertEquals("Get it", c.announcements[1].button)
        val bad = cfg("""{ "announcements": [ { "id": "a", "title": "T", "link": "javascript:alert(1)", "button": "Go" },
            { "id": "b", "title": "T", "link": "http://plain.example" }, { "id": "c", "title": "T", "link": "intent://x" } ] }""")
        assertTrue(bad.announcements.all { it.link == null && it.button == null })
    }

    @Test
    fun updateLockByNameOrCode() {
        val c = cfg(sample)
        assertTrue(c.lockApplies(v499))
        assertFalse(c.lockApplies(v500))
        assertFalse(c.lockApplies(InstalledVersion.of("5.0.1", 28)))
        val byCode = cfg("""{ "update_lock": { "min_version": 27 } }""")
        assertTrue(byCode.lockApplies(v499))
        assertFalse(byCode.lockApplies(v500))
        assertNull(byCode.lock?.message)
        // an unknown installed version is never locked out
        assertFalse(c.lockApplies(InstalledVersion.of(null, 0)))
        assertFalse(byCode.lockApplies(InstalledVersion.of(null, 0)))
        assertNull(cfg("""{ "update_lock": { "min_version": "soon" } }""").lock)
        assertNull(cfg("""{ "update_lock": { "min_version": 0 } }""").lock)
        assertNull(VibeConfig.DEFAULT.lock)
    }

    @Test
    fun brokenEntriesAreSkippedAndTheRestKept() {
        val c = cfg("""
        { "events": [
            { "id": "ok", "from": "10-20" },
            { "id": "badDate", "from": "02-30" },
            { "id": "badColour", "from": "01-01", "accent": "orange" },
            { "id": "mixedYears", "from": "2026-01-01", "to": "01-05" },
            { "id": "wrongEndYear", "from": "2026-12-30", "to": "2026-01-02" },
            { "id": "has spaces!", "from": "01-01" },
            { "id": "ok", "from": "11-11" },
            "not a map", 42, null,
            { "name": "Holi Hai", "from": "03-04", "banner": "${"x".repeat(61)}" }
          ],
          "announcements": [ { "id": "noTitle" }, { "title": "no id" }, { "id": "v", "title": "T", "min_version": "x.y" } ],
          "greetings": [ "", 5, { "text": "only one line" }, "ok\nfine" ],
          "switches": "nope",
          "update_lock": "nope"
        }""")
        assertEquals(listOf("ok", "holi-hai"), c.events.map { it.id })
        assertNull(c.events[1].banner) // too long: dropped, the festival stays
        assertEquals("Holi Hai", c.events[1].name)
        assertTrue(c.announcements.isEmpty())
        assertTrue(c.greetings.isNotEmpty())
        assertTrue(c.skipped >= 10)
        assertTrue(c.switches.isEmpty())
        assertNull(c.lock)
    }

    @Test
    fun garbageIsTheDefault() {
        assertEquals(VibeConfig.DEFAULT, VibeConfigParser.parse(null))
        assertEquals(VibeConfig.DEFAULT, VibeConfigParser.parse(emptyMap<String, Any>()))
        assertEquals(VibeConfig.DEFAULT, cfg("""{ "events": "x", "announcements": {}, "greetings": 1 }"""))
        // deeply nested junk doesn't blow up
        var deep: Any = "x"
        repeat(5000) { deep = mapOf("a" to deep) }
        VibeConfigParser.parse(mapOf("events" to listOf(deep), "switches" to deep))
    }

    @Test
    fun capsKeepTheDocumentSmall() {
        val events = (1..60).joinToString(",") { """{ "id": "e$it", "from": "01-01" }""" }
        val c = cfg("""{ "events": [ $events ] }""")
        assertEquals(VibeConfigParser.MAX_EVENTS, c.events.size)
        assertEquals(60 - VibeConfigParser.MAX_EVENTS, c.skipped)
    }

    @Test
    fun textIsCleaned() {
        assertEquals("a b", VibeConfigParser.cleanText("  a \t\u0007 b ", 10))
        assertEquals("a b", VibeConfigParser.cleanText("a\nb", 10))
        assertEquals("a\n\nb", VibeConfigParser.cleanText("a\n\n\n\nb", 10, keepNewlines = true))
        assertNull(VibeConfigParser.cleanText("   ", 10))
        assertNull(VibeConfigParser.cleanText("abcdef", 5))
        assertNull(VibeConfigParser.cleanText(7, 5))
        assertEquals(0xFF00AAFF.toInt(), VibeConfigParser.parseColour("00aaff"))
        assertNull(VibeConfigParser.parseColour("#0af"))
        assertNull(VibeConfigParser.parseColour("#FF00AAFF"))
    }

    @Test
    fun firestoreValuesAreNormalised() {
        val date = GregorianCalendar(2026, 10, 8, 9, 30).time
        val marker = Any()
        val raw = mapOf(
            "events" to listOf(mapOf("id" to "diwali", "from" to marker, "to" to "2026-11-09", "n" to 3)),
            "f" to 1.5f
        )
        val out = VibeConfigValues.normalize(raw, { v -> if (v === marker) date else null }) as Map<*, *>
        val e = (out["events"] as List<*>)[0] as Map<*, *>
        assertEquals("2026-11-08", e["from"])
        assertEquals(3, e["n"])
        assertEquals(1.5, out["f"])
        val c = VibeConfigParser.parse(out)
        assertEquals("diwali", c.activeEvent(day(2026, 11, 9))?.id)
        assertNull(c.activeEvent(day(2027, 11, 9)))
        // an unknown type becomes null rather than crashing the parser
        assertNull(VibeConfigValues.normalize(Any(), { null }))
    }

    @Test
    fun refreshScheduleThrottles() {
        val s = VibeRefreshSchedule(okMs = 1000, failMs = 100)
        assertTrue(s.shouldRefresh(0))
        s.onFinished(0, ok = true)
        assertFalse(s.shouldRefresh(999))
        assertTrue(s.shouldRefresh(1000))
        assertTrue(s.shouldRefresh(10, force = true))
        s.onFinished(2000, ok = false)
        assertFalse(s.shouldRefresh(2050))
        assertTrue(s.shouldRefresh(2100))
        assertTrue(s.shouldRefresh(5)) // clock went back (reboot): read again
    }

    @Test
    fun festivalColourOnlyWhenNothingPlaysInDynamic() {
        val festive = 0xFFE8A33D.toInt()
        assertEquals(festive, ColourMode.seedFor(0, dynamic = true, festivalSeed = festive))
        assertEquals(123, ColourMode.seedFor(123, dynamic = true, festivalSeed = festive))
        assertEquals(0, ColourMode.seedFor(0, dynamic = false, festivalSeed = festive))
        assertEquals(0, ColourMode.seedFor(123, dynamic = false, festivalSeed = festive))
        assertEquals(0, ColourMode.seedFor(0, dynamic = true))
    }

    @Test
    fun greetingFilesMerge() {
        val a = RemoteGreetingFile(2, listOf(RemoteGreeting("a", "1")), emptyList())
        val b = cfg(sample).greetingFile()
        val m = RemoteGreetingFile.merge(a, b)
        assertEquals(a.anytime + b.anytime, m.anytime)
        assertEquals(b.occasions, m.occasions)
        assertSame(a, RemoteGreetingFile.merge(a, RemoteGreetingFile.EMPTY))
        assertSame(b, RemoteGreetingFile.merge(RemoteGreetingFile.EMPTY, b))
        assertEquals(m, RemoteGreetingFile.merge(a, b))
    }

    @Test
    fun seenIdsStayShortAndUnique() {
        var ids = SeenIds.read(null)
        assertTrue(ids.isEmpty())
        ids = SeenIds.add(ids, "a")
        ids = SeenIds.add(ids, "b")
        ids = SeenIds.add(ids, "a")
        assertEquals(listOf("b", "a"), ids)
        assertEquals(ids, SeenIds.read(SeenIds.write(ids)))
        repeat(150) { ids = SeenIds.add(ids, "x$it") }
        assertEquals(SeenIds.MAX, ids.size)
        assertEquals("x149", ids.last())
        assertEquals(ids, SeenIds.add(ids, "  "))
    }

    @Test
    fun configNeverLivesUnderTheAccount() {
        // Delete account removes users/{uid}; the config document sits outside it
        assertEquals("config", com.minim.music.data.account.CloudPaths.CONFIG)
        assertFalse(com.minim.music.data.account.CloudPaths.CONFIG == com.minim.music.data.account.CloudPaths.USERS)
        assertEquals("app", VibeConfigSource.DOC)
    }
}
