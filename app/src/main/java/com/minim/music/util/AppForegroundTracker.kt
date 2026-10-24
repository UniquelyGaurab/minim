package com.minim.music.util

/**
 * v4.9.8: when the whole app (not one activity) comes to the front. Pure; fed by MinimApp's activity
 * lifecycle callbacks, so it's unit-tested.
 *
 * Why: 4.9.6/4.9.7 decided "this is an app open" from flags on MainActivity. A music app's process
 * outlives its activity (playback keeps it alive after Minim is swiped away from recents, or after
 * Android closes the screen to save memory), and a new MainActivity in that process never counted as
 * an open, so the update check didn't run at all. Here the count of started activities going from 0
 * to 1 is the open, wherever it happens. A rotation or theme change (old activity stops while it's
 * being recreated) is not an open.
 */
class AppForegroundTracker(private val clock: () -> Long) {

    /** One time the app came to the front. [awayMs] is how long it was in the background (0 on a cold start). */
    data class AppOpen(val coldStart: Boolean, val awayMs: Long)

    private var started = 0
    private var everStarted = false
    private var recreating = false
    private var backgroundSince = 0L
    private var open: AppOpen? = null

    val isInForeground: Boolean get() = started > 0

    fun onActivityStarted() {
        if (started == 0) {
            if (recreating) {
                recreating = false
            } else {
                open = if (!everStarted) AppOpen(coldStart = true, awayMs = 0L)
                else AppOpen(coldStart = false, awayMs = (clock() - backgroundSince).coerceAtLeast(0L))
                everStarted = true
            }
        }
        started++
    }

    fun onActivityStopped(changingConfigurations: Boolean) {
        if (started > 0) started--
        if (started == 0) {
            if (changingConfigurations) {
                recreating = true
            } else {
                recreating = false
                backgroundSince = clock()
            }
        }
    }

    /** The latest open not handled yet (each open is handed out once). */
    fun takeOpen(): AppOpen? = open.also { open = null }
}
