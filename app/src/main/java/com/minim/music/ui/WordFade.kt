package com.minim.music.ui

import android.graphics.Color
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
 */
class WordFade(private val view: TextView) {

    private class AlphaSpan(val startAt: Long) : CharacterStyle(), UpdateAppearance {
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

    private val frame = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            val now = System.currentTimeMillis()
            val it = live.iterator()
            while (it.hasNext()) {
                val s = it.next()
                s.alpha = ((now - s.startAt).toFloat() / FADE_MS).coerceIn(0f, 1f)
                if (s.alpha >= 1f) it.remove()
            }
            view.invalidate()
            if (live.isNotEmpty() && view.isAttachedToWindow) {
                Choreographer.getInstance().postFrameCallback(this)
            } else {
                ticking = false
                if (live.isNotEmpty()) finish()
            }
        }
    }

    /** Shows [text] at once, with no animation (rebuilding the chat). */
    fun setInstant(text: CharSequence) {
        live.clear()
        buf.clear()
        buf.append(text)
        view.text = buf
    }

    /** Appends streamed text; each word in it gets its own fade. */
    fun append(delta: String) {
        if (delta.isEmpty()) return
        val now = System.currentTimeMillis()
        for (m in WORD.findAll(delta)) {
            val start = buf.length
            buf.append(m.value)
            lastStart = maxOf(now, lastStart + STAGGER_MS)
            val span = AlphaSpan(lastStart)
            buf.setSpan(span, start, buf.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            live += span
        }
        view.setText(buf, TextView.BufferType.SPANNABLE)
        if (!ticking) {
            ticking = true
            Choreographer.getInstance().postFrameCallback(frame)
        }
    }

    /** Shows everything fully (when the view leaves the screen mid-fade). */
    fun finish() {
        live.forEach { it.alpha = 1f }
        live.clear()
        view.invalidate()
    }

    companion object {
        const val FADE_MS = 260L
        const val STAGGER_MS = 38L
        private val WORD = Regex("\\s*\\S+\\s*|\\s+")
    }
}
