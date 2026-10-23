package com.minim.music.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class ColourModeTest {
    @Test
    fun dynamicKeepsCoverSeed() {
        assertEquals(0xFF3366AA.toInt(), ColourMode.seedFor(0xFF3366AA.toInt(), dynamic = true))
    }

    @Test
    fun defaultIgnoresCoverSeed() {
        assertEquals(0, ColourMode.seedFor(0xFF3366AA.toInt(), dynamic = false))
    }

    @Test
    fun nothingPlayingIsBlackAndWhiteInBoth() {
        assertEquals(0, ColourMode.seedFor(0, dynamic = true))
        assertEquals(0, ColourMode.seedFor(0, dynamic = false))
    }
}
