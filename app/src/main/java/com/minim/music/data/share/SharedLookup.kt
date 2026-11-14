package com.minim.music.data.share

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 6.6.0: a v2 playlist link carries only video ids, so the "Shared with you" pop-up looks each song up
 * (title and artist) before Play and Save. A few at a time, each with a time limit, in link order.
 *
 * Pure Kotlin with the lookup passed in, so the offline / slow / unavailable cases run in unit tests.
 */
object SharedLookup {

    sealed class One {
        data class Found(val song: ShareLinks.Song) : One()
        /** YouTube says it's gone, private or blocked: left out of Play and Save. */
        object Unavailable : One()
        /** No answer (offline, slow, an error): worth a Try again. */
        object Failed : One()
    }

    const val PARALLEL = 4
    const val TIMEOUT_MS = 20_000L

    /**
     * Fills the empty and [One.Failed] places of [results] (one per id in [ids]) by calling [lookup],
     * at most [parallel] at once, each given [timeoutMs]. [onEach] runs after each answer, in the
     * caller's context, so the UI can update a row. Never throws, except for cancellation.
     */
    suspend fun fill(
        ids: List<String>,
        results: Array<One?>,
        parallel: Int = PARALLEL,
        timeoutMs: Long = TIMEOUT_MS,
        lookup: suspend (String) -> One,
        onEach: (Int, One) -> Unit = { _, _ -> }
    ) {
        require(results.size == ids.size)
        val gate = Semaphore(parallel.coerceAtLeast(1))
        coroutineScope {
            ids.indices.filter { results[it] == null || results[it] is One.Failed }.map { i ->
                async {
                    gate.withPermit {
                        val r = try {
                            withTimeoutOrNull(timeoutMs) { lookup(ids[i]) } ?: One.Failed
                        } catch (c: kotlinx.coroutines.CancellationException) {
                            throw c
                        } catch (_: Throwable) {
                            One.Failed
                        }
                        results[i] = r
                        onEach(i, r)
                    }
                }
            }.awaitAll()
        }
    }

    /** Where a lookup stands: the songs found (in order), and how many are gone or still unanswered. */
    data class Summary(val found: List<ShareLinks.Song>, val unavailable: Int, val failed: Int, val pending: Int) {
        val done: Boolean get() = pending == 0
        /** Play and Save can go: everything answered, nothing to retry, at least one song. */
        val ready: Boolean get() = done && failed == 0 && found.isNotEmpty()
    }

    fun summary(results: Array<One?>): Summary {
        var un = 0
        var failed = 0
        var pending = 0
        val found = ArrayList<ShareLinks.Song>()
        for (r in results) when (r) {
            is One.Found -> found.add(r.song)
            One.Unavailable -> un++
            One.Failed -> failed++
            null -> pending++
        }
        return Summary(found, un, failed, pending)
    }
}
