package com.minim.music.data.sync

import java.security.MessageDigest
import java.util.Locale

/*
 * Minim 5.0.0 part 2: cloud sync, the pure parts (no Android, no Firestore), so they can be
 * unit tested: stable IDs, the compact encodings stored in Firestore, and the merge rules.
 *
 * Every merge here is idempotent (merging the same data twice changes nothing) and does not care
 * about order (A then B gives the same as B then A), so a phone can merge the cloud as often as
 * it likes, and two phones end up with the same library.
 */

/** What a row needs to show a song: title, artist, cover and length. */
data class SongMeta(
    val title: String,
    val artist: String,
    val thumb: String,
    val durationSec: Long
)

object SyncIds {
    /**
     * A playlist's stable sync ID, from its name (names are unique on a phone, and there's no
     * rename). Two phones that each made "Gym" get the same ID, so their songs merge into one
     * playlist instead of two "Gym"s.
     */
    fun playlistUid(name: String): String = "pl_" + sha1(name.trim()).take(20)

    /**
     * A play's stable event ID: when it started (ms) + the song. The same play always gets the
     * same ID, on any phone, so merging it twice never counts it twice.
     */
    fun eventId(playedAt: Long, trackId: String): String =
        java.lang.Long.toString(playedAt, 36) + "_" + trackId

    /** This phone's sync device key, from Android's per-app device ID (stable across reinstalls). */
    fun deviceKey(androidId: String?, fallback: String): String {
        val base = androidId?.takeIf { it.isNotBlank() && it != "9774d56d682e549c" } ?: fallback
        return "d" + sha1("minim:$base").take(12)
    }

    /** A song ID that can be a Firestore document ID / map key on its own. */
    fun isSyncableSongId(id: String): Boolean =
        id.isNotEmpty() && id.length <= 64 && id.all { it.isLetterOrDigit() || it == '-' || it == '_' } &&
            !(id.startsWith("__") && id.endsWith("__"))

    private fun sha1(s: String): String {
        val d = MessageDigest.getInstance("SHA-1").digest(s.toByteArray(Charsets.UTF_8))
        return d.joinToString("") { String.format(Locale.US, "%02x", it) }
    }
}

/**
 * The compact encodings. Song details and entries are packed into one string per entry (fields
 * split by the invisible unit separator) so a big playlist or a busy day is one Firestore field per
 * entry instead of five: smaller documents, fewer index entries.
 */
object SyncCodec {
    const val SEP = '\u001F'

    private fun clean(s: String): String = if (s.indexOf(SEP) < 0) s else s.replace(SEP.toString(), "")

    fun encodeMeta(m: SongMeta): String =
        listOf(clean(m.title), clean(m.artist), clean(m.thumb), m.durationSec.toString()).joinToString(SEP.toString())

    fun decodeMeta(s: Any?): SongMeta? {
        if (s !is String || s.isEmpty()) return null
        val p = s.split(SEP)
        return SongMeta(
            title = p.getOrNull(0) ?: "",
            artist = p.getOrNull(1) ?: "",
            thumb = p.getOrNull(2) ?: "",
            durationSec = p.getOrNull(3)?.toLongOrNull() ?: 0L
        )
    }

    /** Playlist song entry: position, changed-at, removed flag, then the song details. */
    fun encodeEntry(e: PlEntry): String {
        val head = listOf(e.pos.toString(), e.at.toString(), if (e.removed) "1" else "0")
        val meta = e.meta ?: SongMeta("", "", "", 0L)
        return (head + listOf(clean(meta.title), clean(meta.artist), clean(meta.thumb), meta.durationSec.toString()))
            .joinToString(SEP.toString())
    }

    fun decodeEntry(s: Any?): PlEntry? {
        if (s !is String || s.isEmpty()) return null
        val p = s.split(SEP)
        val pos = p.getOrNull(0)?.toLongOrNull() ?: return null
        val at = p.getOrNull(1)?.toLongOrNull() ?: return null
        val removed = p.getOrNull(2) == "1"
        val title = p.getOrNull(3) ?: ""
        val meta = if (p.size >= 7 && (title.isNotEmpty() || (p.getOrNull(4) ?: "").isNotEmpty())) {
            SongMeta(title, p[4], p[5], p[6].toLongOrNull() ?: 0L)
        } else null
        return PlEntry(pos, at, removed, meta)
    }

