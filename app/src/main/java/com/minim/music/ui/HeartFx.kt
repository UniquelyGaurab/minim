package com.minim.music.ui

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import android.widget.ImageView
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.PathParser

/**
 * 5.5.0: the heart icon as one drawable that can be part filled. The fill spreads out from the
 * centre like ink (a growing circle clips the filled heart over the outline) instead of a
 * straight swap between the two icons. Same shapes as ic_heart_outline / ic_heart_filled.
 * Honours the ImageView's tint, so the Painter's "tint:" roles still colour it.
 */
class HeartDrawable(private val density: Float) : Drawable() {

    // androidx.core's PathParser is stable and public; lint only flags its library-group marker
    @SuppressLint("RestrictedApi")
    private val outline: Path = PathParser.createPathFromPathData(OUTLINE)
    @SuppressLint("RestrictedApi")
    private val filled: Path = PathParser.createPathFromPathData(FILLED)
    private val scaledOutline = Path()
    private val scaledFilled = Path()
    private val clip = Path()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = Color.WHITE }
    private var tint: ColorStateList? = null
    private var alphaValue = 255

    /** 0 = outline, 1 = filled. */
    var fill = 0f
        set(value) {
            val v = value.coerceIn(0f, 1f)
            if (v != field) { field = v; invalidateSelf() }
        }

    override fun getIntrinsicWidth(): Int = (24 * density).toInt()
    override fun getIntrinsicHeight(): Int = (24 * density).toInt()

    override fun onBoundsChange(bounds: Rect) {
        val m = Matrix()
        m.setScale(bounds.width() / 24f, bounds.height() / 24f)
        m.postTranslate(bounds.left.toFloat(), bounds.top.toFloat())
        outline.transform(m, scaledOutline)
        filled.transform(m, scaledFilled)
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.isEmpty) return
        paint.color = tint?.getColorForState(state, tint!!.defaultColor) ?: Color.WHITE
        paint.alpha = (Color.alpha(paint.color) * alphaValue / 255)
        when {
            fill <= 0f -> canvas.drawPath(scaledOutline, paint)
            fill >= 1f -> canvas.drawPath(scaledFilled, paint)
            else -> {
                canvas.drawPath(scaledOutline, paint)
                // The ink: a circle from the heart's centre, big enough at 1 to cover it all
                val cx = b.left + b.width() * 0.5f
                val cy = b.top + b.height() * 0.52f
                val maxR = b.width() * 0.62f
                clip.reset()
                clip.addCircle(cx, cy, maxR * HeartMotion.inkRadius(fill), Path.Direction.CW)
                canvas.save()
                canvas.clipPath(clip)
                canvas.drawPath(scaledFilled, paint)
                canvas.restore()
            }
        }
    }

    override fun setTintList(tint: ColorStateList?) {
        this.tint = tint
        invalidateSelf()
    }

    override fun isStateful(): Boolean = tint?.isStateful == true
    override fun onStateChange(state: IntArray): Boolean = tint?.isStateful == true
    override fun setAlpha(alpha: Int) { alphaValue = alpha; invalidateSelf() }
    override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter; invalidateSelf() }
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    companion object {
        const val FILLED =
            "M12,21.35l-1.45,-1.32C5.4,15.36 2,12.28 2,8.5 2,5.42 4.42,3 7.5,3c1.74,0 3.41,0.81 4.5,2.09C13.09,3.81 14.76,3 16.5,3 19.58,3 22,5.42 22,8.5c0,3.78 -3.4,6.86 -8.55,11.54L12,21.35z"
        const val OUTLINE =
            "M16.5,3c-1.74,0 -3.41,0.81 -4.5,2.09C10.91,3.81 9.24,3 7.5,3 4.42,3 2,5.42 2,8.5c0,3.78 3.4,6.86 8.55,11.54L12,21.35l1.45,-1.32C18.6,15.36 22,12.28 22,8.5 22,5.42 19.58,3 16.5,3zM12.1,18.55l-0.1,0.1 -0.1,-0.1C7.14,14.24 4,11.39 4,8.5 4,6.5 5.5,5 7.5,5c1.54,0 3.04,0.99 3.57,2.36h1.87C13.46,5.99 14.96,5 16.5,5c2,0 3.5,1.5 3.5,3.5 0,2.89 -3.14,5.74 -7.9,10.05z"
    }
}

