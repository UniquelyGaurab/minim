package com.minim.music.data.local

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.minim.music.data.model.Playlist
import com.minim.music.data.model.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

class LibraryDatabase(context: Context) :
    SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {

    private val _likedIds = MutableStateFlow<Set<String>>(emptySet())
    val likedIds: StateFlow<Set<String>> = _likedIds.asStateFlow()

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE tracks (
                id TEXT PRIMARY KEY,
                title TEXT NOT NULL,
                artist TEXT NOT NULL,
                duration_sec INTEGER NOT NULL,
                thumbnail_url TEXT NOT NULL,
                source_url TEXT NOT NULL,
                is_liked INTEGER NOT NULL DEFAULT 0,
                liked_at INTEGER NOT NULL DEFAULT 0,
                last_played_at INTEGER NOT NULL DEFAULT 0,
                play_count INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE TABLE playlists (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                name TEXT NOT NULL UNIQUE,
                created_at INTEGER NOT NULL
            )
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE TABLE playlist_tracks (
                playlist_id INTEGER NOT NULL,
                track_id TEXT NOT NULL,
                position INTEGER NOT NULL,
                PRIMARY KEY (playlist_id, track_id)
            )
            """.trimIndent()
        )
        createV2Tables(db)
        createV3Tables(db)
        createV4Sync(db)
        createV5Import(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // v4.3: real step-by-step migrations. Never drop tables: liked songs, playlists and
        // recents must survive every update.
        if (oldVersion < 2) createV2Tables(db)
        if (oldVersion < 3) createV3Tables(db)
        if (oldVersion < 4) createV4Sync(db)
        if (oldVersion < 5) createV5Import(db)
    }

    /** 5.3.0: playlists.source = where an imported playlist came from ("spotify", "youtube", "text"). */
    private fun createV5Import(db: SQLiteDatabase) {
        addColumn(db, "playlists", "source", "TEXT")
    }

    override fun onDowngrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // An older build opening a newer database: keep everything, the extra tables are ignored.
    }

    /** v2 (Minim 4.3.0): counted plays (30+ s heard) and the "not interested" list. */
    private fun createV2Tables(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS plays (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                track_id TEXT NOT NULL,
                played_at INTEGER NOT NULL,
                listened_ms INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_plays_track ON plays(track_id)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_plays_time ON plays(played_at)")
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS not_interested (
                track_id TEXT PRIMARY KEY,
                title TEXT NOT NULL DEFAULT '',
                artist TEXT NOT NULL DEFAULT '',
                added_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
    }

    /**
     * v3 (Minim 4.9.0): real listening time. listen_time holds the seconds actually heard per
     * song, per hour, per day (yyyymmdd, local time), so listening that crosses midnight is split
     * by day. Title/artist/cover are kept with it so songs skipped before they count don't have to
     * go into the tracks table (Discover reads that one). plays.tracked = 1 for plays recorded
     * from 4.9.0 on (their time is in listen_time); older plays (tracked = 0) get the estimate.
     * Only adds things: every existing table and row stays as it is.
     */
    private fun createV3Tables(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS listen_time (
                day INTEGER NOT NULL,
                hour INTEGER NOT NULL,
                track_id TEXT NOT NULL,
                title TEXT NOT NULL DEFAULT '',
                artist TEXT NOT NULL DEFAULT '',
                thumbnail_url TEXT NOT NULL DEFAULT '',
                ms INTEGER NOT NULL DEFAULT 0,
                PRIMARY KEY (day, hour, track_id)
            )
            """.trimIndent()
        )
        var hasTracked = false
        db.rawQuery("PRAGMA table_info(plays)", null).use { c ->
            val nameCol = c.getColumnIndex("name")
            while (c.moveToNext()) if (c.getString(nameCol) == "tracked") hasTracked = true
        }
        if (!hasTracked) db.execSQL("ALTER TABLE plays ADD COLUMN tracked INTEGER NOT NULL DEFAULT 0")
    }

    // ---- v4 (Minim 5.0.0 part 2): cloud sync ---------------------------------------------------

    private fun hasColumn(db: SQLiteDatabase, table: String, column: String): Boolean {
        db.rawQuery("PRAGMA table_info($table)", null).use { c ->
            val nameCol = c.getColumnIndex("name")
            while (c.moveToNext()) if (c.getString(nameCol) == column) return true
        }
        return false
    }

    private fun addColumn(db: SQLiteDatabase, table: String, column: String, def: String) {
        if (!hasColumn(db, table, column)) db.execSQL("ALTER TABLE $table ADD COLUMN $column $def")
    }

    /**
     * v4 (Minim 5.0.0): what cloud sync needs. Only adds things, and is safe to run again (an
     * older build may have opened the database in between):
     * - tracks.like_changed_at: when the like last changed (liked or unliked), so the newest wins
     * - playlists.uid (stable sync ID), changed_at, born; playlist_tracks.changed_at
     * - playlist_removed / playlist_deleted: remembered removals, so they reach the other phones
     * - plays.event_id: a stable ID per play, so merging never counts a play twice
     * - listen_time.device: listening time is kept per phone ('' = this phone)
     * - sync_outbox (what changed and still has to go up), sync_state (markers, account, device)
     */
    private fun createV4Sync(db: SQLiteDatabase) {
        addColumn(db, "tracks", "like_changed_at", "INTEGER NOT NULL DEFAULT 0")
        db.execSQL("UPDATE tracks SET like_changed_at = liked_at WHERE is_liked = 1 AND like_changed_at = 0")

        addColumn(db, "playlists", "uid", "TEXT")
        addColumn(db, "playlists", "changed_at", "INTEGER NOT NULL DEFAULT 0")
        addColumn(db, "playlists", "born", "INTEGER NOT NULL DEFAULT 0")
        db.execSQL("UPDATE playlists SET changed_at = created_at WHERE changed_at = 0")
        val noUid = ArrayList<Pair<Long, String>>()
        db.rawQuery("SELECT id, name FROM playlists WHERE uid IS NULL", null).use { c ->
            while (c.moveToNext()) noUid.add(c.getLong(0) to c.getString(1))
        }
        for ((id, name) in noUid) {
            db.execSQL("UPDATE playlists SET uid = ? WHERE id = ?", arrayOf<Any>(freePlaylistUid(db, name), id))
        }
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS idx_playlists_uid ON playlists(uid)")
        addColumn(db, "playlist_tracks", "changed_at", "INTEGER NOT NULL DEFAULT 0")
        db.execSQL("UPDATE playlist_tracks SET changed_at = position WHERE changed_at = 0")
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS playlist_removed (
                playlist_uid TEXT NOT NULL,
                track_id TEXT NOT NULL,
                at INTEGER NOT NULL,
                PRIMARY KEY (playlist_uid, track_id)
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS playlist_deleted (
                uid TEXT PRIMARY KEY,
                name TEXT NOT NULL,
                created_at INTEGER NOT NULL DEFAULT 0,
                at INTEGER NOT NULL,
                born INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )

        addColumn(db, "plays", "event_id", "TEXT")
        val noEvent = ArrayList<Triple<Long, Long, String>>()
        db.rawQuery("SELECT id, played_at, track_id FROM plays WHERE event_id IS NULL", null).use { c ->
            while (c.moveToNext()) noEvent.add(Triple(c.getLong(0), c.getLong(1), c.getString(2)))
        }
        val used = HashSet<String>()
        db.rawQuery("SELECT event_id FROM plays WHERE event_id IS NOT NULL", null).use { c ->
            while (c.moveToNext()) used.add(c.getString(0))
        }
        for ((id, at, track) in noEvent) {
            var eid = com.minim.music.data.sync.SyncIds.eventId(at, track)
            if (!used.add(eid)) { eid = eid + "_" + id; used.add(eid) }
            db.execSQL("UPDATE plays SET event_id = ? WHERE id = ?", arrayOf<Any>(eid, id))
        }
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS idx_plays_event ON plays(event_id)")

        if (!hasColumn(db, "listen_time", "device")) {
            db.execSQL(
                """
                CREATE TABLE listen_time_v4 (
                    day INTEGER NOT NULL,
                    hour INTEGER NOT NULL,
                    track_id TEXT NOT NULL,
                    title TEXT NOT NULL DEFAULT '',
                    artist TEXT NOT NULL DEFAULT '',
                    thumbnail_url TEXT NOT NULL DEFAULT '',
                    ms INTEGER NOT NULL DEFAULT 0,
                    device TEXT NOT NULL DEFAULT '',
                    PRIMARY KEY (day, hour, track_id, device)
                )
                """.trimIndent()
            )
            db.execSQL(
                """
                INSERT INTO listen_time_v4 (day, hour, track_id, title, artist, thumbnail_url, ms, device)
                SELECT day, hour, track_id, title, artist, thumbnail_url, ms, '' FROM listen_time
                """.trimIndent()
            )
            db.execSQL("DROP TABLE listen_time")
            db.execSQL("ALTER TABLE listen_time_v4 RENAME TO listen_time")
        }

        db.execSQL("CREATE TABLE IF NOT EXISTS sync_outbox (id INTEGER PRIMARY KEY AUTOINCREMENT, k TEXT NOT NULL UNIQUE)")
        db.execSQL("CREATE TABLE IF NOT EXISTS sync_state (k TEXT PRIMARY KEY, v TEXT NOT NULL)")
    }

    /** The playlist's stable sync ID, or (only on a clash with another playlist) a unique one. */
    internal fun freePlaylistUid(db: SQLiteDatabase, name: String): String {
        val base = com.minim.music.data.sync.SyncIds.playlistUid(name)
        var taken = false
        db.rawQuery("SELECT name FROM playlists WHERE uid = ?", arrayOf(base)).use { c ->
            if (c.moveToFirst()) taken = c.getString(0) != name.trim()
        }
        return if (!taken) base else com.minim.music.data.sync.SyncIds.playlistUid(name + "#" + System.nanoTime())
    }

    /**
     * Sync hooks. While the user is signed in, [syncTracking] is on and every change to likes,
     * playlists, "not interested" and listening history puts a key in sync_outbox (in the same
     * transaction as the change), then calls [onSyncChange]: urgent for likes/playlists (they go up
     * within seconds), not urgent for listening (batched every few minutes).
     */
    @Volatile var syncTracking: Boolean = false
    @Volatile var onSyncChange: ((urgent: Boolean) -> Unit)? = null

    internal fun queueSync(db: SQLiteDatabase, key: String) {
        if (!syncTracking) return
        db.execSQL("INSERT OR REPLACE INTO sync_outbox (k) VALUES (?)", arrayOf<Any>(key))
    }

    private fun notifySync(urgent: Boolean) {
        if (syncTracking) try { onSyncChange?.invoke(urgent) } catch (_: Exception) { }
    }

    private fun playlistUidOf(db: SQLiteDatabase, playlistId: Long): String? =
        db.rawQuery("SELECT uid FROM playlists WHERE id = ?", arrayOf(playlistId.toString())).use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getString(0) else null
        }

    suspend fun refreshLikedCache() = withContext(Dispatchers.IO) {
        val ids = HashSet<String>()
        readableDatabase.rawQuery("SELECT id FROM tracks WHERE is_liked = 1", null).use { cursor ->
            while (cursor.moveToNext()) {
                ids.add(cursor.getString(0))
            }
        }
        _likedIds.value = ids
    }

    /**
     * The user started this song on purpose (tapped it): it goes to the top of Recents right away.
     * Songs the queue moves on to only reach Recents once they count as a play (see [recordCountedPlay]).
     */
    suspend fun recordPlayback(track: Track) = withContext(Dispatchers.IO) {
        val db = writableDatabase
        val now = System.currentTimeMillis()
        db.beginTransaction()
        try {
            upsertTrackInternal(db, track)
            db.execSQL(
                "UPDATE tracks SET last_played_at = ? WHERE id = ?",
                arrayOf<Any>(now, track.id)
            )
            queueSync(db, "M|${com.minim.music.data.stats.Days.keyOf(now)}|${track.id}")
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        notifySync(urgent = false)
    }

    /** v4.3: a play that counts (30+ seconds heard). Stored with its time; bumps count + Recents. */
    suspend fun recordCountedPlay(track: Track, listenedMs: Long): Long = withContext(Dispatchers.IO) {
        val db = writableDatabase
        val now = System.currentTimeMillis()
        var rowId = -1L
        db.beginTransaction()
        try {
            upsertTrackInternal(db, track)
            db.execSQL(
                "UPDATE tracks SET last_played_at = ?, play_count = play_count + 1 WHERE id = ?",
                arrayOf<Any>(now, track.id)
            )
            val cv = ContentValues().apply {
                put("track_id", track.id)
                put("played_at", now)
                put("listened_ms", listenedMs)
                put("tracked", 1)
                put("event_id", com.minim.music.data.sync.SyncIds.eventId(now, track.id))
            }
            rowId = db.insertWithOnConflict("plays", null, cv, SQLiteDatabase.CONFLICT_IGNORE)
            queueSync(db, "E|" + com.minim.music.data.sync.SyncIds.eventId(now, track.id))
            queueSync(db, "M|${com.minim.music.data.stats.Days.keyOf(now)}|${track.id}")
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        notifySync(urgent = false)
        rowId
    }

    // ---- v4.9: real listening time and day stats ------------------------------------------------

    /** One piece of listening to add: [ms] heard of [track] in [slice]'s day and hour. */
    data class ListenAdd(val slice: com.minim.music.data.stats.ListenSlice, val track: Track)

    /**
     * Adds heard time (the player saves it every few seconds while playing, and on pause/skip).
     * [playRowId]/[playListenedMs]: the counted play of the song that's playing gets its real
     * listening time so far (plays.listened_ms), so it survives the app being closed.
     */
    suspend fun addListenTime(adds: List<ListenAdd>, playRowId: Long, playListenedMs: Long) =
        withContext(Dispatchers.IO) {
            val db = writableDatabase
            db.beginTransaction()
            try {
                for (a in adds) {
                    if (a.slice.ms <= 0L) continue
                    // Android 9 ships SQLite 3.22 (no upsert): insert the row if it's new, then add
                    // v5.0.0: this phone's own listening is device '' (other phones' come from sync)
                    db.execSQL(
                        "INSERT OR IGNORE INTO listen_time (day, hour, track_id, ms, device) VALUES (?, ?, ?, 0, '')",
                        arrayOf<Any>(a.slice.day, a.slice.hour, a.track.id)
                    )
                    db.execSQL(
                        """
                        UPDATE listen_time SET ms = ms + ?, title = ?, artist = ?, thumbnail_url = ?
                        WHERE day = ? AND hour = ? AND track_id = ? AND device = ''
                        """.trimIndent(),
                        arrayOf<Any>(
                            a.slice.ms, a.track.title, a.track.artist, a.track.thumbnailUrl,
                            a.slice.day, a.slice.hour, a.track.id
                        )
                    )
                    queueSync(db, "M|${a.slice.day}|${a.track.id}")
                }
                if (playRowId > 0L && playListenedMs > 0L) {
                    db.execSQL(
                        "UPDATE plays SET listened_ms = MAX(listened_ms, ?) WHERE id = ?",
                        arrayOf<Any>(playListenedMs, playRowId)
                    )
                    if (syncTracking) {
                        db.rawQuery("SELECT event_id FROM plays WHERE id = ?", arrayOf(playRowId.toString())).use { c ->
                            if (c.moveToFirst() && !c.isNull(0)) queueSync(db, "E|" + c.getString(0))
                        }
                    }
                }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
            notifySync(urgent = false)
        }

    /** Counted plays of one day (12:00 AM to 11:59 PM local), oldest first. */
    suspend fun getDayPlays(day: Int): List<com.minim.music.data.stats.PlayRow> = withContext(Dispatchers.IO) {
        val start = com.minim.music.data.stats.Days.startMs(day)
        val end = com.minim.music.data.stats.Days.endMs(day)
        val list = ArrayList<com.minim.music.data.stats.PlayRow>()
        val sql = """
            SELECT t.id, t.title, t.artist, t.thumbnail_url, t.duration_sec,
                   p.played_at, p.listened_ms, p.tracked
            FROM plays p INNER JOIN tracks t ON t.id = p.track_id
            WHERE p.played_at >= ? AND p.played_at < ?
            ORDER BY p.played_at ASC
        """.trimIndent()
        readableDatabase.rawQuery(sql, arrayOf(start.toString(), end.toString())).use { c ->
            while (c.moveToNext()) {
                list.add(
                    com.minim.music.data.stats.PlayRow(
                        trackId = c.getString(0), title = c.getString(1), artist = c.getString(2),
                        thumbnailUrl = c.getString(3), durationSec = c.getLong(4), playedAt = c.getLong(5),
                        listenedMs = c.getLong(6), tracked = c.getInt(7) == 1
                    )
                )
            }
        }
        list
    }

    /** Real listening time of one day, per song and hour. */
    suspend fun getDayListens(day: Int): List<com.minim.music.data.stats.ListenRow> = withContext(Dispatchers.IO) {
        val list = ArrayList<com.minim.music.data.stats.ListenRow>()
        readableDatabase.rawQuery(
            // v5.0.0: one row per song and hour, summed over phones (synced listening is per phone)
            """
            SELECT track_id, MAX(title), MAX(artist), MAX(thumbnail_url), hour, SUM(ms)
            FROM listen_time WHERE day = ? GROUP BY track_id, hour
            """.trimIndent(),
            arrayOf(day.toString())
        ).use { c ->
            while (c.moveToNext()) {
                list.add(
                    com.minim.music.data.stats.ListenRow(
                        trackId = c.getString(0), title = c.getString(1), artist = c.getString(2),
                        thumbnailUrl = c.getString(3), hour = c.getInt(4), ms = c.getLong(5)
                    )
                )
            }
        }
        list
    }

    /**
     * v4.9.2: listening time per day from [fromDay] to [toDay] (both included), for the day streak:
     * real listening time (4.9.0+) plus the old-data estimate for plays from before 4.9.0.
     */
    suspend fun getDayTotals(fromDay: Int, toDay: Int): Map<Int, Long> = withContext(Dispatchers.IO) {
        val out = HashMap<Int, Long>()
        readableDatabase.rawQuery(
            "SELECT day, SUM(ms) FROM listen_time WHERE day >= ? AND day <= ? GROUP BY day",
            arrayOf(fromDay.toString(), toDay.toString())
        ).use { c ->
            while (c.moveToNext()) out[c.getInt(0)] = (out[c.getInt(0)] ?: 0L) + c.getLong(1)
        }
        val start = com.minim.music.data.stats.Days.startMs(fromDay)
        val end = com.minim.music.data.stats.Days.endMs(toDay)
        readableDatabase.rawQuery(
            """
            SELECT p.played_at, p.listened_ms, t.duration_sec FROM plays p
            INNER JOIN tracks t ON t.id = p.track_id
            WHERE p.tracked = 0 AND p.played_at >= ? AND p.played_at < ?
            """.trimIndent(),
            arrayOf(start.toString(), end.toString())
        ).use { c ->
            while (c.moveToNext()) {
                val day = com.minim.music.data.stats.Days.keyOf(c.getLong(0))
                val ms = if (c.getLong(2) > 0) c.getLong(2) * 1000L else c.getLong(1).coerceAtLeast(0L)
                out[day] = (out[day] ?: 0L) + ms
            }
        }
        out
    }

    /** v4.9.2: saved songs by id, in the order of [ids] (missing ones are left out). */
    suspend fun getTracksByIds(ids: List<String>): List<Track> = withContext(Dispatchers.IO) {
        if (ids.isEmpty()) return@withContext emptyList()
        val found = HashMap<String, Track>()
        ids.distinct().chunked(400).forEach { part ->
            val marks = part.joinToString(",") { "?" }
            readableDatabase.rawQuery("SELECT * FROM tracks WHERE id IN ($marks)", part.toTypedArray()).use { c ->
                while (c.moveToNext()) cursorToTrack(c).let { found[it.id] = it }
            }
        }
        ids.mapNotNull { found[it] }
    }

    suspend fun getDayStats(day: Int): com.minim.music.data.stats.DayStats =
        com.minim.music.data.stats.StatsEngine.compute(day, getDayPlays(day), getDayListens(day))

    /**
     * The most recent day before [today] with listening history (a counted play, or at least a
     * minute heard), or null. This is the recap day: only a newer day with history replaces it.
     */
    suspend fun latestPastHistoryDay(today: Int): Int? = withContext(Dispatchers.IO) {
        val todayStart = com.minim.music.data.stats.Days.startMs(today)
        var fromPlays: Int? = null
        readableDatabase.rawQuery(
            "SELECT MAX(played_at) FROM plays WHERE played_at < ?",
            arrayOf(todayStart.toString())
        ).use { c ->
            if (c.moveToFirst() && !c.isNull(0)) fromPlays = com.minim.music.data.stats.Days.keyOf(c.getLong(0))
        }
        var fromListens: Int? = null
        readableDatabase.rawQuery(
            // (the minute is written into the SQL: rawQuery binds text, and SUM() has no number affinity)
            "SELECT day FROM listen_time WHERE day < ? GROUP BY day HAVING SUM(ms) >= " +
                com.minim.music.data.stats.StatsEngine.HISTORY_MS + " ORDER BY day DESC LIMIT 1",
            arrayOf(today.toString())
        ).use { c ->
            if (c.moveToFirst()) fromListens = c.getInt(0)
        }
        listOfNotNull(fromPlays, fromListens).let { com.minim.music.data.stats.RecapRules.recapDay(today, it) }
    }

    /**
     * Past days (before [today]) with listening history in the last [lookbackDays], newest first.
     * The Stats tab walks these until it finds a non-quiet day for the recap.
     */
    suspend fun pastHistoryDays(today: Int, lookbackDays: Int = 120): List<Int> = withContext(Dispatchers.IO) {
        val todayStart = com.minim.music.data.stats.Days.startMs(today)
        val from = com.minim.music.data.stats.Days.startMs(
            com.minim.music.data.stats.Days.key(com.minim.music.data.stats.Days.date(today).minusDays(lookbackDays.toLong()))
        )
        val days = HashSet<Int>()
        readableDatabase.rawQuery(
            "SELECT played_at FROM plays WHERE played_at >= ? AND played_at < ?",
            arrayOf(from.toString(), todayStart.toString())
        ).use { c ->
            while (c.moveToNext()) days.add(com.minim.music.data.stats.Days.keyOf(c.getLong(0)))
        }
        val fromKey = com.minim.music.data.stats.Days.keyOf(from)
        readableDatabase.rawQuery(
            "SELECT day FROM listen_time WHERE day >= ? AND day < ? GROUP BY day HAVING SUM(ms) >= " +
                com.minim.music.data.stats.StatsEngine.HISTORY_MS,
            arrayOf(fromKey.toString(), today.toString())
        ).use { c ->
            while (c.moveToNext()) days.add(c.getInt(0))
        }
        days.filter { it < today }.sortedDescending()
    }

    /** One counted play with its song (for Discover). */
    data class PlayEvent(val track: Track, val playedAt: Long)

    /** Counted plays since [sinceMs], newest first, with each song's details. */
    suspend fun getPlayEvents(sinceMs: Long = 0L, limit: Int = 3000): List<PlayEvent> = withContext(Dispatchers.IO) {
        val list = ArrayList<PlayEvent>()
        val sql = """
            SELECT t.*, p.played_at AS p_played_at FROM plays p
            INNER JOIN tracks t ON t.id = p.track_id
            WHERE p.played_at >= ?
            ORDER BY p.played_at DESC LIMIT ?
        """.trimIndent()
        readableDatabase.rawQuery(sql, arrayOf(sinceMs.toString(), limit.toString())).use { c ->
            val col = c.getColumnIndexOrThrow("p_played_at")
            while (c.moveToNext()) list.add(PlayEvent(cursorToTrack(c), c.getLong(col)))
        }
        list
    }

    /** Song stats: how often (counted plays, plus older counts from before v4.3) and when last played. */
    data class TrackStats(val track: Track, val playCount: Int, val lastPlayedAt: Long, val isLiked: Boolean, val likedAt: Long)

    suspend fun getAllTrackStats(): List<TrackStats> = withContext(Dispatchers.IO) {
        val list = ArrayList<TrackStats>()
        readableDatabase.rawQuery("SELECT * FROM tracks", null).use { c ->
            val cCount = c.getColumnIndexOrThrow("play_count")
            val cLast = c.getColumnIndexOrThrow("last_played_at")
            val cLiked = c.getColumnIndexOrThrow("is_liked")
            val cLikedAt = c.getColumnIndexOrThrow("liked_at")
            while (c.moveToNext()) {
                list.add(TrackStats(cursorToTrack(c), c.getInt(cCount), c.getLong(cLast), c.getInt(cLiked) == 1, c.getLong(cLikedAt)))
            }
        }
        list
    }

    /** Last counted play per song (0 = never counted since v4.3). */
    suspend fun getLastCountedPlays(): Map<String, Long> = withContext(Dispatchers.IO) {
        val map = HashMap<String, Long>()
        readableDatabase.rawQuery("SELECT track_id, MAX(played_at) FROM plays GROUP BY track_id", null).use { c ->
            while (c.moveToNext()) map[c.getString(0)] = c.getLong(1)
        }
        map
    }

    /**
     * v4.5: what the fun Home greetings talk about, in one quick read (a handful of small COUNT /
     * GROUP BY queries on indexed columns, off the main thread). The "now playing" part is left
     * empty: the screen fills it from the player. Plays "today" start at [dayStartMs], "this week"
     * is the last 7 days.
     */
    suspend fun getGreetingFacts(dayStartMs: Long): com.minim.music.ui.GreetingFacts = withContext(Dispatchers.IO) {
        val db = readableDatabase
        val now = System.currentTimeMillis()
        val weekStart = (now - 7L * 24 * 60 * 60 * 1000).toString()
        val likedSince = (now - 14L * 24 * 60 * 60 * 1000).toString()

        fun count(sql: String, args: Array<String>): Int =
            db.rawQuery(sql, args).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }

        var lastId: String? = null
        var lastTitle: String? = null
        var lastArtist: String? = null
        db.rawQuery(
            "SELECT id, title, artist FROM tracks WHERE last_played_at > 0 ORDER BY last_played_at DESC LIMIT 1", null
        ).use { c ->
            if (c.moveToFirst()) {
                lastId = c.getString(0); lastTitle = c.getString(1); lastArtist = c.getString(2)
            }
        }

        val playsToday = count("SELECT COUNT(*) FROM plays WHERE played_at >= ?", arrayOf(dayStartMs.toString()))

        var topTrack: String? = null
        var topTrackPlays = 0
        db.rawQuery(
            """
            SELECT t.title, COUNT(*) AS c FROM plays p INNER JOIN tracks t ON t.id = p.track_id
            WHERE p.played_at >= ? GROUP BY p.track_id ORDER BY c DESC, MAX(p.played_at) DESC LIMIT 1
            """.trimIndent(), arrayOf(weekStart)
        ).use { c -> if (c.moveToFirst()) { topTrack = c.getString(0); topTrackPlays = c.getInt(1) } }

        var topArtist: String? = null
        var topArtistPlays = 0
        db.rawQuery(
            """
            SELECT t.artist, COUNT(*) AS c FROM plays p INNER JOIN tracks t ON t.id = p.track_id
            WHERE p.played_at >= ? GROUP BY t.artist ORDER BY c DESC, MAX(p.played_at) DESC LIMIT 1
            """.trimIndent(), arrayOf(weekStart)
        ).use { c -> if (c.moveToFirst()) { topArtist = c.getString(0); topArtistPlays = c.getInt(1) } }

        // 5.1.0: plays per song this week, for the {plays} greetings about the song in the player
        val weekPlays = HashMap<String, Int>()
        db.rawQuery(
            "SELECT track_id, COUNT(*) AS c FROM plays WHERE played_at >= ? GROUP BY track_id ORDER BY c DESC LIMIT 500",
            arrayOf(weekStart)
        ).use { c -> while (c.moveToNext()) c.getString(0)?.let { weekPlays[it] = c.getInt(1) } }

        val artistsWeek = count(
            "SELECT COUNT(DISTINCT t.artist) FROM plays p INNER JOIN tracks t ON t.id = p.track_id WHERE p.played_at >= ?",
            arrayOf(weekStart)
        )

        val likedCount = count("SELECT COUNT(*) FROM tracks WHERE is_liked = 1", emptyArray())
        var recentLiked: String? = null
        db.rawQuery(
            "SELECT title FROM tracks WHERE is_liked = 1 AND liked_at >= ? ORDER BY liked_at DESC LIMIT 1",
            arrayOf(likedSince)
        ).use { c -> if (c.moveToFirst()) recentLiked = c.getString(0) }

        var likedArtist: String? = null
        var likedArtistCount = 0
        db.rawQuery(
            "SELECT artist, COUNT(*) AS c FROM tracks WHERE is_liked = 1 GROUP BY artist ORDER BY c DESC LIMIT 1", null
        ).use { c -> if (c.moveToFirst()) { likedArtist = c.getString(0); likedArtistCount = c.getInt(1) } }

        var playlistCount = 0
        var newest: String? = null
        var biggest: String? = null
        var biggestSongs = 0
        db.rawQuery(
            """
            SELECT p.name, COUNT(pt.track_id) AS cnt FROM playlists p
            LEFT JOIN playlist_tracks pt ON p.id = pt.playlist_id
            GROUP BY p.id ORDER BY p.created_at DESC
            """.trimIndent(), null
        ).use { c ->
            while (c.moveToNext()) {
                val name = c.getString(0)
                val cnt = c.getInt(1)
                if (playlistCount == 0) newest = name
                if (cnt > biggestSongs) { biggest = name; biggestSongs = cnt }
                playlistCount++
            }
        }

        com.minim.music.ui.GreetingFacts(
            lastId = lastId,
            lastTitle = lastTitle,
            lastArtist = lastArtist,
            playsToday = playsToday,
            topTrackWeekTitle = topTrack,
            topTrackWeekPlays = topTrackPlays,
            topArtistWeek = topArtist,
            topArtistWeekPlays = topArtistPlays,
            artistsWeek = artistsWeek,
            likedCount = likedCount,
            recentLikedTitle = recentLiked,
            topLikedArtist = likedArtist,
            topLikedArtistCount = likedArtistCount,
            playlistCount = playlistCount,
            newestPlaylist = newest,
            biggestPlaylist = biggest,
            biggestPlaylistSongs = biggestSongs,
            weekPlays = weekPlays
        )
    }

    // ---- "Not interested" ----------------------------------------------------------------------

    private val _notInterestedIds = MutableStateFlow<Set<String>>(emptySet())
    val notInterestedIds: StateFlow<Set<String>> = _notInterestedIds.asStateFlow()

    suspend fun refreshNotInterestedCache() = withContext(Dispatchers.IO) {
        val ids = HashSet<String>()
        readableDatabase.rawQuery("SELECT track_id FROM not_interested", null).use { c ->
            while (c.moveToNext()) ids.add(c.getString(0))
        }
        _notInterestedIds.value = ids
    }

    suspend fun markNotInterested(track: Track) = withContext(Dispatchers.IO) {
        val cv = ContentValues().apply {
            put("track_id", track.id)
            put("title", track.title)
            put("artist", track.artist)
            put("added_at", System.currentTimeMillis())
        }
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.insertWithOnConflict("not_interested", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
            queueSync(db, "H|${track.id}")
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        _notInterestedIds.value = _notInterestedIds.value + track.id
        notifySync(urgent = true)
    }

    suspend fun toggleLike(track: Track): Boolean = withContext(Dispatchers.IO) {
        val db = writableDatabase
        var newLiked = 0
        db.beginTransaction()
        try {
            upsertTrackInternal(db, track)
            var currentlyLiked = false
            db.rawQuery("SELECT is_liked FROM tracks WHERE id = ?", arrayOf(track.id)).use { c ->
                if (c.moveToFirst()) {
                    currentlyLiked = c.getInt(0) == 1
                }
            }
            newLiked = if (currentlyLiked) 0 else 1
            val changedAt = System.currentTimeMillis()
            val cv = ContentValues().apply {
                put("is_liked", newLiked)
                put("liked_at", if (newLiked == 1) changedAt else 0L)
                // v5.0.0: when it changed either way, so the newest change wins across phones
                put("like_changed_at", changedAt)
            }
            db.update("tracks", cv, "id = ?", arrayOf(track.id))
            queueSync(db, "L|${track.id}")
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        refreshLikedCache()
        notifySync(urgent = true)
        newLiked == 1
    }

    suspend fun getRecentTracks(limit: Int = 25): List<Track> = withContext(Dispatchers.IO) {
        queryTracks(
            "SELECT * FROM tracks WHERE last_played_at > 0 ORDER BY last_played_at DESC LIMIT ?",
            arrayOf(limit.toString())
        )
    }

    suspend fun getLikedTracks(): List<Track> = withContext(Dispatchers.IO) {
        queryTracks(
            "SELECT * FROM tracks WHERE is_liked = 1 ORDER BY liked_at DESC",
            emptyArray()
        )
    }

    /** [source] (5.3.0): set for an imported playlist, shown as "from Spotify" under its name. */
    suspend fun createPlaylist(name: String, source: String? = null): Long = withContext(Dispatchers.IO) {
        val clean = name.trim()
        if (clean.isEmpty()) return@withContext -1L
        val db = writableDatabase
        val now = System.currentTimeMillis()
        var id = -1L
        db.beginTransaction()
        try {
            val uid = freePlaylistUid(db, clean)
            // made again after a delete (here or on another phone): it starts empty
            var born = 0L
            db.rawQuery("SELECT at, born FROM playlist_deleted WHERE uid = ?", arrayOf(uid)).use { c ->
                if (c.moveToFirst()) born = maxOf(c.getLong(0), c.getLong(1))
            }
            val cv = ContentValues().apply {
                put("name", clean)
                put("created_at", now)
                put("uid", uid)
                put("changed_at", now)
                put("born", born)
                if (source != null) put("source", source)
            }
            id = db.insertWithOnConflict("playlists", null, cv, SQLiteDatabase.CONFLICT_IGNORE)
            if (id != -1L) {
                db.delete("playlist_deleted", "uid = ?", arrayOf(uid))
                db.delete("playlist_removed", "playlist_uid = ?", arrayOf(uid))
                queueSync(db, "P|$uid")
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        if (id != -1L) notifySync(urgent = true)
        id
    }

    /** 5.3.2: what [renamePlaylist] did. */
    enum class RenameResult { RENAMED, SAME, TAKEN, BLANK, MISSING }

    /**
     * 5.3.2: renames a playlist (tap its title). Its sync id stays the same, and the new name is
     * a header change, so it wins over the old name on your other phones. A name another
     * playlist already has is refused ([RenameResult.TAKEN]) so the user can pick another.
     */
    suspend fun renamePlaylist(playlistId: Long, newName: String): RenameResult = withContext(Dispatchers.IO) {
        val clean = newName.trim()
        if (clean.isEmpty()) return@withContext RenameResult.BLANK
        val db = writableDatabase
        var result = RenameResult.MISSING
        db.beginTransaction()
        try {
            var uid: String? = null
            var old: String? = null
            db.rawQuery("SELECT uid, name FROM playlists WHERE id = ?", arrayOf(playlistId.toString())).use { c ->
                if (c.moveToFirst()) {
                    uid = if (c.isNull(0)) null else c.getString(0)
                    old = c.getString(1)
                }
            }
            when {
                old == null -> result = RenameResult.MISSING
                old == clean -> result = RenameResult.SAME
                else -> {
                    var taken = false
                    db.rawQuery(
                        "SELECT 1 FROM playlists WHERE name = ? AND id != ?",
                        arrayOf(clean, playlistId.toString())
                    ).use { c -> taken = c.moveToFirst() }
                    if (taken) {
                        result = RenameResult.TAKEN
                    } else {
                        val cv = ContentValues().apply {
                            put("name", clean)
                            put("changed_at", System.currentTimeMillis())
                        }
                        db.update("playlists", cv, "id = ?", arrayOf(playlistId.toString()))
                        uid?.let { queueSync(db, "P|$it") }
                        result = RenameResult.RENAMED
                    }
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        if (result == RenameResult.RENAMED) notifySync(urgent = true)
        result
    }

    suspend fun deletePlaylist(playlistId: Long) = withContext(Dispatchers.IO) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            // v5.0.0: remember the delete (with its time) so it reaches the other phones
            db.rawQuery(
                "SELECT uid, name, created_at, born FROM playlists WHERE id = ?",
                arrayOf(playlistId.toString())
            ).use { c ->
                if (c.moveToFirst() && !c.isNull(0)) {
                    val uid = c.getString(0)
                    val cv = ContentValues().apply {
                        put("uid", uid)
                        put("name", c.getString(1))
                        put("created_at", c.getLong(2))
                        put("at", System.currentTimeMillis())
                        put("born", c.getLong(3))
                    }
                    db.insertWithOnConflict("playlist_deleted", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
                    db.delete("playlist_removed", "playlist_uid = ?", arrayOf(uid))
                    db.execSQL("DELETE FROM sync_outbox WHERE k LIKE ?", arrayOf<Any>("T|$uid|%"))
                    queueSync(db, "P|$uid")
                }
            }
            db.delete("playlist_tracks", "playlist_id = ?", arrayOf(playlistId.toString()))
            db.delete("playlists", "id = ?", arrayOf(playlistId.toString()))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        notifySync(urgent = true)
    }

    suspend fun getPlaylists(): List<Playlist> = withContext(Dispatchers.IO) {
        val result = ArrayList<Playlist>()
        val sql = """
            SELECT p.id, p.name, COUNT(pt.track_id) AS cnt, p.source
            FROM playlists p
            LEFT JOIN playlist_tracks pt ON p.id = pt.playlist_id
            GROUP BY p.id
            ORDER BY p.created_at DESC
        """.trimIndent()
        readableDatabase.rawQuery(sql, null).use { c ->
            while (c.moveToNext()) {
                result.add(
                    Playlist(
                        id = c.getLong(0),
                        name = c.getString(1),
                        trackCount = c.getInt(2),
                        source = if (c.isNull(3)) null else c.getString(3)
                    )
                )
            }
        }
        result
    }

    suspend fun addTrackToPlaylist(playlistId: Long, track: Track) = withContext(Dispatchers.IO) {
        val db = writableDatabase
        val now = System.currentTimeMillis()
        db.beginTransaction()
        try {
            upsertTrackInternal(db, track)
            val cv = ContentValues().apply {
                put("playlist_id", playlistId)
                put("track_id", track.id)
                put("position", now)
                put("changed_at", now)
            }
            db.insertWithOnConflict("playlist_tracks", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
            playlistUidOf(db, playlistId)?.let { uid ->
                db.delete("playlist_removed", "playlist_uid = ? AND track_id = ?", arrayOf(uid, track.id))
                queueSync(db, "T|$uid|${track.id}")
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        notifySync(urgent = true)
    }

    /**
     * 5.3.0 import: adds [tracks] in this order in one go (positions count up from now, so the
     * playlist keeps the imported order). A song already in this playlist just stays once.
     */
    suspend fun addTracksToPlaylist(playlistId: Long, tracks: List<Track>) = withContext(Dispatchers.IO) {
        if (tracks.isEmpty()) return@withContext
        val db = writableDatabase
        val now = System.currentTimeMillis()
        db.beginTransaction()
        try {
            val uid = playlistUidOf(db, playlistId)
            tracks.forEachIndexed { i, track ->
                upsertTrackInternal(db, track)
                val cv = ContentValues().apply {
                    put("playlist_id", playlistId)
                    put("track_id", track.id)
                    put("position", now + i)
                    put("changed_at", now + i)
                }
                db.insertWithOnConflict("playlist_tracks", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
                if (uid != null) {
                    db.delete("playlist_removed", "playlist_uid = ? AND track_id = ?", arrayOf(uid, track.id))
                    queueSync(db, "T|$uid|${track.id}")
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        notifySync(urgent = true)
    }

    /** v4.8: which playlists already have this song (ticked in the Add to playlist sheet). */
    suspend fun getPlaylistIdsContaining(trackId: String): Set<Long> = withContext(Dispatchers.IO) {
        val ids = HashSet<Long>()
        readableDatabase.rawQuery(
            "SELECT playlist_id FROM playlist_tracks WHERE track_id = ?",
            arrayOf(trackId)
        ).use { c ->
            while (c.moveToNext()) ids.add(c.getLong(0))
        }
        ids
    }

    suspend fun removeTrackFromPlaylist(playlistId: Long, trackId: String) = withContext(Dispatchers.IO) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val n = db.delete(
                "playlist_tracks",
                "playlist_id = ? AND track_id = ?",
                arrayOf(playlistId.toString(), trackId)
            )
            val uid = playlistUidOf(db, playlistId)
            if (n > 0 && uid != null) {
                // v5.0.0: remember the removal (with its time) so it reaches the other phones
                val cv = ContentValues().apply {
                    put("playlist_uid", uid)
                    put("track_id", trackId)
                    put("at", System.currentTimeMillis())
                }
                db.insertWithOnConflict("playlist_removed", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
                queueSync(db, "T|$uid|$trackId")
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        notifySync(urgent = true)
    }

    suspend fun getPlaylistTracks(playlistId: Long): List<Track> = withContext(Dispatchers.IO) {
        val sql = """
            SELECT t.* FROM tracks t
            INNER JOIN playlist_tracks pt ON t.id = pt.track_id
            WHERE pt.playlist_id = ?
            ORDER BY pt.position ASC
        """.trimIndent()
        queryTracks(sql, arrayOf(playlistId.toString()))
    }

    suspend fun clearHistory() = withContext(Dispatchers.IO) {
        val cv = ContentValues().apply {
            put("last_played_at", 0L)
            put("play_count", 0)
        }
        writableDatabase.update("tracks", cv, null, null)
        writableDatabase.delete("plays", null, null)
        writableDatabase.delete("listen_time", null, null)
        writableDatabase.delete("tracks", "is_liked = 0 AND id NOT IN (SELECT track_id FROM playlist_tracks)", null)
    }

    internal fun upsertTrackInternal(db: SQLiteDatabase, track: Track) {
        val cv = ContentValues().apply {
            put("id", track.id)
            put("title", track.title)
            put("artist", track.artist)
            put("duration_sec", track.durationSec)
            put("thumbnail_url", track.thumbnailUrl)
            put("source_url", track.sourceUrl)
        }
        val inserted = db.insertWithOnConflict("tracks", null, cv, SQLiteDatabase.CONFLICT_IGNORE)
        if (inserted == -1L) {
            val updateCv = ContentValues().apply {
                put("title", track.title)
                put("artist", track.artist)
                put("duration_sec", track.durationSec)
                put("thumbnail_url", track.thumbnailUrl)
                put("source_url", track.sourceUrl)
            }
            db.update("tracks", updateCv, "id = ?", arrayOf(track.id))
        }
    }

    private fun queryTracks(sql: String, args: Array<String>): List<Track> {
        val list = ArrayList<Track>()
        readableDatabase.rawQuery(sql, args).use { c ->
            while (c.moveToNext()) {
                list.add(cursorToTrack(c))
            }
        }
        return list
    }

    private fun cursorToTrack(c: Cursor): Track {
        return Track(
            id = c.getString(c.getColumnIndexOrThrow("id")),
            title = c.getString(c.getColumnIndexOrThrow("title")),
            artist = c.getString(c.getColumnIndexOrThrow("artist")),
            durationSec = c.getLong(c.getColumnIndexOrThrow("duration_sec")),
            thumbnailUrl = c.getString(c.getColumnIndexOrThrow("thumbnail_url")),
            sourceUrl = c.getString(c.getColumnIndexOrThrow("source_url"))
        )
    }

    companion object {
        const val DB_NAME = "minim_library.db"
        private const val DB_VERSION = 5
    }
}
