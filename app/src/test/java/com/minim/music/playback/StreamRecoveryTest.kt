package com.minim.music.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class StreamRecoveryTest {

    @Before
    fun clean() {
        StreamRecovery.reset("song")
        StreamRecovery.reset("other")
    }

    @Test
    fun restartsWithAnotherFormatThenGivesUp() {
        assertTrue(StreamRecovery.onStreamFailure("song", 140, otherOptions = 3, nowMs = 0))
        assertEquals(setOf(140), StreamRecovery.excludedItags("song"))
        assertTrue(StreamRecovery.isRecovering("song", 1000))
        assertTrue(StreamRecovery.onStreamFailure("song", 139, otherOptions = 2, nowMs = 2000))
        assertEquals(setOf(140, 139), StreamRecovery.excludedItags("song"))
        // third failure: limit reached, clear error
        assertFalse(StreamRecovery.onStreamFailure("song", 251, otherOptions = 1, nowMs = 4000))
        assertFalse(StreamRecovery.isRecovering("song", 4001))
        assertEquals(StreamRecovery.GIVE_UP_MESSAGE, StreamRecovery.takeGiveUpMessage("song"))
        assertNull(StreamRecovery.takeGiveUpMessage("song"))
    }

    @Test
    fun singleFormatGetsOneFreshTryOnly() {
        assertTrue(StreamRecovery.onStreamFailure("song", 140, otherOptions = 0, nowMs = 0))
        assertTrue(StreamRecovery.excludedItags("song").isEmpty())
        assertFalse(StreamRecovery.onStreamFailure("song", 140, otherOptions = 0, nowMs = 10))
    }

    @Test
    fun playingClearsRecoveringAndNewSongResets() {
        StreamRecovery.onStreamFailure("song", 140, otherOptions = 2, nowMs = 0)
        StreamRecovery.onPlaying("song")
        assertFalse(StreamRecovery.isRecovering("song", 1))
        StreamRecovery.reset("song")
        assertTrue(StreamRecovery.excludedItags("song").isEmpty())
        assertTrue(StreamRecovery.onStreamFailure("song", 140, otherOptions = 2, nowMs = 5))
    }

    @Test
    fun recoveringFlagExpires() {
        StreamRecovery.onStreamFailure("other", 140, otherOptions = 2, nowMs = 0)
        assertTrue(StreamRecovery.isRecovering("other", 29_000))
        assertFalse(StreamRecovery.isRecovering("other", 31_000))
        assertFalse(StreamRecovery.isRecovering("song", 1))
    }
}
