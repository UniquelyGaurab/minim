package com.minim.music.data.themes

import com.minim.music.ui.ArtistTakeover
import com.minim.music.ui.GreetingFacts
import com.minim.music.ui.Greetings
import com.minim.music.ui.ThemePreviewText
import com.minim.music.ui.ThemeWallpaper
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.Calendar
import kotlin.random.Random

/** 5.5.2: festival themes (themes.json, a festival's greetings file, the store, the preview). */
class FestivalThemesTest {

    private val themesJson = """
        {
          "version": 1,
          "festivals": [
            {
              "id": "diwali-2026", "name": "Diwali", "start": "2026-11-01", "end": "2026-11-10",
              "priority": 10, "wallpaper": "themes/diwali-2026/wallpaper.webp",
              "greetings": "themes/diwali-2026/greetings.json",
              "burst": { "shape": "sparks", "colors": ["#FFB300", "#FF6D00", "nope"] }
            },
            { "id": "_template", "name": "Template", "start": "2000-01-01", "end": "2000-01-02" },
            { "id": "off", "name": "Off", "enabled": false, "start": "2026-11-01", "end": "2026-11-10" },
            { "id": "small", "name": "Small", "start": "2026-11-05", "end": "2026-11-06", "wallpaper": "../x.webp" }
          ]
        }
    """.trimIndent()

    private val greetingsJson = """
        {
          "version": 1,
          "anytime": [
            { "text": "Morning lights\nfor {name}", "hours": "5-11" },
            { "text": "Night of diyas", "hours": "18-23" },
            { "text": "Happy Diwali" }
          ],
          "stats": [ { "text": "{today} songs\nand counting" } ],
          "days": [ { "date": "2026-11-08", "greetings": [ { "text": "It's Diwali!" } ] } ],
          "topArtist": [ "{topartist} lit up\nyour Diwali", { "text": "{minutes} min of\n{topartist}" }, "{song} nope" ]
        }
    """.trimIndent()

    private fun d(s: String) = ThemeDate.parse(s)!!
    private val file get() = FestivalThemesParser.parse(themesJson)!!
    private val diwali get() = file.festivals.first { it.id == "diwali-2026" }

    @After fun reset() {
        Greetings.theme = null
        Greetings.themeSet = null
    }

    // ---- themes.json ---------------------------------------------------------------------------

    @Test fun parsesTheDiwaliEntry() {
        val f = diwali
        assertEquals("Diwali", f.name)
        assertEquals(d("2026-11-01"), f.start)
        assertEquals(d("2026-11-10"), f.end)
        assertEquals("themes/diwali-2026/wallpaper.webp", f.wallpaper)
        assertEquals("themes/diwali-2026/greetings.json", f.greetingsPath)
        assertEquals("sparks", f.burst!!.shape)
        assertEquals(2, f.burst!!.colors.size)
    }

    @Test fun templatesAndSwitchedOffFestivalsNeverShow() {
        val ids = file.festivals.map { it.id }
        assertFalse("_template" in ids)
        assertFalse("off" in ids)
        assertEquals(0, file.skipped)
    }

    @Test fun aBrokenWallpaperPathMeansNoWallpaperNotASkippedFestival() {
        val small = file.festivals.first { it.id == "small" }
        assertNull(small.wallpaper)
    }

    @Test fun aNewerVersionIsIgnored() {
        assertNull(FestivalThemesParser.parse(themesJson.replace("\"version\": 1", "\"version\": 2")))
        assertNull(FestivalThemesParser.parse("not json"))
    }

    @Test fun higherPriorityWinsWhenFestivalsOverlap() {
        assertEquals("diwali-2026", ThemePicker.active(file.festivals, d("2026-11-05"))!!.id)
        assertEquals("diwali-2026", ThemePicker.active(file.festivals, d("2026-11-01"))!!.id)
        assertEquals("diwali-2026", ThemePicker.active(file.festivals, d("2026-11-10"))!!.id)
        assertNull(ThemePicker.active(file.festivals, d("2026-10-31")))
        assertNull(ThemePicker.active(file.festivals, d("2026-11-11")))
    }

    @Test fun previewPicksTheNextFestival() {
        assertEquals("diwali-2026", ThemePicker.upcoming(file.festivals, d("2026-10-28"))!!.id)
        assertNull(ThemePicker.upcoming(file.festivals, d("2026-11-11")))
    }

