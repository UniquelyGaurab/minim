package com.minim.music.data.share

import com.minim.music.data.share.LyricsCardAudio.Preview
import com.minim.music.data.share.LyricsCardAudio.Status
import com.minim.music.data.share.LyricsCardOptions.Background
import com.minim.music.data.share.LyricsCardOptions.Lyrics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 6.7.0: which rows the lyrics-card sheet shows, what a card is made with, and how choices are stored. */
class LyricsCardOptionsTest {

    @Test fun defaultsAreStaticOriginalColours() {
        val d = LyricsCardOptions.DEFAULT
        assertFalse(d.animated)
        assertTrue(d.sound)
        assertEquals(Lyrics.ORIGINAL, d.lyrics)
        assertEquals(Background.COLOURS, d.background)
    }

    @Test fun soundRowOnlyForAnimated() {
        assertFalse(LyricsCardRules.shown(LyricsCardOptions(animated = false), synced = true, hasTranslation = false).soundRow)
        assertTrue(LyricsCardRules.shown(LyricsCardOptions(animated = true), synced = true, hasTranslation = false).soundRow)
    }

    @Test fun animatedDisabledWithNoteWithoutTiming() {
        val s = LyricsCardRules.shown(LyricsCardOptions(animated = true), synced = false, hasTranslation = false)
        assertFalse(s.animatedEnabled)
        assertTrue(s.animatedNote)
        // a remembered Animated falls back to a still card, and so no Sound row
        assertFalse(s.soundRow)
        val synced = LyricsCardRules.shown(LyricsCardOptions(), synced = true, hasTranslation = false)
        assertTrue(synced.animatedEnabled)
        assertFalse(synced.animatedNote)
    }

    @Test fun lyricsRowOnlyWithTranslation() {
        assertFalse(LyricsCardRules.shown(LyricsCardOptions(), synced = true, hasTranslation = false).lyricsRow)
        assertTrue(LyricsCardRules.shown(LyricsCardOptions(), synced = true, hasTranslation = true).lyricsRow)
        assertTrue(LyricsCardRules.shown(LyricsCardOptions(), synced = false, hasTranslation = true).lyricsRow)
    }

    @Test fun effectiveIgnoresWhatTheSongCantDo() {
        val o = LyricsCardOptions(animated = true, sound = true, lyrics = Lyrics.BOTH, background = Background.BLUR)
        val plain = LyricsCardRules.effective(o, synced = false, hasTranslation = false)
        assertFalse(plain.animated)
        assertFalse(plain.sound)
        assertEquals(Lyrics.ORIGINAL, plain.lyrics)
        assertEquals(Background.BLUR, plain.background)
        assertEquals(o, LyricsCardRules.effective(o, synced = true, hasTranslation = true))
        // Sound Off stays off; a still card never has sound
        assertFalse(LyricsCardRules.effective(o.copy(sound = false), true, true).sound)
        assertFalse(LyricsCardRules.effective(o.copy(animated = false), true, true).sound)
    }

    @Test fun encodeDecodeRoundTrips() {
        for (animated in listOf(true, false)) for (sound in listOf(true, false))
            for (l in Lyrics.values()) for (b in Background.values()) {
                val o = LyricsCardOptions(animated, sound, l, b)
                assertEquals(o, LyricsCardOptions.decode(o.encode()))
            }
    }

    @Test fun decodeGarbageFallsBackPerChoice() {
        assertEquals(LyricsCardOptions.DEFAULT, LyricsCardOptions.decode(null))
        assertEquals(LyricsCardOptions.DEFAULT, LyricsCardOptions.decode(""))
        assertEquals(LyricsCardOptions.DEFAULT, LyricsCardOptions.decode("nonsense;;==;x"))
        val partial = LyricsCardOptions.decode("style=animated;lyrics=klingon")
        assertTrue(partial.animated)
        assertTrue(partial.sound)
        assertEquals(Lyrics.ORIGINAL, partial.lyrics)
        assertEquals(Background.COLOURS, partial.background)
        assertEquals(Background.BLUR, LyricsCardOptions.decode(" BG = Blur ".lowercase()).background)
    }

    // ---- audio fallback ----

    private val anim = LyricsCardOptions(animated = true, sound = true)