/** 5.5.0: the 5-6 tiny dots that pop out of the heart on a like, drawn in the screen's overlay. */
private class BurstDrawable(
    private val cx: Float,
    private val cy: Float,
    private val startR: Float,
    private val endR: Float,
    private val dotR: Float,
    color: Int
) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
    private val baseAlpha = Color.alpha(color)
    var t = 0f
        set(v) { field = v; invalidateSelf() }

    override fun draw(canvas: Canvas) {
        for (i in 0 until HeartMotion.BURST_DOTS) {
            val d = HeartMotion.burstDot(i, t, startR, endR, dotR)
            if (d.radius <= 0.1f || d.alpha <= 0f) continue
            paint.alpha = (baseAlpha * d.alpha).toInt()
            canvas.drawCircle(cx + d.dx, cy + d.dy, d.radius, paint)
        }
    }

    override fun setAlpha(alpha: Int) = Unit
    override fun setColorFilter(colorFilter: ColorFilter?) = Unit
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

/** 5.5.0: the big heart that fades up over the cover on a double tap and floats away. */
private class BigHeartDrawable(
    private val cx: Float,
    private val cy: Float,
    private val size: Float,
    private val risePx: Float,
    private val density: Float
) : Drawable() {
    @SuppressLint("RestrictedApi")
    private val path: Path = PathParser.createPathFromPathData(HeartDrawable.FILLED)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        setShadowLayer(10f * density, 0f, 2f * density, 0x55000000)
    }
    var t = 0f
        set(v) { field = v; invalidateSelf() }

    override fun draw(canvas: Canvas) {
        val b = HeartMotion.bigHeart(t)
        if (b.alpha <= 0f) return
        paint.alpha = (255 * b.alpha).toInt()
        val s = size * b.scale / 24f
        canvas.save()
        canvas.translate(cx, cy - risePx * b.rise)
        canvas.scale(s, s)
        canvas.translate(-12f, -12f)
        canvas.drawPath(path, paint)
        canvas.restore()
    }

    override fun setAlpha(alpha: Int) = Unit
    override fun setColorFilter(colorFilter: ColorFilter?) = Unit
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

/**
 * 5.5.0: drives every heart (Now Playing's and both mini players'). [overlayHost] is the screen's
 * root: the burst and the big heart are drawn in its overlay, so they can spill past the heart's
 * own circle without being clipped.
 */
