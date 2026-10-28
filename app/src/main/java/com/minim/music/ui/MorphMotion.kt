package com.minim.music.ui

import kotlin.math.abs

/**
 * 5.5.0: the maths behind the mini player <-> Now Playing morph, free of views so it can be
 * tested. [p] is how open Now Playing is: 0 = the mini player pill, 1 = Now Playing. The finger
 * drives [p] directly; a release springs it to 0 or 1; a tap plays 0 -> 1; closing is the same
 * timeline backwards, so it is the exact reverse.
 */
object MorphMotion {
    /** Tap to open / the back pill or Back to close, played automatically. */
    const val OPEN_MS = 420L
    const val CLOSE_MS = 360L
    /** The shortest a release may take to settle. */
    const val SETTLE_MIN_MS = 150L
    /** Release past this much of the way and it carries on; otherwise it springs back. */
    const val SNAP_FRACTION = 1f / 3f
    /** A flick this fast (dp per second) decides on its own, even from a short swipe. */
    const val FLICK_DP_PER_S = 900f
    /** How dark the screen underneath gets once Now Playing is fully open over it. */
    const val DIM_MAX = 0.45f

    /** Progress after the finger moved [dyPx] (negative = up) from where the drag started at [startP]. */
    fun dragProgress(startP: Float, dyPx: Float, travelPx: Float): Float {
        if (travelPx <= 0f) return startP.coerceIn(0f, 1f)
        return (startP - dyPx / travelPx).coerceIn(0f, 1f)
    }

    /**
     * True when a release at [p] with vertical speed [velocityDpPerS] (negative = up) should end
     * open. [startedOpen] = the drag began on Now Playing (closing) rather than on the pill.
     */
    fun settlesOpen(p: Float, velocityDpPerS: Float, startedOpen: Boolean): Boolean {
        if (velocityDpPerS <= -FLICK_DP_PER_S) return true
        if (velocityDpPerS >= FLICK_DP_PER_S) return false
        val moved = if (startedOpen) 1f - p else p
        val passed = moved >= SNAP_FRACTION
        return if (startedOpen) !passed else passed
    }

    /**
     * How long the spring to [to] takes from [from]: a full trip takes [fullMs]; a fast release
     * toward the target ([speedPerS] in progress per second, positive = toward it) gets there sooner.
     */
    fun settleMs(from: Float, to: Float, speedPerS: Float, fullMs: Long): Long {
        val dist = abs(to - from)
        if (dist <= 0f) return 0L
        var ms = fullMs * dist
        if (speedPerS > 0f) ms = minOf(ms, dist / speedPerS * 1000f * 2.2f)
        return ms.toLong().coerceIn(SETTLE_MIN_MS, fullMs)
    }

    /** 0..1 progress of [p] inside the window [start]..[end]. */
    fun window(p: Float, start: Float, end: Float): Float =
        if (end <= start) (if (p >= end) 1f else 0f) else ((p - start) / (end - start)).coerceIn(0f, 1f)

    /** The pill's black turning into the album colours. */
    fun color(p: Float): Float = window(p, 0.05f, 0.75f)

    /** The pill's own buttons (play, next) fading out as it starts to stretch. */
    fun pillButtons(p: Float): Float = 1f - window(p, 0f, 0.2f)

    /** Staggered fades: the back pill and "You're vibing to", then the lyric line, then the
     *  seek bar and big controls, then the pills at the bottom, each a beat later. */
    fun header(p: Float): Float = window(p, 0.5f, 1f)
    fun lyric(p: Float): Float = window(p, 0.55f, 1f)
    fun controls(p: Float): Float = window(p, 0.6f, 1f)
    fun pills(p: Float): Float = window(p, 0.7f, 1f)

    /** How dark the screen underneath is: lighter as Now Playing is dragged down. */
    fun dim(p: Float): Float = DIM_MAX * p.coerceIn(0f, 1f)

    /** The container's corners: the pill's round ends at 0, square at 1. */
    fun corner(p: Float, pillRadius: Float): Float = pillRadius * (1f - p.coerceIn(0f, 1f))

    /** The cover's corner radius on screen: the small round cover's to the big card's. */
    fun coverRadius(p: Float, smallRadius: Float, bigRadius: Float): Float = lerp(smallRadius, bigRadius, p)

    /** The radius to give a view scaled by [scale] so it shows [visualRadius] on screen. */
    fun radiusForScale(visualRadius: Float, scale: Float): Float = if (scale <= 0f) visualRadius else visualRadius / scale

    fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t

    /** A rect between two rects (left, top, right, bottom). */
    fun lerpRect(a: FloatArray, b: FloatArray, t: Float): FloatArray = FloatArray(4) { i -> a[i] + (b[i] - a[i]) * t }

    /** True when a value moving from [before] to [after] has just locked fully open or closed. */
    fun locked(before: Float, after: Float): Boolean = before != after && (after <= 0f || after >= 1f)
}
