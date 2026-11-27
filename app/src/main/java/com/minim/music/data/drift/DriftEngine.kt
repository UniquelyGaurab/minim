package com.minim.music.data.drift

import com.minim.music.data.model.Track
import kotlin.math.ln

/**
 * 7.1.0 Drift: an endless radio that picks the next song only when it's needed, from your own
 * library and Fermata's ranking, steered live by what you skip, finish, replay and heart.
 *
 * Everything here is plain Kotlin with no Android or I/O, so it's fast (a few hundred candidates
 * score in well under a millisecond on a desktop, a few ms on an Android 9 phone) and testable.
 * The playback controller builds the [DriftCandidate] pool once (library + likes + history, plus
 * YouTube Music related songs when online, all ranked by Fermata), then asks [DriftEngine.pick]
 * for the next one or two songs whenever they're due.
 */
data class DriftCandidate(
    val track: Track,
    /** In your library already (played or liked). False: a new find from a related list. */
    val familiar: Boolean,
    /** 0..1: how much you like it before this session (Fermata's rank blended with plays and likes). */
    val taste: Double,
    /** When you last heard it (0 = never). */
    val lastPlayedAt: Long = 0L,
    /** The song whose related list it came from, if any: its "sound" neighbourhood. */
    val origin: String? = null,
)

/**
 * What this Drift session has learned so far. Every skip, full play, replay and heart moves the
 * weights; the next pick reads them. Lives only in memory, for one session.
 */
class DriftSignals {
    /** Songs skipped early this session: never again in this session. */
    val skipped = HashSet<String>()
    /** Artist key to a lean (+) or steer-away (-) weight. */
    val artist = HashMap<String, Double>()
    /** Song id to a weight for the songs related to it (its "sound"). */
    val sound = HashMap<String, Double>()

    /** A copy for a background pick, so the live one can keep changing on the main thread. */
    fun snapshot(): DriftSignals = DriftSignals().also {
        it.skipped.addAll(skipped)
        it.artist.putAll(artist)
        it.sound.putAll(sound)
    }

    /** Skipped within the first 30 seconds: away from that song, its artist and its sound. */
    fun earlySkip(c: DriftCandidate?, track: Track) {
        skipped.add(track.id)
        bump(artist, DriftEngine.artistKey(track.artist), -EARLY_SKIP_ARTIST)
        // Songs related to it, and songs from the same related list as it, sound like it
        bump(sound, track.id, -EARLY_SKIP_SOUND)
        c?.origin?.let { bump(sound, it, -EARLY_SKIP_SOUND / 2) }
    }

    /** Played to the end: lean in a little. */
    fun fullPlay(c: DriftCandidate?, track: Track) {
        bump(artist, DriftEngine.artistKey(track.artist), FULL_PLAY_ARTIST)
        bump(sound, track.id, FULL_PLAY_SOUND)
        c?.origin?.let { bump(sound, it, FULL_PLAY_SOUND / 2) }
    }

    /** Played again (repeat one, or back to it): lean in more. */
    fun replay(track: Track) {
        bump(artist, DriftEngine.artistKey(track.artist), REPLAY_ARTIST)
        bump(sound, track.id, REPLAY_SOUND)
    }

    /** Hearted while Drift played it: lean in most. */
    fun like(track: Track) {
        bump(artist, DriftEngine.artistKey(track.artist), LIKE_ARTIST)
        bump(sound, track.id, LIKE_SOUND)
    }

    private fun bump(map: HashMap<String, Double>, key: String, by: Double) {
        if (key.isBlank()) return
        map[key] = ((map[key] ?: 0.0) + by).coerceIn(-MAX_WEIGHT, MAX_WEIGHT)
    }

    companion object {
        const val EARLY_SKIP_ARTIST = 0.6
        const val EARLY_SKIP_SOUND = 0.4
        const val FULL_PLAY_ARTIST = 0.12
        const val FULL_PLAY_SOUND = 0.1
        const val REPLAY_ARTIST = 0.3
        const val REPLAY_SOUND = 0.25
        const val LIKE_ARTIST = 0.4
        const val LIKE_SOUND = 0.35
        const val MAX_WEIGHT = 2.0
    }
}

/**
 * Listening by hour of day, per artist, from your play history: who you play at 2 AM is not who
 * you play at 5 PM. Built once per Drift session from (artist, hour) pairs.
 */
class HourAffinity private constructor(private val byArtist: Map<String, IntArray>) {