    @Test fun filesDownloadTwoDaysBeforeTheStart() {
        assertFalse(ThemePicker.shouldPrefetch(diwali, d("2026-10-29")))
        assertTrue(ThemePicker.shouldPrefetch(diwali, d("2026-10-30")))
        assertTrue(ThemePicker.shouldPrefetch(diwali, d("2026-11-10")))
        assertFalse(ThemePicker.shouldPrefetch(diwali, d("2026-11-11")))
    }

    // ---- a festival's greetings file -----------------------------------------------------------

    @Test fun greetingsFileHasItsFourGroups() {
        val set = ThemeGreetingsParser.parse(greetingsJson)!!
        assertEquals(3, set.file.anytime.size)
        assertEquals(1, set.file.stats.size)
        assertEquals(1, set.file.occasions.size)
        assertEquals(listOf(d("2026-11-08")), set.specialDays)
        assertEquals(2, set.topArtist.size) // the {song} line is skipped
        assertTrue(set.file.exclusive)
    }

    @Test fun aLineBreakSplitsTheTwoHomeLines() {
        val set = ThemeGreetingsParser.parse(greetingsJson)!!
        val g = set.file.anytime[0]
        val now = Calendar.getInstance().apply { set(2026, Calendar.NOVEMBER, 2, 8, 0) }
        assertEquals("Morning lights" to "for Gaurab", g.render("Gaurab", GreetingFacts().toData(now), now))
        // {name} without a saved name: the line doesn't show
        assertNull(g.render("", GreetingFacts().toData(now), now))
        // outside its hours: doesn't show
        val night = Calendar.getInstance().apply { set(2026, Calendar.NOVEMBER, 2, 21, 0) }
        assertNull(g.render("Gaurab", GreetingFacts().toData(night), night))
    }

    @Test fun statsLinesNeedTheirData() {
        val set = ThemeGreetingsParser.parse(greetingsJson)!!
        val now = Calendar.getInstance()
        assertNull(set.file.stats[0].render("", GreetingFacts().toData(now), now))
    }

    @Test fun specialDayLinesOnlyShowOnTheirDayUnlessPreviewed() {
        val set = ThemeGreetingsParser.parse(greetingsJson)!!
        val g = set.file.occasions[0]
        val nov8 = Calendar.getInstance().apply { set(2026, Calendar.NOVEMBER, 8, 12, 0) }
        val nov7 = Calendar.getInstance().apply { set(2026, Calendar.NOVEMBER, 7, 12, 0) }
        assertNotNull(g.render("", GreetingFacts().toData(nov8), nov8))
        assertNull(g.render("", GreetingFacts().toData(nov7), nov7))
        val moved = ThemeGreetingsParser.parse(greetingsJson, d("2026-10-28"))!!.file.occasions[0]
        val oct28 = Calendar.getInstance().apply { set(2026, Calendar.OCTOBER, 28, 12, 0) }
        assertNotNull(moved.render("", GreetingFacts().toData(oct28), oct28))
    }

    @Test fun festivalLinesCantHaveTheirOwnDates() {
        assertNull(ThemeGreetingsParser.entry(mapOf("text" to "Hi", "from" to "11-01")))
        assertNotNull(ThemeGreetingsParser.entry(mapOf("text" to "Hi")))
    }

    @Test fun takeoverLinesFillTheArtistAndMinutes() {
        val set = ThemeGreetingsParser.parse(greetingsJson)!!
        assertEquals("Anirudh lit up" to "your Diwali", set.takeoverLine(0, "Anirudh"))
        assertEquals("47 min of" to "Anirudh", set.takeoverLine(1, "Anirudh", ms = 47 * 60_000L))
    }

    @Test fun aNewerGreetingsVersionIsIgnored() {
        assertNull(ThemeGreetingsParser.parse(greetingsJson.replace("\"version\": 1", "\"version\": 3")))
    }

    // ---- what's on today ------------------------------------------------------------------------

    @Test fun resolveUsesTheFestivalOnTodayOrThePreview() {
        val none = ThemeState.resolve(file, d("2026-10-28"), null, { greetingsJson }, { null })
        assertFalse(none.isOn)
        val on = ThemeState.resolve(file, d("2026-11-03"), null, { greetingsJson }, { null })
        assertEquals("diwali-2026", on.theme!!.id)
        assertNotNull(on.greetings)
        val preview = ThemeState.resolve(file, d("2026-10-28"), ThemePreview("diwali-2026"), { greetingsJson }, { null })
        assertEquals("diwali-2026", preview.theme!!.id)
        // A greetings file that never downloaded: theme on, regular greetings stay
        val noFile = ThemeState.resolve(file, d("2026-11-03"), null, { null }, { null })
        assertTrue(noFile.isOn)
        assertNull(noFile.greetings)
        // A preview of a festival that's over does nothing
        assertFalse(ThemeState.resolve(file, d("2026-11-12"), ThemePreview("diwali-2026"), { greetingsJson }, { null }).isOn)
    }

