package com.minim.music.ui

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 6.9.0 (74): runs one load per key at a time, for the Stats pills. While a song plays, Stats
 * refreshes every 5 seconds. Build 73 cancelled the pill's load in flight on every refresh and
 * started it again, so on a slower phone a load never finished and the hero, Top 5s and number
 * boxes stayed blank. Now asking again for the key that is already loading only queues one more
 * pass after it lands; only a different key (another pill) cancels it. Call from one thread
 * (the main thread in the app).
 */
class LatestLoad<K>(
    private val scope: CoroutineScope,
    private val onError: (Exception) -> Unit = {},
    private val run: suspend (K) -> Unit
) {
    private var job: Job? = null
    private var key: K? = null
    private var again = false

    /** True while a load is running. */
    val busy: Boolean get() = job?.isActive == true

    fun request(k: K) {
        val running = job
        if (running != null && running.isActive && key == k) {
            again = true
            return
        }
        running?.cancel()
        key = k
        again = false
        job = scope.launch {
            do {
                again = false
                try {
                    run(k)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    onError(e)
                }
            } while (again && isActive)
        }
    }

    fun cancel() {
        job?.cancel()
        job = null
        again = false
    }
}