    /**
     * -[MAX]..+[MAX]: how much more (or less) than usual this artist is played around [hour]
     * (the hour itself and one either side). 0 with too few plays to tell.
     */
    fun of(artist: String, hour: Int): Double {
        val counts = byArtist[DriftEngine.artistKey(artist)] ?: return 0.0
        val total = counts.sum()
        if (total < MIN_PLAYS) return 0.0
        val h = ((hour % 24) + 24) % 24
        val near = counts[(h + 23) % 24] + counts[h] + counts[(h + 1) % 24]
        val share = near.toDouble() / total
        val expected = 3.0 / 24.0
        // share/expected: 1 = as usual; 3 = three times as often at this time; 0 = never
        val lift = (share / expected) - 1.0
        val confidence = (total.toDouble() / CONFIDENT_PLAYS).coerceAtMost(1.0)
        return (lift * 0.15 * confidence).coerceIn(-MAX, MAX)
    }

    companion object {
        const val MIN_PLAYS = 5
        const val CONFIDENT_PLAYS = 30
        const val MAX = 0.3
        val EMPTY = HourAffinity(emptyMap())

        fun build(plays: List<Pair<String, Int>>): HourAffinity {
            val map = HashMap<String, IntArray>()
            for ((artist, hour) in plays) {
                val key = DriftEngine.artistKey(artist)
                if (key.isBlank() || hour !in 0..23) continue
                map.getOrPut(key) { IntArray(24) }[hour]++
            }
            return HourAffinity(map)
        }
    }
}

object DriftEngine {
    /** A song you heard among the last this many Drift picks stays out. */
    const val COOLDOWN_SONGS = 50
    /** A song you heard (anywhere) this recently stays out. */
    const val COOLDOWN_MS = 3L * 60 * 60 * 1000
    /** The same artist only after this many other songs, when there's anyone else to play. */
    const val ARTIST_GAP = 4
    /** Over ~10 minutes is an album, a set or a loop, not a song. */
    const val MAX_SONG_SEC = 600L
    /** A skip this early counts as "not this". */
    const val EARLY_SKIP_MS = 30_000L
    /** The next song is chosen (again) this close to the end of the current one. */
    const val PICK_BEFORE_END_MS = 20_000L
    /** Up Next shows this many Drift picks. */
    const val AHEAD = 2
    /** The dial's default: half familiar, half new. */
    const val DEFAULT_EXPLORE = 0.5f

    private const val W_TASTE = 1.0
    private const val JITTER = 0.08

    /** Who a song is by, for spacing and steering: the first artist, lower case. */
    fun artistKey(artist: String): String {
        var s = artist.lowercase()
        for (sep in listOf(",", "&", " feat", " ft.", " x ", " and ", "•", "|")) {
            val i = s.indexOf(sep)
            if (i > 0) s = s.substring(0, i)
        }
        return s.trim()
    }

    /** A song, not a tape or an hour-long upload. */
    fun isSong(t: Track): Boolean =
        t.durationSec <= MAX_SONG_SEC && !com.minim.music.data.stories.StoryTracks.isStory(t)

    /**
     * The state the next pick needs. [recent] is the Drift play history, oldest first (the songs
     * already queued count too); [familiarPicked]/[newPicked] are this session's counts so far,
     * which the dial balances.
     */
    data class State(
        val explore: Float = DEFAULT_EXPLORE,
        val now: Long,
        val hour: Int,
        val recent: List<Track> = emptyList(),
        val hidden: Set<String> = emptySet(),
        /** No connection: only songs saved offline can play. */
        val offline: Boolean = false,
        val savedOffline: Set<String> = emptySet(),
        val familiarPicked: Int = 0,
        val newPicked: Int = 0,
        val hours: HourAffinity = HourAffinity.EMPTY,
        /** Songs from earlier Drift sessions, still cooling down (ids only). */
        val earlier: Set<String> = emptySet(),
        /**
         * Makes the small random part of each score stable for one session: the same state gives
         * the same picks, so choosing again near the end of a song only changes what's next when
         * something you did changed it (and the next song's prefetch is kept otherwise).
         */
        val salt: Long = 0L,
    )

    /** Whether [c] may play now at all (before scoring). */
    fun eligible(c: DriftCandidate, s: State, signals: DriftSignals, recentIds: Set<String>): Boolean {
        val id = c.track.id
        if (id in s.hidden || id in signals.skipped || id in recentIds) return false
        if (!isSong(c.track)) return false
        if (s.offline && id !in s.savedOffline) return false
        if (c.lastPlayedAt > 0 && s.now - c.lastPlayedAt in 0 until COOLDOWN_MS) return false
        return true
    }