    /** One play: song, started at, ms heard, tracked flag (real listening time vs old estimate). */
    fun encodePlay(e: PlayEv): String =
        listOf(e.songId, e.playedAt.toString(), e.listenedMs.toString(), if (e.tracked) "1" else "0")
            .joinToString(SEP.toString())

    fun decodePlay(s: Any?): PlayEv? {
        if (s !is String || s.isEmpty()) return null
        val p = s.split(SEP)
        val song = p.getOrNull(0)?.takeIf { it.isNotEmpty() } ?: return null
        val t = p.getOrNull(1)?.toLongOrNull() ?: return null
        return PlayEv(song, t, p.getOrNull(2)?.toLongOrNull() ?: 0L, p.getOrNull(3) == "1")
    }

    /** Listening-time key inside a day: "<hour>_<songId>". */
    fun listenKey(hour: Int, songId: String): String = "${hour}_$songId"

    fun parseListenKey(k: String): Pair<Int, String>? {
        val i = k.indexOf('_')
        if (i <= 0 || i == k.length - 1) return null
        val h = k.substring(0, i).toIntOrNull() ?: return null
        if (h !in 0..23) return null
        return h to k.substring(i + 1)
    }

    /** Firestore hands numbers back as Long or Double. */
    fun num(v: Any?): Long? = when (v) {
        is Long -> v
        is Int -> v.toLong()
        is Double -> v.toLong()
        is Number -> v.toLong()
        is String -> v.toLongOrNull()
        else -> null
    }

    @Suppress("UNCHECKED_CAST")
    fun map(v: Any?): Map<String, Any?> = (v as? Map<String, Any?>) ?: emptyMap()

    // ---- documents ----------------------------------------------------------------------------

    fun likeDoc(s: LikeState, meta: SongMeta?): Map<String, Any?> = buildMap {
        put("l", s.liked)
        put("at", s.at)
        if (meta != null) put("m", encodeMeta(meta))
    }

    fun decodeLike(data: Map<String, Any?>): LikeState? {
        val at = num(data["at"]) ?: return null
        val liked = data["l"] as? Boolean ?: return null
        return LikeState(liked, at)
    }

    fun playlistHeader(s: PlState): Map<String, Any?> = mapOf(
        "n" to s.name, "c" to s.created, "h" to s.changed, "b" to s.born, "del" to s.deleted
    )

    fun decodePlaylist(data: Map<String, Any?>): PlState? {
        val name = data["n"] as? String ?: return null
        val tracks = HashMap<String, PlEntry>()
        for ((k, v) in map(data["t"])) {
            val e = decodeEntry(v) ?: continue
            tracks[k] = e
        }
        return PlState(
            name = name,
            created = num(data["c"]) ?: 0L,
            changed = num(data["h"]) ?: 0L,
            born = num(data["b"]) ?: 0L,
            deleted = data["del"] as? Boolean ?: false,
            tracks = tracks
        )
    }
}

// ---- likes ----------------------------------------------------------------------------------------

/** A song's like state and when it last changed (liked or unliked), on the phone that changed it. */
data class LikeState(val liked: Boolean, val at: Long)

// ---- playlists ------------------------------------------------------------------------------------

/** One song in a playlist: [pos] orders the list (the time it was added), [at] is its last change. */
data class PlEntry(val pos: Long, val at: Long, val removed: Boolean, val meta: SongMeta? = null) {
    /** Same entry, ignoring the song details (those are only for showing it). */
    fun sameAs(o: PlEntry?): Boolean = o != null && pos == o.pos && at == o.at && removed == o.removed
}

/**
 * A playlist's sync state. [changed] is when its header last changed (made or deleted). [born] is
 * when it was last made again after being deleted: songs from before that belong to the deleted
 * one and are dropped. A deleted playlist keeps no songs.
 */
data class PlState(
    val name: String,
    val created: Long,
    val changed: Long,
    val born: Long,
    val deleted: Boolean,
    val tracks: Map<String, PlEntry>
) {
    fun sameHeader(o: PlState?): Boolean = o != null && name == o.name && created == o.created &&
        changed == o.changed && born == o.born && deleted == o.deleted

    fun sameAs(o: PlState?): Boolean {
        if (!sameHeader(o)) return false
        o!!
        if (tracks.keys != o.tracks.keys) return false
        return tracks.all { (k, e) -> e.sameAs(o.tracks[k]) }
    }

    /** Visible songs in order. */
    fun visibleSongs(): List<String> =
        tracks.filter { !it.value.removed }.entries.sortedWith(compareBy({ it.value.pos }, { it.key })).map { it.key }
}

