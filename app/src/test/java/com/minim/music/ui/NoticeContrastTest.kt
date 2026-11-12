package com.minim.music.ui

import android.graphics.Color
import androidx.core.graphics.ColorUtils
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 6.1.1: the "No more accounts" note's body text was too faint on dark covers (muted on the soft
 * card, about 2:1). The body colour must read well on the soft card in light, dark and any cover
 * colour (7:1 where the palette allows, within 15% of the title otherwise), and stay softer
 * than the title.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NoticeContrastTest {

    private val seeds = listOf(
        0, // nothing playing: black and white
        Color.rgb(20, 120, 120), // dark teal (the screenshot)
        Color.rgb(0, 90, 80), Color.rgb(200, 40, 90), Color.rgb(30, 60, 140),
        Color.rgb(240, 200, 30), Color.rgb(90, 160, 60), Color.rgb(150, 80, 200),
        Color.rgb(230, 120, 40), Color.rgb(120, 120, 125), Color.rgb(10, 10, 12), Color.rgb(0, 160, 160),
    )

    @Test
    fun bodyReadsWellOnTheSoftCardEverywhere() {
        for (seed in seeds) for (dark in listOf(false, true)) {
            val p = Pal.build(seed, dark)
            val card = ColorUtils.compositeColors(ColorUtils.setAlphaComponent(p.fg, 0x1C), p.bg)
            val body = Painter.bodyText(p)
            val bodyC = ColorUtils.calculateContrast(body, card)
            val mutedC = ColorUtils.calculateContrast(p.muted, card)
            val titleC = ColorUtils.calculateContrast(p.fg, card)
            println("seed=%08x dark=$dark muted=%.2f body=%.2f title=%.2f".format(seed, mutedC, bodyC, titleC))
            assertTrue("seed=$seed dark=$dark body=$bodyC", bodyC >= 4.0)
            assertTrue("seed=$seed dark=$dark body=$bodyC title=$titleC", bodyC >= minOf(7.0, titleC * 0.85) - 0.01)
            assertTrue("seed=$seed dark=$dark body must beat muted", bodyC > mutedC)
            assertTrue("seed=$seed dark=$dark body softer than title", bodyC < titleC)
        }
    }
}
