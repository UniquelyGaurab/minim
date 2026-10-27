package com.minim.music.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import androidx.core.graphics.ColorUtils

/**
 * 5.4.0: the small ring shown while a song or a playlist saves offline. A faint full circle with
 * the saved part drawn over it, clockwise from the top. 0 shows a slowly turning short arc
 * (waiting for its turn). Coloured with the "tint:" tag like the icons.
 */
class ProgressRing @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private var color = 0xFF000000.toInt()
    private var fraction = 0f
    private val stroke = resources.displayMetrics.density * 2f
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = stroke }
    private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = stroke; strokeCap = Paint.Cap.ROUND
    }
    private val box = RectF()
    private var spin = 0f

    fun setColor(c: Int) {
        if (c == color) return
        color = c
        invalidate()
    }

    /** 0..1 saved; 0 = waiting. */
    fun setProgress(f: Float) {
        val v = f.coerceIn(0f, 1f)
        if (v == fraction) return
        fraction = v
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val inset = stroke / 2f + 0.5f
        box.set(inset, inset, width - inset, height - inset)
        track.color = ColorUtils.setAlphaComponent(color, 0x40)
        arc.color = color
        canvas.drawOval(box, track)
        if (fraction <= 0.001f) {
            // Waiting: a short arc going round
            canvas.drawArc(box, spin - 90f, 70f, false, arc)
            spin = (spin + 6f) % 360f
            if (isShown) postInvalidateDelayed(32)
        } else {
            canvas.drawArc(box, -90f, 360f * fraction, false, arc)
        }
    }
}
