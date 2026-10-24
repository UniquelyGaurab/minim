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
}