class HeartFx(
    private val overlayHost: ViewGroup,
    private val animationsOn: () -> Boolean
) {
    private val density = overlayHost.resources.displayMetrics.density
    private val hearts = HashMap<ImageView, HeartDrawable>()
    private val target = HashMap<ImageView, Boolean>()
    private val fills = HashMap<ImageView, ValueAnimator>()
    private val scales = HashMap<ImageView, ValueAnimator>()

    /** Gives [view] the part-fillable heart and the press squish. Its click still does the toggle. */
    @SuppressLint("ClickableViewAccessibility")
    fun attach(view: ImageView) {
        val d = HeartDrawable(density)
        hearts[view] = d
        target[view] = false
        view.setImageDrawable(d)
        view.setOnTouchListener { v, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> if (animationsOn()) {
                    scales.remove(view)?.cancel()
                    v.animate().scaleX(HeartMotion.PRESS_SCALE).scaleY(HeartMotion.PRESS_SCALE)
                        .setStartDelay(0L).setDuration(HeartMotion.PRESS_MS).start()
                }
                // A cancelled press (finger slid off) just springs back; a real tap is the click
                MotionEvent.ACTION_CANCEL -> v.animate().scaleX(1f).scaleY(1f).setStartDelay(0L).setDuration(160L).start()
                MotionEvent.ACTION_UP -> v.postDelayed({
                    // Safety: if no click followed (finger left the view), let it settle
                    if (scales[view] == null && v.scaleX != 1f && !v.isPressed) {
                        v.animate().scaleX(1f).scaleY(1f).setStartDelay(0L).setDuration(160L).start()
                    }
                }, 60L)
            }
            false
        }
    }

    fun isLikedShown(view: ImageView): Boolean = target[view] == true

    /**
     * Shows [liked] on [view]. With [animate] the ink spreads or drains; nothing happens when the
     * heart already shows that state (so the tapped heart, which animated at once, isn't redone).
     */
    fun setLiked(view: ImageView, liked: Boolean, animate: Boolean) {
        val d = hearts[view] ?: return
        if (target[view] == liked && fills[view] == null && d.fill == (if (liked) 1f else 0f)) return
        if (target[view] == liked && fills[view] != null) return
        target[view] = liked
        fills.remove(view)?.cancel()
        val end = if (liked) 1f else 0f
        if (!animate || !animationsOn() || !view.isShown) {
            d.fill = end
            return
        }
        val a = ValueAnimator.ofFloat(d.fill, end).apply {
            duration = if (liked) HeartMotion.INK_FILL_MS else HeartMotion.UNLIKE_MS
            interpolator = LinearInterpolator()
            addUpdateListener { d.fill = it.animatedValue as Float }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    if (fills[view] === animation) fills.remove(view)
                    d.fill = end
                }
            })
        }
        fills[view] = a
        a.start()
    }

    /**
     * The full like / unlike on the heart the user touched: spring (or shrink), ink, and for a
     * like the dot burst in [burstColor]. Haptics are the caller's (crisp on like, soft on unlike).
     */
    fun play(view: ImageView, liked: Boolean, burstColor: Int) {
        setLiked(view, liked, animate = true)
        if (!animationsOn() || !view.isShown) {
            view.scaleX = 1f; view.scaleY = 1f
            return
        }
        view.animate().cancel()
        scales.remove(view)?.cancel()
        val from = view.scaleX
        val a = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = if (liked) HeartMotion.LIKE_SPRING_MS else HeartMotion.UNLIKE_MS
            interpolator = LinearInterpolator()
            addUpdateListener {
                val t = it.animatedValue as Float
                val s = if (liked) HeartMotion.likeScale(t, from) else HeartMotion.unlikeScale(t, from)
                view.scaleX = s; view.scaleY = s
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    if (scales[view] === animation) scales.remove(view)
                    view.scaleX = 1f; view.scaleY = 1f
                }
            })
        }
        scales[view] = a
        a.start()
        if (liked) burst(view, burstColor)
    }

    private fun burst(view: ImageView, color: Int) {
        val c = centreIn(view) ?: return
        // The heart icon's own radius on screen (its 24dp box inside the padding)
        val icon = (view.width - view.paddingLeft - view.paddingRight).coerceAtLeast(1) / 2f
        val d = BurstDrawable(c.first, c.second, icon * 1.1f, icon * 2.3f, 2.2f * density, color)
        d.setBounds(0, 0, overlayHost.width, overlayHost.height)
        overlayHost.overlay.add(d)
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = HeartMotion.BURST_MS
            interpolator = LinearInterpolator()
            addUpdateListener { d.t = it.animatedValue as Float }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) { overlayHost.overlay.remove(d) }
            })
            start()
        }
    }

    /** The big heart over [art] (double tap on the cover). */
    fun bigHeart(art: View) {
        if (!animationsOn() || !art.isShown) return
        val c = centreIn(art) ?: return
        val size = art.width * art.scaleX * 0.32f
        val d = BigHeartDrawable(c.first, c.second, size, art.height * 0.18f, density)
        d.setBounds(0, 0, overlayHost.width, overlayHost.height)
        overlayHost.overlay.add(d)
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = HeartMotion.BIG_HEART_MS
            interpolator = LinearInterpolator()
            addUpdateListener { d.t = it.animatedValue as Float }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) { overlayHost.overlay.remove(d) }
            })
            start()
        }
    }

    private val a = IntArray(2)
    private val b = IntArray(2)

    /** [v]'s visual centre in [overlayHost]'s coordinates (follows translations and scale). */
    private fun centreIn(v: View): Pair<Float, Float>? {
        if (v.width == 0 || overlayHost.width == 0) return null
        v.getLocationInWindow(a)
        overlayHost.getLocationInWindow(b)
        // getLocationInWindow already includes v's own transform at its top left corner
        val x = a[0] - b[0] + v.width * v.scaleX / 2f
        val y = a[1] - b[1] + v.height * v.scaleY / 2f
        return x to y
    }

    companion object {
        /**
         * The burst's "album colour": the cover's own hue at a mid lightness and good saturation,
         * so the dots read on both the light and the dark side of the palette. Grey covers (or
         * nothing) fall back to [fallback].
         */
        fun burstColor(seed: Int, fallback: Int): Int {
            if (seed == 0) return fallback
            val hsl = FloatArray(3)
            ColorUtils.colorToHSL(seed, hsl)
            if (hsl[1] < 0.15f) return fallback
            return ColorUtils.HSLToColor(floatArrayOf(hsl[0], hsl[1].coerceAtLeast(0.6f), 0.55f))
        }
    }
}
