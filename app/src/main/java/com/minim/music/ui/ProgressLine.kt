package com.minim.music.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

/**
 * Thin rounded progress bar from the designs: played part in the dark colour, the rest muted.
 * Drag or tap anywhere on it to seek.
 */
class ProgressLine @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val density = resources.displayMetrics.density
    private val thickness = 5f * density
    private val playedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = thickness
        strokeCap = Paint.Cap.ROUND
    }
    private val restPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = thickness
        strokeCap = Paint.Cap.ROUND
    }

    /** 0..1 */
    private var fraction = 0f
    private var dragging = false

    /** Called with the chosen fraction while dragging (fromUser=true until release). */
    var onSeek: ((fraction: Float, finished: Boolean) -> Unit)? = null

    fun setColors(played: Int, rest: Int) {
        playedPaint.color = played
        restPaint.color = rest
        invalidate()
    }

    fun setFraction(f: Float) {
        if (dragging) return
        val v = f.coerceIn(0f, 1f)
        if (v != fraction) {
            fraction = v
            invalidate()
        }
    }

    override fun onDraw(canvas: Canvas) {
        val half = thickness / 2f
        val left = paddingLeft + half
        val right = width - paddingRight - half
        val y = height / 2f
        val x = left + (right - left) * fraction
        canvas.drawLine(left, y, right, y, restPaint)
        if (fraction > 0f) canvas.drawLine(left, y, x, y, playedPaint)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled) return false
        val half = thickness / 2f
        val left = paddingLeft + half
        val right = width - paddingRight - half
        val f = ((event.x - left) / (right - left)).coerceIn(0f, 1f)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                dragging = true
                fraction = f
                invalidate()
                onSeek?.invoke(f, false)
            }
            MotionEvent.ACTION_MOVE -> {
                fraction = f
                invalidate()
                onSeek?.invoke(f, false)
            }
            MotionEvent.ACTION_UP -> {
                fraction = f
                dragging = false
                invalidate()
                onSeek?.invoke(f, true)
            }
            MotionEvent.ACTION_CANCEL -> {
                dragging = false
                onSeek?.invoke(fraction, true)
            }
        }
        return true
    }
}
