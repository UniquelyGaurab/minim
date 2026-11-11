package com.minim.music.data.fermata

import java.time.ZoneId
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

/** Where the logger writes (the library database on the phone, a fake in tests). */
interface FermataSink {
    /** Saves a new row and returns its id. */
    fun insert(e: FermataEvent): Long
    /** Updates an open play row with the time heard so far. */
    fun updatePlay(rowId: Long, listenedMs: Long, durationMs: Long?, speedPct: Int?)
}

/**
 * 5.9.0 u59: turns what the player does into Fermata event rows.
 *
 * Called on the main thread only; every database write goes to [executor] (one background
 * thread), and any error there drops that row and nothing else. Every public call is also
 * wrapped, so a bug in here can never reach the player.
 *
 * A play row is written as soon as a track starts, then kept up to date with the time heard (on
 * every listening-time save, on pause and when the service stops). So if the app is killed mid
 * song, the row is already there with what was heard up to the last save, and there's only ever
 * one row per play: later saves update it, they never add another.
 */
class FermataLogger(
    private val sink: FermataSink,
    kv: FermataKv,
    private val executor: Executor,
    private val clock: () -> Long = System::currentTimeMillis,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
    newSessionId: (() -> String)? = null,
    deviceIdKv: FermataKv = kv,
    private val onError: (Throwable) -> Unit = {}
) {
    private val session = if (newSessionId != null) FermataSession(kv, newSessionId) else FermataSession(kv)
    private val deviceId: String = FermataDeviceId.getOrCreate(deviceIdKv)

    /** The play that's open (started, not yet left). Main thread only. */
    private var open: OpenPlay? = null

    private class OpenPlay(
        val trackId: String,
        val title: String,
        val artist: String,
        val isTape: Boolean,
        val sessionId: String,
        val prevTrackId: String?,
        val source: String,
        var durationMs: Long,
        var speedPct: Int?
    ) {
        var listenedMs = 0L
        var lastPositionMs = 0L
        /** Set on the writer thread once the insert is done; -2 when it failed (row dropped). */
        @Volatile var rowId = -1L
    }

    /** The track whose play row is open, or null. */
    fun openTrackId(): String? = open?.trackId

    /**
     * [trackId] starts (or is playing). Opens its play row unless one is already open for it.
     * A row left open for another track is closed first, as a play that ran out (no skip).
     * Resuming the same track after 30+ minutes of nothing playing closes its row and opens a
     * new one in a new session.
     */
    fun startPlay(
        trackId: String, title: String, artist: String, durationMs: Long,
        isTape: Boolean, source: String?, speedPct: Int? = null
    ) = guard {
        val now = clock()
        val cur = open
        if (cur != null && cur.trackId == trackId && !session.isExpired(now)) return@guard
        if (cur != null) closeOpen(userMovedOn = false, positionMs = null)
        val (sid, prev) = session.beginTrack(trackId, now)
        val play = OpenPlay(
            trackId, title, artist, isTape, sid, prev, FermataSource.normalize(source),
            durationMs.coerceAtLeast(0L), speedPct
        )
        open = play
        val e = FermataEvent(
            type = if (isTape) FermataType.TAPE_PLAY else FermataType.PLAY,
            trackId = trackId, title = title, artist = artist,
            durationMs = durationMs.takeIf { it > 0L }, ts = now,
            hour = FermataTime.hourOf(now, zone()), dow = FermataTime.dowOf(now, zone()),
            sessionId = sid, prevTrackId = prev, source = play.source,
            listenedMs = 0L, positionMs = null, deviceId = deviceId,
            speedPct = if (isTape) speedPct else null
        )
        write { play.rowId = try { sink.insert(e) } catch (t: Throwable) { onError(t); -2L } }
    }

    /**
     * The same track starts over (repeat one): the loop that ended keeps its row and the new
     * loop gets its own, with the same track as the one before it.
     */
    fun startLoop(
        trackId: String, title: String, artist: String, durationMs: Long,
        isTape: Boolean, source: String?, speedPct: Int? = null
    ) {
        guard { if (open != null) closeOpen(userMovedOn = false, positionMs = null) }
        startPlay(trackId, title, artist, durationMs, isTape, source, speedPct)
    }

    /** [ms] more of [trackId] was heard (pauses never reach here). */
    fun heard(trackId: String, ms: Long) = guard {
        val cur = open ?: return@guard
        if (cur.trackId != trackId || ms <= 0L) return@guard
        cur.listenedMs += ms
        session.heard(clock())
    }

    /** Where the player is in [trackId] right now (for the skip position), and its real length. */
    fun position(trackId: String, positionMs: Long, durationMs: Long, speedPct: Int? = null) = guard {
        val cur = open ?: return@guard
        if (cur.trackId != trackId) return@guard
        if (positionMs >= 0L) cur.lastPositionMs = positionMs
        if (durationMs > 0L) cur.durationMs = durationMs
        if (speedPct != null) cur.speedPct = speedPct
    }

    /** Saves the open row's heard time so far (no new row). */
    fun flush() = guard {
        session.saveIfDirty()
        val cur = open ?: return@guard
        updateRow(cur)
    }

    /**
     * The open track leaves the player. [userMovedOn]: you pressed next (in the app, the
     * notification or a headset) or picked another song. With more than 10 seconds left that
     * also writes a skip row at [positionMs] (or the last place seen when null).
     */
    fun leave(userMovedOn: Boolean, positionMs: Long?) = guard {
        if (open != null) closeOpen(userMovedOn, positionMs)
        session.saveIfDirty()
    }

    /** You queued [trackId] by hand (Play next / Add to queue). */
    fun queued(trackId: String, title: String, artist: String, durationMs: Long, source: String?) = guard {
        val now = clock()
        val (sid, prev) = session.current(now)
        val e = FermataEvent(
            type = FermataType.QUEUED, trackId = trackId, title = title, artist = artist,
            durationMs = durationMs.takeIf { it > 0L }, ts = now,
            hour = FermataTime.hourOf(now, zone()), dow = FermataTime.dowOf(now, zone()),
            sessionId = sid, prevTrackId = prev, source = FermataSource.normalize(source),
            listenedMs = null, positionMs = null, deviceId = deviceId
        )
        write { try { sink.insert(e) } catch (t: Throwable) { onError(t) } }
    }

    /**
     * The service is going away: saves the open row, then waits up to [timeoutMs] for the
     * writer so the save lands before the process can be killed.
     */
    fun flushAndWait(timeoutMs: Long) {
        flush()
        try {
            val latch = CountDownLatch(1)
            executor.execute { latch.countDown() }
            latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (t: Throwable) {
            onError(t)
        }
    }

    private fun closeOpen(userMovedOn: Boolean, positionMs: Long?) {
        val cur = open ?: return
        open = null
        updateRow(cur)
        val pos = positionMs ?: cur.lastPositionMs
        if (FermataSkip.isSkip(userMovedOn, pos, cur.durationMs)) {
            val now = clock()
            val e = FermataEvent(
                type = FermataType.SKIP, trackId = cur.trackId, title = cur.title, artist = cur.artist,
                durationMs = cur.durationMs.takeIf { it > 0L }, ts = now,
                hour = FermataTime.hourOf(now, zone()), dow = FermataTime.dowOf(now, zone()),
                sessionId = cur.sessionId, prevTrackId = cur.prevTrackId, source = cur.source,
                listenedMs = null, positionMs = pos.coerceAtLeast(0L), deviceId = deviceId
            )
            write { try { sink.insert(e) } catch (t: Throwable) { onError(t) } }
        }
    }

    private fun updateRow(cur: OpenPlay) {
        val heard = cur.listenedMs
        val dur = cur.durationMs.takeIf { it > 0L }
        val speed = if (cur.isTape) cur.speedPct else null
        write {
            val id = cur.rowId
            if (id > 0L) try { sink.updatePlay(id, heard, dur, speed) } catch (t: Throwable) { onError(t) }
        }
    }

    private fun write(block: () -> Unit) {
        try {
            executor.execute {
                try { block() } catch (t: Throwable) { onError(t) }
            }
        } catch (t: Throwable) {
            onError(t) // a refused task just drops the row
        }
    }

    private inline fun guard(block: () -> Unit) {
        try { block() } catch (t: Throwable) { onError(t) }
    }
}
