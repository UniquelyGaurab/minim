package com.minim.music.ui

/**
 * 5.5.0: the pure maths behind Now Playing's seek bar, kept free of views so it can be tested.
 */
object SeekMotion {
    /** Grabbed, the bar is this many times its resting thickness. */
    const val GRAB_THICKNESS = 3f
    const val GROW_MS = 260L
    const val SHRINK_MS = 200L
    /** A tap slides the fill to the spot over this long instead of teleporting. */
    const val TAP_SLIDE_MS = 240L
    /** After a seek, ignore older positions that still arrive for this long. */
    const val SEEK_HOLD_MS = 1200L

    /** Finger x (px) on a bar running from [left] to [right] (px) as 0..1. */
    fun fractionAt(x: Float, left: Float, right: Float): Float {
        if (right <= left) return 0f
        return ((x - left) / (right - left)).coerceIn(0f, 1f)
    }

    /** The song time for a fraction of [durationMs]. */
    fun targetMs(fraction: Float, durationMs: Long): Long =
        if (durationMs <= 0L) 0L else (fraction.coerceIn(0f, 1f) * durationMs).toLong().coerceIn(0L, durationMs)

    /** The time pill's text, like "2:47" (or "1:02:05" past an hour). */
    fun label(ms: Long): String {
        val total = (ms.coerceAtLeast(0L)) / 1000L
        val h = total / 3600L
        val m = (total % 3600L) / 60L
        val s = total % 60L
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }

    /** True when the move went past the touch slop, so it's a drag and not a tap. */
    fun isDrag(downX: Float, x: Float, slop: Float): Boolean = kotlin.math.abs(x - downX) > slop

    /**
     * The edge the finger is on: -1 at 0:00, 1 at the very end, 0 in between. The light tick plays
     * when this changes to -1 or 1 while dragging.
     */
    fun edge(fraction: Float): Int = when {
        fraction <= 0f -> -1
        fraction >= 1f -> 1
        else -> 0
    }

    /** True when moving from edge [before] to [now] should tick (arriving at either end). */
    fun edgeTicks(before: Int, now: Int): Boolean = now != 0 && now != before

    /**
     * The fill while playing, worked out every frame from the last reported position, so it
     * glides instead of jumping once a second. Paused it stays where it was reported.
     */
    fun liveFraction(basePositionMs: Long, durationMs: Long, sinceBaseMs: Long, playing: Boolean): Float {
        if (durationMs <= 0L) return 0f
        val pos = basePositionMs + if (playing) sinceBaseMs.coerceAtLeast(0L) else 0L
        return (pos.toFloat() / durationMs).coerceIn(0f, 1f)
    }

    /** Thickness for grow 0 (slim) .. 1 (grabbed). */
    fun thickness(rest: Float, grow: Float): Float = rest * (1f + (GRAB_THICKNESS - 1f) * grow)

    /**
     * True when a reported position should be shown while a seek to [targetMs] is settling: once
     * it lands near the target, or once the hold has run out.
     */
    fun acceptsPosition(reportedMs: Long, targetMs: Long, sinceSeekMs: Long): Boolean =
        sinceSeekMs >= SEEK_HOLD_MS || kotlin.math.abs(reportedMs - targetMs) <= 1500L

    /** The seek dip's volume factor as it goes down before the jump ([ms] since it started). */
    fun dipOut(ms: Long): Float {
        val t = (ms.toFloat() / DIP_OUT_MS).coerceIn(0f, 1f)
        return 1f + (DIP_FLOOR - 1f) * t
    }

    /** The seek dip's volume factor as it comes back at the new spot ([ms] since it started rising). */
    fun dipIn(ms: Long): Float {
        val t = (ms.toFloat() / DIP_IN_MS).coerceIn(0f, 1f)
        val e = 1f - (1f - t) * (1f - t)
        return DIP_FLOOR + (1f - DIP_FLOOR) * e
    }

    const val DIP_OUT_MS = 90L
    const val DIP_IN_MS = 260L
    const val DIP_FLOOR = 0.12f
    /** Never wait longer than this for the new spot to be ready before coming back up. */
    const val DIP_MAX_HOLD_MS = 700L
}
