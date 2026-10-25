package com.minim.music.ui

import android.app.Activity
import android.os.SystemClock
import android.text.SpanWatcher
import android.text.Spannable
import android.text.Spanned
import android.widget.FrameLayout
import android.widget.TextView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import java.time.Duration

/** 5.2.1: Gemini replies froze half see-through, or never showed. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class WordFadeTest {

    private fun attachedView(): TextView {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val tv = TextView(activity).apply { setTextIsSelectable(true) }
        activity.setContentView(FrameLayout(activity).apply { addView(tv) })
        return tv
    }

    private fun fadeSpans(tv: TextView): Array<WordFade.AlphaSpan> {
        val t = tv.text as? Spanned ?: return emptyArray()
        return t.getSpans(0, t.length, WordFade.AlphaSpan::class.java)
    }

    @Test
    fun replyEndsFullyVisibleWithNoFadeLeft() {
        val tv = attachedView()
        val fade = WordFade(tv)
        fade.append("Hi ")
        fade.append("Gaurab!")
        assertEquals("Hi Gaurab!", tv.text.toString())
        assertTrue(fadeSpans(tv).size >= 2)
        fade.step(SystemClock.uptimeMillis() + 10_000)
        assertEquals("Hi Gaurab!", tv.text.toString())
        assertEquals(0, fadeSpans(tv).size)
        assertTrue(!fade.isFading)
    }

    @Test
    fun everyFrameTellsTheSelectableTextToRedraw() {
        val tv = attachedView()
        val fade = WordFade(tv)
        fade.append("Hello there")
        val text = tv.text as Spannable
        var changes = 0
        text.setSpan(object : SpanWatcher {
            override fun onSpanAdded(t: Spannable, what: Any, start: Int, end: Int) {}
            override fun onSpanRemoved(t: Spannable, what: Any, start: Int, end: Int) {
                if (what is WordFade.AlphaSpan) changes++
            }
            override fun onSpanChanged(t: Spannable, what: Any, os: Int, oe: Int, ns: Int, ne: Int) {
                if (what is WordFade.AlphaSpan) changes++
            }
        }, 0, text.length, Spanned.SPAN_INCLUSIVE_INCLUSIVE)
        val now = SystemClock.uptimeMillis()
        fade.step(now + 100) // part way
        assertTrue("fading words must notify the TextView", changes > 0)
        val mid = changes
        fade.step(now + 10_000) // done: spans removed
        assertTrue(changes > mid)
        assertEquals(0, fadeSpans(tv).size)
    }

    @Test
    fun finishesEvenWithoutFrames() {
        // detached view (sheet closed / app in the background): the timer still ends the fade
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val tv = TextView(activity).apply { setTextIsSelectable(true) }
        val fade = WordFade(tv)
        fade.append("one two three four five")
        ShadowLooper.idleMainLooper(5, java.util.concurrent.TimeUnit.SECONDS)
        assertEquals("one two three four five", tv.text.toString())
        assertEquals(0, fadeSpans(tv).size)
    }

    @Test
    fun fastChunksNeverQueueLongerThanTheCap() {
        val tv = attachedView()
        val fade = WordFade(tv)
        val before = SystemClock.uptimeMillis()
        repeat(40) { fade.append("word$it word$it word$it ") }
        val latest = fadeSpans(tv).maxOf { it.startAt }
        assertTrue(latest <= SystemClock.uptimeMillis() + WordFade.MAX_LAG_MS)
        assertTrue(latest >= before)
        ShadowLooper.idleMainLooper(Duration.ofSeconds(5).toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS)
        assertEquals(0, fadeSpans(tv).size)
    }

    @Test
    fun instantTextReplacesEverything() {
        val tv = attachedView()
        val fade = WordFade(tv)
        fade.append("Hel")
        fade.setInstant("Hello, fixed")
        assertEquals("Hello, fixed", tv.text.toString())
        assertEquals(0, fadeSpans(tv).size)
        fade.append(" more")
        assertEquals("Hello, fixed more", fade.shown)
    }
}
