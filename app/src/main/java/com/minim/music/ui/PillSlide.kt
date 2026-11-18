package com.minim.music.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import kotlin.math.abs
import kotlin.math.max

/**
 * 6.9.0 b77: the floating mini player comes up from below the bottom edge of the screen and goes
 * back down off it, with a slight fade. Only translationY and alpha move, never layout, and its
 * resting spot is where it always was. One animator runs from wherever the pill is right now, so
 * quick back-and-forth turns around smoothly with no flicker or jump. GONE once it's fully out,
 * as before, so nothing behind it moves.
 */
class PillSlide(
    private val view: View,
    private val enabled: () -> Boolean = { ValueAnimator.areAnimatorsEnabled() }
) {
    /** 0: fully below the screen, 1: at rest. */
    var progress: Float = if (view.visibility == View.VISIBLE) 1f else 0f
        private set
    /** Where it's going (null until first set). */
    var target: Boolean? = if (view.visibility == View.VISIBLE) true else null
        private set
    private var anim: ValueAnimator? = null
    private var waitingForLayout = false

    val isAnimating: Boolean get() = anim?.isRunning == true || waitingForLayout

    /** How far down it goes to be fully off the screen: to the parent's bottom edge (nav bar included) and a little past. */
    fun offset(): Float {
        val lp = view.layoutParams as? ViewGroup.MarginLayoutParams
        val parentH = (view.parent as? View)?.height ?: 0
        val toEdge = if (parentH > 0 && view.height > 0) parentH - view.top else 0
        val bySize = view.height + (lp?.bottomMargin ?: 0)
        return (max(toEdge, bySize) + view.resources.displayMetrics.density * EXTRA_DP)
    }

    fun set(show: Boolean, animate: Boolean) {
        if (show && target == true && view.visibility == View.VISIBLE) return
        if (!show && target == false) return
        target = show
        if (!animate || !enabled()) {
            stop()
            progress = if (show) 1f else 0f
            view.visibility = if (show) View.VISIBLE else View.GONE
            apply()
            return
        }
        if (show) {
            if (view.visibility != View.VISIBLE) {
                progress = 0f
                view.visibility = View.VISIBLE
                apply()
            }
            if (view.width == 0 || view.height == 0) {
                // Not measured yet (first play): stay invisible until it is, then rise
                stop()
                view.alpha = 0f
                waitingForLayout = true
                view.addOnLayoutChangeListener(object : View.OnLayoutChangeListener {
                    override fun onLayoutChange(v: View, l: Int, t: Int, r: Int, b: Int, ol: Int, ot: Int, or: Int, ob: Int) {
                        if (v.width == 0 || v.height == 0) return
                        v.removeOnLayoutChangeListener(this)
                        if (!waitingForLayout) return
                        waitingForLayout = false
                        if (target == true) {
                            apply()
                            run(1f)
                        }
                    }
                })
                return
            }
            run(1f)
        } else {
            if (view.visibility != View.VISIBLE) {
                stop()
                progress = 0f
                return
            }
            if (waitingForLayout) {
                stop()
                progress = 0f
                view.visibility = View.GONE
                apply()
                return
            }
            run(0f)
        }
    }

    private fun stop() {
        waitingForLayout = false
        val a = anim
        anim = null
        a?.cancel()
    }

    private fun run(end: Float) {
        stop()
        val start = progress
        if (start == end) {
            finish(end)
            return
        }
        val ms = max(MIN_MS, ((if (end > start) IN_MS else OUT_MS) * abs(end - start)).toLong())
        val a = ValueAnimator.ofFloat(start, end)
        a.duration = ms
        a.interpolator = if (end > start) DecelerateInterpolator(1.6f) else AccelerateInterpolator(1.4f)
        a.addUpdateListener {
            progress = it.animatedValue as Float
            apply()
        }
        a.addListener(object : AnimatorListenerAdapter() {
            private var cancelled = false
            override fun onAnimationCancel(animation: Animator) {
                cancelled = true
            }

            override fun onAnimationEnd(animation: Animator) {
                if (cancelled || anim !== a) return
                anim = null
                finish(end)
            }
        })
        anim = a
        a.start()
    }

    private fun finish(end: Float) {
        progress = end
        if (end == 0f) view.visibility = View.GONE
        apply()
    }

    private fun apply() {
        val p = progress
        if (p >= 1f) {
            view.translationY = 0f
            view.alpha = 1f
            return
        }
        view.translationY = offset() * (1f - p)
        view.alpha = alphaAt(p)
    }

    companion object {
        const val IN_MS = 240L
        const val OUT_MS = 200L
        const val MIN_MS = 80L
        const val EXTRA_DP = 8f
        /** A slight fade: mostly there for most of the way, so the slide carries it. */
        fun alphaAt(p: Float): Float = (0.2f + 0.8f * p).coerceIn(0f, 1f)
    }
}
