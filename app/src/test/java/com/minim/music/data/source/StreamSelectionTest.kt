package com.minim.music.data.source

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StreamSelectionTest {

    private fun opt(itag: Int, format: String, kbps: Int, progressive: Boolean = true) =
        StreamOption(itag, "https://x.googlevideo.com/videoplayback?itag=$itag", format, kbps, progressive, 1000L)

    private val options = listOf(
        opt(251, "WEBMA_OPUS", 160),
        opt(140, "M4A", 128),
        opt(139, "M4A", 48),
        opt(250, "WEBMA_OPUS", 70),
        opt(999, "M4A", 128, progressive = false)
    )

    @Test
    fun prefersM4a128LikeBefore() {
        assertEquals(140, StreamSelection.pick(options)!!.itag)
    }

    @Test
    fun skipsFormatsThatGot403() {
        assertEquals(139, StreamSelection.pick(options, setOf(140))!!.itag)
        assertEquals(251, StreamSelection.pick(options, setOf(140, 139))!!.itag)
    }

    @Test
    fun refreshKeepsTheSameFormat() {
        assertEquals(251, StreamSelection.pick(options, emptySet(), preferItag = 251)!!.itag)
    }

    @Test
    fun nothingLeftGivesNull() {
        assertNull(StreamSelection.pick(options, setOf(140, 139, 251, 250)))
    }

    @Test
    fun nonProgressiveIsNeverChunked() {
        assertEquals(3, StreamSelection.remaining(options, emptySet(), 140))
    }
}
