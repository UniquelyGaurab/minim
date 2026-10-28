package com.minim.music.ui

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * 5.5.0: the pure maths and rules behind the like (heart) animation, kept free of Android views
 * so they can be unit tested. The views and drawables are in HeartFx.kt.
 */
object HeartMotion {

    /** Scale while the finger is down on the heart. */
    const val PRESS_SCALE = 0.85f
    /** The spring's peak after a like. */
    const val PEAK_SCALE = 1.2f
    /** The slight shrink while a like drains away. */
    const val UNLIKE_DIP = 0.9f

    const val PRESS_MS = 90L
    const val LIKE_SPRING_MS = 420L
    const val INK_FILL_MS = 260L
    const val UNLIKE_MS = 240L
    const val BURST_MS = 300L
    const val BURST_DOTS = 6
    const val BIG_HEART_MS = 850L

    /** What a tap on the heart does: it always flips. */
    fun afterTap(liked: Boolean): Boolean = !liked

    /**
     * What a double tap on the cover does: it only ever likes. Double tapping a liked song again
     * keeps it liked (the big heart still shows, nothing is unliked).
     */
    fun afterDoubleTap(liked: Boolean): Boolean = true

    /** True when a double tap actually changes the song (so the small heart fills and bursts). */
    fun doubleTapChanges(liked: Boolean): Boolean = afterDoubleTap(liked) != liked

    /**
     * Heart scale through the like spring, [t] 0..1: from where the press left it up to
     * [PEAK_SCALE], a small dip under 1 and settled at exactly 1.
     */
    fun likeScale(t: Float, from: Float = PRESS_SCALE): Float {
        val x = t.coerceIn(0f, 1f)
        return when {
            x < 0.35f -> lerp(from, PEAK_SCALE, easeOut(x / 0.35f))
            x < 0.7f -> lerp(PEAK_SCALE, 0.96f, easeInOut((x - 0.35f) / 0.35f))
            x < 1f -> lerp(0.96f, 1f, easeOut((x - 0.7f) / 0.3f))
            else -> 1f
        }
    }

    /** Heart scale while unliking: a slight shrink and back to 1. */
    fun unlikeScale(t: Float, from: Float = PRESS_SCALE): Float {
        val x = t.coerceIn(0f, 1f)
        return when {
            x < 0.4f -> lerp(from, UNLIKE_DIP, easeOut(x / 0.4f))
            x < 1f -> lerp(UNLIKE_DIP, 1f, easeInOut((x - 0.4f) / 0.6f))
            else -> 1f
        }
    }

    /**
     * The ink circle's radius for a fill level (0 = outline only, 1 = filled), as a fraction of
     * the radius that covers the whole heart. Eased so the ink spreads quickly then slows.
     */
    fun inkRadius(fill: Float): Float = easeOut(fill.coerceIn(0f, 1f))

    /** One burst dot at [t] 0..1: where it is (dx, dy from the heart centre), its radius and alpha. */
    data class Dot(val dx: Float, val dy: Float, val radius: Float, val alpha: Float)

    /**
     * Dot [i] of [BURST_DOTS], flying out from [startR] to [endR] (px) and shrinking from
     * [dotR] to nothing while it fades. The ring is turned a little so no dot points straight up.
     */
    fun burstDot(i: Int, t: Float, startR: Float, endR: Float, dotR: Float, count: Int = BURST_DOTS): Dot {
        val x = t.coerceIn(0f, 1f)
        val angle = (2.0 * PI * i / count) - PI / 2 + PI / count
        val r = lerp(startR, endR, easeOut(x))
        val size = dotR * (1f - x * x)
        val alpha = if (x < 0.5f) 1f else 1f - (x - 0.5f) / 0.5f
        return Dot((cos(angle) * r).toFloat(), (sin(angle) * r).toFloat(), size.coerceAtLeast(0f), alpha.coerceIn(0f, 1f))
    }

    /** The big heart over the cover at [t] 0..1: scale, alpha and how far it has floated up (0..1). */
    data class Big(val scale: Float, val alpha: Float, val rise: Float)

    fun bigHeart(t: Float): Big {
        val x = t.coerceIn(0f, 1f)
        val scale = when {
            x < 0.25f -> lerp(0.4f, 1.1f, easeOut(x / 0.25f))
            x < 0.4f -> lerp(1.1f, 1f, easeInOut((x - 0.25f) / 0.15f))
            else -> 1f
        }
        val alpha = when {
            x < 0.2f -> x / 0.2f
            x < 0.55f -> 1f
            else -> 1f - (x - 0.55f) / 0.45f
        }
        val rise = if (x < 0.45f) 0f else easeIn((x - 0.45f) / 0.55f)
        return Big(scale, alpha.coerceIn(0f, 1f), rise)
    }

    fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t
    fun easeOut(t: Float): Float = 1f - (1f - t) * (1f - t)
    fun easeIn(t: Float): Float = t * t
    fun easeInOut(t: Float): Float = if (t < 0.5f) 2f * t * t else 1f - 2f * (1f - t) * (1f - t)
}
