package com.minim.music.ui

import com.minim.music.data.stats.StatsFormat
import kotlin.math.roundToLong

/**
 * v4.9.9 part 3: the pure maths behind the list entrance, the press scale and the Stats count up,
 * kept free of Android views so they can be unit tested.
 */
object RowEntranceMath {

    /** How far each row rises into place, in dp. */
    const val SHIFT_DP = 14f

    /** One row's rise and fade. */
    const val ITEM_MS = 240L

    /** The gap between one row starting and the next. */
    const val STAGGER_MS = 25L

    /** Only the first rows are staggered; the rest start with the last of them. */
    const val MAX_STAGGERED = 10

    /** When the row [rank] places after the first visible one starts. */
    fun delayFor(rank: Int): Long = STAGGER_MS * rank.coerceIn(0, MAX_STAGGERED - 1)

    /** The whole entrance, however many rows there are. */
    val TOTAL_MS: Long = delayFor(MAX_STAGGERED - 1) + ITEM_MS

    /** The row's progress at [elapsedMs] into the entrance, 0 (hidden, lowered) to 1 (in place), eased. */
    fun progress(elapsedMs: Long, rank: Int): Float {
        val t = ((elapsedMs - delayFor(rank)).toFloat() / ITEM_MS).coerceIn(0f, 1f)
        return easeOut(t)
    }

    /** Cubic ease-out: quick start, soft landing, never past 1. */
    fun easeOut(t: Float): Float {
        val u = 1f - t.coerceIn(0f, 1f)
        return 1f - u * u * u
    }
}

/** v4.9.9 part 3: the press on pills and circle buttons. */
object PressScaleMath {
    const val PRESSED_SCALE = 0.96f
    const val DOWN_MS = 100L
    const val UP_MS = 140L

    /** Pills are 999dp-cornered rectangles; cards (16dp) and small art (8dp) are not. */
    fun isPillCorner(cornerRadiusPx: Float, density: Float): Boolean = cornerRadiusPx >= 100f * density
}

/** v4.9.9 part 3: the Stats numbers counting up when the tab opens. */
object CountUpMath {

    /** The count from zero when the Stats tab opens. */
    const val DURATION_MS = 700L

    /** A new value arriving while a count is still running glides there from where it is. */
    const val RETARGET_MS = 300L

    fun ease(t: Float): Float = RowEntranceMath.easeOut(t)

    /** The number shown at [t] (0..1, not yet eased) of a count from [from] to [to]. */
    fun valueAt(from: Long, to: Long, t: Float): Long {
        if (t >= 1f) return to
        if (t <= 0f) return from
        return from + ((to - from) * ease(t)).roundToLong()
    }

    /** A plain count: "0", "7", "12". */
    fun countText(from: Long, to: Long, t: Float): String = valueAt(from, to, t).toString()

    /**
     * Listened time counting up: whole minutes, written the way the card writes them ("12m",
     * "1h 23m"), so the format never changes mid count. Under a minute it counts seconds ("45s").
     * It ends on exactly the card's own text.
     */
    fun durationText(fromMs: Long, toMs: Long, t: Float): String {
        if (t >= 1f) return StatsFormat.duration(toMs)
        val ms = valueAt(fromMs, toMs, t).coerceAtLeast(0L)
        if (toMs < 60_000L) return "${ms / 1000L}s"
        val minutes = ms / 60_000L
        return if (minutes == 0L) "0m" else StatsFormat.duration(minutes * 60_000L)
    }
}

/** v4.9.9 part 3: when the Up next list animates its changes, and when it just redraws. */
object QueueDiffRules {

    /**
     * True when [new] is basically a different queue from [old] (a new album, a new radio, the
     * first fill): animating that would fade dozens of rows, so it simply redraws.
     * A move, a removal, a few songs added or the current song changing stay animated.
     */
    fun isWholesale(old: List<String>, new: List<String>): Boolean {
        if (old.isEmpty() || new.isEmpty()) return true
        val oldIds = old.toHashSet()
        val shared = new.count { it in oldIds }
        return shared * 2 < new.size || shared * 2 < old.size
    }

    /** A row redraws when its song, its highlight or one of its two arrows changes. */
    fun sameContents(
        oldId: String, oldPos: Int, oldActive: Int, oldSize: Int,
        newId: String, newPos: Int, newActive: Int, newSize: Int
    ): Boolean = oldId == newId &&
        (oldPos == oldActive) == (newPos == newActive) &&
        (oldPos > 0) == (newPos > 0) &&
        (oldPos < oldSize - 1) == (newPos < newSize - 1)
}
