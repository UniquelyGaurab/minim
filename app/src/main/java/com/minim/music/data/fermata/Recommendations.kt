package com.minim.music.data.fermata

import com.minim.fermata.FermataRecs
import com.minim.fermata.log.TrackRef
import com.minim.fermata.recs.ArtistPick
import com.minim.fermata.recs.Pick
import com.minim.music.data.model.Track
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * 6.1: Fermata's recommendations for the radio and Discover.
 *
 * Nothing here blocks the caller except [awaitReady], which only background code calls. Until
 * Fermata's taste model is built (once per process, on [executor], started by [warmUpAsync]),
 * and whenever there is too little history (cold start), every call gives back what Minim does
 * without Fermata: the radio keeps YouTube's order, Discover builds its shelves as before.
 *
 * [recs] is null when Fermata did not start.
 */
class Recommendations(
    private val recs: () -> FermataRecs?,
    private val executor: Executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "fermata-recs").apply { isDaemon = true; priority = Thread.NORM_PRIORITY - 1 }
    },
) {
    /** The warm-up in progress (counted down when it ends), or null. */
    private val inFlight = AtomicReference<CountDownLatch?>(null)

    /** Builds (or rebuilds, after a merge) the taste model on the background thread, if needed. */
    fun warmUpAsync() {
        val r = recs() ?: return
        if (!r.needsWarmUp()) return
        val latch = CountDownLatch(1)
        if (!inFlight.compareAndSet(null, latch)) return
        try {
            executor.execute {
                try { r.warmUp() } catch (t: Throwable) { android.util.Log.w(TAG, "warm up", t) } finally {
                    inFlight.set(null)
                    latch.countDown()
                }
            }
        } catch (t: Throwable) {
            inFlight.set(null)
            latch.countDown()
        }
    }

    /**
     * Background threads only: starts the warm-up if needed and waits up to [timeoutMs] for it.
     * Returns Fermata's recommendations when they are ready and past cold start, else null.
     */
    fun awaitReady(timeoutMs: Long): FermataRecs? {
        val r = recs() ?: return null
        if (r.state() == FermataRecs.State.NOT_READY) {
            warmUpAsync()
            try { inFlight.get()?.await(timeoutMs, TimeUnit.MILLISECONDS) } catch (_: InterruptedException) { }
        }
        return r.takeIf { it.state() == FermataRecs.State.READY }
    }

    /** Ready and past cold start, without waiting (a rebuild after a merge is started, if needed). */
    fun ready(): FermataRecs? {
        val r = recs() ?: return null
        if (r.needsWarmUp()) warmUpAsync()
        return r.takeIf { it.state() == FermataRecs.State.READY }
    }

    /**
     * A radio batch (YouTube's order, already free of queued, seen and "not interested" songs)
     * reordered by taste: liked songs and artists higher, songs skipped early lower, songs skipped
     * early twice in a row and songs heard in the last few hours left out. Gives [pool] back
     * unchanged when Fermata is not ready, in cold start, or if ranking would leave nothing.
     */
    fun reorderRadio(pool: List<Track>): List<Track> {
        if (pool.isEmpty()) return pool
        val r = ready() ?: return pool
        val out = try { r.radio(pool.map { it.toRef() }) } catch (t: Throwable) { null } ?: return pool
        return backToTracks(out.tracks.map { it.id }, pool).ifEmpty { pool }
    }

    /** A shelf's songs reordered by taste, skipped-out songs left out; [pool] unchanged when not ready. */
    fun reorderShelf(pool: List<Track>, r: FermataRecs? = ready()): List<Track> {
        if (r == null || pool.isEmpty()) return pool
        val out = try { r.rank(pool.map { it.toRef() }) } catch (t: Throwable) { null } ?: return pool
        return backToTracks(out.tracks.map { it.id }, pool)
    }

    companion object {
        private const val TAG = "Recommendations"

        fun Track.toRef(): TrackRef = TrackRef(id, title, artist, durationSec.coerceAtLeast(0L) * 1000, thumbnailUrl, sourceUrl)

        /** A Fermata pick as a Minim track: the library's copy when there is one (artwork, source), else built from the pick. */
        fun Pick.toTrack(known: Map<String, Track>): Track = known[track.id] ?: track.toTrack()

        fun TrackRef.toTrack(): Track = Track(
            id = id, title = title, artist = artist, durationSec = durationMs / 1000,
            thumbnailUrl = thumbnailUrl.ifBlank { "https://i.ytimg.com/vi/$id/hqdefault.jpg" },
            sourceUrl = sourceUrl.ifBlank { "https://www.youtube.com/watch?v=$id" },
        )

        /** Seeds for "Because you like X" as Minim tracks. */
        fun ArtistPick.seedTracks(known: Map<String, Track>): List<Track> = seeds.map { known[it.id] ?: it.toTrack() }

        private fun backToTracks(ids: List<String>, pool: List<Track>): List<Track> {
            val byId = pool.associateBy { it.id }
            return ids.mapNotNull { byId[it] }
        }
    }
}