// ---- history --------------------------------------------------------------------------------------

/** One counted play. */
data class PlayEv(val songId: String, val playedAt: Long, val listenedMs: Long, val tracked: Boolean)

object SyncMerge {

    /**
     * Likes: the newest change wins, so unliking on one phone isn't undone by the other phone's
     * older like. A tie between different states (same millisecond) goes to "not liked".
     */
    fun like(a: LikeState?, b: LikeState?): LikeState? {
        if (a == null) return b
        if (b == null) return a
        return when {
            a.at > b.at -> a
            b.at > a.at -> b
            a.liked == b.liked -> a
            else -> LikeState(false, a.at)
        }
    }

    /** One playlist song: the newest change wins; a tie goes to "removed". Keeps any song details. */
    fun entry(a: PlEntry?, b: PlEntry?): PlEntry? {
        if (a == null) return b
        if (b == null) return a
        val win = when {
            a.at > b.at -> a
            b.at > a.at -> b
            a.removed != b.removed -> if (a.removed) a else b
            a.pos <= b.pos -> a
            else -> b
        }
        return if (win.meta == null) win.copy(meta = a.meta ?: b.meta) else win
    }

    /**
     * Playlists, matched by their stable ID:
     * - both alive: one playlist, songs merged (each song's newest change wins, no duplicates),
     *   ordered by when each song was added
     * - deleted on one side: the newer header change wins. A delete newer than the other side's
     *   last change deletes it; a playlist made again after the delete stays, without the songs it
     *   had before the delete
     */
    fun playlist(a: PlState?, b: PlState?): PlState? {
        if (a == null) return b?.let { normalize(it) }
        if (b == null) return normalize(a)
        val header: PlState = when {
            !a.deleted && !b.deleted -> {
                val newer = if (a.changed > b.changed || (a.changed == b.changed && a.name >= b.name)) a else b
                PlState(
                    name = newer.name,
                    created = minPositive(a.created, b.created),
                    changed = maxOf(a.changed, b.changed),
                    born = maxOf(a.born, b.born),
                    deleted = false,
                    tracks = emptyMap()
                )
            }
            a.deleted && b.deleted -> {
                val newer = if (a.changed >= b.changed) a else b
                newer.copy(born = maxOf(a.born, b.born), tracks = emptyMap())
            }
            else -> {
                val dead = if (a.deleted) a else b
                val alive = if (a.deleted) b else a
                if (alive.changed > dead.changed) {
                    // made again after the delete: what it held before the delete is gone
                    alive.copy(born = maxOf(alive.born, dead.born, dead.changed), tracks = emptyMap())
                } else {
                    dead.copy(born = maxOf(alive.born, dead.born), tracks = emptyMap())
                }
            }
        }
        if (header.deleted) return header
        val merged = HashMap<String, PlEntry>()
        for (k in a.tracks.keys + b.tracks.keys) {
            val e = entry(a.tracks[k], b.tracks[k]) ?: continue
            merged[k] = e
        }
        return normalize(header.copy(tracks = merged))
    }

    /** Drops what a playlist can't hold: every song of a deleted one, songs from before [PlState.born]. */
    fun normalize(s: PlState): PlState =
        if (s.deleted) {
            if (s.tracks.isEmpty()) s else s.copy(tracks = emptyMap())
        } else if (s.born > 0L && s.tracks.values.any { it.at < s.born }) {
            s.copy(tracks = s.tracks.filterValues { it.at >= s.born })
        } else s

    /** A play seen on both sides: the longer listening time wins (it only ever grows). */
    fun play(a: PlayEv?, b: PlayEv?): PlayEv? {
        if (a == null) return b
        if (b == null) return a
        return if (b.listenedMs > a.listenedMs) b else a
    }

