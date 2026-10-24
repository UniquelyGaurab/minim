package com.minim.music.util

import com.minim.music.util.AppForegroundTracker.AppOpen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppForegroundTrackerTest {

    private var now = 10_000L
    private val t = AppForegroundTracker(clock = { now })

    @Test
    fun firstStartIsAColdOpenHandedOutOnce() {
        t.onActivityStarted()
        assertEquals(AppOpen(coldStart = true, awayMs = 0L), t.takeOpen())
        assertNull(t.takeOpen())
        assertTrue(t.isInForeground)
    }

    @Test
    fun comingBackIsAnOpenWithTheTimeAway() {
        t.onActivityStarted(); t.takeOpen()
        t.onActivityStopped(changingConfigurations = false)
        assertFalse(t.isInForeground)
        now += 5_000L
        t.onActivityStarted()
        assertEquals(AppOpen(coldStart = false, awayMs = 5_000L), t.takeOpen())
    }

    @Test
    fun aNewActivityInTheSameProcessIsStillAnOpen() {
        // 4.9.7's bug: MainActivity closed (swiped away) while playback kept the process alive
        t.onActivityStarted(); t.takeOpen()
        t.onActivityStopped(changingConfigurations = false) // old activity stops, then is destroyed
        now += 60_000L
        t.onActivityStarted() // a brand new MainActivity
        assertEquals(AppOpen(coldStart = false, awayMs = 60_000L), t.takeOpen())
    }

    @Test
    fun recreatingForAConfigChangeIsNotAnOpen() {
        t.onActivityStarted(); t.takeOpen()
        t.onActivityStopped(changingConfigurations = true)
        t.onActivityStarted()
        assertNull(t.takeOpen())
        // ...and a real trip to the background afterwards still counts
        t.onActivityStopped(changingConfigurations = false)
        now += 1_000L
        t.onActivityStarted()
        assertEquals(AppOpen(false, 1_000L), t.takeOpen())
    }

    @Test
    fun switchingBetweenOwnActivitiesIsNotAnOpen() {
        t.onActivityStarted(); t.takeOpen()
        t.onActivityStarted() // second activity starts before the first stops
        t.onActivityStopped(false)
        assertNull(t.takeOpen())
        assertTrue(t.isInForeground)
    }
}
