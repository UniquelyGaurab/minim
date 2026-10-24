package com.minim.music.data.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateCheckScheduleTest {

    private val min = 60_000L
    private val hour = 60 * min
    private val s = UpdateCheckSchedule()

    @Test
    fun coldStartAlwaysChecks() {
        assertTrue(s.shouldCheck(0L, coldStart = true))
        s.onCheckStarted(0L); s.onCheckFinished(1L, ok = true)
        assertTrue(s.shouldCheck(2L, coldStart = true))
    }

    @Test
    fun firstForegroundWithNoCheckYetChecks() {
        assertTrue(s.shouldCheck(5 * hour, coldStart = false))
    }

    @Test
    fun foregroundChecksAreThrottledToEveryFewHours() {
        s.onCheckStarted(0L); s.onCheckFinished(1_000L, ok = true)
        assertFalse(s.shouldCheck(10 * min, coldStart = false))
        assertFalse(s.shouldCheck(2 * hour + 59 * min, coldStart = false))
        assertTrue(s.shouldCheck(3 * hour + 1_000L, coldStart = false))
    }

    @Test
    fun failedChecksRetrySooner() {
        s.onCheckStarted(0L); s.onCheckFinished(1_000L, ok = false)
        assertFalse(s.shouldCheck(10 * min, coldStart = false))
        assertTrue(s.shouldCheck(15 * min, coldStart = false))
        // Fails after a success too
        s.onCheckStarted(15 * min); s.onCheckFinished(15 * min, ok = true)
        s.onCheckStarted(4 * hour); s.onCheckFinished(4 * hour, ok = false)
        assertFalse(s.shouldCheck(4 * hour + 5 * min, coldStart = false))
        assertTrue(s.shouldCheck(4 * hour + 15 * min, coldStart = false))
    }

    @Test
    fun neverTwoAtOnce() {
        s.onCheckStarted(0L)
        assertFalse(s.shouldCheck(5 * hour, coldStart = false))
        assertFalse(s.shouldCheck(5 * hour, coldStart = true))
    }

    @Test
    fun cancelledCheckDoesNotCount() {
        s.onCheckStarted(0L); s.onCheckFinished(0L, ok = true)
        s.onCheckStarted(4 * hour)
        s.onCheckCancelled()
        // Not throttled by the cancelled attempt
        assertTrue(s.shouldCheck(4 * hour + 1, coldStart = false))
    }

    @Test
    fun settingsCheckCountsAsAGoodCheck() {
        s.onCheckFinished(hour, ok = true) // manual, no onCheckStarted
        assertFalse(s.shouldCheck(2 * hour, coldStart = false))
        assertTrue(s.shouldCheck(4 * hour, coldStart = false))
    }

    @Test
    fun failedResultsAreNotOk() {
        assertFalse(UpdateCheckSchedule.isOk(UpdateCheckResult.Failed("x")))
        assertTrue(UpdateCheckSchedule.isOk(UpdateCheckResult.NoRelease))
        assertTrue(UpdateCheckSchedule.isOk(UpdateCheckResult.UpToDate("v4.9.7")))
    }
}
