package com.minim.music.data.sync

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import com.minim.music.data.local.LibraryDatabase
import com.minim.music.data.stats.Days
import java.io.File

/**
 * Minim 5.0.0 part 2: the phone side of cloud sync. Reads what has to go up (sync_outbox) and
 * builds the Firestore writes for it, and merges what came down into the local database. No
 * Firestore code here (that's [CloudSync]): documents come in and go out as plain maps, so all of
 * this runs in unit tests.
 *
 * Outbox keys (one per thing that changed, deduped):
 * - L|songId           like or unlike             -> likes/{songId}
 * - H|songId           "not interested"           -> hidden/{songId}
 * - P|playlistUid      playlist made / deleted    -> playlists/{uid}
 * - T|playlistUid|song song added / removed       -> playlists/{uid} (t.{song})
 * - E|eventId          a counted play             -> history/{day} (p.{eventId})
 * - M|day|song         this phone's listening time and taps for a song on a day -> history/{day}
 * - N|day|song         same, every phone's numbers (only the full merge after signing in)
 */
class LibrarySync(private val lib: LibraryDatabase) {

    data class OutboxItem(val id: Long, val key: String)

    /** One Firestore write, relative to users/{uid}. [CloudSync] adds the "u" server time. */
    data class CloudWrite(val path: String, val data: Map<String, Any?>, val merge: Boolean)

    data class RemoteDoc(val id: String, val data: Map<String, Any?>)

    private val db: SQLiteDatabase get() = lib.writableDatabase

    /** This phone's device key in synced listening time (set by [ensureDevice]). */
    @Volatile var myDevice: String = ""
        private set

    // ---- small state table ---------------------------------------------------------------------

