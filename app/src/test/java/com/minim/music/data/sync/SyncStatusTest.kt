package com.minim.music.data.sync

import com.minim.music.data.sync.CloudSync.Phase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Minim 5.0.0 part 2c: the Settings sync line and when it redraws (pure). */
class SyncStatusTest {

    private val now = 1_800_000_000_000L
    private val min = 60_000L
    private val hour = 60 * min
    private val day = 24 * hour

    @Test
    fun synced_line_reads_naturally() {
        assertEquals("Synced just now", SyncText.statusLine(Phase.IDLE, now - 20_000L, now))
        assertEquals("Synced 1 min ago", SyncText.statusLine(Phase.IDLE, now - min - 5_000L, now))
        assertEquals("Synced 59 min ago", SyncText.statusLine(Phase.IDLE, now - 59 * min, now))
        assertEquals("Synced 1 h ago", SyncText.statusLine(Phase.IDLE, now - hour, now))
        assertEquals("Synced 3 d ago", SyncText.statusLine(Phase.IDLE, now - 3 * day - hour, now))
        // a clock that went backwards never shows a negative time
        assertEquals("Synced just now", SyncText.statusLine(Phase.IDLE, now + 5 * min, now))
    }

    @Test
    fun each_phase_has_its_line() {
        assertEquals("Sync on", SyncText.statusLine(Phase.OFF, 0L, now))
        assertEquals("Sync on", SyncText.statusLine(Phase.IDLE, 0L, now))
        assertEquals("Syncing your music…", SyncText.statusLine(Phase.FIRST_SYNC, 0L, now))
        assertEquals("Syncing…", SyncText.statusLine(Phase.SYNCING, 0L, now))
        assertTrue(SyncText.statusLine(Phase.NEEDS_CHOICE, 0L, now).contains("Tap to choose"))
    }

    @Test
    fun a_running_sync_keeps_the_last_synced_line() {
        // no "Syncing…" flash every time a like goes up
        assertEquals("Synced 4 min ago", SyncText.statusLine(Phase.SYNCING, now - 4 * min, now))
    }

    @Test
    fun redraws_when_the_line_would_change() {
        assertEquals(40_000L, SyncText.nextChangeMs(now, now - 20_000L))
        assertEquals(min, SyncText.nextChangeMs(now, now))
        assertEquals(30_000L, SyncText.nextChangeMs(now, now - 5 * min - 30_000L))
        // over an hour: the next whole hour
        assertEquals(50 * min, SyncText.nextChangeMs(now, now - hour - 10 * min))
        // over a day: capped at an hour, so a long wait still wakes up now and then
        assertEquals(hour, SyncText.nextChangeMs(now, now - 2 * day))
        // never synced: check again in a minute
        assertEquals(min, SyncText.nextChangeMs(now, 0L))
        // the line it predicts really changes at that moment
        val last = now - 7 * min - 12_345L
        val wait = SyncText.nextChangeMs(now, last)
        assertEquals("Synced 7 min ago", SyncText.syncedLine(now + wait - 1, last))
        assertEquals("Synced 8 min ago", SyncText.syncedLine(now + wait, last))
    }

    @Test
    fun problems_have_short_lines() {
        assertNull(SyncText.problemLine(null))
        for (p in SyncProblem.values()) {
            val line = SyncText.problemLine(p)!!
            assertTrue(line, line.length <= 80)
        }
        assertEquals(SyncProblem.OFFLINE, SyncText.classify("UNAVAILABLE"))
        assertEquals(SyncProblem.PERMISSION, SyncText.classify("PERMISSION_DENIED"))
    }
}