    /**
     * Listening time is kept per phone (each phone only adds to its own counter), so a merge takes
     * the bigger value per phone and key, and the day's total is the sum over phones. Merging the
     * same numbers twice changes nothing.
     */
    fun listens(a: Map<String, Map<String, Long>>, b: Map<String, Map<String, Long>>): Map<String, Map<String, Long>> {
        val out = HashMap<String, Map<String, Long>>()
        for (dev in a.keys + b.keys) {
            val x = a[dev] ?: emptyMap()
            val y = b[dev] ?: emptyMap()
            val m = HashMap<String, Long>()
            for (k in x.keys + y.keys) m[k] = maxOf(x[k] ?: 0L, y[k] ?: 0L)
            out[dev] = m
        }
        return out
    }

    private fun minPositive(x: Long, y: Long): Long = when {
        x <= 0L -> y
        y <= 0L -> x
        else -> minOf(x, y)
    }
}

// ---- status ---------------------------------------------------------------------------------------

/** Why the last sync didn't finish. */
enum class SyncProblem { OFFLINE, PERMISSION, NOT_READY, QUOTA, FAILED }

object SyncText {
    /** "Synced just now" / "Synced 5 min ago" / "Synced 3 h ago" / "Synced 2 d ago". */
    fun syncedLine(nowMs: Long, lastSyncAt: Long): String {
        if (lastSyncAt <= 0L) return "Sync on"
        val diff = (nowMs - lastSyncAt).coerceAtLeast(0L)
        val min = diff / 60_000L
        return when {
            min < 1L -> "Synced just now"
            min < 60L -> "Synced $min min ago"
            min < 60L * 24L -> "Synced ${min / 60L} h ago"
            else -> "Synced ${min / (60L * 24L)} d ago"
        }
    }

    /**
     * Part 2c: the small line under the name in Settings while signed in. While a sync runs after
     * an earlier one, the "Synced X ago" line stays (no flicker every few seconds after a like).
     */
    fun statusLine(phase: CloudSync.Phase, lastSyncAt: Long, nowMs: Long): String = when (phase) {
        CloudSync.Phase.OFF -> "Sync on"
        CloudSync.Phase.NEEDS_CHOICE -> "Sync is waiting for you. Tap to choose."
        CloudSync.Phase.FIRST_SYNC -> "Syncing your music…"
        CloudSync.Phase.SYNCING ->
            if (lastSyncAt <= 0L) "Syncing…" else syncedLine(nowMs, lastSyncAt)
        CloudSync.Phase.IDLE -> syncedLine(nowMs, lastSyncAt)
    }

    /**
     * How long until [syncedLine] reads differently: the next whole minute while under an hour, the
     * next whole hour under a day, then the next whole day. Never under a second, never over an hour.
     */
    fun nextChangeMs(nowMs: Long, lastSyncAt: Long): Long {
        if (lastSyncAt <= 0L) return 60_000L
        val diff = (nowMs - lastSyncAt).coerceAtLeast(0L)
        val step = when {
            diff < 60L * 60_000L -> 60_000L
            diff < 24L * 60L * 60_000L -> 60L * 60_000L
            else -> 24L * 60L * 60_000L
        }
        return (step - diff % step).coerceIn(1_000L, 60L * 60_000L)
    }

    fun problemLine(p: SyncProblem?): String? = when (p) {
        null -> null
        SyncProblem.OFFLINE -> "You're offline. Changes will sync when you're back online."
        SyncProblem.PERMISSION -> "Sync was refused for this account. Try signing out and in again."
        SyncProblem.NOT_READY -> "Sync isn't set up on the server yet."
        SyncProblem.QUOTA -> "Sync reached today's free limit. It picks up again tomorrow."
        SyncProblem.FAILED -> "Couldn't sync. Minim will try again soon."
    }

    /** Firestore error code name (FirebaseFirestoreException.Code) to a problem. */
    fun classify(code: String?, timedOut: Boolean = false): SyncProblem = when {
        timedOut -> SyncProblem.OFFLINE
        code == "UNAVAILABLE" || code == "DEADLINE_EXCEEDED" -> SyncProblem.OFFLINE
        code == "PERMISSION_DENIED" || code == "UNAUTHENTICATED" -> SyncProblem.PERMISSION
        code == "NOT_FOUND" || code == "FAILED_PRECONDITION" -> SyncProblem.NOT_READY
        code == "RESOURCE_EXHAUSTED" -> SyncProblem.QUOTA
        else -> SyncProblem.FAILED
    }
}
