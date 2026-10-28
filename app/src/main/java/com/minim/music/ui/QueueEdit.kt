package com.minim.music.ui

import kotlin.math.max

/**
 * 5.5.1: the rules behind editing Up Next, free of views so they can be tested.
 *
 * Up Next shows the playing song first, pinned (it can't be dragged, dropped on or removed),
 * then the songs after it. A "shown" position is a row in that list; a queue index is the
 * player's own position. Drops and removals are worked out against the queue as it is when the
 * finger lets go, so a song that ended mid-drag or a radio top-up can't move the wrong song.
 */
object QueueEdit {

    /** The first queue index shown: the playing song (or the start when nothing is playing). */
    fun offset(currentIndex: Int): Int = max(0, currentIndex)

    fun shownSize(queueSize: Int, currentIndex: Int): Int = (queueSize - offset(currentIndex)).coerceAtLeast(0)

    fun toQueueIndex(shownPos: Int, currentIndex: Int): Int = offset(currentIndex) + shownPos

    /** The playing song's row: always the first one, and it stays put. */
    fun isPinned(shownPos: Int, currentIndex: Int): Boolean = currentIndex >= 0 && shownPos == 0

    /** A row the finger can pick up, or drop another row onto. */
    fun isMovable(shownPos: Int, currentIndex: Int, shownSize: Int): Boolean =
        shownPos in 0 until shownSize && !isPinned(shownPos, currentIndex)

    /** The first queue index a song may be moved to (right after the playing song). */
    fun firstMovable(currentIndex: Int): Int = if (currentIndex >= 0) currentIndex + 1 else 0

    /** [list] with the item at [from] taken out and put back in at [to]. */
    fun <T> move(list: List<T>, from: Int, to: Int): List<T> {
        if (from !in list.indices || to !in list.indices || from == to) return list
        val out = list.toMutableList()
        out.add(to, out.removeAt(from))
        return out
    }

    /** Where the playing song sits after a move [from] -> [to] in the player's queue. */
    fun currentAfterMove(current: Int, from: Int, to: Int): Int = when {
        current < 0 -> current
        from == current -> to
        from < current && to >= current -> current - 1
        from > current && to <= current -> current + 1
        else -> current
    }

    /** Where the playing song sits after the song at [removed] is taken out. */
    fun currentAfterRemove(current: Int, removed: Int): Int =
        if (current >= 0 && removed < current) current - 1 else current

    /**
     * The player move (from, to) for a row with song [draggedId] that started at queue index
     * [fromHint] and was dropped at shown position [toShown], against the queue [ids] as it is
     * now. Null when there's nothing to do (dropped where it was, the song is gone, or it's the
     * playing song). The target never lands on or before the playing song.
     */
    fun resolveMove(ids: List<String>, currentIndex: Int, draggedId: String, fromHint: Int, toShown: Int): Pair<Int, Int>? {
        val from = locate(ids, currentIndex, draggedId, fromHint) ?: return null
        val first = firstMovable(currentIndex)
        if (first > ids.size - 1) return null
        val to = (offset(currentIndex) + toShown).coerceIn(first, ids.size - 1)
        return if (from == to) null else from to to
    }

    /** The queue index to remove for song [id] last seen at [hint]; never the playing song. */
    fun resolveRemove(ids: List<String>, currentIndex: Int, id: String, hint: Int): Int? =
        locate(ids, currentIndex, id, hint)

    /** [id] at [hint] when it's still there, else its nearest copy; never the playing song. */
    private fun locate(ids: List<String>, currentIndex: Int, id: String, hint: Int): Int? {
        if (ids.getOrNull(hint) == id && hint != currentIndex) return hint
        var best: Int? = null
        for (i in ids.indices) {
            if (ids[i] != id || i == currentIndex) continue
            if (best == null || kotlin.math.abs(i - hint) < kotlin.math.abs(best - hint)) best = i
        }
        return best
    }
}
