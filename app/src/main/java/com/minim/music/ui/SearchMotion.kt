package com.minim.music.ui

/**
 * 5.5.0: the timing behind the search pill -> Search screen morph, free of views so it can be
 * tested. [t] runs 0..1 while opening; closing plays the same timeline backwards (1..0), so it's
 * the exact reverse.
 */
object SearchMotion {
    const val OPEN_MS = 340L
    const val CLOSE_MS = 300L
    /** The results below start fading in this long after the morph starts, one after another. */
    const val LIST_START_MS = 150L
    const val LIST_STEP_MS = 35L
    const val LIST_ITEM_MS = 220L
    /** At most this many rows cascade; the rest arrive with the last one. */
    const val LIST_MAX_STAGGER = 8

    /** 0..1 progress of [t] inside the window [start]..[end]. */
    fun window(t: Float, start: Float, end: Float): Float =
        if (end <= start) (if (t >= end) 1f else 0f) else ((t - start) / (end - start)).coerceIn(0f, 1f)

    /** How solid the Search screen's background is (the screen behind fades away under it). */
    fun backdrop(t: Float): Float = ease(window(t, 0f, 0.6f))

    /** How far the pill has stretched into the bar (its shape and the magnifier). */
    fun shape(t: Float): Float = ease(t.coerceIn(0f, 1f))

    /** The pill's filled look fading into the bar's outline. */
    fun fillToOutline(t: Float): Float = window(t, 0.2f, 0.85f)

    /** The source pill's own words and icon fading out as it starts to stretch. */
    fun sourceContent(t: Float): Float = 1f - window(t, 0f, 0.35f)

    /** The real search bar (and the "minim" logo) taking over from the stretched pill. */
    fun realBar(t: Float): Float = window(t, 0.7f, 1f)
    fun logo(t: Float): Float = window(t, 0.35f, 1f)

    /** The magnifier stays visible all the way, then hands over to the Search button it lands on. */
    fun magnifier(t: Float): Float = 1f - window(t, 0.85f, 1f)
    fun goButton(t: Float): Float = window(t, 0.75f, 1f)

    /** Start delay of row [index] below the bar. */
    fun listDelay(index: Int): Long = LIST_START_MS + LIST_STEP_MS * index.coerceIn(0, LIST_MAX_STAGGER)

    /** A rect between two rects (left, top, right, bottom). */
    fun lerpRect(a: FloatArray, b: FloatArray, t: Float): FloatArray =
        FloatArray(4) { i -> a[i] + (b[i] - a[i]) * t }

    /** Colour channels between two colours (ARGB). */
    fun lerpColor(a: Int, b: Int, t: Float): Int {
        val x = t.coerceIn(0f, 1f)
        fun ch(shift: Int): Int {
            val ca = (a ushr shift) and 0xFF
            val cb = (b ushr shift) and 0xFF
            return (ca + ((cb - ca) * x).toInt()) and 0xFF
        }
        return (ch(24) shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    /** Ease in and out (cubic). */
    fun ease(t: Float): Float {
        val x = t.coerceIn(0f, 1f)
        return if (x < 0.5f) 4f * x * x * x else 1f - (-2f * x + 2f).let { it * it * it } / 2f
    }
}
