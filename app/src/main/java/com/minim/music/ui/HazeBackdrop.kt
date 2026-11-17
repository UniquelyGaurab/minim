package com.minim.music.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.view.View
import java.util.Collections
import java.util.WeakHashMap

/**
 * 6.8.0 (71): the Haze background. One small blurred copy of the cover (made once per song, never
 * per frame) is shared by every screen. Each screen's background is a [HazeDrawable] that draws
 * the part of it that sits behind that screen, so stacked screens line up into one picture.
 * Songs crossfade over [FADE_MS].
 */
object HazeBackdrop {

    const val FADE_MS = 500L

    /** Width and height of the small blurred copy, and the blur radius on it. */
    const val SIZE = 96
    const val RADIUS = 8

    /** True while the Haze theme is on (Painter swaps screen backgrounds for the haze). */
    @Volatile
    var active = false

    /** The look and the cover key on screen (MainActivity's, kept here across a recreate). */
    var look: HazeLook.Look? = null
    var key: String? = null

    private var current: Bitmap? = null
    private var previous: Bitmap? = null

    /** 0..1: how far [current] has faded in over [previous]. */
    var fade = 1f
        private set

    /** Shown with no cover at all (the Dark look). */
    var emptyColor = HazeLook.NO_COVER_PAL.bg

    private val drawables: MutableSet<HazeDrawable> = Collections.newSetFromMap(WeakHashMap())

    val hasCover: Boolean get() = current != null

    /** A new song's haze: the old one stays underneath until [setFade] reaches 1. */
    fun push(bmp: Bitmap?) {
        previous = current
        current = bmp
        fade = if (previous == null && bmp == null) 1f else 0f
        invalidateAll()
    }

    fun setFade(t: Float) {
        fade = t.coerceIn(0f, 1f)
        if (fade >= 1f) previous = null
        invalidateAll()
    }

    fun clear() {
        current = null
        previous = null
        fade = 1f
        invalidateAll()
    }

    fun invalidateAll() {
        for (d in drawables.toList()) d.invalidateSelf()
    }

    internal fun register(d: HazeDrawable) { drawables.add(d) }

    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
    private val src = Rect()

    /** Draws the haze for a window [dst] (in the canvas's coordinates). */
    /**
     * 6.8.0 b72: where the window-sized blurred cover sits in [v]'s own coordinates, so a drawable
     * on any view (a screen, a morphing sheet) shows the part of the one backdrop behind it.
     */
    fun windowFor(v: View, b: android.graphics.Rect, out: RectF): Boolean {
        val root = v.rootView
        var x = 0f
        var y = 0f
        var cur: View? = v
        while (cur != null && cur !== root) {
            x += cur.left - cur.scrollX
            y += cur.top - cur.scrollY
            cur = cur.parent as? View
        }
        // Scrolled parents move the view, not its background: undo the view's own scroll
        x += v.scrollX
        y += v.scrollY
        val w = if (root.width > 0) root.width.toFloat() else v.resources.displayMetrics.widthPixels.toFloat()
        val h = if (root.height > 0) root.height.toFloat() else v.resources.displayMetrics.heightPixels.toFloat()
        out.set(b.left - x, b.top - y, b.left - x + w, b.top - y + h)
        return true
    }

    fun drawWindow(canvas: Canvas, dst: RectF) {
        val cur = current
        val prev = previous
        if (cur == null && prev == null) {
            canvas.drawColor(emptyColor)
            return
        }
        if (prev != null && fade < 1f) drawOne(canvas, prev, dst, 255)
        else if (cur == null) {
            canvas.drawColor(emptyColor)
            return
        }
        val a = if (prev == null || fade >= 1f) 255 else (fade * 255f + 0.5f).toInt()
        if (cur != null) {
            drawOne(canvas, cur, dst, a)
        } else {
            // Fading out to the Dark look
            paint.color = emptyColor
            paint.alpha = a
            canvas.drawRect(dst, paint)
        }
    }