    @Test fun stillCardIsAnImage() {
        assertEquals(Preview.IMAGE, LyricsCardAudio.preview(LyricsCardOptions(), false, Status.FAILED, false))
        assertTrue(LyricsCardAudio.ready(Preview.IMAGE))
    }

    @Test fun soundWaitsForVideoAndAudio() {
        assertEquals(Preview.RENDERING, LyricsCardAudio.preview(anim, false, Status.LOADING, false))
        assertEquals(Preview.RENDERING, LyricsCardAudio.preview(anim, true, Status.LOADING, false))
        assertEquals(Preview.RENDERING, LyricsCardAudio.preview(anim, false, Status.READY, false))
        assertEquals(Preview.VIDEO_SOUND, LyricsCardAudio.preview(anim, true, Status.READY, false))
        assertFalse(LyricsCardAudio.ready(Preview.RENDERING))
        assertTrue(LyricsCardAudio.ready(Preview.VIDEO_SOUND))
    }

    @Test fun audioFailureOffersShareWithoutSound() {
        // whether or not the video is done, a failed fetch shows the message, never a broken share
        assertEquals(Preview.AUDIO_FAILED, LyricsCardAudio.preview(anim, false, Status.FAILED, false))
        assertEquals(Preview.AUDIO_FAILED, LyricsCardAudio.preview(anim, true, Status.FAILED, false))
        assertFalse(LyricsCardAudio.ready(Preview.AUDIO_FAILED))
        // "Share without sound": the silent video takes over
        assertEquals(Preview.RENDERING, LyricsCardAudio.preview(anim, false, Status.FAILED, true))
        assertEquals(Preview.VIDEO_SILENT, LyricsCardAudio.preview(anim, true, Status.FAILED, true))
        assertTrue(LyricsCardAudio.ready(Preview.VIDEO_SILENT))
    }

    @Test fun soundOffNeverWaitsForAudio() {
        val off = anim.copy(sound = false)
        assertEquals(Preview.VIDEO_SILENT, LyricsCardAudio.preview(off, true, Status.IDLE, false))
        assertEquals(Preview.VIDEO_SILENT, LyricsCardAudio.preview(off, true, Status.FAILED, false))
        assertEquals(Preview.RENDERING, LyricsCardAudio.preview(off, false, Status.IDLE, false))
    }

    // ---- 6.7.0 revision: Length ----

    @Test fun lengthRowOnlyForAnimated() {
        val anim = LyricsCardOptions(animated = true, length = LyricsCardOptions.Length.S45)
        assertTrue(LyricsCardRules.shown(anim, synced = true, hasTranslation = false).lengthRow)
        assertFalse(LyricsCardRules.shown(anim.copy(animated = false), synced = true, hasTranslation = false).lengthRow)
        // no timing: no animated card, so no Length either
        assertFalse(LyricsCardRules.shown(anim, synced = false, hasTranslation = false).lengthRow)
        // a static card is never made with a length
        assertEquals(LyricsCardOptions.Length.FIT, LyricsCardRules.effective(anim.copy(animated = false), true, false).length)
        assertEquals(LyricsCardOptions.Length.S45, LyricsCardRules.effective(anim, true, false).length)
    }

    @Test fun lengthIsRemembered() {
        assertEquals(LyricsCardOptions.Length.FIT, LyricsCardOptions.DEFAULT.length)
        for (len in LyricsCardOptions.Length.values()) {
            val o = LyricsCardOptions(animated = true, sound = false, lyrics = Lyrics.BOTH, background = Background.BLUR, length = len)
            assertEquals(o, LyricsCardOptions.decode(o.encode()))
        }
        assertTrue(LyricsCardOptions(animated = true, length = LyricsCardOptions.Length.S60).encode().endsWith("len=60"))
        // choices saved by the first 6.7.0 test (no length) and junk fall back to Fit
        assertEquals(LyricsCardOptions.Length.FIT, LyricsCardOptions.decode("style=animated;sound=on;lyrics=both;bg=blur").length)
        assertEquals(LyricsCardOptions.Length.FIT, LyricsCardOptions.decode("style=animated;len=90").length)
        assertTrue(LyricsCardOptions.decode("style=animated;sound=on;lyrics=both;bg=blur").animated)
    }

    @Test fun lengthSeconds() {
        assertEquals(listOf(null, 30, 45, 60), LyricsCardOptions.Length.values().map { it.seconds })
    }
}
