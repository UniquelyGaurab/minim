package com.minim.music.data.fermata

import android.annotation.SuppressLint
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.provider.Settings
import android.util.Log
import com.minim.fermata.Fermata
import com.minim.fermata.FermataEventRow
import com.minim.fermata.MinimImport
import com.minim.fermata.MinimTables
import com.minim.fermata.android.MinimDatabaseReader
import com.minim.fermata.android.SqliteEventStore
import com.minim.fermata.log.Event
import com.minim.fermata.log.EventBody
import com.minim.fermata.log.Source
import com.minim.fermata.log.Subject
import com.minim.fermata.log.TrackRef
import com.minim.music.data.local.LibraryDatabase
import com.minim.music.data.local.LibraryFiles
import com.minim.music.data.stats.Days
import com.minim.music.data.sync.LibrarySync
import com.minim.music.data.sync.SyncIds
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * Minim 5.10.0: everything Minim writes goes through Fermata.
 *
 * The log is Fermata's ([fermata]), kept inside Minim's own database file (table
 * [LibraryDatabase.FERMATA_LOG_TABLE]) so an event commits in the same SQLite transaction as the
 * rows the screens read. The screens keep reading Minim's tables, which are now what the log
 * says: every change made here is recorded into the log as it commits, and every change that
 * reaches the log from elsewhere (another phone's backup merged in, a restore) is applied to the
 * tables from the log.
 *
 * How a change gets into the log:
 * - Every write to likes, playlists, "not interested", counted plays, listening time and tapes
 *   puts a key in `fermata_outbox` (LibraryDatabase.queueFermata, in the write's own transaction).
 * - Writes run under Fermata's lock (LibraryDatabase.WriteGate); just before the transaction
 *   commits, [drain] turns the queued keys into events, inside the same transaction. A kill
 *   leaves both the rows and their events, or neither.
 * - The event for a key is the same document write Minim sends to Firestore
 *   (LibrarySync.buildWrites), as a Fermata app event: [WRITE], or [HISTORY_WRITE] for listening
 *   history (so a "clear listening history" covers it). Tapes, which never synced, get writes
 *   of their own under `tape_plays/` and `tape_listen/`.
 * - What the player does (plays, skips, queue adds, tape plays) becomes Fermata's own typed events
 *   through [JournalSink]: they feed taste and recommendations. 5.9.0's `fermata_events` table
 *   is no longer written; only the first-launch import reads it.
 *
 * How the log gets back into the tables: the writes are folded in log order, the way Firestore
 * would have merged them ([foldedDocs]), and the result goes through the same LibrarySync.apply*
 * merge that Firestore pulls use (likes and playlists by their change times, plays by sync id,
 * listening time by the bigger number per phone). Whatever this phone has that the folded copy
 * lacks is queued and recorded, so the next backup carries it.
 */
class FermataBridge(
    context: Context,
    val lib: LibraryDatabase,
    val appVersion: String?,
    deviceId: String? = null,
    clock: java.time.Clock = java.time.Clock.systemDefaultZone(),
    /**
     * Whether Firebase sign-in is on. While it is, other phones' listening time is left out when
     * folder backups are merged: Firestore already brings it, and taking it from both could count
     * it twice. Plays, likes and playlists still merge (they carry ids, so they never double).
     */
    private val signedIn: () -> Boolean = { false },
) {
    private val context = context.applicationContext

    val fermata: Fermata = Fermata(
        SqliteEventStore.inDatabase(LibraryDatabase.FERMATA_LOG_TABLE) { lib.writableDatabase },
        deviceId ?: FermataDeviceId.getOrCreate(FermataStore.deviceKv(this.context)),
        clock = clock,
    )

    /** Builds the writes for queued keys (and is where a folder merge queues what's missing). */
    private val sync = LibrarySync(lib, outbox = "fermata_outbox")

    /**
     * Goes up by one whenever a merge from the backup folder, a restore, or the old copy's log
     * after a rollback changed Minim's tables, so the screens can reload (like CloudSync.dataVersion).
     */
    private val _dataVersion = kotlinx.coroutines.flow.MutableStateFlow(0L)
    val dataVersion: kotlinx.coroutines.flow.StateFlow<Long> = _dataVersion

    private fun changed() {
        try {
            kotlinx.coroutines.runBlocking {
                lib.refreshLikedCache()
                lib.refreshNotInterestedCache()
            }
        } catch (t: Throwable) {
            Log.w(TAG, "refreshing caches", t)
        }
        _dataVersion.value = _dataVersion.value + 1
    }

    private val draining = ThreadLocal.withInitial { false }
    private val applying = ThreadLocal.withInitial { false }

    /**
     * Set while [importOnce] runs: the 5.9.1 state it records (likes, "not interested", playlists)
     * goes into the log at the time each change was made, not at the time of the upgrade. See
     * [recordImported].
     */
    private val importing = ThreadLocal.withInitial { false }

    /** Test hook: runs inside the first-launch import's transaction, after the import. */
    @Volatile internal var afterImportForTest: (() -> Unit)? = null

    /** Tests: runs as the log is applied to the tables (throw to stand in for a kill there). */
    @Volatile internal var beforeApplyForTest: (() -> Unit)? = null

    private val gate = object : LibraryDatabase.WriteGate {
        override val lock get() = fermata.writeLock
        override fun onCommit(db: SQLiteDatabase) = drain(db)
        override fun onRollback() = fermata.rolledBack()
        override fun timestamp(): Long = fermata.timestamp()
    }

    /**
     * Starts writing through Fermata. Call once, early, on a background thread: it does the
     * first-launch import (only the first time), picks up a play a kill left open, and folds in
     * the log of an older 5.10.0 copy after a rollback to 5.9.1 and back.
     */
    fun start() {
        attach()
        finishStart()
    }

    /** The quick part of [start], on the main thread before anything writes. */
    fun attach() {
        if (lib.writeGate === gate) return
        sync.ensureDevice(deviceKey())
        lib.writeGate = gate
        lib.onClearHistory = { db -> onClearHistory(db) }
        // a clear that came from Firestore goes into the log too, so folder backups carry it
        lib.onRemoteHistoryClear = { point -> fermata.clearHistory(point) }
        // Ids the import will bring in under their own numbers are taken from the first write on,
        // not only once the import has run (a like in between took id 1 and dropped an old play).
        fermata.reserveCountersThrough(takenCounters())
        fermata.setApplier { change -> applyChange(change) }
        fermata.addListener { change -> onChange(change) }
    }

    /**
     * The highest id of this phone that is not in the log yet but will be: 5.9.0's
     * `fermata_events` rows (imported as `EventId(device_id, id)`), and after a rollback to 5.9.1
     * and back, the kept 5.10.0 copy's log (loaded in [finishStart]).
     */
    internal fun takenCounters(): Long {
        var max = 0L
        try {
            lib.readableDatabase.rawQuery(
                "SELECT MAX(id) FROM fermata_events WHERE device_id = ?", arrayOf(fermata.deviceId)
            ).use { c -> if (c.moveToFirst() && !c.isNull(0)) max = c.getLong(0) }
        } catch (_: Exception) {
            // no fermata_events table (a library from before 5.9.0)
        }
        val prev = context.getDatabasePath(LibraryFiles.PREVIOUS_NAME)
        if (prev.exists() && !imported()) {
            try {
                SQLiteDatabase.openDatabase(prev.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                    SqliteEventStore.inDatabase(LibraryDatabase.FERMATA_LOG_TABLE) { db }.heads()[fermata.deviceId]
                        ?.let { if (it > max) max = it }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "previous copy's ids", t)
            }
        }
        return max
    }

    /** The slow part of [start] (the first-launch import), on a background thread. */
    fun finishStart() {
        try {
            loadPreviousLog()
        } catch (t: Throwable) {
            Log.w(TAG, "previous copy's log", t)
        }
        try {
            importOnce()
        } catch (t: Throwable) {
            // Writes still go through Fermata; the import is tried again on the next start.
            Log.w(TAG, "first-launch import failed", t)
        }
        try {
            recoverPrevious()
        } catch (t: Throwable) {
            Log.w(TAG, "previous copy", t)
        }
        try {
            journal.finishStale(60_000L)
        } catch (t: Throwable) {
            Log.w(TAG, "journal", t)
        }
    }

    /**
     * Before a backup: records plays the journal still holds (idle for a minute) and anything in
     * the tables the log does not have yet, such as what a Firestore pull brought in. Writes
     * equal to the last one recorded for their document are skipped, so this adds little.
     */
    fun prepareBackup() {
        if (!imported()) return
        journal.finishStale(60_000L)
        lib.inWrite { db ->
            sync.enqueueAll()
            db.execSQL("INSERT OR IGNORE INTO fermata_outbox (k) SELECT 'TP|' || id FROM tape_plays WHERE fermata_key IS NULL")
            db.execSQL("INSERT OR IGNORE INTO fermata_outbox (k) SELECT DISTINCT 'TL|' || day || '|' || video_id FROM tape_listen_time")
        }
    }

    // ---- first-launch import -------------------------------------------------------------------

    fun imported(): Boolean = meta("imported") != null

    /**
     * Copies what 5.9.1 left in the tables into the log, once. One transaction: a kill partway
     * leaves nothing behind and the next start does it again. Running it twice adds nothing
     * (Fermata's import uses fixed ids, and writes equal to the last one recorded are skipped).
     */
    fun importOnce(): Boolean {
        if (imported()) return false
        applying.set(true) // the import's own events are not "news from elsewhere"
        importing.set(true)
        try {
            lib.inWrite { db ->
                if (meta("imported") != null) return@inWrite
                val r = fermata.importMinim(MinimDatabaseReader.read(db))
                val since = reUpgrade
                if (since == null) {
                    sync.enqueueAll()
                    db.execSQL("INSERT OR IGNORE INTO fermata_outbox (k) SELECT 'TP|' || id FROM tape_plays WHERE fermata_key IS NULL")
                    db.execSQL("INSERT OR IGNORE INTO fermata_outbox (k) SELECT DISTINCT 'TL|' || day || '|' || video_id FROM tape_listen_time")
                    // Likes and "not interested" as Fermata's own events too, for taste
                    db.execSQL("INSERT OR IGNORE INTO fermata_outbox (k) SELECT 'L|' || id FROM tracks WHERE is_liked = 1")
                } else {
                    // After a rollback and back: only what 5.9.1 changed since the kept copy's
                    // import. The rest is in the kept copy's log, with any later unlike, removal
                    // or clear, and recoverPrevious applies that to the tables.
                    // The clear the kept copy recorded covers these tables' copies of the old
                    // history too: drop them first, so none of it is recorded again.
                    // Listening time is kept per hour, so the clear's own hour needs care: when
                    // the kept copy imported in or after that hour, 5.9.1's rows for it hold
                    // minutes from before the clear (already in the kept log, and cleared there),
                    // so that whole hour goes too. What 5.10.0 played in it after the clear comes
                    // back from the kept log; only minutes played on 5.9.1 within that same clock
                    // hour after going back are lost.
                    fermata.clearedBefore()?.let { point ->
                        val at = java.time.Instant.ofEpochMilli(point).atZone(java.time.ZoneId.systemDefault())
                        val hourStart = at.truncatedTo(java.time.temporal.ChronoUnit.HOURS).toInstant().toEpochMilli()
                        val upTo = if (since >= hourStart) at.hour + 1 else at.hour
                        LibraryDatabase.deleteHistoryBefore(db, point, Days.keyOf(point), upTo)
                    }
                    val fromDay = Days.keyOf(since)
                    sync.enqueueChangedSince(since, fromDay)
                    db.execSQL("INSERT OR IGNORE INTO fermata_outbox (k) SELECT 'TP|' || id FROM tape_plays WHERE fermata_key IS NULL AND played_at > ?", arrayOf<Any>(since))
                    db.execSQL("INSERT OR IGNORE INTO fermata_outbox (k) SELECT DISTINCT 'TL|' || day || '|' || video_id FROM tape_listen_time WHERE day >= ?", arrayOf<Any>(fromDay))
                }
                setMeta(db, "imported", "${System.currentTimeMillis()} ${r.added}")
                afterImportForTest?.invoke()
            }
        } finally {
            importing.set(false)
            applying.set(false)
        }
        return true
    }

    /**
     * Rolled back to 5.9.1 and upgraded again: LibraryFiles kept the old 5.10.0 copy as
     * PREVIOUS_NAME and made a fresh copy of 5.9.1's file. The old copy's log holds what was done
     * in 5.10.0 the first time (and what came from other phones); merge it in, then delete it.
     */
    private fun previousEvents(prev: File) =
        SQLiteDatabase.openDatabase(prev.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            SqliteEventStore.inDatabase(LibraryDatabase.FERMATA_LOG_TABLE) { db }.all()
        }

    /**
     * After a rollback to 5.9.1 and back, the fresh copy's log starts empty. The older copy's log
     * goes in first, before the import, so this phone's numbering continues past every id it
     * already used (otherwise the import would reuse them and the merge would drop the older
     * copy's events as already seen). The tables are not touched here: the import then records
     * what 5.9.1 changed since, with later times, and [recoverPrevious] folds it all in.
     */
    private fun loadPreviousLog() {
        val prev = context.getDatabasePath(LibraryFiles.PREVIOUS_NAME)
        if (!prev.exists() || imported()) return
        val events = previousEvents(prev)
        reUpgrade = previousImportTime(prev) ?: events.minOfOrNull { it.ctx.timestamp } ?: 0L
        applying.set(true)
        try {
            fermata.merge(events)
        } finally {
            applying.set(false)
        }
    }

    /**
     * When the kept copy did its own first-launch import: everything 5.9.1's tables held then is
     * in that copy's log already, so the import after the rollback only records what changed
     * after it. Null when the copy never finished its import.
     */
    private fun previousImportTime(prev: File): Long? = try {
        SQLiteDatabase.openDatabase(prev.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery("SELECT v FROM fermata_meta WHERE k = 'imported'", null).use { c ->
                if (c.moveToFirst()) c.getString(0)?.substringBefore(' ')?.toLongOrNull() else null
            }
        }
    } catch (t: Throwable) {
        Log.w(TAG, "previous copy's import time", t)
        null
    }

    /** Set by [loadPreviousLog]: this start is a re-upgrade, and when the kept copy imported. */
    @Volatile private var reUpgrade: Long? = null

    private fun recoverPrevious() {
        val prev = context.getDatabasePath(LibraryFiles.PREVIOUS_NAME)
        if (!prev.exists() || !imported()) return
        fermata.merge(previousEvents(prev))
        // One transaction: the kept copy's clears, unlikes and removals win over the older
        // state 5.9.1's tables still hold. A kill before the copy is deleted runs this again.
        applying.set(true)
        try {
            lib.inWrite {
                fermata.clearedBefore()?.let { applyClear(it) }
                applyPaths(null)
            }
        } finally {
            applying.set(false)
        }
        changed()
        for (sfx in listOf("", "-wal", "-shm", "-journal")) File(prev.path + sfx).delete()
    }

    // ---- this phone's changes into the log -----------------------------------------------------

    /** Runs just before a write transaction commits, inside it: queued keys become events. */
    internal fun drain(db: SQLiteDatabase) {
        if (draining.get()) return
        draining.set(true)
        try {
            while (true) {
                val items = ArrayList<LibrarySync.OutboxItem>()
                db.rawQuery("SELECT id, k FROM fermata_outbox ORDER BY id LIMIT 500", null).use { c ->
                    while (c.moveToNext()) items.add(LibrarySync.OutboxItem(c.getLong(0), c.getString(1)))
                }
                if (items.isEmpty()) return
                val syncItems = ArrayList<LibrarySync.OutboxItem>()
                for (item in items) {
                    when {
                        item.key.startsWith("TP|") -> item.key.substring(3).toLongOrNull()?.let { recordTapePlay(db, it) }
                        item.key.startsWith("TL|") -> {
                            val p = item.key.split('|')
                            val day = p.getOrNull(1)?.toIntOrNull()
                            val video = p.getOrNull(2)
                            if (day != null && video != null) recordTapeListen(db, day, video)
                        }
                        else -> syncItems.add(item)
                    }
                }
                if (syncItems.isNotEmpty()) {
                    for (w in sync.buildWrites(syncItems)) {
                        if (importing.get()) recordImported(db, w) else recordWrite(db, w.path, w.merge, w.data)
                    }
                    recordTyped(db, syncItems)
                }
                db.execSQL("DELETE FROM fermata_outbox WHERE id <= ?", arrayOf<Any>(items.last().id))
            }
        } finally {
            draining.set(false)
        }
    }

    /**
     * The 5.9.1 tables' state, recorded by the first-launch import (or, after a rollback and back,
     * what 5.9.1 changed since): each document write goes into the log at the time its change was
     * made, the same time the document carries and the screens compare. Recorded at the time of
     * the upgrade, an old like sorted after a newer unlike from another phone: the log (and
     * Fermata's library) said "liked" while the screens, comparing the documents' own times, said
     * "not liked", until the next merge re-recorded it.
     * - likes/ and hidden/: one write at its `at`.
     * - playlists/: a deleted playlist is one full write at its header time; otherwise the header
     *   at its own time and the songs at theirs (grouped by time), so each field folds in the same
     *   order the playlist merge compares.
     * - Anything else (history, the clear): as before.
     */
    private fun recordImported(db: SQLiteDatabase, w: LibrarySync.CloudWrite) {
        fun t(v: Any?) = num(v).coerceAtLeast(1L)
        when {
            w.path.startsWith("likes/") || w.path.startsWith("hidden/") ->
                recordWrite(db, w.path, w.merge, w.data, at = t(w.data["at"]))
            w.path.startsWith("playlists/") -> {
                val header = w.data.filterKeys { it != "t" }
                val headerAt = maxOf(t(w.data["c"]), t(w.data["h"]), t(w.data["b"]))
                if (!w.merge) {
                    recordWrite(db, w.path, false, w.data, at = headerAt)
                    return
                }
                recordWrite(db, w.path, true, header, at = headerAt)
                @Suppress("UNCHECKED_CAST")
                val songs = w.data["t"] as? Map<String, Any?> ?: return
                val byTime = java.util.TreeMap<Long, LinkedHashMap<String, Any?>>()
                for ((song, entry) in songs) {
                    val at = com.minim.music.data.sync.SyncCodec.decodeEntry(entry)?.at?.coerceAtLeast(1L) ?: headerAt
                    byTime.getOrPut(at) { LinkedHashMap() }[song] = entry
                }
                for ((at, group) in byTime) recordWrite(db, w.path, true, mapOf("t" to group), at = at)
            }
            else -> recordWrite(db, w.path, w.merge, w.data)
        }
    }

    /** Likes, unlikes and "not interested" also as Fermata's typed events (taste reads those). */
    private fun recordTyped(db: SQLiteDatabase, items: List<LibrarySync.OutboxItem>) {
        if (applying.get() && !importing()) return // merged from elsewhere: the other phone recorded it
        val stamped = importing.get()
        for (item in items) {
            val parts = item.key.split('|')
            val song = parts.getOrNull(1) ?: continue
            when (parts[0]) {
                "L" -> db.rawQuery("SELECT is_liked, title, artist, duration_sec, like_changed_at FROM tracks WHERE id = ?", arrayOf(song)).use { c ->
                    if (!c.moveToFirst()) return@use
                    val ref = TrackRef(song, c.getString(1) ?: "", c.getString(2) ?: "", c.getLong(3) * 1000)
                    val liked = c.getInt(0) == 1
                    val last = meta("like|$song")
                    if (last == liked.toString()) return@use
                    val body = if (liked) EventBody.Like(Subject.Track(ref)) else EventBody.Unlike(Subject.Track(ref))
                    // imported: at the time of the change, like its write (see recordImported)
                    if (stamped) fermata.recordAt(body, c.getLong(4).coerceAtLeast(1L)) else fermata.record(body)
                    setMeta(db, "like|$song", liked.toString())
                }
                "H" -> db.rawQuery("SELECT title, artist, added_at FROM not_interested WHERE track_id = ?", arrayOf(song)).use { c ->
                    if (!c.moveToFirst() || meta("hide|$song") != null) return@use
                    val body = EventBody.NotInterested(Subject.Track(TrackRef(song, c.getString(0) ?: "", c.getString(1) ?: "")))
                    if (stamped) fermata.recordAt(body, c.getLong(2).coerceAtLeast(1L)) else fermata.record(body)
                    setMeta(db, "hide|$song", "1")
                }
            }
        }
    }

    private fun importing(): Boolean = meta("imported") == null

    /**
     * Records one document write, unless it equals the last one recorded for that path. [at]: an
     * imported change, recorded at the time it was made ([recordImported]); otherwise now.
     */
    private fun recordWrite(db: SQLiteDatabase, path: String, merge: Boolean, data: Map<String, Any?>, at: Long? = null) {
        val json = JSONObject().put("p", path).put("m", merge).put("d", toJson(data)).toString()
        val hash = sha1(json)
        val last = db.rawQuery("SELECT hash FROM fermata_written WHERE path = ?", arrayOf(path)).use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
        if (last == hash) return
        val history = path.startsWith("history/") || path.startsWith("tape_")
        val body = EventBody.Unknown(if (history) HISTORY_WRITE else WRITE, json)
        if (at != null) fermata.recordAt(body, at) else fermata.record(body)
        db.execSQL("INSERT OR REPLACE INTO fermata_written (path, hash) VALUES (?, ?)", arrayOf<Any>(path, hash))
    }

    private fun recordTapePlay(db: SQLiteDatabase, rowId: Long) {
        db.rawQuery(
            "SELECT video_id, title, channel, channel_id, thumbnail_url, duration_sec, played_at, day, fermata_key FROM tape_plays WHERE id = ?",
            arrayOf(rowId.toString())
        ).use { c ->
            if (!c.moveToFirst() || !c.isNull(8)) return // gone, or another phone's
            val data = linkedMapOf<String, Any?>(
                "v" to c.getString(0), "t" to c.getString(1), "c" to c.getString(2), "ci" to c.getString(3),
                "th" to c.getString(4), "du" to c.getLong(5), "at" to c.getLong(6), "d" to c.getLong(7),
            )
            recordWrite(db, "tape_plays/${fermata.deviceId}.$rowId", false, data)
        }
    }

    private fun recordTapeListen(db: SQLiteDatabase, day: Int, video: String) {
        val rows = LinkedHashMap<String, Any?>()
        var meta: Map<String, Any?> = emptyMap()
        db.rawQuery(
            "SELECT hour, speed, ms, title, channel, channel_id, thumbnail_url, duration_sec FROM tape_listen_time WHERE day = ? AND video_id = ?",
            arrayOf(day.toString(), video)
        ).use { c ->
            while (c.moveToNext()) {
                rows["${c.getInt(0)}:${c.getInt(1)}"] = c.getLong(2)
                meta = mapOf("t" to c.getString(3), "c" to c.getString(4), "ci" to c.getString(5), "th" to c.getString(6), "du" to c.getLong(7))
            }
        }
        recordWrite(db, "tape_listen/$day/${fermata.deviceId}/$video", false, mapOf("d" to day.toLong(), "v" to video, "r" to rows, "m" to meta))
    }

    // ---- the log back into the tables ----------------------------------------------------------

    /**
     * Brings a merge, import or restore into the screens' tables. Fermata runs this inside the
     * transaction that adds the events to the log (the log lives in this database), so both are
     * saved together. If it throws, the merge throws and nothing of it is kept: the next merge
     * brings the same events again, and the error reaches the caller (the status line).
     */
    private fun applyChange(change: Fermata.Change) {
        if (applying.get() || !imported()) return
        if (change.historyCleared) fermata.clearedBefore()?.let { applyClear(it) }
        val paths = pathsOf(change)
        if (paths == null || paths.isNotEmpty()) applyPaths(paths)
    }

    private fun pathsOf(change: Fermata.Change): Set<String>? =
        if (change.origin == Fermata.ChangeOrigin.RESTORE) null
        else change.added.mapNotNullTo(HashSet()) { writeOf(it)?.first }

    /** After the change is saved: reload caches and tell the screens. */
    private fun onChange(change: Fermata.Change) {
        if (applying.get() || !imported()) return
        val paths = pathsOf(change)
        if (change.historyCleared || paths == null || paths.isNotEmpty()) changed()
    }

    /**
     * The log's document writes folded in log order (time, then phone, then counter), the way
     * Firestore merges them: a full write replaces the document, a merge write merges map fields
     * into it, nested maps included.
     */
    fun foldedDocs(): Map<String, Map<String, Any?>> = fold().first

    /**
     * [foldedDocs], and for each path whose latest write replaces the document, that write as
     * recorded (what [recordWrite] hashes).
     */
    private fun fold(): Pair<Map<String, Map<String, Any?>>, Map<String, String>> {
        val writes = fermata.events().mapNotNull { e -> writeOf(e)?.let { e to it } }
            .sortedWith(compareBy({ it.first.timestamp }, { it.first.id.device }, { it.first.id.counter }))
        val docs = LinkedHashMap<String, MutableMap<String, Any?>>()
        val latest = HashMap<String, String>()
        for ((e, w) in writes) {
            val (path, merge, data) = w
            if (!merge) {
                docs[path] = deepCopy(data)
                latest[path] = (e.body as EventBody.Unknown).json
            } else {
                deepMerge(docs.getOrPut(path) { LinkedHashMap() }, data)
                latest.remove(path)
            }
        }
        return docs to latest
    }

    /** Applies the folded documents at [paths] (all when null) to the tables. */
    fun applyPaths(paths: Set<String>?) {
        beforeApplyForTest?.invoke()
        val (all, latest) = fold()
        val docs = all.filterKeys { paths == null || it in paths }
        if (docs.isEmpty()) return
        // What recordWrite compares with is the log's latest write for the path, whichever phone
        // made it, not this phone's own last write: when the tables keep a newer state than the
        // log's latest (an older change merged in later), that state is recorded again even if
        // this phone recorded the same document before. Skipping it left the log saying "liked"
        // while the screen said "not liked", on one phone and then on the others.
        lib.inWrite { db ->
            for (path in docs.keys) {
                val json = latest[path]
                if (json != null) {
                    db.execSQL("INSERT OR REPLACE INTO fermata_written (path, hash) VALUES (?, ?)", arrayOf<Any>(path, sha1(json)))
                } else if (!path.startsWith("history/") && !path.startsWith("tape_")) {
                    db.execSQL("DELETE FROM fermata_written WHERE path = ?", arrayOf<Any>(path))
                }
            }
        }
        fun group(prefix: String) = docs.filterKeys { it.startsWith(prefix) }
            .map { (k, v) -> LibrarySync.RemoteDoc(k.substring(prefix.length), v) }
        val was = applying.get()
        applying.set(true)
        try {
            group("likes/").takeIf { it.isNotEmpty() }?.let { sync.applyLikes(it) }
            group("hidden/").takeIf { it.isNotEmpty() }?.let { sync.applyHidden(it) }
            group("playlists/").takeIf { it.isNotEmpty() }?.let { sync.applyPlaylists(it) }
            group("history/").takeIf { it.isNotEmpty() }?.let {
                (if (signedIn()) LibrarySync(lib, outbox = "fermata_outbox", skipOtherListening = true) else sync).applyHistory(it)
            }
            val tapes = docs.filterKeys { it.startsWith("tape_") }
            if (tapes.isNotEmpty()) lib.inWrite { db -> for ((p, d) in tapes) applyTape(db, p, d) }
        } finally {
            applying.set(was)
        }
    }

    private fun applyTape(db: SQLiteDatabase, path: String, d: Map<String, Any?>) {
        val parts = path.split('/')
        when (parts[0]) {
            "tape_plays" -> {
                val device = parts.getOrNull(1)?.substringBeforeLast('.') ?: return
                if (device == fermata.deviceId) return
                val cv = android.content.ContentValues().apply {
                    put("video_id", d["v"] as? String ?: return)
                    put("title", d["t"] as? String ?: ""); put("channel", d["c"] as? String ?: "")
                    put("channel_id", d["ci"] as? String ?: ""); put("thumbnail_url", d["th"] as? String ?: "")
                    put("duration_sec", num(d["du"])); put("played_at", num(d["at"])); put("day", num(d["d"]))
                    put("fermata_key", path)
                }
                db.insertWithOnConflict("tape_plays", null, cv, SQLiteDatabase.CONFLICT_IGNORE)
            }
            "tape_listen" -> {
                val day = parts.getOrNull(1)?.toIntOrNull() ?: return
                val device = parts.getOrNull(2) ?: return
                val video = parts.getOrNull(3) ?: return
                if (device == fermata.deviceId) return
                @Suppress("UNCHECKED_CAST") val m = d["m"] as? Map<String, Any?> ?: emptyMap()
                db.execSQL("DELETE FROM tape_listen_remote WHERE device = ? AND day = ? AND video_id = ?", arrayOf<Any>(device, day, video))
                @Suppress("UNCHECKED_CAST") val rows = d["r"] as? Map<String, Any?> ?: emptyMap()
                for ((k, ms) in rows) {
                    val hour = k.substringBefore(':').toIntOrNull() ?: continue
                    val speed = k.substringAfter(':').toIntOrNull() ?: continue
                    db.execSQL(
                        """INSERT OR REPLACE INTO tape_listen_remote
                           (device, day, hour, video_id, speed, title, channel, channel_id, thumbnail_url, duration_sec, ms)
                           VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                        arrayOf<Any>(
                            device, day, hour, video, speed, m["t"] as? String ?: "", m["c"] as? String ?: "",
                            m["ci"] as? String ?: "", m["th"] as? String ?: "", num(m["du"]), num(ms)
                        )
                    )
                }
            }
        }
    }

    /**
     * A "clear listening history" from another phone (or a restore that carried one): drop this
     * phone's history from before [before], as that phone did. Likes, playlists and "not
     * interested" stay.
     */
    internal fun applyClear(before: Long) {
        val day = Days.keyOf(before)
        val hour = java.time.Instant.ofEpochMilli(before).atZone(java.time.ZoneId.systemDefault()).hour
        val was = applying.get()
        applying.set(true)
        try {
            lib.inWrite { db ->
                // a clear merged from another phone's backup reaches Firestore from here too
                LibraryDatabase.markHistoryCleared(db, before)
                LibraryDatabase.deleteHistoryBefore(db, before, day, hour)
            }
        } finally {
            applying.set(was)
        }
    }

    // ---- clear, backups ------------------------------------------------------------------------

    /** Called by LibraryDatabase.clearHistory inside its transaction. */
    internal fun onClearHistory(db: SQLiteDatabase) {
        // Firestore's history/cleared was marked by deleteHistoryBefore, at the wall-clock time
        fermata.clearHistory()
        db.execSQL("DELETE FROM fermata_written WHERE path LIKE 'history/%' OR path LIKE 'tape_%'")
        db.execSQL("DELETE FROM fermata_journal")
    }

    // ---- the playback journal ------------------------------------------------------------------

    val journal = JournalSink()

    /**
     * Replaces 5.9.0's fermata_events writes. A play or tape play is kept in `fermata_journal`
     * while it goes (written at track start, updated every 5 s, as before) and recorded into the
     * log as one Play or TapePlay event when the next track starts, at the next app start, or
     * before a backup. Skips and queue adds are recorded at once.
     */
    inner class JournalSink : FermataSink {
        override fun insert(e: FermataEvent): Long {
            val playLike = e.type == FermataType.PLAY || e.type == FermataType.TAPE_PLAY
            if (!playLike) {
                lib.inWrite { db ->
                    // a skip ends the play it belongs to: record that play first
                    if (e.type == FermataType.SKIP) finishOpen(db, trackId = e.trackId)
                    recordRow(rowOf(e, 0L, e.listenedMs))
                }
                return -1L
            }
            return lib.inWrite { db ->
                finishOpen(db)
                val cv = android.content.ContentValues().apply {
                    put("type", e.type); put("track_id", e.trackId); put("title", e.title); put("artist", e.artist)
                    put("duration_ms", e.durationMs); put("ts", e.ts); put("session_id", e.sessionId)
                    put("prev_track_id", e.prevTrackId); put("source", e.source); put("listened_ms", e.listenedMs ?: 0L)
                    put("position_ms", e.positionMs); put("speed_pct", e.speedPct); put("updated_at", System.currentTimeMillis())
                }
                db.insert("fermata_journal", null, cv)
            }
        }

        override fun updatePlay(rowId: Long, listenedMs: Long, durationMs: Long?, speedPct: Int?) {
            if (rowId <= 0L) return
            val cv = android.content.ContentValues().apply {
                put("listened_ms", listenedMs); put("updated_at", System.currentTimeMillis())
                if (durationMs != null) put("duration_ms", durationMs)
                if (speedPct != null) put("speed_pct", speedPct)
            }
            lib.writableDatabase.update("fermata_journal", cv, "id = ?", arrayOf(rowId.toString()))
        }

        /** Records journal rows not updated for [idleMs] (0: all of them). */
        fun finishStale(idleMs: Long) {
            lib.inWrite { db -> finishOpen(db, System.currentTimeMillis() - idleMs) }
        }

        private fun finishOpen(db: SQLiteDatabase, updatedBefore: Long = Long.MAX_VALUE, trackId: String? = null) {
            val rows = ArrayList<Pair<Long, FermataEventRow>>()
            db.rawQuery(
                """SELECT id, type, track_id, title, artist, duration_ms, ts, session_id, prev_track_id, source,
                   listened_ms, position_ms, speed_pct FROM fermata_journal
                   WHERE updated_at <= ? ORDER BY id""",
                arrayOf(updatedBefore.toString())
            ).use { c ->
                while (c.moveToNext()) {
                    if (trackId != null && c.getString(2) != trackId) continue
                    rows += c.getLong(0) to FermataEventRow(
                        id = c.getLong(0), type = c.getString(1), trackId = c.getString(2), title = c.getString(3),
                        artist = c.getString(4), durationMs = if (c.isNull(5)) null else c.getLong(5), ts = c.getLong(6),
                        sessionId = c.getString(7), prevTrackId = c.getString(8), source = c.getString(9),
                        listenedMs = c.getLong(10), positionMs = if (c.isNull(11)) null else c.getLong(11),
                        deviceId = fermata.deviceId, speedPct = if (c.isNull(12)) null else c.getInt(12),
                    )
                }
            }
            for ((id, row) in rows) {
                recordRow(row)
                db.delete("fermata_journal", "id = ?", arrayOf(id.toString()))
            }
        }

        private fun rowOf(e: FermataEvent, id: Long, listened: Long?) = FermataEventRow(
            id = id.coerceAtLeast(1L), type = e.type, trackId = e.trackId, title = e.title, artist = e.artist,
            durationMs = e.durationMs, ts = e.ts, sessionId = e.sessionId, prevTrackId = e.prevTrackId,
            source = e.source, listenedMs = listened, positionMs = e.positionMs, deviceId = fermata.deviceId,
            speedPct = e.speedPct,
        )

        /** The same row-to-event rules as the 5.9.0 import, recorded under a new id of this phone. */
        private fun recordRow(row: FermataEventRow) {
            val e = MinimImport.convert(MinimTables(fermataEvents = listOf(row))).events.firstOrNull() ?: return
            fermata.record(e.body, e.ctx.source, e.ctx.sessionId, e.ctx.previousTrackId, at = e.timestamp)
        }
    }

    // ---- helpers -------------------------------------------------------------------------------

    /** Same key as CloudSync's, so Firestore and the backups name this phone the same way. */
    @SuppressLint("HardwareIds") // hashed into the same key CloudSync uses, never sent as it is
    private fun deviceKey(): String {
        val androidId = try {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
        } catch (_: Throwable) { null }
        val fallback = sync.state("device_seed") ?: java.util.UUID.randomUUID().toString().also { sync.setState("device_seed", it) }
        return SyncIds.deviceKey(androidId, fallback)
    }

    private fun meta(k: String): String? =
        lib.writableDatabase.rawQuery("SELECT v FROM fermata_meta WHERE k = ?", arrayOf(k)).use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }

    private fun setMeta(db: SQLiteDatabase, k: String, v: String) =
        db.execSQL("INSERT OR REPLACE INTO fermata_meta (k, v) VALUES (?, ?)", arrayOf<Any>(k, v))

    companion object {
        private const val TAG = "FermataBridge"
        const val WRITE = "minim.write"
        const val HISTORY_WRITE = EventBody.APP_HISTORY_PREFIX + "minim.write"

        /** (path, merge, data) of a Minim document write, or null for any other event. */
        fun writeOf(e: Event): Triple<String, Boolean, Map<String, Any?>>? {
            val b = e.body as? EventBody.Unknown ?: return null
            if (b.type != WRITE && b.type != HISTORY_WRITE) return null
            return try {
                val o = JSONObject(b.json)
                @Suppress("UNCHECKED_CAST")
                Triple(o.getString("p"), o.optBoolean("m"), fromJson(o.getJSONObject("d")) as Map<String, Any?>)
            } catch (_: Exception) {
                null
            }
        }

        private fun num(v: Any?): Long = (v as? Number)?.toLong() ?: (v as? String)?.toLongOrNull() ?: 0L

        internal fun toJson(v: Any?): Any = when (v) {
            null -> JSONObject.NULL
            is Map<*, *> -> JSONObject().also { o -> for ((k, x) in v) o.put(k.toString(), toJson(x)) }
            is Iterable<*> -> JSONArray().also { a -> for (x in v) a.put(toJson(x)) }
            is Array<*> -> JSONArray().also { a -> for (x in v) a.put(toJson(x)) }
            is Int -> v.toLong()
            is Number, is Boolean, is String -> v
            else -> v.toString()
        }

        internal fun fromJson(v: Any?): Any? = when (v) {
            null, JSONObject.NULL -> null
            is JSONObject -> LinkedHashMap<String, Any?>().also { m -> for (k in v.keys()) m[k] = fromJson(v.get(k)) }
            is JSONArray -> (0 until v.length()).map { fromJson(v.get(it)) }
            is Int -> v.toLong()
            else -> v
        }

        @Suppress("UNCHECKED_CAST")
        private fun deepCopy(m: Map<String, Any?>): MutableMap<String, Any?> =
            LinkedHashMap<String, Any?>().also { out ->
                for ((k, v) in m) out[k] = if (v is Map<*, *>) deepCopy(v as Map<String, Any?>) else v
            }

        @Suppress("UNCHECKED_CAST")
        private fun deepMerge(into: MutableMap<String, Any?>, from: Map<String, Any?>) {
            for ((k, v) in from) {
                val cur = into[k]
                if (v is Map<*, *> && cur is MutableMap<*, *>) deepMerge(cur as MutableMap<String, Any?>, v as Map<String, Any?>)
                else into[k] = if (v is Map<*, *>) deepCopy(v as Map<String, Any?>) else v
            }
        }

        private fun sha1(s: String): String =
            MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