    private fun drawOne(canvas: Canvas, bmp: Bitmap, dst: RectF, alpha: Int) {
        if (bmp.isRecycled || alpha <= 0) return
        // Centre crop the square cover to the window's shape
        val bw = bmp.width.toFloat()
        val bh = bmp.height.toFloat()
        val dw = dst.width().coerceAtLeast(1f)
        val dh = dst.height().coerceAtLeast(1f)
        if (bw / bh > dw / dh) {
            val w = bh * dw / dh
            val l = ((bw - w) / 2f).toInt()
            src.set(l, 0, (l + w).toInt().coerceAtLeast(l + 1), bh.toInt())
        } else {
            val h = bw * dh / dw
            val t = ((bh - h) / 2f).toInt()
            src.set(0, t, bw.toInt(), (t + h).toInt().coerceAtLeast(t + 1))
        }
        paint.color = 0xFF000000.toInt()
        paint.alpha = alpha.coerceIn(0, 255)
        canvas.drawBitmap(bmp, src, dst, paint)
    }

    /**
     * The blurred, scrimmed copy of [cover] and its look. Runs off the main thread: about 9,000
     * pixels, blurred in software on every Android version (cheaper than a RenderEffect pass).
     */
    fun build(cover: Bitmap, wasLight: Boolean?): Pair<Bitmap, HazeLook.Look> {
        val side = minOf(cover.width, cover.height).coerceAtLeast(1)
        val square = Bitmap.createBitmap(cover, (cover.width - side) / 2, (cover.height - side) / 2, side, side)
        val small = Bitmap.createScaledBitmap(square, SIZE, SIZE, true)
        val px = IntArray(SIZE * SIZE)
        small.getPixels(px, 0, SIZE, 0, 0, SIZE, SIZE)
        if (small !== square && small !== cover) small.recycle()
        if (square !== cover) square.recycle()
        for (i in px.indices) px[i] = px[i] or 0xFF000000.toInt()
        val blurred = SoftwareBlur.blur(px, SIZE, SIZE, RADIUS)
        val stats = HazeLook.stats(blurred)
        val look = HazeLook.look(stats.luma, stats.average, stats.brightest, stats.darkest, wasLight)
        // The faint scrim is baked in, so drawing stays one bitmap per screen
        for (i in blurred.indices) blurred[i] = HazeLook.over(look.scrim, look.scrimAlpha, blurred[i])
        val out = Bitmap.createBitmap(blurred, SIZE, SIZE, Bitmap.Config.ARGB_8888)
        return out to look
    }
}

/**
 * A screen's background under Haze: the part of the shared haze behind this view, placed by the
 * view's resting position in the window (so a screen sliding in carries its part with it and
 * lands lined up). [topRadius] rounds the top corners (sheet panels) and [frost] lays a frosted
 * ink over it.
 */
class HazeDrawable(private val topRadius: Float = 0f, var frost: Int = 0) : Drawable() {

    /** A sheet panel's frosted haze (rounded top), not a screen's. */
    val sheet: Boolean get() = topRadius > 0f

    init { HazeBackdrop.register(this) }

    private val dst = RectF()
    private val path = Path()
    private val tintPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun draw(canvas: Canvas) {
        val v = callback as? View ?: return
        val b = bounds
        HazeBackdrop.windowFor(v, b, dst)
        val save = canvas.save()
        if (topRadius > 0f) {
            path.reset()
            val r = topRadius
            path.addRoundRect(
                RectF(b), floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f), Path.Direction.CW
            )
            canvas.clipPath(path)
        } else {
            canvas.clipRect(b)
        }
        HazeBackdrop.drawWindow(canvas, dst)
        if (frost != 0) {
            tintPaint.color = frost
            canvas.drawRect(RectF(b), tintPaint)
        }
        canvas.restoreToCount(save)
    }

    override fun setAlpha(alpha: Int) = Unit
    override fun setColorFilter(colorFilter: ColorFilter?) = Unit
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = if (topRadius > 0f) PixelFormat.TRANSLUCENT else PixelFormat.OPAQUE
}
