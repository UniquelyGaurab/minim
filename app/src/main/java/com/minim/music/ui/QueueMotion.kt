package com.minim.music.ui

import kotlin.math.abs

/**
 * 5.5.1: the maths behind the Up Next pill <-> Up Next sheet morph, free of views so it can be
 * tested. [p] is how open the sheet is: 0 = the "Up Next" pill on Now Playing, 1 = the sheet.
 * A tap plays 0 -> 1; dragging the sheet down drives [p] by the finger and a release springs it
 * to 0 or 1 (the same one-third / flick rule as the mini player morph); Back plays 1 -> 0, the
 * exact reverse.
 */
object QueueMotion {
    const val OPEN_MS = 380L
    const val CLOSE_MS = 320L
    /** The sheet's corners while it travels (the pill's round ends grow into these, then square off). */
    const val SHEET_CORNER_DP = 28f
    /** How dark Now Playing gets under the open sheet. */
    const val DIM_MAX = 0.3f
    /** The songs fade in one after another, starting this long after the tap. */
    const val LIST_START_MS = 140L
    const val LIST_STEP_MS = 32L
    const val LIST_ITEM_MS = 220L
    /** At most this many rows cascade; the rest arrive with the last one. */
    const val LIST_MAX_STAGGER = 9

    /** 0..1 progress of [p] inside the window [start]..[end]. */
    fun window(p: Float, start: Float, end: Float): Float =
        if (end <= start) (if (p >= end) 1f else 0f) else ((p - start) / (end - start)).coerceIn(0f, 1f)

    /** The pill's filled colour turning into the sheet's background colour. */
    fun color(p: Float): Float = window(p, 0.05f, 0.6f)

    /** The pill's own "Up Next" label fading as it starts to stretch. */
    fun pillLabel(p: Float): Float = 1f - window(p, 0f, 0.25f)

    /** The sheet's title row and close button, a beat late. */
    fun header(p: Float): Float = window(p, 0.55f, 1f)

    /** The song list as a whole (closing: it fades as the sheet shrinks back into the pill). */
    fun content(p: Float): Float = window(p, 0.45f, 0.95f)

    /** Corner radius: the pill's round ends at 0, rounder sheet corners mid-way, square at 1. */
    fun corner(p: Float, pillRadius: Float, sheetRadius: Float): Float {
        val grow = MorphMotion.lerp(pillRadius, sheetRadius, window(p, 0f, 0.35f))
        return grow * (1f - window(p, 0.8f, 1f))
    }

    /** How dark Now Playing is under the sheet. */
    fun dim(p: Float): Float = DIM_MAX * p.coerceIn(0f, 1f)

    /** Start delay of song row [index] while the sheet opens. */
    fun listDelay(index: Int): Long = LIST_START_MS + LIST_STEP_MS * index.coerceIn(0, LIST_MAX_STAGGER)

    /** Progress while the finger drags the open sheet down by [dyPx] (positive = down). */
    fun dragProgress(dyPx: Float, travelPx: Float): Float = MorphMotion.dragProgress(1f, dyPx, travelPx)

    /** True when a release at [p] with speed [velocityDpPerS] (positive = down) should stay open. */
    fun settlesOpen(p: Float, velocityDpPerS: Float): Boolean =
        MorphMotion.settlesOpen(p, velocityDpPerS, startedOpen = true)

    /**
     * True when a finger that moved ([dx], [dy]) from where it went down should start dragging
     * the sheet closed: clearly downward, past the touch slop, and either on the title row or on
     * a list that is already scrolled to the top. Never while a song row is being dragged.
     */
    fun startsClose(dx: Float, dy: Float, slop: Float, onHeader: Boolean, listAtTop: Boolean, rowDragging: Boolean): Boolean {
        if (rowDragging) return false
        if (dy <= slop || dy <= abs(dx) * 1.5f) return false
        return onHeader || listAtTop
    }
}
