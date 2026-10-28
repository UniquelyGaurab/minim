package com.minim.music.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 5.5.1: Up Next editing: the pinned playing song, moves, removals and the indices after them. */
class QueueEditTest {

    // Queue: two played songs, then the playing one (index 2), then four to come
    private val ids = listOf("p1", "p2", "now", "a", "b", "c", "d")
    private val cur = 2

    @Test fun listStartsAtThePlayingSong() {
        assertEquals(2, QueueEdit.offset(cur))
        assertEquals(5, QueueEdit.shownSize(ids.size, cur))
        assertEquals(2, QueueEdit.toQueueIndex(0, cur))
        assertEquals(5, QueueEdit.toQueueIndex(3, cur))
    }

    @Test fun nothingPlayingShowsTheWholeQueueAndNothingIsPinned() {
        assertEquals(0, QueueEdit.offset(-1))
        assertEquals(7, QueueEdit.shownSize(ids.size, -1))
        assertFalse(QueueEdit.isPinned(0, -1))
        assertTrue(QueueEdit.isMovable(0, -1, 7))
        assertEquals(0, QueueEdit.firstMovable(-1))
    }

    @Test fun playingSongIsPinnedAndCantBeMovedOrDroppedOn() {
        assertTrue(QueueEdit.isPinned(0, cur))
        assertFalse(QueueEdit.isMovable(0, cur, 5))
        assertTrue(QueueEdit.isMovable(1, cur, 5))
        assertTrue(QueueEdit.isMovable(4, cur, 5))
        assertFalse(QueueEdit.isMovable(5, cur, 5))
        assertFalse(QueueEdit.isMovable(-1, cur, 5))
    }

    @Test fun moveTakesOutAndPutsBack() {
        assertEquals(listOf("b", "c", "a"), QueueEdit.move(listOf("a", "b", "c"), 0, 2))
        assertEquals(listOf("c", "a", "b"), QueueEdit.move(listOf("a", "b", "c"), 2, 0))
        assertEquals(listOf("a", "b", "c"), QueueEdit.move(listOf("a", "b", "c"), 1, 1))
        assertEquals(listOf("a", "b", "c"), QueueEdit.move(listOf("a", "b", "c"), 0, 9))
    }

    @Test fun dropResolvesToPlayerIndices() {
        // "d" (shown 4, queue 6) dragged to the first spot after the playing song (shown 1)
        assertEquals(6 to 3, QueueEdit.resolveMove(ids, cur, "d", 6, 1))
        // "a" dragged to the bottom
        assertEquals(3 to 6, QueueEdit.resolveMove(ids, cur, "a", 3, 4))
        val after = QueueEdit.move(ids, 3, 6)
        assertEquals(listOf("p1", "p2", "now", "b", "c", "d", "a"), after)
    }

    @Test fun dropNeverLandsOnOrAboveThePlayingSong() {
        assertEquals(5 to 3, QueueEdit.resolveMove(ids, cur, "c", 5, 0))
        assertEquals(5 to 3, QueueEdit.resolveMove(ids, cur, "c", 5, -3))
        assertEquals(3 to 6, QueueEdit.resolveMove(ids, cur, "a", 3, 40))
    }

    @Test fun playingSongItselfNeverMoves() {
        assertNull(QueueEdit.resolveMove(ids, cur, "now", 2, 3))
        assertNull(QueueEdit.resolveRemove(ids, cur, "now", 2))
    }

    @Test fun droppedWhereItWasDoesNothing() {
        assertNull(QueueEdit.resolveMove(ids, cur, "b", 4, 2))
    }

    @Test fun songEndedMidDragStillMovesTheRightSong() {
        // The drag started with "now" playing; "a" started meanwhile (current 3)
        val r = QueueEdit.resolveMove(ids, 3, "d", 6, 1)
        assertEquals(6 to 4, r)
    }

    @Test fun radioTrimMidDragFindsTheSongByName() {
        // Two played songs were trimmed from the front while dragging: everything shifted by 2
        val trimmed = ids.drop(2)
        assertEquals(4 to 1, QueueEdit.resolveMove(trimmed, 0, "d", 6, 1))
    }

    @Test fun goneSongIsNotMoved() {
        assertNull(QueueEdit.resolveMove(ids, cur, "zzz", 4, 1))
        assertNull(QueueEdit.resolveRemove(ids, cur, "zzz", 4))
    }

    @Test fun removeUsesTheHintOrTheNearestCopy() {
        assertEquals(4, QueueEdit.resolveRemove(ids, cur, "b", 4))
        assertEquals(4, QueueEdit.resolveRemove(ids, cur, "b", 6))
        val dup = listOf("x", "now", "y", "x", "y")
        assertEquals(4, QueueEdit.resolveRemove(dup, 1, "y", 4))
        assertEquals(3, QueueEdit.resolveRemove(dup, 1, "x", 3))
    }

    @Test fun onlySongAfterPlayingHasNowhereToGo() {
        assertNull(QueueEdit.resolveMove(listOf("now", "a"), 0, "a", 1, 1))
        assertNull(QueueEdit.resolveMove(listOf("now"), 0, "now", 0, 0))
    }

    @Test fun playingIndexAfterMoves() {
        assertEquals(2, QueueEdit.currentAfterMove(2, 3, 6))
        assertEquals(2, QueueEdit.currentAfterMove(2, 6, 3))
        assertEquals(1, QueueEdit.currentAfterMove(2, 0, 4))
        assertEquals(3, QueueEdit.currentAfterMove(2, 5, 1))
        assertEquals(5, QueueEdit.currentAfterMove(2, 2, 5))
        assertEquals(-1, QueueEdit.currentAfterMove(-1, 0, 3))
    }

    @Test fun playingIndexAfterRemovals() {
        assertEquals(2, QueueEdit.currentAfterRemove(2, 4))
        assertEquals(1, QueueEdit.currentAfterRemove(2, 0))
        assertEquals(-1, QueueEdit.currentAfterRemove(-1, 0))
    }

    @Test fun nextSongAfterReorderAndRemoveIsTheNewNext() {
        // Move "c" right after the playing song, then remove "a": next is "c", then "b", "d"
        var q = ids
        var c = cur
        val (f1, t1) = QueueEdit.resolveMove(q, c, "c", 5, 1)!!
        c = QueueEdit.currentAfterMove(c, f1, t1)
        q = QueueEdit.move(q, f1, t1)
        val r = QueueEdit.resolveRemove(q, c, "a", 4)!!
        c = QueueEdit.currentAfterRemove(c, r)
        q = q.toMutableList().apply { removeAt(r) }
        assertEquals("now", q[c])
        assertEquals(listOf("c", "b", "d"), q.drop(c + 1))
        // Previous still goes back to the played songs
        assertEquals("p2", q[c - 1])
    }
}
