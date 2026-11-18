package com.minim.music.ui

import android.content.Context
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.time.Duration

/** 6.9.0 b77: the floating mini player slides up from / down off the bottom of the screen. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = android.app.Application::class, qualifiers = "w360dp-h760dp-xhdpi")
class PillSlideTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val looper get() = shadowOf(Looper.getMainLooper())

    private fun setup(visible: Boolean): Triple<FrameLayout, View, PillSlide> {
        val parent = FrameLayout(ctx)
        val pill = View(ctx)
        parent.addView(pill, FrameLayout.LayoutParams(400, 108, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = 60 })
        pill.visibility = if (visible) View.VISIBLE else View.GONE
        lay(parent)
        return Triple(parent, pill, PillSlide(pill) { true })
    }

    private fun lay(v: View) {
        v.measure(View.MeasureSpec.makeMeasureSpec(720, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(1520, View.MeasureSpec.EXACTLY))
        v.layout(0, 0, 720, 1520)
    }

    private fun run(ms: Long) = looper.idleFor(Duration.ofMillis(ms))

    // Robolectric jumps a started animator to its end on the first frame, so these check where it
    // starts and where it lands, and turnarounds made before any frame runs.

    @Test fun hidesBySlidingFullyOffTheBottomThenGoes() {
        val (parent, pill, slide) = setup(true)
        val top = pill.top
        slide.set(false, animate = true)
        assertEquals(View.VISIBLE, pill.visibility) // still there: it slides away, no pop
        assertTrue(slide.isAnimating)
        run(400)
        assertEquals(View.GONE, pill.visibility)
        assertTrue(slide.offset() >= parent.height - top) // fully past the bottom edge, nav bar included
        assertEquals(top, pill.top) // layout never moved
    }

    @Test fun showsByRisingFromBelowToItsUsualSpot() {
        val (_, pill, slide) = setup(true)
        val top = pill.top
        slide.set(false, animate = false)
        assertEquals(View.GONE, pill.visibility)
        slide.set(true, animate = true)
        assertEquals(View.VISIBLE, pill.visibility)
        assertEquals(slide.offset(), pill.translationY, 0.5f) // starts fully below the screen
        assertEquals(PillSlide.alphaAt(0f), pill.alpha, 0.001f)
        run(400)
        assertEquals(0f, pill.translationY, 0.001f)
        assertEquals(1f, pill.alpha, 0.001f)
        assertEquals(top, pill.top)
    }

    @Test fun quickBackAndForthEndsInTheRightStateWithNoJumps() {
        val (_, pill, slide) = setup(true)
        repeat(9) { k ->
            slide.set(k % 2 == 1, animate = true)
            // A turnaround starts from where the pill is: no snap to either end
            assertEquals(View.VISIBLE, pill.visibility)
            assertEquals(0f, pill.translationY, 0.5f)
        }
        run(500)
        assertEquals(View.GONE, pill.visibility) // last was a hide
        assertEquals(false, slide.target)
        repeat(7) { k ->
            slide.set(k % 2 == 0, animate = true)
            if (k % 2 == 1) run(5)
        }
        run(500)
        assertEquals(View.VISIBLE, pill.visibility) // last was a show
        assertEquals(0f, pill.translationY, 0.001f)
        assertEquals(1f, pill.alpha, 0.001f)
        assertEquals(true, slide.target)
        assertTrue(!slide.isAnimating)
    }

    @Test fun firstPlayRisesOnceItHasASize() {
        val parent = FrameLayout(ctx)
        val pill = View(ctx).apply { visibility = View.GONE }
        parent.addView(pill, FrameLayout.LayoutParams(400, 108, Gravity.BOTTOM))
        val slide = PillSlide(pill) { true }
        slide.set(true, animate = true)
        assertEquals(0f, pill.alpha, 0.001f) // invisible until measured, never a flash at rest
        lay(parent)
        run(400)
        assertEquals(1f, pill.alpha, 0.001f)
        assertEquals(0f, pill.translationY, 0.001f)
    }

    @Test fun instantMovesForTheMorph() {
        val (_, pill, slide) = setup(true)
        slide.set(false, animate = true)
        run(50)
        slide.set(true, animate = false)
        assertEquals(0f, pill.translationY, 0.001f)
        assertEquals(View.VISIBLE, pill.visibility)
    }

    @Test fun wiredToTheFloatingPillOnlyAndTheHomePillIsUnchanged() {
        val main = File("src/main/java/com/minim/music/ui/MainActivity.kt").readText()
        assertTrue(main.contains("floatSlide.set("))
        assertTrue(!main.contains("setShown(floatMini.root"))
        assertTrue(main.contains("setShown(homeMini.root, hasTrack, playbackChanged)"))
    }
}
