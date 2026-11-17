package com.minim.music.ui

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import java.time.LocalDate
import java.time.YearMonth

/**
 * 6.9.0 b76: the Weekly and Monthly Reprise, worked out ahead so they open at once.
 *
 * Building one reads the whole week or month (and Fermata's history for the insights), which took
 * seconds on the tap. They're now built in the background once they drop, and when Stats or the
 * Previews open, and kept here. A tap takes the kept one, or joins the build already running.
 *
 * A week or month that has ended is kept for the session ([doneTtlMs]); one still going (a preview
 * of this week or month) is kept for [liveTtlMs] so it doesn't go stale. Main thread only.
 */
class RepriseOpenCache<T : Any>(
    private val clock: () -> Long = { android.os.SystemClock.elapsedRealtime() },
    private val liveTtlMs: Long = 10 * 60_000L,
    private val doneTtlMs: Long = 6 * 60 * 60_000L,
    private val max: Int = 4
) {
    private class Entry<T>(val value: T, val at: Long, val done: Boolean)

    private val kept = LinkedHashMap<String, Entry<T>>()
    private val building = HashMap<String, Deferred<T?>>()

    /** The kept one for [key], if still fresh. */
    fun get(key: String): T? {
        val e = kept[key] ?: return null
        val ttl = if (e.done) doneTtlMs else liveTtlMs
        if (clock() - e.at > ttl) {
            kept.remove(key)
            return null
        }
        return e.value
    }

    fun put(key: String, value: T, done: Boolean) {
        kept.remove(key)
        kept[key] = Entry(value, clock(), done)
        while (kept.size > max) kept.remove(kept.keys.first())
    }

    /** The build running for [key], if any. */
    fun building(key: String): Deferred<T?>? = building[key]?.takeIf { it.isActive }

    /**
     * The kept one, else the running build, else a new build in [scope]. A build that returns null
     * (nothing to show yet) isn't kept.
     */
    fun obtain(scope: CoroutineScope, key: String, done: Boolean, build: suspend () -> T?): Deferred<T?> {
        get(key)?.let { v -> return kotlinx.coroutines.CompletableDeferred<T?>(v) }
        building(key)?.let { return it }
        val d = scope.async {
            val v = try { build() } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { null }
            if (v != null) put(key, v, done)
            v
        }
        building[key] = d
        d.invokeOnCompletion { if (building[key] === d) building.remove(key) }
        return d
    }

    fun clear() {
        kept.clear()
    }

    companion object {
        fun weekKey(start: LocalDate) = "W:$start"
        fun monthKey(ym: YearMonth) = "M:$ym"
    }
}
