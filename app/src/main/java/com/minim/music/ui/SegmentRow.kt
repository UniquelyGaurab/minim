package com.minim.music.ui

import android.animation.ObjectAnimator
import android.animation.StateListAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import android.view.animation.PathInterpolator
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.ColorUtils
import com.minim.music.R

/**
 * 5.8.4 u56: the Settings choice control. One soft pill track holding every option, with a solid
 * pill (the thumb) behind the chosen one that slides to the new choice. Each option is a plain
 * child TextView that keeps its own id and isSelected, so the Settings code still just selects a
 * child; the text colour follows the thumb as it passes, so it never reads wrong mid-slide.
 * Colours come from Painter (the current cover's palette), nothing is fixed.
 */
class SegmentRow @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : LinearLayout(context, attrs) {

    private val density = resources.displayMetrics.density
    private val inset = 3f * density
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val track = RectF()
    private val thumb = RectF()

    private var fg = 0xFF000000.toInt()
    private var bg = 0xFFFFFFFF.toInt()
    private var shown = -1          // index the thumb rests on (or is heading to)
    private var fromL = 0f
    private var fromR = 0f
    private var anim: ValueAnimator? = null

    private val regular: Typeface? = runCatching { ResourcesCompat.getFont(context, R.font.poppins_regular) }.getOrNull()
    private val semi: Typeface? = runCatching { ResourcesCompat.getFont(context, R.font.poppins_semibold) }.getOrNull()

    init {
        orientation = HORIZONTAL
        setWillNotDraw(false)
    }

    /** A soft dim while an option is held down, so a tap is felt before the thumb moves. */
    override fun onViewAdded(child: View) {
        super.onViewAdded(child)
        val pressed = intArrayOf(android.R.attr.state_pressed)
        child.stateListAnimator = StateListAnimator().apply {
            addState(pressed, ObjectAnimator.ofFloat(child, View.ALPHA, 0.55f).setDuration(90L))
            addState(IntArray(0), ObjectAnimator.ofFloat(child, View.ALPHA, 1f).setDuration(160L))
        }
    }

    /** Called by Painter with the palette; also picks up a changed selection. */
    fun setColors(fg: Int, bg: Int) {
        this.fg = fg
        this.bg = bg
        trackPaint.color = ColorUtils.setAlphaComponent(fg, 0x1C)
        thumbPaint.color = fg
        sync(animate = isLaidOut && shown >= 0)
        ink()
        invalidate()
    }

    /** Index of the selected child, or -1. */
    fun selectedIndex(): Int {
        for (i in 0 until childCount) if (getChildAt(i).isSelected) return i
        return -1
    }

    private fun sync(animate: Boolean) {
        val want = selectedIndex()
        if (want == shown) return
        shown = want
        if (want < 0 || !isLaidOut) { anim?.cancel(); snap(); return }
        if (!animate || thumb.isEmpty) { anim?.cancel(); snap(); return }
        fromL = thumb.left
        fromR = thumb.right
        anim?.cancel()
        anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 260L
            interpolator = PathInterpolator(0.2f, 0f, 0f, 1f)
            addUpdateListener {
                val t = it.animatedValue as Float
                val target = targetRect() ?: return@addUpdateListener
                thumb.set(
                    fromL + (target.left - fromL) * t, target.top,
                    fromR + (target.right - fromR) * t, target.bottom
                )
                ink()
                invalidate()
            }
            start()
        }
    }

    private fun targetRect(): RectF? {
        val c = getChildAt(shown.takeIf { it in 0 until childCount } ?: return null) ?: return null
        return RectF(c.left + inset, inset, c.right - inset, height - inset)
    }

    private fun snap() {
        val r = targetRect()
        if (r == null) thumb.setEmpty() else thumb.set(r)
        ink()
        invalidate()
    }

    /** Text over the thumb takes the background colour, the rest the main colour. */
    private fun ink() {
        for (i in 0 until childCount) {
            val c = getChildAt(i) as? TextView ?: continue
            val f = if (thumb.isEmpty || c.width == 0) (if (c.isSelected) 1f else 0f)
            else {
                val over = minOf(thumb.right, c.right.toFloat()) - maxOf(thumb.left, c.left.toFloat())
                (over / (c.width - 2 * inset).coerceAtLeast(1f)).coerceIn(0f, 1f)
            }
            c.setTextColor(ColorUtils.blendARGB(fg, bg, f))
            val face = if (c.isSelected) semi else regular
            if (face != null && c.typeface != face) c.typeface = face
        }
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        super.onLayout(changed, l, t, r, b)
        track.set(0f, 0f, width.toFloat(), height.toFloat())
        if (anim?.isRunning != true) {
            shown = selectedIndex()
            snap()
        }
    }

    override fun onDraw(canvas: Canvas) {
        val r = height / 2f
        canvas.drawRoundRect(track, r, r, trackPaint)
        if (!thumb.isEmpty) {
            val tr = thumb.height() / 2f
            canvas.drawRoundRect(thumb, tr, tr, thumbPaint)
        }
    }

    override fun onDetachedFromWindow() {
        anim?.cancel()
        super.onDetachedFromWindow()
    }

    /** For tests: where the thumb is drawn now. */
    internal fun thumbRect(): RectF = RectF(thumb)
}
