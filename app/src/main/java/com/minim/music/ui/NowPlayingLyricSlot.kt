package com.minim.music.ui

import com.minim.music.data.model.LyricsData

/**
 * v4.9.8: the line slot above the title on Now Playing.
 *   LOADING -> empty, tapping does nothing (lyrics are still being fetched)
 *   NONE    -> a faint "Lyrics not available", tapping does nothing
 *   LINES   -> the active synced line (empty between lines or for plain lyrics); a tap opens
 *              the full screen lyrics (MainActivity.openFullscreenLyrics, filled in by part 3)
 */
object NowPlayingLyricSlot {

    enum class Mode { LOADING, NONE, LINES }

    /** Opacity of the "Lyrics not available" text (in the current text colour). */
    const val UNAVAILABLE_ALPHA = 0.35f

    /** Text size of the "Lyrics not available" text; real lines auto-size between 18 and 22 sp. */
    const val UNAVAILABLE_TEXT_SP = 15

    fun mode(data: LyricsData?): Mode = when {
        data == null -> Mode.LOADING
        data.lines.none { it.text.isNotBlank() } -> Mode.NONE
        else -> Mode.LINES
    }

    fun isTappable(mode: Mode): Boolean = mode == Mode.LINES

    // ------------------------------------------------------------------------------------------
    // v4.9.9 part 1: narrower lines, Flow and the line change animation
    // ------------------------------------------------------------------------------------------

    /** Lines never come closer to the screen edge than this... */
    const val SIDE_MIN_DP = 48f
    /** ...and never get wider than this (the full screen mode: 36 dp / 560 dp at 27 sp). */
    const val LINE_MAX_DP = 400f

    /** How far a line slides up when it changes. */
    const val SLIDE_DP = 10f
    /** The new line slides up into place and fades in over this long (ease-out)... */
    const val SLIDE_IN_MS = 250L
    /** ...while the old one slides up and fades out a little quicker, so they barely overlap. */
    const val SLIDE_OUT_MS = 200L
    /** A seek or a song change: the old line fades out and the new one in, in place. */
    const val SWAP_MS = 180L
    /** Flow keeps drawing this long after a line change while paused, while its row fades in. */
    const val SETTLE_MS = SLIDE_IN_MS + 80L

    /** Side padding for a slot [widthPx] wide: centred lines, at most [maxLinePx] wide. */
    fun sidePadding(widthPx: Int, minSidePx: Int, maxLinePx: Int): Int =
        maxOf(minSidePx, (widthPx - maxLinePx) / 2)

    /** How the slot moves from one line to the next. */
    enum class Change { INSTANT, SLIDE, SWAP }

    /**
     * The change for a new line: a seek (the Flow clock saw a jump) or a song change swaps in place,
     * the song moving on to its next line slides, and nothing animates when animations are off
     * (Remove animations), the caller asked for none or nobody can see the slot.
     */
    fun change(jump: Boolean, animate: Boolean, canAnimate: Boolean): Change = when {
        !animate || !canAnimate -> Change.INSTANT
        jump -> Change.SWAP
        else -> Change.SLIDE
    }

    /**
     * While Flow runs and plays, it moves to the next line itself, a little before the player's
     * ~4 per second tick says so. That tick's (older) line is not a step back: ignore it. Real
     * seeks back come in as clock jumps instead.
     */
    fun ignoreTick(index: Int, active: Int, flowRunning: Boolean, playing: Boolean): Boolean =
        index < active && flowRunning && playing
}
