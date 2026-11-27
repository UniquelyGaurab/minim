package com.minim.music.data.drift

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.minim.music.data.fermata.Recommendations
import com.minim.music.data.fermata.Recommendations.Companion.toRef
import com.minim.music.data.local.LibraryDatabase
import com.minim.music.data.model.SourceResult
import com.minim.music.data.model.Track
import com.minim.music.data.offline.OfflineStore
import com.minim.music.data.source.MusicSourceAdapter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Instant
import java.time.ZoneId

/**
 * 7.1.0 Drift: gathers the songs Drift can choose from. Everything is read and ranked here, on a
 * background thread, once per session (plus a few related lists as it goes); the picks themselves
 * are then pure in-memory work in [DriftEngine].
 *
 * - Familiar: your library (played or liked songs), and songs saved offline.
 * - New: YouTube Music's related songs for a few of your favourites and for songs you lean into
 *   during the session, the same source the radio uses.
 * Fermata ranks both on the phone; songs Fermata leaves out (skipped early again and again,
 * not interested) stay out.
 */
class DriftPool(
    private val context: Context,
    private val database: LibraryDatabase,
    private val source: MusicSourceAdapter,
    private val recs: Recommendations,
    private val offline: () -> OfflineStore?,
) {
    data class Library(val candidates: List<DriftCandidate>, val hours: HourAffinity, val savedOffline: Set<String>)

    fun isOnline(): Boolean = try {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
        caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    } catch (_: Exception) {
        true
    }

    /** Your own songs as candidates, Fermata-ranked, plus the hour-of-day picture. */
    suspend fun library(now: Long = System.currentTimeMillis()): Library = withContext(Dispatchers.Default) {
        val stats = database.getAllTrackStats()
        val saved = offline()?.saved?.value.orEmpty()
        val savedIds = saved.mapTo(HashSet()) { it.track.id }
        val byId = LinkedHashMap<String, DriftCandidate>()
        for (s in stats) {
            if (!DriftEngine.isSong(s.track)) continue
            if (s.playCount <= 0 && !s.isLiked && s.track.id !in savedIds) continue
            byId[s.track.id] = DriftCandidate(
                s.track, familiar = true,
                taste = DriftEngine.localTaste(s.playCount, s.isLiked, s.lastPlayedAt, now),
                lastPlayedAt = s.lastPlayedAt,
            )
        }
        for (o in saved) {
            if (o.track.id in byId || !DriftEngine.isSong(o.track)) continue
            byId[o.track.id] = DriftCandidate(o.track, familiar = true, taste = 0.2)
        }
        // The best few hundred (plays and hearts first) go to Fermata; the rest would rarely be picked anyway
        val top = byId.values.sortedByDescending { it.taste }.take(MAX_FAMILIAR)
        val ranked = fermataRank(top)
        val hours = try {
            val zone = ZoneId.systemDefault()
            val plays = database.getPlayEvents(now - HISTORY_MS, HISTORY_LIMIT)
            HourAffinity.build(plays.map { it.track.artist to Instant.ofEpochMilli(it.playedAt).atZone(zone).hour })
        } catch (_: Exception) {
            HourAffinity.EMPTY
        }
        Library(ranked, hours, savedIds)
    }

    /**
     * New finds related to [seeds] (YouTube Music related, like the radio), Fermata-ranked.
     * Songs in [known] are left out; at most a few seconds, then whatever arrived.
     */
    suspend fun related(seeds: List<Track>, known: Set<String>): List<DriftCandidate> = withContext(Dispatchers.IO) {
        if (seeds.isEmpty()) return@withContext emptyList()
        val lists = withTimeoutOrNull(RELATED_TIMEOUT_MS) {
            coroutineScope {
                seeds.map { seed ->
                    async {
                        val r = try { source.fetchRadio(seed.id) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { null }
                        seed to ((r as? SourceResult.Success)?.data.orEmpty())
                    }
                }.awaitAll()
            }
        }.orEmpty()
        val out = LinkedHashMap<String, DriftCandidate>()
        for ((seed, list) in lists) {
            val n = list.size.coerceAtLeast(1)
            list.forEachIndexed { i, t ->
                if (t.id == seed.id || t.id in known || t.id in out || !DriftEngine.isSong(t)) return@forEachIndexed
                // Earlier in YouTube's related list = closer to the seed
                out[t.id] = DriftCandidate(t, familiar = false, taste = 0.6 * (1.0 - i.toDouble() / n), origin = seed.id)
            }
        }
        withContext(Dispatchers.Default) { fermataRank(out.values.toList()) }
    }

    /** Fermata's rank blended into each taste; songs Fermata drops are left out. Unchanged when it isn't ready. */
    private fun fermataRank(list: List<DriftCandidate>): List<DriftCandidate> {
        if (list.isEmpty()) return list
        val r = recs.ready() ?: return list
        val out = try { r.rank(list.map { it.track.toRef() }) } catch (_: Throwable) { null } ?: return list
        val scores = DriftEngine.rankScores(out.tracks.map { it.id })
        val dropped = out.dropped.mapTo(HashSet()) { it.track.id }
        return list.filter { it.track.id !in dropped }.map { it.copy(taste = DriftEngine.blend(it.taste, scores[it.track.id])) }
    }

    companion object {
        const val MAX_FAMILIAR = 600
        const val HISTORY_MS = 60L * 24 * 60 * 60 * 1000
        const val HISTORY_LIMIT = 3000
        const val RELATED_TIMEOUT_MS = 8_000L
    }
}