    @Test fun bigDayPreviewMovesTheSpecialLinesToToday() {
        val s = ThemeState.resolve(file, d("2026-10-28"), ThemePreview("diwali-2026", bigDay = true), { greetingsJson }, { null })
        val oct28 = Calendar.getInstance().apply { set(2026, Calendar.OCTOBER, 28, 12, 0) }
        val g = s.greetings!!.file.occasions[0]
        assertNotNull(g.render("", GreetingFacts().toData(oct28), oct28))
    }

    // ---- the rotation during a festival ---------------------------------------------------------

    @Test fun onlyFestivalLinesRotateAndTheOpenerIsATimeOfDayLine() {
        val set = ThemeGreetingsParser.parse(greetingsJson)!!
        Greetings.theme = set.file
        Greetings.themeSet = set
        assertTrue(Greetings.themeOn)
        val rot = Greetings.Rotation(Random(1))
        val morning = Calendar.getInstance().apply { set(2026, Calendar.NOVEMBER, 2, 8, 0) }
        val fits = { id: Int -> Greetings.fits(id, GreetingFacts(), "Gaurab", morning) }
        assertEquals(Greetings.ANYTIME_BASE + 0, rot.opener(fits))
        repeat(30) {
            val id = rot.next(morning, fits)
            assertTrue("regular greeting $id during the festival", Greetings.isRemote(id))
        }
    }

    @Test fun withoutAFestivalTheOpenerIsTheUsualTimeGreeting() {
        val rot = Greetings.Rotation(Random(1))
        assertEquals(Greetings.TIME_BASED, rot.opener { true })
    }

    @Test fun takeoverIsSevenSecondsDuringAFestivalAndTenOtherwise() {
        assertEquals(7_000L, ArtistTakeover.durationMs(true))
        assertEquals(10_000L, ArtistTakeover.durationMs(false))
    }

    // ---- the files on GitHub (checked when the public repo sits next to the project) ----------

    @Test fun publishedDiwaliFilesParseInTheApp() {
        val repo = listOf("../minim-public", "../../minim-public").map { File(it) }.firstOrNull { File(it, "themes/themes.json").exists() }
        org.junit.Assume.assumeTrue(repo != null)
        val themes = FestivalThemesParser.parse(File(repo, "themes/themes.json").readText())!!
        assertEquals(0, themes.skipped)
        val diwali = themes.festivals.first { it.id == "diwali" }
        assertEquals(d("2026-11-01"), diwali.start)
        assertEquals(d("2026-11-10"), diwali.end)
        assertEquals("themes/diwali/wallpaper.webp", diwali.wallpaper)
        assertTrue(File(repo, diwali.wallpaper!!).exists())
        val set = ThemeGreetingsParser.parse(File(repo, diwali.greetingsPath!!).readText())!!
        assertEquals(0, set.skipped)
        assertEquals(25, set.file.anytime.size)
        assertEquals(15, set.file.stats.size)
        assertEquals(10, set.file.occasions.size)
        assertEquals(listOf(d("2026-11-08")), set.specialDays)
        assertEquals(7, set.topArtist.size)
        assertEquals("Anirudh is today's" to "brightest diya.", set.takeoverLine(0, "Anirudh"))
        // The first Diwali time line: 5 to 9am, as the user wrote it
        val g = set.file.anytime[0]
        val at = { h: Int -> Calendar.getInstance().apply { set(2026, Calendar.NOVEMBER, 2, h, 30) } }
        assertNotNull(g.render("", GreetingFacts().toData(at(5)), at(5)))
        assertNotNull(g.render("", GreetingFacts().toData(at(8)), at(8)))
        assertNull(g.render("", GreetingFacts().toData(at(9)), at(9)))
        // A roast line (no hours) shows at any hour; it needs the saved name only when it uses it
        val roast = set.file.anytime[11]
        assertNotNull(roast.render("", GreetingFacts().toData(at(3)), at(3)))
        assertNotNull(roast.render("", GreetingFacts().toData(at(15)), at(15)))
        // 10pm to 1am wraps past midnight
        val late = set.file.anytime[8]
        assertNotNull(late.render("", GreetingFacts().toData(at(0)), at(0)))
        assertNull(late.render("", GreetingFacts().toData(at(1)), at(1)))
    }

