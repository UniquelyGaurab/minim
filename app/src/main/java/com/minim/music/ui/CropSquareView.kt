package com.minim.music.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View

/**
 * 6.8.0: crops a gallery photo to a square for a playlist cover. The square frame (rounded like
 * the cover) stays in the middle; drag the photo to move it and pinch to zoom. The photo always
 * fills the frame, so there's never an empty edge.
 */
class CropSquareView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private var bitmap: Bitmap? = null
    private var scale = 1f
    private var minScale = 1f
    private var tx = 0f
    private var ty = 0f
    private val frame = RectF()
    private val radius = 16f * resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val dim = Paint().apply { color = Color.argb(150, 0, 0, 0) }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * resources.displayMetrics.density
        color = Color.argb(200, 255, 255, 255)
    }
    private val m = Matrix()
    private val hole = Path()
    private var lastX = 0f
    private var lastY = 0f
    private var dragging = false

    private val scaler = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(d: ScaleGestureDetector): Boolean {
            val next = (scale * d.scaleFactor).coerceIn(minScale, minScale * 6f)
            val f = next / scale
            tx = d.focusX - (d.focusX - tx) * f
            ty = d.focusY - (d.focusY - ty) * f
            scale = next
            clamp()
            invalidate()
            return true
        }
    })

    fun setBitmap(b: Bitmap?) {
        bitmap = b
        fit()
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(w, w)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val inset = w * 0.08f
        frame.set(inset, inset, w - inset, h - inset)
        hole.reset()
        hole.addRect(0f, 0f, w.toFloat(), h.toFloat(), Path.Direction.CW)
        hole.addRoundRect(frame, radius, radius, Path.Direction.CCW)
        fit()
    }

    private fun fit() {
        val b = bitmap ?: return
        if (frame.width() <= 0f) return
        minScale = maxOf(frame.width() / b.width, frame.height() / b.height)
        scale = minScale
        tx = frame.centerX() - b.width * scale / 2f
        ty = frame.centerY() - b.height * scale / 2f
    }

    private fun clamp() {
        val b = bitmap ?: return
        val w = b.width * scale
        val h = b.height * scale
        tx = tx.coerceIn(frame.right - w, frame.left)
        ty = ty.coerceIn(frame.bottom - h, frame.top)
    }

    // a tap does nothing here, but accessibility services expect touches to end in a click
    override fun performClick(): Boolean = super.performClick()

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (bitmap == null) return false
        scaler.onTouchEvent(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                lastX = e.x; lastY = e.y; dragging = true
            }
            MotionEvent.ACTION_POINTER_DOWN -> dragging = false
            MotionEvent.ACTION_MOVE -> if (!scaler.isInProgress && e.pointerCount == 1) {
                if (dragging) {
                    tx += e.x - lastX
                    ty += e.y - lastY
                    clamp()
                    invalidate()
                }
                lastX = e.x; lastY = e.y; dragging = true
            }
            MotionEvent.ACTION_POINTER_UP -> dragging = false
            MotionEvent.ACTION_UP -> {
                dragging = false
                parent?.requestDisallowInterceptTouchEvent(false)
                performClick()
            }
            MotionEvent.ACTION_CANCEL -> {
                dragging = false
                parent?.requestDisallowInterceptTouchEvent(false)
            }
        }
        return true
    }

    override fun onDraw(canvas: Canvas) {
        val b = bitmap ?: return
        m.reset()
        m.postScale(scale, scale)
        m.postTranslate(tx, ty)
        canvas.drawBitmap(b, m, paint)
        canvas.drawPath(hole, dim)
        canvas.drawRoundRect(frame, radius, radius, line)
    }

    /** The part of the photo inside the frame, in the photo's own pixels. */
    fun cropRect(): Rect? {
        val b = bitmap ?: return null
        val l = ((frame.left - tx) / scale).toInt().coerceIn(0, b.width - 1)
        val t = ((frame.top - ty) / scale).toInt().coerceIn(0, b.height - 1)
        val side = (frame.width() / scale).toInt().coerceIn(1, minOf(b.width - l, b.height - t))
        return Rect(l, t, l + side, t + side)
    }
}