    /** How well [c] fits right now: taste, the hour, and this session's leanings. */
    fun score(c: DriftCandidate, s: State, signals: DriftSignals): Double {
        val key = artistKey(c.track.artist)
        var v = c.taste * W_TASTE
        v += s.hours.of(c.track.artist, s.hour)
        v += signals.artist[key] ?: 0.0
        v += signals.sound[c.track.id] ?: 0.0
        c.origin?.let { v += signals.sound[it] ?: 0.0 }
        return v
    }

    /** True when the dial wants a new find next, to keep the familiar/new ratio near [explore]. */
    fun wantsNew(explore: Float, familiarPicked: Int, newPicked: Int): Boolean {
        if (explore <= 0f) return false
        if (explore >= 1f) return true
        val total = familiarPicked + newPicked + 1
        return newPicked < explore * total
    }

    /**
     * The next [count] songs, best first. Leaves out hidden, recently heard, skipped and (offline)
     * unsaved songs; keeps the same artist [ARTIST_GAP] songs apart when anyone else fits; and
     * alternates familiar and new to match the dial. Empty only when nothing can play.
     */
    fun pick(
        pool: List<DriftCandidate>,
        state: State,
        signals: DriftSignals,
        count: Int = 1,
    ): List<DriftCandidate> {
        if (pool.isEmpty() || count <= 0) return emptyList()
        val recentTail = state.recent.takeLast(COOLDOWN_SONGS)
        val recentIds = recentTail.mapTo(HashSet()) { it.id }
        recentIds.addAll(state.earlier)
        val eligible = pool.filter { eligible(it, state, signals, recentIds) }.distinctBy { it.track.id }
        if (eligible.isEmpty()) return emptyList()
        // Score once; a little jitter so two sessions don't play the same order
        val scored = eligible.map { it to score(it, state, signals) + jitter(it.track.id, state.salt) }
            .sortedByDescending { it.second }
        val out = ArrayList<DriftCandidate>(count)
        val used = HashSet<String>()
        val artists = ArrayList<String>(recentTail.size + count)
        recentTail.mapTo(artists) { artistKey(it.artist) }
        var fam = state.familiarPicked
        var new = state.newPicked
        while (out.size < count) {
            val wantNew = wantsNew(state.explore, fam, new)
            val gap = artists.takeLast(ARTIST_GAP).toHashSet()
            fun first(familiar: Boolean?, spaced: Boolean) = scored.firstOrNull { (c, _) ->
                c.track.id !in used && (familiar == null || c.familiar == familiar) &&
                    (!spaced || artistKey(c.track.artist) !in gap)
            }?.first
            val next = first(!wantNew, true) ?: first(null, true) ?: first(!wantNew, false) ?: first(null, false) ?: break
            out.add(next)
            used.add(next.track.id)
            artists.add(artistKey(next.track.artist))
            if (next.familiar) fam++ else new++
        }
        return out
    }

    /** -JITTER..+JITTER, the same for the same song and salt. */
    fun jitter(id: String, salt: Long): Double {
        var h = id.hashCode().toLong() * -0x61c8864680b583ebL xor salt
        h = (h xor (h ushr 31)) * -0x4b47d5b1b3e5d5b5L
        h = h xor (h ushr 29)
        val unit = (h ushr 11).toDouble() / (1L shl 53).toDouble()
        return (unit * 2 - 1) * JITTER
    }

    /**
     * A library song's taste before Fermata: plays (diminishing), a heart, and a little for being
     * heard in the last month. 0..1.
     */
    fun localTaste(playCount: Int, liked: Boolean, lastPlayedAt: Long, now: Long): Double {
        val plays = (ln(1.0 + playCount.coerceAtLeast(0)) / ln(51.0)).coerceAtMost(1.0)
        val month = 30L * 24 * 60 * 60 * 1000
        val fresh = if (lastPlayedAt > 0 && now - lastPlayedAt < month) 0.1 else 0.0
        return (plays * 0.5 + (if (liked) 0.4 else 0.0) + fresh).coerceIn(0.0, 1.0)
    }

    /** Fermata's order as 0..1 (first = 1), or null for songs it left out or wasn't asked about. */
    fun rankScores(rankedIds: List<String>): Map<String, Double> {
        val n = rankedIds.size
        if (n == 0) return emptyMap()
        val out = HashMap<String, Double>(n * 2)
        rankedIds.forEachIndexed { i, id -> if (id !in out) out[id] = if (n == 1) 1.0 else 1.0 - i.toDouble() / (n - 1) }
        return out
    }

    /** A candidate's taste: Fermata's rank blended with [local] when Fermata has an opinion. */
    fun blend(local: Double, fermata: Double?): Double =
        if (fermata == null) local else (fermata * 0.6 + local * 0.4)
}
