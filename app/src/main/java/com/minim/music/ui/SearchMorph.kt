package com.minim.music.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.widget.ImageView
import androidx.core.view.drawToBitmap
import androidx.recyclerview.widget.RecyclerView

/**
 * 5.5.0: tapping a search pill (Home, the top row, the Artist page) morphs it into the Search
 * screen instead of jumping: the pill stretches up and out into the search bar, its magnifier
 * slides over to the Search button, the screen behind fades away, the keyboard rises with it
 * and the results below fade in one after another. Closing plays it backwards into the pill it
 * came from.
 *
 * The moving pill is drawn in [overlayHost]'s overlay; the real views only fade, so nothing is
 * re-laid out during the animation.
 */
class SearchMorph(
    private val overlayHost: ViewGroup,
    private val screen: View,
    private val logo: View,
    private val input: View,
    private val go: View,
    private val results: RecyclerView,
    private val status: View,
    private val colors: () -> Pair<Int, Int>, // (fg, bg)
    private val animationsOn: () -> Boolean
) {
    private val density = overlayHost.resources.displayMetrics.density
    private var anim: ValueAnimator? = null
    private var ghost: Ghost? = null
    private var source: View? = null
    var closing = false
        private set

    /** Opens from [pill] (null or not on screen: no morph, just shows). [then] runs when it starts. */
    fun open(pill: View?, then: () -> Unit) {
        finish()
        screen.visibility = View.VISIBLE
        val p = pill?.takeIf { it.isShown && it.width > 0 && it.height > 0 }
        if (p == null || !animationsOn()) {
            source = null
            resetReal()
            then()
            return
        }
        source = p
        // Hide the real bar until the first frame is laid out, then start from the pill
        setReal(0f)
        screen.background?.alpha = 0
        then()
        val start = Runnable { if (source === p && screen.visibility == View.VISIBLE) run(p, opening = true, onEnd = null) }
        if (screen.isLaidOut && !screen.isLayoutRequested) start.run() else screen.post(start)
    }

    /**
     * Closes back into the pill it came from; [hide] makes the screen gone at the end. Without a
     * pill still on screen it just hides at once, as before.
     */
    fun close(hide: () -> Unit) {
        if (closing) return
        val p = source?.takeIf { it.isShown && it.width > 0 && it.height > 0 }
        anim?.end()
        if (p == null || !animationsOn() || screen.visibility != View.VISIBLE) {
            finish()
            hide()
            resetReal()
            return
        }
        closing = true
        run(p, opening = false) {
            closing = false
            hide()
            resetReal()
        }
    }

    /** Drops any running morph and puts every view back to normal. */
    fun finish() {
        anim?.cancel()
        anim = null
        removeGhost()
        source?.alpha = 1f
        closing = false
    }

    private fun run(pill: View, opening: Boolean, onEnd: (() -> Unit)?) {
        removeGhost()
        val from = rectOf(pill)
        val to = rectOf(input)
        val icon = (pill as? ViewGroup)?.let { firstImage(it) }
        val iconFrom = icon?.let { rectOf(it) } ?: RectF(from.left + from.height() * 0.3f, from.centerY() - 8 * density, from.left + from.height() * 0.3f + 16 * density, from.centerY() + 8 * density)
        val goRect = rectOf(go)
        val iconSize = iconFrom.width()
        val iconTo = RectF(goRect.centerX() - iconSize / 2f, goRect.centerY() - iconSize / 2f, goRect.centerX() + iconSize / 2f, goRect.centerY() + iconSize / 2f)
        val (fg, bg) = colors()
        val pillFill = pill.backgroundTintList?.defaultColor ?: fg
        val iconColor = icon?.imageTintList?.defaultColor ?: bg
        val snapshot = runCatching { pill.drawToBitmap() }.getOrNull()
        val iconDrawable = icon?.drawable?.constantState?.newDrawable()?.mutate()
        val g = Ghost(from, to, iconFrom, iconTo, pillFill, fg, iconColor, bg, snapshot, iconDrawable, 1.5f * density)
        g.setBounds(0, 0, overlayHost.width, overlayHost.height)
        overlayHost.overlay.add(g)
        ghost = g
        pill.alpha = 0f

        val rows = if (opening) prepareRows() else emptyList()
        val a = ValueAnimator.ofFloat(if (opening) 0f else 1f, if (opening) 1f else 0f).apply {
            duration = if (opening) SearchMotion.OPEN_MS else SearchMotion.CLOSE_MS
            interpolator = LinearInterpolator()
            addUpdateListener {
                val t = it.animatedValue as Float
                g.t = t
                screen.background?.alpha = (255 * SearchMotion.backdrop(t)).toInt()
                input.alpha = SearchMotion.realBar(t)
                logo.alpha = SearchMotion.logo(t)
                logo.translationX = (1f - SearchMotion.logo(t)) * -12f * density
                go.alpha = SearchMotion.goButton(t)
                val gs = 0.9f + 0.1f * SearchMotion.goButton(t)
                go.scaleX = gs; go.scaleY = gs
                if (!opening) {
                    // The results leave first, quickly
                    val r = SearchMotion.window(t, 0.55f, 1f)
                    results.alpha = r
                    status.alpha = r
                }
            }
            addListener(object : AnimatorListenerAdapter() {
                private var cancelled = false
                override fun onAnimationCancel(animation: Animator) { cancelled = true }
                override fun onAnimationEnd(animation: Animator) {
                    if (anim === animation) anim = null
                    removeGhost()
                    pill.alpha = 1f
                    if (opening) resetReal()
                    if (!cancelled || !opening) onEnd?.invoke()
                }
            })
        }
        anim = a
        a.start()
        if (opening) cascade(rows)
    }

    /** The rows below the bar start hidden, then fade up one after another. */
    private fun prepareRows(): List<View> {
        val rows = ArrayList<View>()
        if (status.visibility == View.VISIBLE) rows.add(status)
        for (i in 0 until results.childCount) rows.add(results.getChildAt(i))
        val shift = 10f * density
        for (v in rows) { v.alpha = 0f; v.translationY = shift }
        results.alpha = 1f
        return rows
    }

    private fun cascade(rows: List<View>) {
        rows.forEachIndexed { i, v ->
            v.animate().alpha(1f).translationY(0f)
                .setStartDelay(SearchMotion.listDelay(i))
                .setDuration(SearchMotion.LIST_ITEM_MS)
                .setInterpolator(DecelerateInterpolator())
                .start()
        }
    }

    private fun setReal(a: Float) {
        input.alpha = a; logo.alpha = a; go.alpha = a
    }

    private fun resetReal() {
        setReal(1f)
        logo.translationX = 0f
        go.scaleX = 1f; go.scaleY = 1f
        results.alpha = 1f
        status.alpha = 1f
        screen.background?.alpha = 255
    }

    private fun removeGhost() {
        ghost?.let { overlayHost.overlay.remove(it); it.recycle() }
        ghost = null
    }

    private fun firstImage(g: ViewGroup): ImageView? {
        for (i in 0 until g.childCount) {
            val c = g.getChildAt(i)
            if (c is ImageView && c.visibility == View.VISIBLE) return c
            if (c is ViewGroup) firstImage(c)?.let { return it }
        }
        return null
    }

    private val a = IntArray(2)
    private val b = IntArray(2)

    private fun rectOf(v: View): RectF {
        v.getLocationInWindow(a)
        overlayHost.getLocationInWindow(b)
        val l = (a[0] - b[0]).toFloat()
        val t = (a[1] - b[1]).toFloat()
        return RectF(l, t, l + v.width * v.scaleX, t + v.height * v.scaleY)
    }

    /** The stretching pill, its words fading away and the travelling magnifier. */
    private class Ghost(
        private val from: RectF,
        private val to: RectF,
        private val iconFrom: RectF,
        private val iconTo: RectF,
        private val fillColor: Int,
        private val outlineColor: Int,
        private val iconStart: Int,
        private val iconEnd: Int,
        private var snapshot: Bitmap?,
        private val icon: Drawable?,
        private val stroke: Float
    ) : Drawable() {
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = stroke }
        private val bmp = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        private val r = RectF()
        var t = 0f
            set(v) { field = v; invalidateSelf() }

        override fun draw(canvas: Canvas) {
            val s = SearchMotion.shape(t)
            val rr = SearchMotion.lerpRect(floatArrayOf(from.left, from.top, from.right, from.bottom), floatArrayOf(to.left, to.top, to.right, to.bottom), s)
            r.set(rr[0], rr[1], rr[2], rr[3])
            val radius = r.height() / 2f
            val outline = SearchMotion.fillToOutline(t)
            // The bar itself takes over at the very end
            val handover = 1f - SearchMotion.realBar(t)
            fill.color = fillColor
            fill.alpha = (Color.alpha(fillColor) * (1f - outline) * handover).toInt()
            if (fill.alpha > 0) canvas.drawRoundRect(r, radius, radius, fill)
            line.color = outlineColor
            line.alpha = (Color.alpha(outlineColor) * outline * handover).toInt()
            if (line.alpha > 0) {
                val inset = stroke / 2f
                r.inset(inset, inset)
                canvas.drawRoundRect(r, radius - inset, radius - inset, line)
                r.inset(-inset, -inset)
            }
            // The pill's own words, unstretched at the left, fading as it grows
            val words = SearchMotion.sourceContent(t)
            val b = snapshot
            if (b != null && words > 0f) {
                bmp.alpha = (255 * words).toInt()
                canvas.drawBitmap(b, r.left, r.centerY() - b.height / 2f, bmp)
            }
            // The magnifier, sliding to the Search button
            val m = SearchMotion.magnifier(t)
            if (icon != null && m > 0f) {
                val ir = SearchMotion.lerpRect(floatArrayOf(iconFrom.left, iconFrom.top, iconFrom.right, iconFrom.bottom), floatArrayOf(iconTo.left, iconTo.top, iconTo.right, iconTo.bottom), s)
                val c = SearchMotion.lerpColor(iconStart, if (s < 0.5f) outlineColor else iconEnd, if (s < 0.5f) s * 2f else 1f)
                val c2 = if (s < 0.5f) c else SearchMotion.lerpColor(outlineColor, iconEnd, (s - 0.5f) * 2f)
                icon.colorFilter = PorterDuffColorFilter(c2, PorterDuff.Mode.SRC_IN)
                icon.alpha = (255 * m).toInt()
                icon.setBounds(ir[0].toInt(), ir[1].toInt(), ir[2].toInt(), ir[3].toInt())
                icon.draw(canvas)
            }
        }

        fun recycle() { snapshot = null }

        override fun setAlpha(alpha: Int) = Unit
        override fun setColorFilter(colorFilter: ColorFilter?) = Unit
        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }
}
