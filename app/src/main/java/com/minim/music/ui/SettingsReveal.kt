package com.minim.music.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ValueAnimator
import android.graphics.Rect
import android.graphics.drawable.BitmapDrawable
import android.view.View
import android.view.ViewAnimationUtils
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.animation.PathInterpolator
import androidx.core.view.doOnPreDraw
import androidx.core.view.drawToBitmap
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 5.5.1: the round profile picture in the top bar grows into Settings, and Back shrinks Settings
 * back into it.
 *
 * - Settings is revealed by a circle that starts as the avatar (same centre, same size) and grows
 *   until it covers the screen: a RenderThread clip, so it stays smooth at any refresh rate.
 * - A copy of the avatar sits on top at first and swells and fades as the circle takes over
 *   (closing, it comes back as the circle lands).
 * - The settings rows on screen fade up one after another.
 */
class SettingsReveal(
    private val screen: ViewGroup,
    private val content: ViewGroup,
    /** Draws the avatar copy above everything (the activity's root). */
    private val host: ViewGroup
) {
    private val density = screen.resources.displayMetrics.density
    private var anim: Animator? = null
    private var source: View? = null
    private var ghost: BitmapDrawable? = null
    private val ghostRect = Rect()
    private val rowAnims = ArrayList<View>()

    /** The circle is growing out of the avatar. */
    var isOpening = false
        private set

    /** The circle is shrinking back into the avatar. */
    var isClosing = false
        private set

    val isRunning get() = isOpening || isClosing

    /** Can land back on the avatar it came from. */
    val canClose get() = source?.let { it.isShown && it.width > 0 } == true && screen.isAttachedToWindow

    /**
     * Grows Settings out of [avatar]. The screen must already be VISIBLE; [onEnd] runs once it
     * fills the screen. False when the avatar isn't on screen (Settings just shows, as before).
     */
    fun open(avatar: View, onEnd: () -> Unit): Boolean {
        if (!avatar.isShown || avatar.width == 0 || !screen.isAttachedToWindow) return false
        abort()
        source = avatar
        isOpening = true
        // Hidden until its first frame, when the circle starts at the avatar's own size
        screen.alpha = 0f
        screen.doOnPreDraw {
            if (!isOpening || source !== avatar) return@doOnPreDraw
            screen.alpha = 1f
            val c = center(avatar)
            val small = avatar.width * avatar.scaleX / 2f
            val big = farthest(c[0], c[1])
            val reveal = runCatching {
                ViewAnimationUtils.createCircularReveal(screen, c[0].roundToInt(), c[1].roundToInt(), small, big)
            }.getOrNull()
            if (reveal == null) {
                finishOpen(onEnd)
                return@doOnPreDraw
            }
            reveal.interpolator = PathInterpolator(0.3f, 0f, 0f, 1f)
            reveal.duration = OPEN_MS
            val fade = ghostAnimator(avatar, opening = true)
            val set = AnimatorSet().apply { playTogether(listOfNotNull(reveal, fade)) }
            set.addListener(object : AnimatorListenerAdapter() {
                private var cancelled = false
                override fun onAnimationCancel(animation: Animator) { cancelled = true }
                override fun onAnimationEnd(animation: Animator) {
                    if (anim !== animation) return
                    anim = null
                    clearGhost()
                    if (!cancelled) finishOpen(onEnd)
                }
            })
            anim = set
            set.start()
            cascade()
        }
        return true
    }

    /** Shrinks Settings back into the avatar; [onEnd] hides it. False when there's no avatar. */
    fun close(onEnd: () -> Unit): Boolean {
        val avatar = source
        if (avatar == null || !canClose || screen.width == 0) return false
        stop()
        endCascade()
        screen.alpha = 1f
        isOpening = false
        isClosing = true
        val c = center(avatar)
        val small = avatar.width * avatar.scaleX / 2f
        val big = farthest(c[0], c[1])
        val reveal = runCatching {
            ViewAnimationUtils.createCircularReveal(screen, c[0].roundToInt(), c[1].roundToInt(), big, small)
        }.getOrNull() ?: run {
            isClosing = false
            return false
        }
        reveal.interpolator = PathInterpolator(0.4f, 0f, 0.2f, 1f)
        reveal.duration = CLOSE_MS
        val fade = ghostAnimator(avatar, opening = false)
        val set = AnimatorSet().apply { playTogether(listOfNotNull(reveal, fade)) }
        set.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                if (anim !== animation) return
                anim = null
                isClosing = false
                // Hidden first, then the copy goes, so nothing flashes for a frame
                onEnd()
                clearGhost()
                source = null
            }
        })
        anim = set
        set.start()
        return true
    }

    /** Drops any reveal at once: Settings is shown or hidden by the caller as it is. */
    fun abort() {
        stop()
        endCascade()
        clearGhost()
        screen.alpha = 1f
        source = null
        isOpening = false
        isClosing = false
    }

    private fun stop() {
        val a = anim
        anim = null
        a?.cancel()
    }

    private fun finishOpen(onEnd: () -> Unit) {
        isOpening = false
        onEnd()
    }

    private val a = IntArray(2)
    private val b = IntArray(2)

    /** The avatar's centre in the screen's own coordinates. */
    private fun center(v: View): FloatArray {
        v.getLocationInWindow(a)
        screen.getLocationInWindow(b)
        return floatArrayOf(a[0] - b[0] + v.width * v.scaleX / 2f, a[1] - b[1] + v.height * v.scaleY / 2f)
    }

    private fun farthest(cx: Float, cy: Float): Float {
        val w = max(screen.width, host.width).toFloat()
        val h = max(screen.height, host.height).toFloat()
        return max(max(hypot(cx, cy), hypot(w - cx, cy)), max(hypot(cx, h - cy), hypot(w - cx, h - cy)))
    }

    /** The avatar copy: swells and fades as the circle grows (and the reverse as it lands). */
    private fun ghostAnimator(avatar: View, opening: Boolean): Animator? {
        clearGhost()
        val bmp = runCatching { avatar.drawToBitmap() }.getOrNull() ?: return null
        val d = BitmapDrawable(screen.resources, bmp)
        avatar.getLocationInWindow(a)
        host.getLocationInWindow(b)
        val w = (avatar.width * avatar.scaleX).roundToInt()
        val h = (avatar.height * avatar.scaleY).roundToInt()
        val l = a[0] - b[0]
        val t = a[1] - b[1]
        ghostRect.set(l, t, l + w, t + h)
        ghost = d
        host.overlay.add(d)
        val setAt = { f: Float ->
            // f: 0 = the avatar as it is, 1 = swollen and gone
            val s = 1f + (GHOST_SCALE - 1f) * f
            val cx = ghostRect.exactCenterX()
            val cy = ghostRect.exactCenterY()
            val hw = ghostRect.width() * s / 2f
            val hh = ghostRect.height() * s / 2f
            d.setBounds((cx - hw).roundToInt(), (cy - hh).roundToInt(), (cx + hw).roundToInt(), (cy + hh).roundToInt())
            d.alpha = (255 * (1f - f)).roundToInt().coerceIn(0, 255)
        }
        setAt(if (opening) 0f else 1f)
        return ValueAnimator.ofFloat(0f, 1f).apply {
            duration = ((if (opening) OPEN_MS else CLOSE_MS) * GHOST_SHARE).toLong()
            if (!opening) startDelay = (CLOSE_MS * (1f - GHOST_SHARE)).toLong()
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                val v = it.animatedValue as Float
                setAt(if (opening) v else 1f - v)
            }
        }
    }

    private fun clearGhost() {
        ghost?.let { host.overlay.remove(it) }
        ghost = null
    }

    /** The settings rows on screen fade up one after another. */
    private fun cascade() {
        endCascade()
        val shift = 14f * density
        val bottom = screen.scrollY + screen.height
        var i = 0
        for (k in 0 until content.childCount) {
            val v = content.getChildAt(k)
            if (v.visibility != View.VISIBLE) continue
            if (v.top > bottom) break
            if (v.bottom < screen.scrollY) continue
            rowAnims.add(v)
            v.alpha = 0f
            v.translationY = shift
            v.animate().alpha(1f).translationY(0f)
                .setStartDelay(ROW_START_MS + ROW_STEP_MS * i.coerceAtMost(ROW_MAX_STAGGER))
                .setDuration(ROW_MS)
                .setInterpolator(DecelerateInterpolator())
                .withEndAction { rowAnims.remove(v) }
                .start()
            i++
        }
    }

    private fun endCascade() {
        for (v in rowAnims.toList()) {
            v.animate().cancel()
            v.alpha = 1f
            v.translationY = 0f
        }
        rowAnims.clear()
    }

    companion object {
        const val OPEN_MS = 420L
        const val CLOSE_MS = 340L
        /** How big the avatar copy swells as it fades. */
        const val GHOST_SCALE = 1.6f
        /** The part of the reveal the avatar copy takes to fade (the first part opening, the last closing). */
        const val GHOST_SHARE = 0.45f
        const val ROW_START_MS = 100L
        const val ROW_STEP_MS = 28L
        const val ROW_MS = 240L
        const val ROW_MAX_STAGGER = 12
    }
}
