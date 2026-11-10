package com.minim.music.data.source

import com.minim.music.data.ai.PhoneSongAudio
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 5.8.5: multi-language videos always play (and save, and send to Gemini) the original audio. */
class OriginalAudioTrackTest {

    private fun opt(
        itag: Int, format: String, kbps: Int, type: String?, id: String?, locale: String?,
        len: Long = 1000L, tag: String = id ?: "x"
    ) = StreamOption(
        itag, "https://x.googlevideo.com/videoplayback?itag=$itag&t=$tag", format, kbps, true, len,
        trackType = type, trackId = id, locale = locale
    )

    // Like a Caleb Hammer video: the Spanish and Hindi dubs are listed before the English original
    private val dubFirst = listOf(
        opt(140, "M4A", 128, "DUBBED", "es.3", "es", 3_100),
        opt(251, "WEBMA_OPUS", 160, "DUBBED", "es.3", "es", 4_100),
        opt(140, "M4A", 128, "DUBBED", "hi.3", "hi", 3_200),
        opt(251, "WEBMA_OPUS", 160, "DUBBED", "hi.3", "hi", 4_200),
        opt(140, "M4A", 128, "ORIGINAL", "en.4", "en", 3_000),
        opt(251, "WEBMA_OPUS", 160, "ORIGINAL", "en.4", "en", 4_000),
        opt(139, "M4A", 48, "ORIGINAL", "en.4", "en", 1_000)
    )

    @Test
    fun dubListedFirstStillPlaysTheOriginal() {
        val pick = StreamSelection.pick(dubFirst)!!
        assertEquals(140, pick.itag)
        assertEquals("en.4", pick.trackId)
        assertEquals("en", pick.locale)
    }

    @Test
    fun fallbackAfter403StaysOnTheOriginal() {
        val pick = StreamSelection.pick(dubFirst, excludedItags = setOf(140))!!
        assertEquals("en.4", pick.trackId)
        assertEquals(139, pick.itag)
        // Only the original's own formats count as fallbacks
        assertEquals(2, StreamSelection.remaining(dubFirst, emptySet(), 140))
    }

    @Test
    fun savingAndGeminiTakeTheOriginalToo() {
        val save = StreamSelection.bestForSaving(dubFirst)!!
        assertEquals(251, save.itag)
        assertEquals("en.4", save.trackId)
        val gemini = PhoneSongAudio.pickForGemini(dubFirst)!!
        assertEquals(140, gemini.itag)
        assertEquals("en.4", gemini.trackId)
    }

    @Test
    fun descriptiveTrackIsNeverPickedWhenThereIsAnotherOne() {
        val withDescription = listOf(
            opt(140, "M4A", 128, "DESCRIPTIVE", "en.2", "en"),
            opt(140, "M4A", 128, "DUBBED", "fr.3", "fr"),
            opt(251, "WEBMA_OPUS", 160, "DESCRIPTIVE", "en.2", "en")
        )
        val pick = StreamSelection.pick(withDescription)!!
        assertEquals("fr.3", pick.trackId)

        val originalAndDescription = listOf(
            opt(140, "M4A", 128, "DESCRIPTIVE", "en.2", "en"),
            opt(140, "M4A", 128, "ORIGINAL", "en.4", "en")
        )
        assertEquals("en.4", StreamSelection.pick(originalAndDescription)!!.trackId)
    }

    @Test
    fun descriptiveOnlyIsStillPlayable() {
        val only = listOf(
            opt(251, "WEBMA_OPUS", 160, "DESCRIPTIVE", "en.2", "en"),
            opt(140, "M4A", 128, "DESCRIPTIVE", "en.2", "en")
        )
        assertEquals(140, StreamSelection.pick(only)!!.itag)
    }

    @Test
    fun noTrackInfoBehavesExactlyAsBefore() {
        val plain = listOf(
            opt(251, "WEBMA_OPUS", 160, null, null, null),
            opt(140, "M4A", 128, null, null, null),
            opt(139, "M4A", 48, null, null, null)
        )
        assertEquals(plain, StreamSelection.originalOnly(plain))
        assertEquals(140, StreamSelection.pick(plain)!!.itag)
        assertEquals(139, StreamSelection.pick(plain, setOf(140))!!.itag)
        assertEquals(251, StreamSelection.pick(plain, emptySet(), preferItag = 251)!!.itag)
        assertEquals(251, StreamSelection.bestForSaving(plain)!!.itag)
        assertNull(StreamSelection.pick(plain)!!.trackId)
    }

    @Test
    fun refreshKeepsTheLanguage() {
        // Mid-song refresh: fresh links come back in a new order, a dub of the same itag first
        val playing = StreamSelection.pick(dubFirst, emptySet(), preferItag = 251)!!
        assertEquals("en.4", playing.trackId)
        val fresh = listOf(
            opt(251, "WEBMA_OPUS", 160, "DUBBED", "hi.3", "hi", 4_200, "hi-new"),
            opt(251, "WEBMA_OPUS", 160, "ORIGINAL", "en.4", "en", 4_000, "en-new"),
            opt(140, "M4A", 128, "ORIGINAL", "en.4", "en", 3_000, "en-new")
        )
        val again = StreamSelection.pick(fresh, emptySet(), preferItag = playing.itag, preferTrackId = playing.trackId)!!
        assertEquals(251, again.itag)
        assertEquals("en.4", again.trackId)
        assertEquals(playing.contentLength, again.contentLength)
        assertTrue(again.url.contains("en-new"))
    }

    @Test
    fun refreshKeepsTheLanguageWhenThereIsNoOriginalLabel() {
        // Two dubs and no original: stays on whichever was playing, never hops between them
        val dubs = listOf(
            opt(140, "M4A", 128, "DUBBED", "de.3", "de"),
            opt(140, "M4A", 128, "DUBBED", "it.3", "it"),
            opt(251, "WEBMA_OPUS", 160, "DUBBED", "it.3", "it")
        )
        assertEquals("de.3", StreamSelection.pick(dubs)!!.trackId)
        val again = StreamSelection.pick(dubs.reversed(), emptySet(), preferItag = 140, preferTrackId = "de.3")!!
        assertEquals("de.3", again.trackId)
    }
}
