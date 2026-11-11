package com.minim.music.data.fermata

import java.security.SecureRandom
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

/**
 * 5.9.0 u59: groundwork for Minim Fermata (the on-device data layer). Minim writes listening
 * events into the local `fermata_events` table, named like Fermata's event format so a later
 * import is a straight copy. Nothing here changes what you see or hear.
 */

/** One row of `fermata_events`. [id] is 0 until it's saved. */
data class FermataEvent(
    val id: Long = 0L,
    val type: String,
    val trackId: String,
    val title: String,
    val artist: String,
    val durationMs: Long?,
    val ts: Long,
    val hour: Int,
    val dow: Int,
    val sessionId: String,
    val prevTrackId: String?,
    val source: String,
    val listenedMs: Long?,
    val positionMs: Long?,
    val deviceId: String,
    /** Tapes only: playback speed x 100 (150 = 1.5x), like Fermata's TapePlay.speedPct. */
    val speedPct: Int? = null
)

object FermataType {
    const val PLAY = "play"
    const val SKIP = "skip"
    const val QUEUED = "queued"
    const val TAPE_PLAY = "tape_play"
}

/** Where a queue entry came from. The same words as Fermata's Source enum, lower case. */
object FermataSource {
    const val RADIO = "radio"
    const val SEARCH = "search"
    const val LIBRARY = "library"
    const val PLAYLIST = "playlist"
    const val DISCOVER = "discover"
    const val TAPE = "tape"
    const val QUEUE = "queue"
    const val UNKNOWN = "unknown"

    private val known = setOf(RADIO, SEARCH, LIBRARY, PLAYLIST, DISCOVER, TAPE, QUEUE, UNKNOWN)

    /** Anything missing or unrecognised (an older queue, a typo) is "unknown". */
    fun normalize(raw: String?): String = raw?.trim()?.lowercase()?.takeIf { it in known } ?: UNKNOWN
}

/** Local hour and ISO weekday of a moment. */
object FermataTime {
    fun hourOf(ts: Long, zone: ZoneId): Int = Instant.ofEpochMilli(ts).atZone(zone).hour

    /** ISO: 1 = Monday ... 7 = Sunday. */
    fun dowOf(ts: Long, zone: ZoneId): Int = Instant.ofEpochMilli(ts).atZone(zone).dayOfWeek.value
}

/** Skip detection. */
object FermataSkip {
    /** More than this left in the song when you moved on makes it a skip. */
    const val MIN_LEFT_MS = 10_000L

    /**
     * A skip is you moving on (next, a notification or headset next, picking another song) with
     * more than 10 seconds of the song left. Running out on its own is never a skip. An unknown
     * length can't be judged, so it isn't one.
     */
    fun isSkip(userMovedOn: Boolean, positionMs: Long, durationMs: Long): Boolean {
        if (!userMovedOn || durationMs <= 0L) return false
        return durationMs - positionMs.coerceAtLeast(0L) > MIN_LEFT_MS
    }
}

/** Which player moves count as you moving on. */
object FermataMoves {
    /**
     * Next (in the app, the notification or a headset), previous, or picking a song in the queue
     * all reach the player as a seek; starting a new list (tapping a song anywhere) replaces the
     * playlist. Running out on its own (auto) and repeat aren't you moving on.
     */
    fun isUserMove(transitionReason: Int): Boolean =
        transitionReason == androidx.media3.common.Player.MEDIA_ITEM_TRANSITION_REASON_SEEK ||
            transitionReason == androidx.media3.common.Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED
}

/** A tiny key-value store (SharedPreferences on the phone, a map in tests). */
interface FermataKv {
    fun getString(key: String): String?
    fun getLong(key: String, default: Long): Long
    fun put(values: Map<String, Any?>)
}

class MapKv : FermataKv {
    val map = HashMap<String, Any?>()
    override fun getString(key: String): String? = map[key] as? String
    override fun getLong(key: String, default: Long): Long = (map[key] as? Long) ?: default
    override fun put(values: Map<String, Any?>) {
        values.forEach { (k, v) -> if (v == null) map.remove(k) else map[k] = v }
    }
}

/** This phone's id, made once. Fermata's format: 16 lower-case hex characters (64 random bits). */
object FermataDeviceId {
    const val KEY = "device_id"

    fun random(): String {
        val b = ByteArray(8)
        SecureRandom().nextBytes(b)
        return b.joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    fun isValid(id: String?): Boolean =
        id != null && id.length == 16 && id.all { it in '0'..'9' || it in 'a'..'f' }

    fun getOrCreate(kv: FermataKv, make: () -> String = ::random): String {
        kv.getString(KEY)?.takeIf { isValid(it) }?.let { return it }
        val id = make()
        kv.put(mapOf(KEY to id))
        return id
    }
}

/**
 * Listening sessions. A new random session id starts when playback starts after 30 or more
 * minutes of nothing playing; otherwise the current one carries on. Saved, so an app restart
 * mid-session continues it.
 */
class FermataSession(
    private val kv: FermataKv,
    private val newId: () -> String = { UUID.randomUUID().toString() }
) {
    private var sessionId: String? = kv.getString(KEY_ID)
    private var lastTrack: String? = kv.getString(KEY_LAST_TRACK)
    private var lastActive: Long = kv.getLong(KEY_LAST_ACTIVE, 0L)
    private var dirty = false

    /** True when the next playback starts a new session. */
    fun isExpired(now: Long): Boolean = sessionId == null || now - lastActive >= GAP_MS

    /**
     * A track starts playing: returns its session id and the track before it in that session
     * (null for the first). The track then becomes "the one before" for the next.
     */
    fun beginTrack(trackId: String, now: Long): Pair<String, String?> {
        val prev: String?
        if (isExpired(now)) {
            sessionId = newId()
            prev = null
        } else {
            prev = lastTrack
        }
        lastTrack = trackId
        lastActive = now
        save()
        return sessionId!! to prev
    }

    /** The session for an event that isn't a track starting (a song queued). */
    fun current(now: Long): Pair<String, String?> {
        if (isExpired(now)) {
            sessionId = newId()
            lastTrack = null
            lastActive = now
            save()
        }
        return sessionId!! to lastTrack
    }

    /** Something was heard just now (kept in memory; saved by [saveIfDirty]). */
    fun heard(now: Long) {
        if (now > lastActive) {
            lastActive = now
            dirty = true
        }
    }

    fun saveIfDirty() {
        if (dirty) save()
    }

    private fun save() {
        dirty = false
        kv.put(mapOf(KEY_ID to sessionId, KEY_LAST_TRACK to lastTrack, KEY_LAST_ACTIVE to lastActive))
    }

    companion object {
        const val GAP_MS = 30 * 60_000L
        const val KEY_ID = "session_id"
        const val KEY_LAST_TRACK = "session_last_track"
        const val KEY_LAST_ACTIVE = "session_last_active"
    }
}
