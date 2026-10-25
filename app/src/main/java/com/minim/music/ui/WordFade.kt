package com.minim.music.ui

import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextPaint
import android.text.style.CharacterStyle
import android.text.style.UpdateAppearance
import android.view.Choreographer
import android.widget.TextView

/**
 * v5.2.0: Gemini's reply fades in word by word (not a typewriter). Each new word starts
 * see-through and fades to full over [FADE_MS], a little after the word before it. The span
 * only scales the TextView's own colour, so a palette crossfade still applies to every word.
 *
 * 5.2.1: replies froze half see-through (or never showed at all). The reply TextView is
 * selectable, and a selectable TextView draws its text from cached blocks that a plain
 * invalidate() doesn't refresh, so changing a span's alpha did nothing on screen until more
 * text arrived. Now every frame re-sets each fading span on the TextView's own text (that is
 * what tells the TextView to redraw those words), a word's span is removed as soon as it is
 * fully visible, and a timer on the main looper finishes everything even when no frames come
 * (app in the background, view detached). The end state never depends on the animation.
 */
class WordFade(private val view: TextView) {

    internal class AlphaSpan(val startAt: Long) : CharacterStyle(), UpdateAppearance {
        var alpha = 0f
        override fun updateDrawState(tp: TextPaint) {
            val c = tp.color
            tp.color = Color.argb((Color.alpha(c) * alpha).toInt(), Color.red(c), Color.green(c), Color.blue(c))
        }
    }

    private val buf = SpannableStringBuilder()
    private val live = ArrayList<AlphaSpan>()
    private var lastStart = 0L
    private var ticking = false
    private val main = Handler(Looper.getMainLooper())
    private val safety = Runnable { finish() }

    private val frame = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!ticking) return
            if (!view.isAttachedToWindow) {
                finish()
                return
            }
            step(SystemClock.uptimeMillis())
            if (live.isEmpty()) stop() else Choreographer.getInstance().postFrameCallback(this)
        }
    }

    /** The text shown so far (fading words included). */
    val shown: String get() = buf.toString()

    /** True while some word is still fading in. */
    val isFading: Boolean get() = live.isNotEmpty()

    /** Adds streamed text; its words fade in one after another. */
    fun append(text: String) {
        if (text.isEmpty()) return
        val now = SystemClock.uptimeMillis()
        // Fast chunks don't pile up a long queue: a word never waits more than MAX_LAG_MS
        if (lastStart < now) lastStart = now
        if (lastStart > now + MAX_LAG_MS) lastStart = now + MAX_LAG_MS
        var i = 0
        while (i < text.length) {
            var j = i
            while (j < text.length && text[j].isWhitespace()) j++
            while (j < text.length && !text[j].isWhitespace()) j++
            val start = buf.length
            buf.append(text, i, j)
            val span = AlphaSpan(lastStart)
            buf.setSpan(span, start, buf.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            live += span
            lastStart = minOf(lastStart + STAGGER_MS, now + MAX_LAG_MS)
            i = j
        }
        // Words that already finished fading are plain text by now, so this copy carries
        // spans only for the ones still fading.
        view.setText(buf, TextView.BufferType.SPANNABLE)
        main.removeCallbacks(safety)
        main.postDelayed(safety, (lastStart - now) + FADE_MS + SAFETY_SLACK_MS)
        if (!ticking) {
            ticking = true
            Choreographer.getInstance().postFrameCallback(frame)
        }
    }

    /** Shows [text] at once, no fade (reopening the chat, or putting a reply right). */
    fun setInstant(text: String) {
        stop()
        live.clear()
        buf.clear()
        buf.clearSpans()
        buf.append(text)
        lastStart = 0L
        view.setText(buf, TextView.BufferType.SPANNABLE)
    }

    /** Every word to full at once. */
    fun finish() {
        stop()
        if (live.isEmpty()) return
        val shownText = view.text as? Spannable
        for (s in live) {
            s.alpha = 1f
            buf.removeSpan(s)
            shownText?.removeSpan(s)
        }
        live.clear()
        view.invalidate()
    }

    /** One animation step at [now]; internal so the tests can drive it without frames. */
    internal fun step(now: Long) {
        val shownText = view.text as? Spannable
        val it = live.iterator()
        while (it.hasNext()) {
            val s = it.next()
            val a = ((now - s.startAt).toFloat() / FADE_MS).coerceIn(0f, 1f)
            if (a == s.alpha && a < 1f) continue
            s.alpha = a
            if (a >= 1f) {
                // fully visible: plain text from now on
                buf.removeSpan(s)
                shownText?.removeSpan(s)
                it.remove()
            } else if (shownText != null) {
                val st = shownText.getSpanStart(s)
                if (st >= 0) {
                    // re-setting the span is what makes a selectable TextView redraw these words
                    shownText.setSpan(s, st, shownText.getSpanEnd(s), shownText.getSpanFlags(s))
                }
            }
        }
        if (shownText == null) view.invalidate()
    }

    private fun stop() {
        ticking = false
        Choreographer.getInstance().removeFrameCallback(frame)
        main.removeCallbacks(safety)
    }

    companion object {
        const val FADE_MS = 260L
        const val STAGGER_MS = 38L
        const val MAX_LAG_MS = 700L
        const val SAFETY_SLACK_MS = 400L
    }
}
