package com.minim.music.data.local

import com.minim.music.data.stats.Days
import com.minim.music.data.stats.ListenRow
import com.minim.music.data.stats.LogRow
import com.minim.music.data.stats.PlayRow
import com.minim.music.data.stats.StatsEngine
import com.minim.music.data.stats.TapeListenRow
import com.minim.music.data.stats.TapePlayRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 6.9.0: multi-day reads for the Stats periods and the Weekly/Monthly Reprise. Read-only, and the
 * same rows the daily stats read, over a range of days. [cutHour] cuts the last day off at that
 * hour (the Monthly Reprise stops at 6 PM on the month's last day).
 */
object RepriseQueries {

    private fun endMs(toDay: Int, cutHour: Int?): Long =
        if (cutHour == null) Days.endMs(toDay)
        else Days.date(toDay).atTime(cutHour, 0).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()

    /** Hour filter on the last day, for tables keyed by day and hour. */
    private fun hourCut(toDay: Int, cutHour: Int?): String =
        if (cutHour == null) "" else " AND NOT (day = $toDay AND hour >= $cutHour)"

    suspend fun plays(db: LibraryDatabase, fromDay: Int, toDay: Int, cutHour: Int? = null): List<PlayRow> = withContext(Dispatchers.IO) {
        val out = ArrayList<PlayRow>()
        db.readableDatabase.rawQuery(
            """
            SELECT t.id, t.title, t.artist, t.thumbnail_url, t.duration_sec, p.played_at, p.listened_ms, p.tracked
            FROM plays p INNER JOIN tracks t ON t.id = p.track_id
            WHERE p.played_at >= ? AND p.played_at < ? ORDER BY p.played_at ASC
            """.trimIndent(),
            arrayOf(Days.startMs(fromDay).toString(), endMs(toDay, cutHour).toString())
        ).use { c ->
            while (c.moveToNext()) out += PlayRow(
                c.getString(0), c.getString(1) ?: "", c.getString(2) ?: "", c.getString(3) ?: "",
                c.getLong(4), c.getLong(5), c.getLong(6), c.getInt(7) == 1
            )
        }
        out
    }

    /** Real listening per song and hour over the range (summed over days). */
    suspend fun listens(db: LibraryDatabase, fromDay: Int, toDay: Int, cutHour: Int? = null): List<ListenRow> = withContext(Dispatchers.IO) {
        val out = ArrayList<ListenRow>()
        db.readableDatabase.rawQuery(
            """
            SELECT track_id, MAX(title), MAX(artist), MAX(thumbnail_url), hour, SUM(ms)
            FROM listen_time WHERE day >= ? AND day <= ?${hourCut(toDay, cutHour)} GROUP BY track_id, hour
            """.trimIndent(),
            arrayOf(fromDay.toString(), toDay.toString())
        ).use { c ->
            while (c.moveToNext()) out += ListenRow(
                c.getString(0), c.getString(1) ?: "", c.getString(2) ?: "", c.getString(3) ?: "", c.getInt(4), c.getLong(5)
            )
        }
        out
    }

    /** Song listening per day (real time, plus the estimate for old untracked plays). */
    suspend fun dayTotals(db: LibraryDatabase, fromDay: Int, toDay: Int, cutHour: Int? = null): Map<Int, Long> {
        if (cutHour == null) return db.getDayTotals(fromDay, toDay)
        return withContext(Dispatchers.IO) {
            val out = HashMap<Int, Long>()
            db.readableDatabase.rawQuery(
                "SELECT day, SUM(ms) FROM listen_time WHERE day >= ? AND day <= ?${hourCut(toDay, cutHour)} GROUP BY day",
                arrayOf(fromDay.toString(), toDay.toString())
            ).use { c -> while (c.moveToNext()) out[c.getInt(0)] = (out[c.getInt(0)] ?: 0L) + c.getLong(1) }
            db.readableDatabase.rawQuery(
                """
                SELECT p.played_at, p.listened_ms, t.duration_sec FROM plays p INNER JOIN tracks t ON t.id = p.track_id
                WHERE p.tracked = 0 AND p.played_at >= ? AND p.played_at < ?
                """.trimIndent(),
                arrayOf(Days.startMs(fromDay).toString(), endMs(toDay, cutHour).toString())
            ).use { c ->
                while (c.moveToNext()) {
                    val d = Days.keyOf(c.getLong(0))
                    val ms = if (c.getLong(2) > 0) c.getLong(2) * 1000L else c.getLong(1).coerceAtLeast(0L)
                    out[d] = (out[d] ?: 0L) + ms
                }
            }
            out
        }
    }

    suspend fun tapeDayTotals(db: LibraryDatabase, fromDay: Int, toDay: Int, cutHour: Int? = null): Map<Int, Long> {
        if (cutHour == null) return db.getTapeDayTotals(fromDay, toDay)
        return withContext(Dispatchers.IO) {
            val out = HashMap<Int, Long>()
            db.readableDatabase.rawQuery(
                "SELECT day, SUM(ms) FROM tape_listen_all WHERE day >= ? AND day <= ?${hourCut(toDay, cutHour)} GROUP BY day",
                arrayOf(fromDay.toString(), toDay.toString())
            ).use { c -> while (c.moveToNext()) out[c.getInt(0)] = c.getLong(1) }
            out
        }
    }

    suspend fun tapePlays(db: LibraryDatabase, fromDay: Int, toDay: Int, cutHour: Int? = null): List<TapePlayRow> = withContext(Dispatchers.IO) {
        val out = ArrayList<TapePlayRow>()
        db.readableDatabase.rawQuery(
            """
            SELECT video_id, title, channel, channel_id, thumbnail_url, duration_sec, played_at
            FROM tape_plays WHERE played_at >= ? AND played_at < ? ORDER BY played_at
            """.trimIndent(),
            arrayOf(Days.startMs(fromDay).toString(), endMs(toDay, cutHour).toString())
        ).use { c ->
            while (c.moveToNext()) out += TapePlayRow(
                c.getString(0), c.getString(1) ?: "", c.getString(2) ?: "", c.getString(3) ?: "",
                c.getString(4) ?: "", c.getLong(5), c.getLong(6)
            )
        }
        out
    }

    suspend fun tapeListens(db: LibraryDatabase, fromDay: Int, toDay: Int, cutHour: Int? = null): List<TapeListenRow> = withContext(Dispatchers.IO) {
        val out = ArrayList<TapeListenRow>()
        db.readableDatabase.rawQuery(
            """
            SELECT video_id, MAX(title), MAX(channel), MAX(channel_id), MAX(thumbnail_url), MAX(duration_sec), hour, speed, SUM(ms)
            FROM tape_listen_all WHERE day >= ? AND day <= ?${hourCut(toDay, cutHour)} GROUP BY video_id, hour, speed
            """.trimIndent(),
            arrayOf(fromDay.toString(), toDay.toString())
        ).use { c ->
            while (c.moveToNext()) out += TapeListenRow(
                c.getString(0), c.getString(1) ?: "", c.getString(2) ?: "", c.getString(3) ?: "",
                c.getString(4) ?: "", c.getLong(5), c.getInt(6), c.getInt(7), c.getLong(8)
            )
        }
        out
    }

    /** Artist keys with any play before [beforeMs] (for "new artists"). */
    suspend fun artistKeysBefore(db: LibraryDatabase, beforeMs: Long): Set<String> = withContext(Dispatchers.IO) {
        val out = HashSet<String>()
        db.readableDatabase.rawQuery(
            "SELECT DISTINCT t.artist FROM plays p INNER JOIN tracks t ON t.id = p.track_id WHERE p.played_at < ?",
            arrayOf(beforeMs.toString())
        ).use { c ->
            while (c.moveToNext()) {
                val name = StatsEngine.primaryArtist(c.getString(0) ?: "")
                if (name.isNotBlank()) out += StatsEngine.artistKey(name)
            }
        }
        out
    }

    /** Songs still in the library: liked, or in any playlist (the skip slide's "never removed"). */
    suspend fun keptIds(db: LibraryDatabase): Set<String> = withContext(Dispatchers.IO) {
        val out = HashSet<String>()
        db.readableDatabase.rawQuery("SELECT id FROM tracks WHERE is_liked = 1", null).use { c -> while (c.moveToNext()) out += c.getString(0) }
        db.readableDatabase.rawQuery("SELECT DISTINCT track_id FROM playlist_tracks", null).use { c -> while (c.moveToNext()) out += c.getString(0) }
        out
    }

    /** The first day with any play, or null. */
    suspend fun firstDay(db: LibraryDatabase): Int? = withContext(Dispatchers.IO) {
        db.readableDatabase.rawQuery("SELECT MIN(played_at) FROM plays", null).use { c ->
            if (c.moveToFirst() && !c.isNull(0) && c.getLong(0) > 0L) Days.keyOf(c.getLong(0)) else null
        }
    }
}

/**
 * 6.9.0: Fermata's log as [LogRow]s (plays and skips only), read through the app's bridge. Read
 * only: nothing in the Fermata module changes. Cleared and replaced events are left out, the same
 * way Fermata's own projections do it.
 */
object RepriseLog {
    /**
     * 6.9.0 b78: the plays and skips are kept in memory and only rows added since the last read are
     * decoded. It used to decode all of Fermata's history (10k+ JSON events) on every Stats pill
     * and every Reprise, and took Fermata's lock (held while its model builds) to find the clear
     * point. Now it reads the log table directly, without that lock: only play, skip and clear
     * rows, plus the rows that replace another, are decoded. A deleted row (a clear, a restore)
     * is seen from the row count, and the whole log is read again once.
     */
    private class Kept(
        val maxRow: Long,
        val count: Long,
        val events: List<com.minim.fermata.log.Event>,
        val rows: List<LogRow>
    )

    @Volatile private var kept: Kept? = null
    @Volatile private var keptFor: Int = 0
    private val lock = Any()

    /** How many times the whole log was decoded (for the tests). */
    @Volatile var fullReads = 0
        private set

    fun forget() { kept = null }

    fun rows(bridge: com.minim.music.data.fermata.FermataBridge?, beforeMs: Long = Long.MAX_VALUE): List<LogRow> {
        val db = bridge?.lib ?: return emptyList()
        return try {
            val all = synchronized(lock) { refresh(db) }
            if (beforeMs == Long.MAX_VALUE) all
            else {
                // rows are in time order: cut at the first one at or after beforeMs
                var hi = all.size
                var lo = 0
                while (lo < hi) { val m = (lo + hi) ushr 1; if (all[m].ts < beforeMs) lo = m + 1 else hi = m }
                if (hi == all.size) all else all.subList(0, hi)
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun refresh(lib: LibraryDatabase): List<LogRow> {
        val db = lib.readableDatabase
        val table = LibraryDatabase.FERMATA_LOG_TABLE
        val (maxRow, count) = db.rawQuery("SELECT IFNULL(MAX(rowid), 0), COUNT(*) FROM $table", null).use { c ->
            if (c.moveToFirst()) c.getLong(0) to c.getLong(1) else 0L to 0L
        }
        val k = kept?.takeIf { keptFor == System.identityHashCode(lib) }
        if (k != null && k.maxRow == maxRow && k.count == count) return k.rows
        val fresh = k == null || maxRow < k.maxRow
        val after = if (fresh) 0L else k!!.maxRow
        val types = "'${com.minim.fermata.log.EventBody.PLAY}','${com.minim.fermata.log.EventBody.SKIP}','${com.minim.fermata.log.EventBody.HISTORY_CLEAR}'"
        val added = ArrayList<com.minim.fermata.log.Event>()
        db.rawQuery(
            "SELECT json FROM $table WHERE rowid > ? AND (type IN ($types) OR json LIKE '%\"replaces\"%')",
            arrayOf(after.toString())
        ).use { c ->
            while (c.moveToNext()) {
                try { added += com.minim.fermata.backup.EventCodec.decode(c.getString(0)) } catch (_: Exception) {}
            }
        }
        // Rows were deleted too (a clear or a restore): start again from the whole log
        if (!fresh && k!!.count + countAfter(db, table, after) != count) {
            kept = null
            return refresh(lib)
        }
        if (fresh) fullReads++
        val events = if (fresh) added else k!!.events + added
        val cleared = com.minim.fermata.log.EventLog.clearedBefore(events)
        val rows = com.minim.fermata.log.EventLog.effective(events).mapNotNull { e ->
            if (com.minim.fermata.log.EventLog.isCleared(e, cleared)) return@mapNotNull null
            when (val b = e.body) {
                is com.minim.fermata.log.EventBody.Play -> LogRow(LogRow.PLAY, b.track.id, b.track.title, b.track.artist, e.ctx.timestamp, e.ctx.source.key, b.track.thumbnailUrl)
                is com.minim.fermata.log.EventBody.Skip -> LogRow(LogRow.SKIP, b.track.id, b.track.title, b.track.artist, e.ctx.timestamp, e.ctx.source.key, b.track.thumbnailUrl)
                else -> null
            }
        }
        // Only what can matter later is kept: the plays, skips, clears and replacing rows
        kept = Kept(maxRow, count, events, rows)
        keptFor = System.identityHashCode(lib)
        return rows
    }

    private fun countAfter(db: android.database.sqlite.SQLiteDatabase, table: String, after: Long): Long =
        db.rawQuery("SELECT COUNT(*) FROM $table WHERE rowid > ?", arrayOf(after.toString())).use { c -> if (c.moveToFirst()) c.getLong(0) else 0L }
}
