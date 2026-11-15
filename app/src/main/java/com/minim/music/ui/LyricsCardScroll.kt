package com.minim.music.ui

/**
 * The scrolling window of a 30s / 45s / 60s animated lyrics card (6.7.0 revision): like a small
 * lyrics screen inside the card. The picked lines show first; once a later line starts, the lines
 * move up smoothly so the line being sung sits near the top third, with the next ones under it.
 *
 * Plain maths over line positions and times, so the card and the tests agree.
 */
object LyricsCardScroll {
    /** How long one move takes. */
    const val SCROLL_MS = 500L
    /** Where the sung line's top settles, as a share of the window height. */
    const val ANCHOR = 0.28f
    /** Lines a window is sized for when the picked lines take less room. */
    const val MIN_VISIBLE_LINES = 4.5f

    /** The line being sung at [nowMs]: the last one that has started (the first before any has). */
    fun activeLine(starts: List<Long>, nowMs: Long): Int {
        var k = 0
        for (i in starts.indices) if (starts[i] <= nowMs) k = i
        return k
    }

    /**
     * Where the window rests while line [k] is sung. While a picked line is sung and all the
     * picked lines fit, it doesn't move; after that the sung line sits at [ANCHOR], never past the
     * end of the text.
     */
    fun target(k: Int, tops: FloatArray, totalH: Float, windowH: Float, pickedCount: Int, pickedH: Float): Float {
        if (tops.isEmpty()) return 0f
        if (k < pickedCount && pickedH <= windowH + 0.5f) return 0f
        val max = (totalH - windowH).coerceAtLeast(0f)
        return (tops[k.coerceIn(0, tops.lastIndex)] - ANCHOR * windowH).coerceIn(0f, max)
    }

    /** How far the text is scrolled at [nowMs]: easing from one resting place to the next. */
    fun offset(
        nowMs: Long,
        starts: List<Long>,
        tops: FloatArray,
        totalH: Float,
        windowH: Float,
        pickedCount: Int,
        pickedH: Float
    ): Float {
        if (starts.isEmpty() || tops.isEmpty()) return 0f
        val k = activeLine(starts, nowMs)
        val to = target(k, tops, totalH, windowH, pickedCount, pickedH)
        if (k == 0) return to
        val from = target(k - 1, tops, totalH, windowH, pickedCount, pickedH)
        val x = ((nowMs - starts[k]).toFloat() / SCROLL_MS).coerceIn(0f, 1f)
        return from + (to - from) * (x * x * (3 - 2 * x))
    }

    /** The lines that show (even in part) in a window [windowH] tall scrolled by [offset]. */
    fun visible(tops: FloatArray, bottoms: FloatArray, offset: Float, windowH: Float, slack: Float = 0f): IntRange {
        var a = -1
        var b = -1
        for (i in tops.indices) {
            if (bottoms[i] > offset - slack && tops[i] < offset + windowH + slack) {
                if (a < 0) a = i
                b = i
            }
        }
        return if (a < 0) IntRange.EMPTY else a..b
    }

    /**
     * The window's height: room for the picked lines, at least [MIN_VISIBLE_LINES] lines of
     * [lineSlot] each, never more than [maxH].
     */
    fun windowHeight(pickedH: Float, lineSlot: Float, maxH: Float): Float =
        maxOf(pickedH, MIN_VISIBLE_LINES * lineSlot - (lineSlot - lineSlot / (1f + LyricsCardSpacing.LINE_GAP)))
            .coerceAtMost(maxH)
}
