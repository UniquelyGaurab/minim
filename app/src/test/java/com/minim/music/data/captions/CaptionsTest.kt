package com.minim.music.data.captions

import com.minim.music.data.model.Track
import com.minim.music.data.stories.NowPlayingCaptions
import com.minim.music.data.stories.StoryTracks
import com.minim.music.data.stories.StoryUpload
import com.minim.music.data.stories.TopRowBranding
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 5.7.0 u46: picking captions, reading YouTube's caption files and joining auto-caption chunks. */
class CaptionsTest {

    private fun t(tag: String, auto: Boolean, name: String = tag) = CaptionTrack(tag, name, auto, mapOf("srv1" to "https://x/$tag/$auto"))

    @Test
    fun picker_channelsOwnFirst_thenAuto_originalLanguageBeforeEnglish() {
        val tracks = listOf(t("en", false), t("te", false), t("fr", false), t("te", true))
        val c = CaptionPicker.choices(tracks)
        // Telugu is spoken (the auto track says so): own Telugu, own English, then auto Telugu. French left out.
        assertEquals(listOf("te" to false, "en" to false, "te" to true), c.map { it.languageTag to it.auto })
    }

    @Test
    fun picker_autoOnly_usesIt_andEnglishOwnBeatsAuto() {
        assertEquals(listOf("hi" to true), CaptionPicker.choices(listOf(t("hi", true))).map { it.languageTag to it.auto })
        val c = CaptionPicker.choices(listOf(t("te", true), t("en", false)))
        assertEquals(listOf("en" to false, "te" to true), c.map { it.languageTag to it.auto })
    }

    @Test
    fun picker_videoLanguageDecidesWithoutAutoTrack_andRegionsCountAsOneLanguage() {
        val c = CaptionPicker.choices(listOf(t("en-GB", false), t("hi", false), t("en", false)), videoLanguage = "hi")
        assertEquals(listOf("hi", "en-GB"), c.map { it.languageTag })
        // Only other languages: still offered rather than nothing
        assertEquals(listOf("fr"), CaptionPicker.choices(listOf(t("fr", false)), "te").map { it.languageTag })
        assertTrue(CaptionPicker.choices(emptyList()).isEmpty())
        assertTrue(CaptionPicker.choices(listOf(CaptionTrack("en", "English", false, emptyMap()))).isEmpty())
    }

    @Test
    fun picker_formatsInOrder() {
        val tr = CaptionTrack("en", "English", true, mapOf("ttml" to "t", "vtt" to "v", "srv1" to "s"))
        assertEquals(listOf("s", "v", "t"), CaptionPicker.urlsInOrder(tr))
    }

    @Test
    fun parser_readsSrv1_srv3_ttml_andVtt() {
        val srv1 = """<?xml version="1.0"?><transcript><text start="1.5" dur="2">it&amp;#39;s a &lt;b&gt;story&lt;/b&gt;</text><text start="4" dur="1.25">next</text></transcript>"""
        assertEquals(listOf(Cue(1500, 3500, "it's a story"), Cue(4000, 5250, "next")), CaptionParser.parse(srv1))
        val srv3 = """<timedtext format="3"><body><p t="100" d="900">hello<s t="300"> there</s></p><p t="2000" d="10"/></body></timedtext>"""
        assertEquals(listOf(Cue(100, 1000, "hello there")), CaptionParser.parse(srv3))
        val ttml = """<tt><body><div><p begin="00:00:01.000" end="00:00:02.500">one<br/>two</p></div></body></tt>"""
        assertEquals(listOf(Cue(1000, 2500, "one two")), CaptionParser.parse(ttml))
        val vtt = "WEBVTT\nKind: captions\n\n00:00:01.000 --> 00:00:02.000\nonce upon\n\n00:00:02.000 --> 00:00:02.010\nonce upon\n\n00:00:02.010 --> 00:00:03.500\nonce upon\na <c>time</c>\n"
        assertEquals(listOf(Cue(1000, 2000, "once upon"), Cue(2010, 3500, "a time")), CaptionParser.parse(vtt))
        assertTrue(CaptionParser.parse("").isEmpty())
        assertTrue(CaptionParser.parse("garbage").isEmpty())
    }

    @Test
    fun joiner_turnsTinyChunksIntoSentenceLines() {
        val chunks = listOf(
            Cue(0, 800, "so one day"), Cue(800, 1600, "the king"), Cue(1600, 2400, "called his"),
            Cue(2400, 3200, "minister."), Cue(3200, 4000, "he asked"), Cue(4000, 4800, "a question")
        )
        val lines = CaptionJoiner.join(chunks, auto = true)
        assertEquals(listOf("So one day the king called his minister.", "He asked a question"), lines.map { it.text })
        assertEquals(0L, lines[0].startMs)
        assertEquals(3200L, lines[0].endMs)
        assertEquals(3200L, lines[1].startMs)
        assertEquals(4800L, lines[1].endMs)
    }