    // ---- the store (offline copy) --------------------------------------------------------------

    private val webp = "RIFF\u0000\u0000\u0000\u0000WEBPVP8 ".toByteArray(Charsets.ISO_8859_1) + ByteArray(32)

    private fun tempDir(): File = Files.createTempDirectory("themes").toFile()

    @Test fun themesFileIsKeptForOffline() {
        val dir = tempDir()
        val store = ThemeStore(dir)
        val ok = ThemeFetcher { _, _, _ -> ThemeFetch.Ok(themesJson.toByteArray(), "e1") }
        assertEquals("Diwali", store.refreshThemes(ok, force = true)!!.festivals[0].name)
        val offline = ThemeFetcher { _, _, _ -> ThemeFetch.Failed("offline") }
        assertEquals("Diwali", ThemeStore(dir).refreshThemes(offline, force = true)!!.festivals[0].name)
        // A broken download keeps the good copy
        val broken = ThemeFetcher { _, _, _ -> ThemeFetch.Ok("{oops".toByteArray(), null) }
        assertNotNull(ThemeStore(dir).refreshThemes(broken, force = true))
        dir.deleteRecursively()
    }

    @Test fun wallpaperMustBeARealWebp() {
        val dir = tempDir()
        val store = ThemeStore(dir)
        val png = ThemeFetcher { url, _, _ ->
            if (url.endsWith(".webp")) ThemeFetch.Ok("\u0089PNG....".toByteArray(), null)
            else ThemeFetch.Ok(greetingsJson.toByteArray(), null)
        }
        store.refreshFestival(png, diwali)
        assertNull(store.wallpaperFor(diwali))
        assertNotNull(store.loadGreetingsText("diwali-2026"))
        val good = ThemeFetcher { url, _, _ ->
            assertTrue(url.startsWith("https://raw.githubusercontent.com/UniquelyGaurab/minim/main/themes/"))
            if (url.endsWith(".webp")) ThemeFetch.Ok(webp, null) else ThemeFetch.Ok(greetingsJson.toByteArray(), null)
        }
        assertTrue(store.refreshFestival(good, diwali))
        assertNotNull(store.wallpaperFor(diwali))
        store.prune(emptySet())
        assertNull(store.wallpaperFor(diwali))
        dir.deleteRecursively()
    }

    @Test fun previewIsRemembered() {
        val dir = tempDir()
        ThemeStore(dir).setPreview(ThemePreview("diwali-2026", bigDay = true))
        assertEquals(ThemePreview("diwali-2026", bigDay = true), ThemeStore(dir).preview())
        ThemeStore(dir).setPreview(null)
        assertNull(ThemeStore(dir).preview())
        dir.deleteRecursively()
    }

    // ---- Home over the wallpaper ---------------------------------------------------------------

    @Test fun darkWallpaperGetsWhiteTextAndLightGetsDark() {
        val dark = ThemeWallpaper.look(0xFF201008.toInt(), 0.08)
        assertFalse(dark.light)
        assertEquals(ThemeWallpaper.LIGHT_INK, dark.ink)
        val light = ThemeWallpaper.look(0xFFF0E0C0.toInt(), 0.8)
        assertTrue(light.light)
        assertEquals(ThemeWallpaper.DARK_INK, light.ink)
        // The base leans away from the ink: darker under white text
        assertTrue((dark.bg shr 16 and 0xFF) < 0x20)
    }

    @Test fun wallpaperDecodesNearScreenSize() {
        assertEquals(1, ThemeWallpaper.sampleSize(1080, 2400, 1080, 2400))
        assertEquals(2, ThemeWallpaper.sampleSize(1080, 2400, 540, 1200))
        assertEquals(1, ThemeWallpaper.sampleSize(1080, 2400, 720, 1600))
    }

    @Test fun previewToastsSayWhatHappened() {
        assertEquals("Diwali preview on", ThemePreviewText.message(PreviewResult.On("Diwali")))
        assertEquals("Diwali preview off", ThemePreviewText.message(PreviewResult.Off("Diwali")))
        assertEquals("Diwali: Nov 8 lines on", ThemePreviewText.message(PreviewResult.BigDayOn("Diwali", d("2026-11-08"))))
    }
}
