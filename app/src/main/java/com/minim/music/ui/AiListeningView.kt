package com.minim.music.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.PI
import kotlin.math.sin

/**
 * 5.8.1 (update 53): the small "Listening…" animation while Gemini writes a song's lyrics. Five
 * rounded bars in the cover's colours rise and fall out of step, like a quiet level meter. It only
 * runs while it's on screen ([running] and attached and shown), so it costs nothing otherwise.
 */
class AiListeningView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private var colors = intArrayOf(0xFFFFFFFF.toInt())
    private var phase = 0f
    /** When false the bars sit still at a low, even height (reduced motion, or paused). */
    var moving: Boolean = true
        set(v) { field = v; update() }
    var running: Boolean = false
        set(v) { if (field != v) { field = v; update() } }

    private val anim = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = CYCLE_MS
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener { phase = it.animatedValue as Float; invalidate() }
    }

    /** The cover's colours (at least one), cycled across the bars. */
    fun setColors(c: IntArray) {
        if (c.isEmpty() || c.contentEquals(colors)) return
        colors = c
        invalidate()
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); update() }
    override fun onDetachedFromWindow() { anim.cancel(); super.onDetachedFromWindow() }
    override fun onVisibilityAggregated(isVisible: Boolean) { super.onVisibilityAggregated(isVisible); update() }

    private fun update() {
        val go = running && moving && isAttachedToWindow && isShown
        if (go && !anim.isStarted) anim.start()
        if (!go && anim.isStarted) { anim.cancel(); phase = 0f; invalidate() }
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val gap = w / (BARS * 2.6f)
        val barW = (w - gap * (BARS - 1)) / BARS
        val r = barW / 2f
        for (i in 0 until BARS) {
            val level = barLevel(i, phase, running && moving)
            val bh = (h * level).coerceAtLeast(barW)
            val x = i * (barW + gap)
            rect.set(x, (h - bh) / 2f, x + barW, (h + bh) / 2f)
            paint.color = colors[i % colors.size]
            canvas.drawRoundRect(rect, r, r, paint)
        }
    }

    companion object {
        const val BARS = 5
        const val CYCLE_MS = 1_400L
        const val REST_LEVEL = 0.28f

        /** Bar [i]'s height (0..1 of the view) at [phase] of the cycle: offset sines, never flat. */
        fun barLevel(i: Int, phase: Float, moving: Boolean): Float {
            if (!moving) return REST_LEVEL
            val a = sin(2 * PI * (phase + i * 0.17)).toFloat()
            val b = sin(2 * PI * (2 * phase + i * 0.41)).toFloat()
            return (0.55f + 0.30f * a + 0.15f * b).coerceIn(0.2f, 1f)
        }
    }
}