    @Test
    fun joiner_breaksAtPauses_longLines_andSlowLines_andDropsSoundTags() {
        val pause = CaptionJoiner.join(listOf(Cue(0, 500, "hello"), Cue(500, 1000, "friends"), Cue(3000, 3500, "welcome back")), true)
        assertEquals(listOf("Hello friends", "Welcome back"), pause.map { it.text })
        // Pause: the first line ends when it was said, not when the next one starts
        assertEquals(1000L, pause[0].endMs)

        val words = (0 until 30).map { Cue(it * 300L, it * 300L + 300, "word$it") }
        val long = CaptionJoiner.join(words, true)
        assertTrue(long.size > 1)
        assertTrue(long.all { it.text.length <= CaptionJoiner.MAX_CHARS })
        assertTrue(long.all { it.endMs - it.startMs <= CaptionJoiner.MAX_LINE_MS + 300 })
        // Every word kept, in order
        assertEquals(words.joinToString(" ") { it.text }, long.joinToString(" ") { it.text }.lowercase())
        // Lines never overlap
        assertTrue(long.zipWithNext().all { (a, b) -> a.endMs <= b.startMs })

        assertEquals(listOf("Okay"), CaptionJoiner.join(listOf(Cue(0, 500, "[Music]"), Cue(600, 900, "okay")), true).map { it.text })
    }

    @Test
    fun joiner_keepsChannelsOwnLines_asTheyAre() {
        val own = listOf(Cue(0, 2000, "First line."), Cue(2000, 4000, "second line"), Cue(4000, 4500, "second line"))
        assertEquals(listOf("First line.", "second line"), CaptionJoiner.join(own, auto = false).map { it.text })
        assertTrue(CaptionJoiner.join(emptyList(), true).isEmpty())
    }

    @Test
    fun captionsBecomeSyncedLyrics_orTheQuietNone() {
        val d = CaptionLyrics.of("vid", listOf(Cue(0, 1000, "Hi")))
        assertTrue(d.isSynced)
        assertEquals("Hi", d.lines.single().text)
        assertTrue(CaptionLyrics.isCaptions(d))
        val none = CaptionLyrics.none("vid")
        assertTrue(none.lines.isEmpty())
        assertTrue(CaptionLyrics.isCaptions(none))
        assertFalse(CaptionLyrics.isCaptions(null))
    }

    @Test
    fun ccButton_onlyForStoriesUploads() {
        val upload = StoryUpload("abc123def45", "Story", "UC1", "Kaushik STORIES", 1500, 0L).toTrack()
        assertTrue(StoryTracks.isStory(upload))
        assertTrue(NowPlayingCaptions.showCcButton(upload))
        val song = Track(id = "song1", title = "Tum Hi Ho", artist = "Arijit Singh", durationSec = 262L, thumbnailUrl = "", sourceUrl = "https://music.youtube.com/watch?v=song1")
        assertFalse(NowPlayingCaptions.showCcButton(song))
        assertFalse(NowPlayingCaptions.showCcButton(null))
        assertEquals("English \u00B7 auto", NowPlayingCaptions.pillLabel("English", true))
        assertEquals("Telugu", NowPlayingCaptions.pillLabel("Telugu", false))
        assertFalse(NowPlayingCaptions.showPill(1))
        assertTrue(NowPlayingCaptions.showPill(2))
    }

    @Test
    fun minimLogo_onHomeAndStories_noMusicSearchOnStories() {
        assertTrue(TopRowBranding.showsLogo("HOME"))
        assertTrue(TopRowBranding.showsLogo("STORIES"))
        assertFalse(TopRowBranding.showsLogo("STATS"))
        assertFalse(TopRowBranding.showsSearch("STORIES"))
        assertTrue(TopRowBranding.showsSearch("LIBRARY"))
    }

    /** 6.1.1: captions on tapes start off; the off state shows nothing and isn't "no captions". */
    @Test
    fun tapeCaptionsStartOff() {
        assertFalse(NowPlayingCaptions.ON_BY_DEFAULT)
        val off = CaptionLyrics.off("vid")
        assertTrue(CaptionLyrics.isOff(off))
        assertTrue(CaptionLyrics.isCaptions(off))
        assertTrue(off.lines.isEmpty())
        assertFalse(CaptionLyrics.isOff(CaptionLyrics.none("vid")))
        assertFalse(CaptionLyrics.isOff(CaptionLyrics.of("vid", listOf(Cue(0, 1_000, "hi")))))
        assertFalse(CaptionLyrics.isOff(null))
        assertEquals(1f, NowPlayingCaptions.ccAlpha(true), 0f)
        assertTrue(NowPlayingCaptions.ccAlpha(false) < 1f)
        assertEquals(com.minim.music.ui.NowPlayingLyricSlot.Mode.NONE, com.minim.music.ui.NowPlayingLyricSlot.mode(off))
        assertFalse(com.minim.music.ui.NowPlayingLyricSlot.slotOpensFullscreen(com.minim.music.ui.NowPlayingLyricSlot.mode(off), isStory = true))
    }
}