    fun state(k: String): String? =
        db.rawQuery("SELECT v FROM sync_state WHERE k = ?", arrayOf(k)).use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }

    fun setState(k: String, v: String?) {
        if (v == null) db.delete("sync_state", "k = ?", arrayOf(k))
        else db.execSQL("INSERT OR REPLACE INTO sync_state (k, v) VALUES (?, ?)", arrayOf<Any>(k, v))
    }

    /**
     * Sets this phone's device key. If the database came from another phone (a restored backup),
     * the listening it holds as "this phone" really belongs to that phone: it's moved under that
     * phone's key first, so it's never uploaded twice under two keys.
     */
    fun ensureDevice(current: String): String {
        val d = db
        val stored = state("device")
        if (stored != null && stored != current) {
            d.beginTransaction()
            try {
                d.execSQL(
                    """
                    INSERT OR IGNORE INTO listen_time (day, hour, track_id, title, artist, thumbnail_url, ms, device)
                    SELECT day, hour, track_id, title, artist, thumbnail_url, 0, ? FROM listen_time WHERE device = ''
                    """.trimIndent(),
                    arrayOf<Any>(stored)
                )
                d.execSQL(
                    """
                    UPDATE listen_time SET ms = MAX(ms, (SELECT o.ms FROM listen_time o WHERE o.device = ''
                        AND o.day = listen_time.day AND o.hour = listen_time.hour AND o.track_id = listen_time.track_id))
                    WHERE device = ? AND EXISTS (SELECT 1 FROM listen_time o WHERE o.device = ''
                        AND o.day = listen_time.day AND o.hour = listen_time.hour AND o.track_id = listen_time.track_id)
                    """.trimIndent(),
                    arrayOf<Any>(stored)
                )
                d.execSQL("DELETE FROM listen_time WHERE device = ''")
                setState("device", current)
                d.setTransactionSuccessful()
            } finally {
                d.endTransaction()
            }
        } else if (stored == null) {
            setState("device", current)
        }
        myDevice = current
        return current
    }

    // ---- outbox --------------------------------------------------------------------------------

    fun readOutbox(limit: Int): List<OutboxItem> {
        val out = ArrayList<OutboxItem>()
        db.rawQuery("SELECT id, k FROM sync_outbox ORDER BY id LIMIT ?", arrayOf(limit.toString())).use { c ->
            while (c.moveToNext()) out.add(OutboxItem(c.getLong(0), c.getString(1)))
        }
        return out
    }

    /** Done with these. A key that changed again since it was read has a new id and stays. */
    fun ack(items: List<OutboxItem>) {
        if (items.isEmpty()) return
        val d = db
        d.beginTransaction()
        try {
            for (i in items) d.execSQL("DELETE FROM sync_outbox WHERE id = ? AND k = ?", arrayOf<Any>(i.id, i.key))
            d.setTransactionSuccessful()
        } finally {
            d.endTransaction()
        }
    }

    fun outboxCount(): Int =
        db.rawQuery("SELECT COUNT(*) FROM sync_outbox", null).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }

    fun outboxKeys(): List<String> = readOutbox(Int.MAX_VALUE).map { it.key }

    fun clearOutbox() {
        db.delete("sync_outbox", null, null)
    }

    private fun dequeue(d: SQLiteDatabase, key: String) {
        d.execSQL("DELETE FROM sync_outbox WHERE k = ?", arrayOf<Any>(key))
    }

    private fun enqueue(d: SQLiteDatabase, key: String) {
        d.execSQL("INSERT OR IGNORE INTO sync_outbox (k) VALUES (?)", arrayOf<Any>(key))
    }

    /**
     * The full merge after signing in: everything on the phone is queued to go up. Merging the
     * cloud right after takes back off every key the cloud already has, so only what the cloud is
     * missing (or has older) is written.
     */
    fun enqueueAll() {
        val d = db
        d.beginTransaction()
        try {
            d.execSQL("INSERT OR IGNORE INTO sync_outbox (k) SELECT 'L|' || id FROM tracks WHERE is_liked = 1 OR like_changed_at > 0")
            d.execSQL("INSERT OR IGNORE INTO sync_outbox (k) SELECT 'H|' || track_id FROM not_interested")
            d.execSQL("INSERT OR IGNORE INTO sync_outbox (k) SELECT 'P|' || uid FROM playlists WHERE uid IS NOT NULL")
            d.execSQL("INSERT OR IGNORE INTO sync_outbox (k) SELECT 'P|' || uid FROM playlist_deleted")
            d.execSQL(
                """
                INSERT OR IGNORE INTO sync_outbox (k)
                SELECT 'T|' || p.uid || '|' || pt.track_id FROM playlist_tracks pt
                INNER JOIN playlists p ON p.id = pt.playlist_id WHERE p.uid IS NOT NULL
                """.trimIndent()
            )
            d.execSQL("INSERT OR IGNORE INTO sync_outbox (k) SELECT 'T|' || playlist_uid || '|' || track_id FROM playlist_removed")
            d.execSQL("INSERT OR IGNORE INTO sync_outbox (k) SELECT 'E|' || event_id FROM plays WHERE event_id IS NOT NULL")
            d.execSQL("INSERT OR IGNORE INTO sync_outbox (k) SELECT 'N|' || day || '|' || track_id FROM listen_time GROUP BY day, track_id")
            val taps = ArrayList<Pair<String, Long>>()
            d.rawQuery("SELECT id, last_played_at FROM tracks WHERE last_played_at > 0", null).use { c ->
                while (c.moveToNext()) taps.add(c.getString(0) to c.getLong(1))
            }
            for ((id, lp) in taps) enqueue(d, "N|${Days.keyOf(lp)}|$id")
            d.setTransactionSuccessful()
        } finally {
            d.endTransaction()
        }
    }

    // ---- building the writes -------------------------------------------------------------------

    private class DayDoc {
        val p = LinkedHashMap<String, Any?>()
        val s = LinkedHashMap<String, Any?>()
        val l = LinkedHashMap<String, MutableMap<String, Any?>>()
        val r = LinkedHashMap<String, MutableMap<String, Any?>>()
        fun isEmpty() = p.isEmpty() && l.isEmpty() && r.isEmpty()
    }

    /** The Firestore writes for these outbox keys: one write per document, however many keys. */
    fun buildWrites(items: List<OutboxItem>): List<CloudWrite> {
        val d = db
        val writes = ArrayList<CloudWrite>()
        val playlists = LinkedHashMap<String, MutableSet<String>>()
        val days = java.util.TreeMap<Int, DayDoc>()
        val metaCache = HashMap<String, SongMeta?>()
        fun meta(song: String) = metaCache.getOrPut(song) { metaOf(d, song) }

        for (item in items) {
            val parts = item.key.split('|')
            when (parts[0]) {
                "L" -> {
                    val song = parts.getOrNull(1) ?: continue
                    if (!SyncIds.isSyncableSongId(song)) continue
                    d.rawQuery("SELECT is_liked, like_changed_at FROM tracks WHERE id = ?", arrayOf(song)).use { c ->
                        if (c.moveToFirst()) {
                            val liked = c.getInt(0) == 1
                            val at = c.getLong(1)
                            if (liked || at > 0L) {
                                writes.add(CloudWrite("likes/$song", SyncCodec.likeDoc(LikeState(liked, at), meta(song)), merge = false))
                            }
                        }
                    }
                }
                "H" -> {
                    val song = parts.getOrNull(1) ?: continue
                    if (!SyncIds.isSyncableSongId(song)) continue
                    d.rawQuery("SELECT title, artist, added_at FROM not_interested WHERE track_id = ?", arrayOf(song)).use { c ->
                        if (c.moveToFirst()) {
                            val m = SongMeta(c.getString(0), c.getString(1), "", 0L)
                            writes.add(CloudWrite("hidden/$song", mapOf("at" to c.getLong(2), "m" to SyncCodec.encodeMeta(m)), merge = false))
                        }
                    }
                }
                "P" -> { val uid = parts.getOrNull(1) ?: continue; playlists.getOrPut(uid) { LinkedHashSet() } }
                "T" -> {
                    val uid = parts.getOrNull(1) ?: continue
                    val song = parts.getOrNull(2) ?: continue
                    playlists.getOrPut(uid) { LinkedHashSet() }.add(song)
                }
                "E" -> {
                    val eid = item.key.substring(2)
                    d.rawQuery("SELECT track_id, played_at, listened_ms, tracked FROM plays WHERE event_id = ?", arrayOf(eid)).use { c ->
                        if (c.moveToFirst()) {
                            val song = c.getString(0)
                            if (SyncIds.isSyncableSongId(song)) {
                                val ev = PlayEv(song, c.getLong(1), c.getLong(2), c.getInt(3) == 1)
                                val doc = days.getOrPut(Days.keyOf(ev.playedAt)) { DayDoc() }
                                doc.p[eid] = SyncCodec.encodePlay(ev)
                                meta(song)?.let { doc.s[song] = SyncCodec.encodeMeta(it) }
                            }
                        }
                    }
                }
                "M", "N" -> {
                    val day = parts.getOrNull(1)?.toIntOrNull() ?: continue
                    val song = parts.getOrNull(2) ?: continue
                    if (!SyncIds.isSyncableSongId(song)) continue
                    val ownOnly = parts[0] == "M"
                    val doc = days.getOrPut(day) { DayDoc() }
                    val sql = "SELECT hour, ms, device FROM listen_time WHERE day = ? AND track_id = ?" +
                        if (ownOnly) " AND device = ''" else ""
                    var any = false
                    d.rawQuery(sql, arrayOf(day.toString(), song)).use { c ->
                        while (c.moveToNext()) {
                            val ms = c.getLong(1)
                            if (ms <= 0L) continue
                            val dev = c.getString(2).ifEmpty { myDevice }
                            if (dev.isEmpty()) continue
                            doc.l.getOrPut(dev) { LinkedHashMap() }[SyncCodec.listenKey(c.getInt(0), song)] = ms
                            any = true
                        }
                    }
                    val lp = lastPlayed(d, song)
                    if (lp > 0L && Days.keyOf(lp) == day && myDevice.isNotEmpty()) {
                        doc.r.getOrPut(myDevice) { LinkedHashMap() }[song] = lp
                        any = true
                    }
                    if (any) meta(song)?.let { doc.s[song] = SyncCodec.encodeMeta(it) }
                }
            }
        }

        for ((uid, songs) in playlists) {
            if (!SyncIds.isSyncableSongId(uid)) continue
            val local = readLocalPlaylist(d, uid) ?: continue
            if (local.deleted) {
                // a full write (not a merge): the deleted playlist's songs go too
                writes.add(CloudWrite("playlists/$uid", SyncCodec.playlistHeader(local), merge = false))
            } else {
                val data = LinkedHashMap<String, Any?>(SyncCodec.playlistHeader(local))
                val t = LinkedHashMap<String, Any?>()
                for (song in songs) {
                    if (!SyncIds.isSyncableSongId(song)) continue
                    val e = local.tracks[song] ?: continue
                    t[song] = SyncCodec.encodeEntry(e)
                }
                if (t.isNotEmpty()) data["t"] = t
                writes.add(CloudWrite("playlists/$uid", data, merge = true))
            }
        }

        for ((day, doc) in days) {
            if (doc.isEmpty()) continue
            val data = LinkedHashMap<String, Any?>()
            data["d"] = day.toLong()
            if (doc.p.isNotEmpty()) data["p"] = doc.p
            if (doc.s.isNotEmpty()) data["s"] = doc.s
            if (doc.l.isNotEmpty()) data["l"] = doc.l
            if (doc.r.isNotEmpty()) data["r"] = doc.r
            writes.add(CloudWrite("history/$day", data, merge = true))
        }
        return writes
    }

    private fun lastPlayed(d: SQLiteDatabase, song: String): Long =
        d.rawQuery("SELECT last_played_at FROM tracks WHERE id = ?", arrayOf(song)).use { c ->
            if (c.moveToFirst()) c.getLong(0) else 0L
        }

    private fun metaOf(d: SQLiteDatabase, song: String): SongMeta? {
        d.rawQuery("SELECT title, artist, thumbnail_url, duration_sec FROM tracks WHERE id = ?", arrayOf(song)).use { c ->
            if (c.moveToFirst()) return SongMeta(c.getString(0), c.getString(1), c.getString(2), c.getLong(3))
        }
        d.rawQuery(
            "SELECT title, artist, thumbnail_url FROM listen_time WHERE track_id = ? AND title != '' LIMIT 1",
            arrayOf(song)
        ).use { c ->
            if (c.moveToFirst()) return SongMeta(c.getString(0), c.getString(1), c.getString(2), 0L)
        }
        return null
    }

    // ---- local playlist state ------------------------------------------------------------------

    internal fun readLocalPlaylist(d: SQLiteDatabase, uid: String): PlState? {
        d.rawQuery("SELECT id, name, created_at, changed_at, born FROM playlists WHERE uid = ?", arrayOf(uid)).use { c ->
            if (c.moveToFirst()) {
                val id = c.getLong(0)
                val tracks = HashMap<String, PlEntry>()
                d.rawQuery(
                    """
                    SELECT pt.track_id, pt.position, pt.changed_at, t.title, t.artist, t.thumbnail_url, t.duration_sec
                    FROM playlist_tracks pt LEFT JOIN tracks t ON t.id = pt.track_id WHERE pt.playlist_id = ?
                    """.trimIndent(),
                    arrayOf(id.toString())
                ).use { r ->
                    while (r.moveToNext()) {
                        val meta = if (r.isNull(3)) null else SongMeta(r.getString(3), r.getString(4), r.getString(5), r.getLong(6))
                        tracks[r.getString(0)] = PlEntry(r.getLong(1), r.getLong(2), false, meta)
                    }
                }
                d.rawQuery("SELECT track_id, at FROM playlist_removed WHERE playlist_uid = ?", arrayOf(uid)).use { r ->
                    while (r.moveToNext()) {
                        val song = r.getString(0)
                        if (song !in tracks) tracks[song] = PlEntry(0L, r.getLong(1), true, null)
                    }
                }
                return PlState(c.getString(1), c.getLong(2), c.getLong(3), c.getLong(4), false, tracks)
            }
        }
        d.rawQuery("SELECT name, created_at, at, born FROM playlist_deleted WHERE uid = ?", arrayOf(uid)).use { c ->
            if (c.moveToFirst()) return PlState(c.getString(0), c.getLong(1), c.getLong(2), c.getLong(3), true, emptyMap())
        }
        return null
    }

    private fun writeLocalPlaylist(d: SQLiteDatabase, uid: String, m: PlState) {
        var aliveId: Long? = null
        d.rawQuery("SELECT id FROM playlists WHERE uid = ?", arrayOf(uid)).use { c -> if (c.moveToFirst()) aliveId = c.getLong(0) }
        if (m.deleted) {
            aliveId?.let {
                d.delete("playlist_tracks", "playlist_id = ?", arrayOf(it.toString()))
                d.delete("playlists", "id = ?", arrayOf(it.toString()))
            }
            val cv = ContentValues().apply {
                put("uid", uid); put("name", m.name); put("created_at", m.created); put("at", m.changed); put("born", m.born)
            }
            d.insertWithOnConflict("playlist_deleted", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
            d.delete("playlist_removed", "playlist_uid = ?", arrayOf(uid))
            return
        }
        d.delete("playlist_deleted", "uid = ?", arrayOf(uid))
        var id = aliveId
        if (id == null) {
            val cv = ContentValues().apply {
                put("name", freeName(d, m.name, uid)); put("created_at", m.created); put("uid", uid)
                put("changed_at", m.changed); put("born", m.born)
            }
            id = d.insert("playlists", null, cv)
            if (id == -1L) return
        } else {
            val cv = ContentValues().apply { put("created_at", m.created); put("changed_at", m.changed); put("born", m.born) }
            // 5.3.2: a rename on another phone (its header is newer) renames it here too
            d.rawQuery("SELECT name, changed_at FROM playlists WHERE id = ?", arrayOf(id.toString())).use { c ->
                if (c.moveToFirst() && m.changed > c.getLong(1) && c.getString(0) != m.name) {
                    cv.put("name", freeName(d, m.name, uid))
                }
            }
            d.update("playlists", cv, "id = ?", arrayOf(id.toString()))
        }
        val pid = id.toString()
        // songs the merge dropped (from before the playlist was made again)
        val keepAlive = m.tracks.filterValues { !it.removed }.keys
        val keepRemoved = m.tracks.filterValues { it.removed }.keys
        val localSongs = ArrayList<String>()
        d.rawQuery("SELECT track_id FROM playlist_tracks WHERE playlist_id = ?", arrayOf(pid)).use { c ->
            while (c.moveToNext()) localSongs.add(c.getString(0))
        }
        for (s in localSongs) if (s !in keepAlive) d.delete("playlist_tracks", "playlist_id = ? AND track_id = ?", arrayOf(pid, s))
        val localRemoved = ArrayList<String>()
        d.rawQuery("SELECT track_id FROM playlist_removed WHERE playlist_uid = ?", arrayOf(uid)).use { c ->
            while (c.moveToNext()) localRemoved.add(c.getString(0))
        }
        for (s in localRemoved) if (s !in keepRemoved) d.delete("playlist_removed", "playlist_uid = ? AND track_id = ?", arrayOf(uid, s))
        for ((song, e) in m.tracks) {
            if (e.removed) {
                val cv = ContentValues().apply { put("playlist_uid", uid); put("track_id", song); put("at", e.at) }
                d.insertWithOnConflict("playlist_removed", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
            } else {
                ensureTrack(d, song, e.meta)
                val cv = ContentValues().apply {
                    put("playlist_id", id); put("track_id", song); put("position", e.pos); put("changed_at", e.at)
                }
                d.insertWithOnConflict("playlist_tracks", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
            }
        }
    }

    /** Playlist names are unique on the phone; a clash (very unlikely) gets " (2)" on this phone only. */
    private fun freeName(d: SQLiteDatabase, name: String, uid: String): String {
        var candidate = name
        var n = 2
        while (true) {
            val taken = d.rawQuery("SELECT uid FROM playlists WHERE name = ?", arrayOf(candidate)).use { c ->
                c.moveToFirst() && c.getString(0) != uid
            }
            if (!taken) return candidate
            candidate = "$name ($n)"
            n++
        }
    }

    /** Makes sure a song has a tracks row (rows, Recents and Stats join on it). */
    private fun ensureTrack(d: SQLiteDatabase, song: String, meta: SongMeta?) {
        val m = meta ?: SongMeta("Unknown song", "", "", 0L)
        d.execSQL(
            """
            INSERT OR IGNORE INTO tracks (id, title, artist, duration_sec, thumbnail_url, source_url)
            VALUES (?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            arrayOf<Any>(song, m.title, m.artist, m.durationSec, m.thumb, "https://www.youtube.com/watch?v=$song")
        )
        if (meta != null) {
            if (meta.thumb.isNotEmpty()) {
                d.execSQL("UPDATE tracks SET thumbnail_url = ? WHERE id = ? AND thumbnail_url = ''", arrayOf<Any>(meta.thumb, song))
            }
            if (meta.durationSec > 0L) {
                d.execSQL("UPDATE tracks SET duration_sec = ? WHERE id = ? AND duration_sec = 0", arrayOf<Any>(meta.durationSec, song))
            }
            if (meta.title.isNotEmpty()) {
                d.execSQL("UPDATE tracks SET title = ?, artist = ? WHERE id = ? AND title = 'Unknown song'", arrayOf<Any>(meta.title, meta.artist, song))
            }
        }
    }

    // ---- merging what came down ----------------------------------------------------------------

    private inline fun inTx(block: (SQLiteDatabase) -> Boolean): Boolean {
        val d = db
        d.beginTransaction()
        try {
            val changed = block(d)
            d.setTransactionSuccessful()
            return changed
        } finally {
            d.endTransaction()
        }
    }

    /** likes/{songId}. Returns true when the library changed. */
    fun applyLikes(docs: List<RemoteDoc>): Boolean = inTx { d ->
        var changed = false
        for (doc in docs) {
            val song = doc.id
            if (!SyncIds.isSyncableSongId(song)) continue
            val remote = SyncCodec.decodeLike(doc.data) ?: continue
            var local: LikeState? = null
            d.rawQuery("SELECT is_liked, like_changed_at FROM tracks WHERE id = ?", arrayOf(song)).use { c ->
                if (c.moveToFirst() && (c.getInt(0) == 1 || c.getLong(1) > 0L)) local = LikeState(c.getInt(0) == 1, c.getLong(1))
            }
            val merged = SyncMerge.like(local, remote) ?: continue
            if (merged != local) {
                ensureTrack(d, song, SyncCodec.decodeMeta(doc.data["m"]))
                val cv = ContentValues().apply {
                    put("is_liked", if (merged.liked) 1 else 0)
                    put("liked_at", if (merged.liked) merged.at else 0L)
                    put("like_changed_at", merged.at)
                }
                d.update("tracks", cv, "id = ?", arrayOf(song))
                changed = true
            }
            if (merged == remote) dequeue(d, "L|$song") else enqueue(d, "L|$song")
        }
        changed
    }

    /** hidden/{songId}: "not interested" only ever adds. */
    fun applyHidden(docs: List<RemoteDoc>): Boolean = inTx { d ->
        var changed = false
        for (doc in docs) {
            val song = doc.id
            if (!SyncIds.isSyncableSongId(song)) continue
            val m = SyncCodec.decodeMeta(doc.data["m"])
            val cv = ContentValues().apply {
                put("track_id", song)
                put("title", m?.title ?: "")
                put("artist", m?.artist ?: "")
                put("added_at", SyncCodec.num(doc.data["at"]) ?: System.currentTimeMillis())
            }
            if (d.insertWithOnConflict("not_interested", null, cv, SQLiteDatabase.CONFLICT_IGNORE) != -1L) changed = true
            dequeue(d, "H|$song")
        }
        changed
    }

    /** playlists/{uid}. */
    fun applyPlaylists(docs: List<RemoteDoc>): Boolean = inTx { d ->
        var changed = false
        for (doc in docs) {
            val uid = doc.id
            if (!SyncIds.isSyncableSongId(uid)) continue
            val remote = SyncCodec.decodePlaylist(doc.data) ?: continue
            val remoteN = SyncMerge.normalize(remote)
            val local = readLocalPlaylist(d, uid)
            val merged = SyncMerge.playlist(local, remote) ?: continue
            if (local == null || !local.sameAs(merged)) {
                writeLocalPlaylist(d, uid, merged)
                changed = true
            }
            if (merged.sameHeader(remoteN)) dequeue(d, "P|$uid") else enqueue(d, "P|$uid")
            if (merged.deleted) {
                d.execSQL("DELETE FROM sync_outbox WHERE k LIKE ?", arrayOf<Any>("T|$uid|%"))
            } else {
                for ((song, e) in merged.tracks) {
                    if (e.sameAs(remoteN.tracks[song])) dequeue(d, "T|$uid|$song") else enqueue(d, "T|$uid|$song")
                }
            }
        }
        changed
    }

    /** history/{day}: plays deduped by event ID, listening time per phone, taps for Recents. */
    fun applyHistory(docs: List<RemoteDoc>): Boolean = inTx { d ->
        var changed = false
        val me = myDevice
        for (doc in docs) {
            val day = SyncCodec.num(doc.data["d"])?.toInt() ?: doc.id.toIntOrNull() ?: continue
            val songs = HashMap<String, SongMeta>()
            for ((k, v) in SyncCodec.map(doc.data["s"])) SyncCodec.decodeMeta(v)?.let { songs[k] = it }

            // plays
            for ((eid, v) in SyncCodec.map(doc.data["p"])) {
                val ev = SyncCodec.decodePlay(v) ?: continue
                if (!SyncIds.isSyncableSongId(ev.songId)) continue
                var localMs: Long? = null
                d.rawQuery("SELECT listened_ms FROM plays WHERE event_id = ?", arrayOf(eid)).use { c ->
                    if (c.moveToFirst()) localMs = c.getLong(0)
                }
                val lm = localMs
                when {
                    lm == null -> {
                        ensureTrack(d, ev.songId, songs[ev.songId])
                        val cv = ContentValues().apply {
                            put("track_id", ev.songId); put("played_at", ev.playedAt); put("listened_ms", ev.listenedMs)
                            put("tracked", if (ev.tracked) 1 else 0); put("event_id", eid)
                        }
                        if (d.insertWithOnConflict("plays", null, cv, SQLiteDatabase.CONFLICT_IGNORE) != -1L) {
                            d.execSQL(
                                "UPDATE tracks SET play_count = play_count + 1, last_played_at = MAX(last_played_at, ?) WHERE id = ?",
                                arrayOf<Any>(ev.playedAt, ev.songId)
                            )
                            changed = true
                        }
                        dequeue(d, "E|$eid")
                    }
                    lm < ev.listenedMs -> {
                        d.execSQL("UPDATE plays SET listened_ms = ? WHERE event_id = ?", arrayOf<Any>(ev.listenedMs, eid))
                        changed = true
                        dequeue(d, "E|$eid")
                    }
                    lm > ev.listenedMs -> enqueue(d, "E|$eid")
                    else -> dequeue(d, "E|$eid")
                }
            }

            // listening time, per phone: the bigger number wins
            val remoteL = HashMap<String, Map<String, Long>>()
            for ((dev, v) in SyncCodec.map(doc.data["l"])) {
                val entries = HashMap<String, Long>()
                for ((k, msAny) in SyncCodec.map(v)) {
                    val ms = SyncCodec.num(msAny) ?: continue
                    entries[k] = ms
                    val (hour, song) = SyncCodec.parseListenKey(k) ?: continue
                    if (!SyncIds.isSyncableSongId(song)) continue
                    val localDev = if (dev == me) "" else dev
                    val m = songs[song]
                    var cur: Long? = null
                    d.rawQuery(
                        "SELECT ms FROM listen_time WHERE day = ? AND hour = ? AND track_id = ? AND device = ?",
                        arrayOf(day.toString(), hour.toString(), song, localDev)
                    ).use { c -> if (c.moveToFirst()) cur = c.getLong(0) }
                    val now = cur
                    if (now == null) {
                        val cv = ContentValues().apply {
                            put("day", day); put("hour", hour); put("track_id", song); put("device", localDev); put("ms", ms)
                            put("title", m?.title ?: ""); put("artist", m?.artist ?: ""); put("thumbnail_url", m?.thumb ?: "")
                        }
                        d.insertWithOnConflict("listen_time", null, cv, SQLiteDatabase.CONFLICT_IGNORE)
                        changed = true
                    } else if (now < ms) {
                        d.execSQL(
                            "UPDATE listen_time SET ms = ? WHERE day = ? AND hour = ? AND track_id = ? AND device = ?",
                            arrayOf<Any>(ms, day, hour, song, localDev)
                        )
                        changed = true
                    }
                }
                remoteL[dev] = entries
            }

            // taps (Recents): the latest wins
            val remoteR = HashMap<String, Map<String, Long>>()
            for ((dev, v) in SyncCodec.map(doc.data["r"])) {
                val entries = HashMap<String, Long>()
                for ((song, lpAny) in SyncCodec.map(v)) {
                    val lp = SyncCodec.num(lpAny) ?: continue
                    entries[song] = lp
                    if (!SyncIds.isSyncableSongId(song)) continue
                    ensureTrack(d, song, songs[song])
                    if (lp > lastPlayed(d, song)) {
                        d.execSQL("UPDATE tracks SET last_played_at = ? WHERE id = ?", arrayOf<Any>(lp, song))
                        changed = true
                    }
                }
                remoteR[dev] = entries
            }

            // what's queued for this day and already in the cloud doesn't have to go up
            val keys = ArrayList<String>()
            d.rawQuery(
                "SELECT k FROM sync_outbox WHERE k LIKE ? OR k LIKE ?",
                arrayOf("M|$day|%", "N|$day|%")
            ).use { c -> while (c.moveToNext()) keys.add(c.getString(0)) }
            for (key in keys) {
                val song = key.substring(key.indexOf('|', 2) + 1)
                val ownOnly = key.startsWith("M")
                var inCloud = true
                val sql = "SELECT hour, ms, device FROM listen_time WHERE day = ? AND track_id = ?" +
                    if (ownOnly) " AND device = ''" else ""
                d.rawQuery(sql, arrayOf(day.toString(), song)).use { c ->
                    while (c.moveToNext() && inCloud) {
                        val dev = c.getString(2).ifEmpty { me }
                        val have = remoteL[dev]?.get(SyncCodec.listenKey(c.getInt(0), song)) ?: -1L
                        if (c.getLong(1) > 0L && have < c.getLong(1)) inCloud = false
                    }
                }
                val lp = lastPlayed(d, song)
                if (inCloud && lp > 0L && Days.keyOf(lp) == day) {
                    if ((remoteR[me]?.get(song) ?: -1L) < lp) inCloud = false
                }
                if (inCloud) dequeue(d, key)
            }
        }
        changed
    }

    // ---- starting fresh ------------------------------------------------------------------------

    /**
     * "Start fresh" when signing in to a different account: this phone's library is emptied so it
     * only gets that account's music. A copy of the database is kept in [backup] first.
     */
    fun wipeLibrary(backup: File?) {
        val d = db
        if (backup != null) {
            try {
                d.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { it.moveToFirst() }
                val src = File(d.path)
                if (src.exists()) src.copyTo(backup, overwrite = true)
            } catch (_: Exception) {
            }
        }
        d.beginTransaction()
        try {
            for (t in listOf(
                "tracks", "playlists", "playlist_tracks", "playlist_removed", "playlist_deleted",
                "plays", "listen_time", "not_interested", "sync_outbox"
            )) d.delete(t, null, null)
            d.setTransactionSuccessful()
        } finally {
            d.endTransaction()
        }
    }
}
