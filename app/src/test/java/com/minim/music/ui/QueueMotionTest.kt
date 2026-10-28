package com.minim.music.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 5.5.1: the Up Next pill <-> sheet morph: drag progress, snap and flick, fades and corners. */
class QueueMotionTest {
    private val eps = 0.0001f

    @Test fun dragDownClosesByTheFinger() {
        assertEquals(1f, QueueMotion.dragProgress(0f, 800f), eps)
        assertEquals(0.5f, QueueMotion.dragProgress(400f, 800f), eps)
        assertEquals(0f, QueueMotion.dragProgress(800f, 800f), eps)
        assertEquals(0f, QueueMotion.dragProgress(2000f, 800f), eps)
        // Dragging up past open stays open
        assertEquals(1f, QueueMotion.dragProgress(-200f, 800f), eps)
    }

    @Test fun releaseSnapsAtOneThird() {
        // Dragged less than a third of the way down: springs back open
        assertTrue(QueueMotion.settlesOpen(0.7f, 0f))
        // More than a third: springs closed into the pill
        assertFalse(QueueMotion.settlesOpen(0.6f, 0f))
    }

    @Test fun flickDownClosesEvenFromAShortDrag() {
        assertFalse(QueueMotion.settlesOpen(0.9f, 1500f))
    }

    @Test fun flickUpKeepsItOpenEvenFromALongDrag() {
        assertTrue(QueueMotion.settlesOpen(0.2f, -1500f))
    }

    @Test fun startsCloseOnlyOnAClearDownwardDrag() {
        // On the title row: always
        assertTrue(QueueMotion.startsClose(2f, 30f, 10f, onHeader = true, listAtTop = false, rowDragging = false))
        // On the list: only when it's scrolled to the top
        assertTrue(QueueMotion.startsClose(2f, 30f, 10f, onHeader = false, listAtTop = true, rowDragging = false))
        assertFalse(QueueMotion.startsClose(2f, 30f, 10f, onHeader = false, listAtTop = false, rowDragging = false))
        // Not under the slop, not sideways, not upward
        assertFalse(QueueMotion.startsClose(0f, 8f, 10f, true, true, false))
        assertFalse(QueueMotion.startsClose(30f, 30f, 10f, true, true, false))
        assertFalse(QueueMotion.startsClose(0f, -30f, 10f, true, true, false))
        // Never while a song row is held by its handle
        assertFalse(QueueMotion.startsClose(0f, 60f, 10f, true, true, rowDragging = true))
    }

    @Test fun pillLabelGoesFirstThenTheSheetFadesIn() {
        assertEquals(1f, QueueMotion.pillLabel(0f), eps)
        assertEquals(0f, QueueMotion.pillLabel(0.25f), eps)
        assertEquals(0f, QueueMotion.header(0.5f), eps)
        assertEquals(1f, QueueMotion.header(1f), eps)
        assertEquals(0f, QueueMotion.content(0.4f), eps)
        assertEquals(1f, QueueMotion.content(1f), eps)
        assertEquals(0f, QueueMotion.color(0f), eps)
        assertEquals(1f, QueueMotion.color(0.6f), eps)
    }

    @Test fun cornersStartAsThePillAndEndSquare() {
        assertEquals(15f, QueueMotion.corner(0f, 15f, 28f), eps)
        assertEquals(28f, QueueMotion.corner(0.5f, 15f, 28f), eps)
        assertEquals(0f, QueueMotion.corner(1f, 15f, 28f), eps)
    }

    @Test fun nowPlayingDimsWithTheSheet() {
        assertEquals(0f, QueueMotion.dim(0f), eps)
        assertEquals(QueueMotion.DIM_MAX, QueueMotion.dim(1f), eps)
        assertEquals(QueueMotion.DIM_MAX, QueueMotion.dim(3f), eps)
    }

    @Test fun songsFadeInOneAfterAnother() {
        assertEquals(QueueMotion.LIST_START_MS, QueueMotion.listDelay(0))
        assertTrue(QueueMotion.listDelay(1) > QueueMotion.listDelay(0))
        assertEquals(QueueMotion.listDelay(QueueMotion.LIST_MAX_STAGGER), QueueMotion.listDelay(50))
    }
}
