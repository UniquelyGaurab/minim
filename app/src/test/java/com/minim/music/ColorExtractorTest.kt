package com.minim.music

import com.minim.music.util.ArtworkPipeline
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ColorExtractorTest {

    @Test
    fun formatDurationSec_formatsCorrectly() {
        assertEquals("0:00", ArtworkPipeline.formatDurationSec(0L))
        assertEquals("0:00", ArtworkPipeline.formatDurationSec(-5L))
        assertEquals("0:09", ArtworkPipeline.formatDurationSec(9L))
        assertEquals("3:45", ArtworkPipeline.formatDurationSec(225L))
        assertEquals("10:02", ArtworkPipeline.formatDurationSec(602L))
    }

    @Test
    fun calculateInSampleSize_downsamplesLargeImagesPowerOfTwo() {
        val sample1 = ArtworkPipeline.calculateInSampleSize(1200, 1200, 240, 240)
        assertTrue(sample1 >= 2)

        val sampleSmall = ArtworkPipeline.calculateInSampleSize(120, 120, 240, 240)
        assertEquals(1, sampleSmall)
    }
}
