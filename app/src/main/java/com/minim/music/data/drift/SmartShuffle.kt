package com.minim.music.data.drift

import com.minim.music.data.model.Track
import kotlin.random.Random

/**
 * 7.1.0 Smart Shuffle: the playlist's own songs in a smarter random order. It never adds a song
 * and never drops one: the result is exactly the input, reordered.
 *
 * - Songs you haven't heard in a while (or ever) tend to come sooner.
 * - The same artist isn't clumped: no two in a row while anyone else is left.
 * - A light nudge from Fermata's rank, so favourites aren't all saved for the end.
 * It stays a shuffle: a random part keeps every play different.
 */
object SmartShuffle {
    private const val DAY_MS = 24L * 60 * 60 * 1000
    /** Unheard for this long counts as "a while" in full. */
    private const val STALE_DAYS = 30.0
    private const val W_STALE = 0.6
    private const val W_RANK = 0.15
    private const val W_RANDOM = 0.5

    /** 0 (heard just now) .. 1 (a month ago or more, or never). */
    fun staleness(lastPlayedAt: Long?, now: Long): Double {
        if (lastPlayedAt == null || lastPlayedAt <= 0L) return 1.0
        val days = (now - lastPlayedAt).coerceAtLeast(0L).toDouble() / DAY_MS
        return (days / STALE_DAYS).coerceIn(0.0, 1.0)
    }

    /**
     * [tracks] reordered. [lastPlayed] maps song id to when you last heard it; [rank] is Fermata's
     * 0..1 rank per id (empty when Fermata isn't ready: no nudge).
     */
    fun order(
        tracks: List<Track>,
        lastPlayed: Map<String, Long>,
        rank: Map<String, Double> = emptyMap(),
        now: Long = System.currentTimeMillis(),
        random: Random = Random.Default,
    ): List<Track> {
        if (tracks.size < 2) return tracks.toList()
        val keyed = tracks.map { t ->
            val k = staleness(lastPlayed[t.id], now) * W_STALE + (rank[t.id] ?: 0.0) * W_RANK + random.nextDouble() * W_RANDOM
            t to k
        }.sortedByDescending { it.second }.map { it.first }
        return spaceArtists(keyed)
    }

    /**
     * Keeps [ordered]'s order but never puts the same artist twice in a row while another artist
     * is still waiting: each turn takes the first remaining song by someone else. Nothing is added
     * or dropped.
     */
    fun spaceArtists(ordered: List<Track>): List<Track> {
        // Artist keys worked out once; counts kept up to date as songs are taken
        val left = ordered.mapTo(ArrayList(ordered.size)) { it to DriftEngine.artistKey(it.artist) }
        val counts = HashMap<String, Int>()
        for ((_, a) in left) counts[a] = (counts[a] ?: 0) + 1
        val out = ArrayList<Track>(ordered.size)
        var last: String? = null
        while (left.isNotEmpty()) {
            // The artist with the most songs left goes first when it would otherwise clump at the end
            var topArtist = ""
            var topCount = 0
            for ((a, n) in counts) if (n > topCount) { topArtist = a; topCount = n }
            val mustTake = topCount * 2 > left.size && topArtist != last
            val i = when {
                mustTake -> left.indexOfFirst { it.second == topArtist }
                else -> left.indexOfFirst { it.second != last }.takeIf { it >= 0 } ?: 0
            }
            val (t, a) = left.removeAt(i)
            out.add(t)
            val n = (counts[a] ?: 1) - 1
            if (n <= 0) counts.remove(a) else counts[a] = n
            last = a
        }
        return out
    }
}
