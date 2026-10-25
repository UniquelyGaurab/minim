package com.minim.music.data.sync

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import com.minim.music.data.local.LibraryDatabase
import com.minim.music.data.model.Track
import com.minim.music.data.stats.Days
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Minim 5.0.0 part 2: the database side of cloud sync on a real SQLite database. The upgrade from
 * the 4.9.10 schema (version 3) must keep the whole library, and merging cloud documents must be
 * idempotent and follow the merge rules.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class LibrarySyncDbTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private var lib: LibraryDatabase? = null

    @Before
    fun clean() {
        context.deleteDatabase(LibraryDatabase.DB_NAME)
    }

    @After
    fun close() {
        lib?.close()
        context.deleteDatabase(LibraryDatabase.DB_NAME)
    }

    private fun open(): LibraryDatabase = LibraryDatabase(context).also { lib = it }

    private fun track(id: String, title: String = "Song $id") =
        Track(id, title, "Artist", 200L, "https://img/$id", "https://www.youtube.com/watch?v=$id")

    private fun count(db: SQLiteDatabase, sql: String, vararg args: String): Int =
        db.rawQuery(sql, args).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }

    private fun outbox(l: LibraryDatabase): List<String> =
        l.readableDatabase.rawQuery("SELECT k FROM sync_outbox ORDER BY id", null).use { c ->
            val out = ArrayList<String>()
            while (c.moveToNext()) out.add(c.getString(0))
            out
        }

    // ---- the upgrade from 4.9.10 ---------------------------------------------------------------

    /** The database exactly as 4.9.10 (schema version 3) leaves it, with a small library in it. */
    private fun createV3Database(): Long {
        val file = context.getDatabasePath(LibraryDatabase.DB_NAME)
        file.parentFile?.mkdirs()
        val db = SQLiteDatabase.openOrCreateDatabase(file, null)
        val sql = listOf(
            """CREATE TABLE tracks (id TEXT PRIMARY KEY, title TEXT NOT NULL, artist TEXT NOT NULL,
                duration_sec INTEGER NOT NULL, thumbnail_url TEXT NOT NULL, source_url TEXT NOT NULL,
                is_liked INTEGER NOT NULL DEFAULT 0, liked_at INTEGER NOT NULL DEFAULT 0,
                last_played_at INTEGER NOT NULL DEFAULT 0, play_count INTEGER NOT NULL DEFAULT 0)""",
            "CREATE TABLE playlists (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL UNIQUE, created_at INTEGER NOT NULL)",
            """CREATE TABLE playlist_tracks (playlist_id INTEGER NOT NULL, track_id TEXT NOT NULL,
                position INTEGER NOT NULL, PRIMARY KEY (playlist_id, track_id))""",
            """CREATE TABLE plays (id INTEGER PRIMARY KEY AUTOINCREMENT, track_id TEXT NOT NULL,
                played_at INTEGER NOT NULL, listened_ms INTEGER NOT NULL DEFAULT 0)""",
            "CREATE INDEX idx_plays_track ON plays(track_id)",
            "CREATE INDEX idx_plays_time ON plays(played_at)",
            """CREATE TABLE not_interested (track_id TEXT PRIMARY KEY, title TEXT NOT NULL DEFAULT '',
                artist TEXT NOT NULL DEFAULT '', added_at INTEGER NOT NULL)""",
            """CREATE TABLE listen_time (day INTEGER NOT NULL, hour INTEGER NOT NULL, track_id TEXT NOT NULL,
                title TEXT NOT NULL DEFAULT '', artist TEXT NOT NULL DEFAULT '', thumbnail_url TEXT NOT NULL DEFAULT '',
                ms INTEGER NOT NULL DEFAULT 0, PRIMARY KEY (day, hour, track_id))""",
            "ALTER TABLE plays ADD COLUMN tracked INTEGER NOT NULL DEFAULT 0"
        )
        sql.forEach { db.execSQL(it) }

        val t0 = 1_760_000_000_000L
        fun addTrack(id: String, liked: Boolean, likedAt: Long, lastPlayed: Long, plays: Int) = db.execSQL(
            "INSERT INTO tracks VALUES (?, ?, 'Artist', 200, ?, ?, ?, ?, ?, ?)",
            arrayOf<Any>(id, "Song $id", "https://img/$id", "https://www.youtube.com/watch?v=$id",
                if (liked) 1 else 0, likedAt, lastPlayed, plays)
        )
        addTrack("likedSong01", true, t0 + 1_000L, t0 + 5_000L, 2)
        addTrack("plainSong02", false, 0L, t0 + 6_000L, 1)
        addTrack("listSong003", false, 0L, 0L, 0)
        db.execSQL("INSERT INTO playlists (name, created_at) VALUES ('Gym', ?)", arrayOf<Any>(t0))
        db.execSQL("INSERT INTO playlists (name, created_at) VALUES ('Late night', ?)", arrayOf<Any>(t0 + 10L))
        db.execSQL("INSERT INTO playlist_tracks VALUES (1, 'listSong003', ?)", arrayOf<Any>(t0 + 100L))
        db.execSQL("INSERT INTO playlist_tracks VALUES (1, 'likedSong01', ?)", arrayOf<Any>(t0 + 200L))
        // two plays of the same song in the same millisecond (old builds could do that): still two plays
        db.execSQL("INSERT INTO plays (track_id, played_at, listened_ms, tracked) VALUES ('likedSong01', ?, 40000, 1)", arrayOf<Any>(t0 + 5_000L))
        db.execSQL("INSERT INTO plays (track_id, played_at, listened_ms, tracked) VALUES ('likedSong01', ?, 35000, 1)", arrayOf<Any>(t0 + 5_000L))
        db.execSQL("INSERT INTO plays (track_id, played_at, listened_ms, tracked) VALUES ('plainSong02', ?, 0, 0)", arrayOf<Any>(t0 + 6_000L))
        val day = Days.keyOf(t0 + 5_000L)
        db.execSQL("INSERT INTO listen_time VALUES (?, 9, 'likedSong01', 'Song likedSong01', 'Artist', '', 75000)", arrayOf<Any>(day))
        db.execSQL("INSERT INTO listen_time VALUES (?, 10, 'plainSong02', 'Song plainSong02', 'Artist', '', 20000)", arrayOf<Any>(day))
        db.execSQL("INSERT INTO not_interested VALUES ('hiddenSong4', 'Nope', 'Someone', ?)", arrayOf<Any>(t0))
        db.version = 3
        db.close()
        return t0
    }

    @Test
    fun upgradeFrom4910KeepsTheWholeLibrary() = runBlocking {
        val t0 = createV3Database()
        val l = open()
        val db = l.writableDatabase
        assertEquals(4, db.version)

        // likes
        val liked = l.getLikedTracks()
        assertEquals(listOf("likedSong01"), liked.map { it.id })
        assertEquals(t0 + 1_000L, db.rawQuery("SELECT like_changed_at FROM tracks WHERE id = 'likedSong01'", null)
            .use { it.moveToFirst(); it.getLong(0) })
        assertEquals(0, count(db, "SELECT like_changed_at FROM tracks WHERE id = 'plainSong02'"))

        // playlists keep their songs and order, and get their sync IDs
        val lists = l.getPlaylists().associateBy { it.name }
        assertEquals(setOf("Gym", "Late night"), lists.keys)
        assertEquals(listOf("listSong003", "likedSong01"), l.getPlaylistTracks(lists.getValue("Gym").id).map { it.id })
        assertEquals(SyncIds.playlistUid("Gym"),
            db.rawQuery("SELECT uid FROM playlists WHERE name = 'Gym'", null).use { it.moveToFirst(); it.getString(0) })
        assertEquals(SyncIds.playlistUid("Late night"),
            db.rawQuery("SELECT uid FROM playlists WHERE name = 'Late night'", null).use { it.moveToFirst(); it.getString(0) })
        assertEquals(0, count(db, "SELECT COUNT(*) FROM playlist_tracks WHERE changed_at = 0"))

        // plays: all three kept, each with its own event ID
        assertEquals(3, count(db, "SELECT COUNT(*) FROM plays"))
        assertEquals(3, count(db, "SELECT COUNT(DISTINCT event_id) FROM plays WHERE event_id IS NOT NULL"))
        val day = Days.keyOf(t0 + 5_000L)
        assertEquals(3, l.getDayPlays(day).size)

        // listening time: moved to "this phone", same numbers
        assertEquals(2, count(db, "SELECT COUNT(*) FROM listen_time WHERE device = ''"))
        val listens = l.getDayListens(day).associateBy { it.trackId }
        assertEquals(75_000L, listens.getValue("likedSong01").ms)
        assertEquals(20_000L, listens.getValue("plainSong02").ms)

        // recents, not interested, and nothing queued for the cloud (nobody is signed in)
        assertEquals(listOf("plainSong02", "likedSong01"), l.getRecentTracks().map { it.id })
        assertEquals(1, count(db, "SELECT COUNT(*) FROM not_interested"))
        assertTrue(outbox(l).isEmpty())

        // the upgrade is safe to run again (an older build may open the database in between)
        l.onUpgrade(db, 3, 4)
        assertEquals(3, count(db, "SELECT COUNT(*) FROM plays"))
        assertEquals(2, count(db, "SELECT COUNT(*) FROM listen_time"))
        assertEquals(2, l.getPlaylistTracks(lists.getValue("Gym").id).size)

        // and the app keeps working on it
        l.toggleLike(track("plainSong02"))
        assertEquals(2, l.getLikedTracks().size)
    }

    @Test
    fun nothingIsQueuedWhileSignedOut() = runBlocking {
        val l = open()
        l.toggleLike(track("songAAAAAAA"))
        val pid = l.createPlaylist("Gym")
        l.addTrackToPlaylist(pid, track("songBBBBBBB"))
        assertTrue(outbox(l).isEmpty())

        l.syncTracking = true
        l.toggleLike(track("songAAAAAAA"))
        l.removeTrackFromPlaylist(pid, "songBBBBBBB")
        val keys = outbox(l)
        assertTrue(keys.contains("L|songAAAAAAA"))
        assertTrue(keys.contains("T|${SyncIds.playlistUid("Gym")}|songBBBBBBB"))
    }

    // ---- likes ---------------------------------------------------------------------------------

    @Test
    fun newerUnlikeFromTheCloudWinsAndMergingTwiceChangesNothing() = runBlocking {
        val l = open()
        l.syncTracking = true
        l.toggleLike(track("songAAAAAAA"))
        val sync = LibrarySync(l)
        val localAt = l.readableDatabase.rawQuery("SELECT like_changed_at FROM tracks WHERE id = 'songAAAAAAA'", null)
            .use { it.moveToFirst(); it.getLong(0) }

        val remote = LibrarySync.RemoteDoc("songAAAAAAA", SyncCodec.likeDoc(LikeState(false, localAt + 10_000L), null))
        assertTrue(sync.applyLikes(listOf(remote)))
        l.refreshLikedCache()
        assertTrue(l.getLikedTracks().isEmpty())
        assertFalse(outbox(l).contains("L|songAAAAAAA")) // the cloud already has it

        assertFalse(sync.applyLikes(listOf(remote)))
        assertTrue(l.getLikedTracks().isEmpty())
    }

    @Test
    fun olderLikeFromTheCloudLosesAndTheLocalChangeGoesUp() = runBlocking {
        val l = open()
        l.syncTracking = true
        l.toggleLike(track("songAAAAAAA"))
        LibrarySync(l).clearOutbox()
        val sync = LibrarySync(l)
        val remote = LibrarySync.RemoteDoc("songAAAAAAA", SyncCodec.likeDoc(LikeState(false, 1_000L), null))
        assertFalse(sync.applyLikes(listOf(remote)))
        assertEquals(listOf("songAAAAAAA"), l.getLikedTracks().map { it.id })
        assertTrue(outbox(l).contains("L|songAAAAAAA"))
    }

    @Test
    fun likeOfAnUnknownSongArrivesWithItsDetails() = runBlocking {
        val l = open()
        val sync = LibrarySync(l)
        val meta = SongMeta("Suroor", "Some Artist", "https://img/s", 240L)
        assertTrue(sync.applyLikes(listOf(LibrarySync.RemoteDoc("songNEW0001", SyncCodec.likeDoc(LikeState(true, 5_000L), meta)))))
        val t = l.getLikedTracks().single()
        assertEquals("Suroor", t.title)
        assertEquals(240L, t.durationSec)
    }

    // ---- history -------------------------------------------------------------------------------

    private fun historyDoc(day: Int, plays: Map<String, PlayEv>, listens: Map<String, Map<String, Long>>): LibrarySync.RemoteDoc =
        LibrarySync.RemoteDoc(
            day.toString(),
            mapOf(
                "d" to day.toLong(),
                "p" to plays.mapValues { SyncCodec.encodePlay(it.value) },
                "s" to mapOf("songAAAAAAA" to SyncCodec.encodeMeta(SongMeta("A", "Artist", "", 100L))),
                "l" to listens
            )
        )

    @Test
    fun playsAreNeverCountedTwice() = runBlocking {
        val l = open()
        val sync = LibrarySync(l)
        sync.ensureDevice("dMINE")
        val at = 1_760_000_000_000L
        val day = Days.keyOf(at)
        val ev = PlayEv("songAAAAAAA", at, 60_000L, true)
        val eid = SyncIds.eventId(at, "songAAAAAAA")
        val doc = historyDoc(day, mapOf(eid to ev), emptyMap())

        assertTrue(sync.applyHistory(listOf(doc)))
        assertFalse(sync.applyHistory(listOf(doc)))
        assertFalse(sync.applyHistory(listOf(doc, doc)))
        val db = l.readableDatabase
        assertEquals(1, count(db, "SELECT COUNT(*) FROM plays"))
        assertEquals(1, count(db, "SELECT play_count FROM tracks WHERE id = 'songAAAAAAA'"))
        assertEquals(1, l.getDayPlays(day).size)

        // the same play heard for longer on the other phone: the longer time wins, still one play
        val longer = historyDoc(day, mapOf(eid to ev.copy(listenedMs = 90_000L)), emptyMap())
        assertTrue(sync.applyHistory(listOf(longer)))
        assertEquals(90_000L, l.getDayPlays(day).single().listenedMs)
        assertEquals(1, count(db, "SELECT play_count FROM tracks WHERE id = 'songAAAAAAA'"))
    }

    @Test
    fun listeningIsPerPhoneAndTheBiggerNumberWins() = runBlocking {
        val l = open()
        val sync = LibrarySync(l)
        sync.ensureDevice("dMINE")
        val day = 20261024
        val db = l.writableDatabase
        db.execSQL("INSERT INTO listen_time (day, hour, track_id, ms, device) VALUES (?, 9, 'songAAAAAAA', 4000, '')", arrayOf<Any>(day))

        val key = SyncCodec.listenKey(9, "songAAAAAAA")
        // this phone's own older number (e.g. from before a reinstall) and another phone's
        val doc = historyDoc(day, emptyMap(), mapOf("dMINE" to mapOf(key to 3_000L), "dOTHER" to mapOf(key to 5_000L)))
        assertTrue(sync.applyHistory(listOf(doc)))
        assertEquals(4_000L, db.rawQuery("SELECT ms FROM listen_time WHERE device = ''", null).use { it.moveToFirst(); it.getLong(0) })
        assertEquals(5_000L, db.rawQuery("SELECT ms FROM listen_time WHERE device = 'dOTHER'", null).use { it.moveToFirst(); it.getLong(0) })
        assertEquals(9_000L, l.getDayListens(day).single().ms)

        // merging again, or an older number, changes nothing
        assertFalse(sync.applyHistory(listOf(doc)))
        val older = historyDoc(day, emptyMap(), mapOf("dOTHER" to mapOf(key to 1_000L)))
        assertFalse(sync.applyHistory(listOf(older)))
        assertEquals(9_000L, l.getDayListens(day).single().ms)

        // a bigger own number from the cloud raises this phone's counter
        val bigger = historyDoc(day, emptyMap(), mapOf("dMINE" to mapOf(key to 7_000L)))
        assertTrue(sync.applyHistory(listOf(bigger)))
        assertEquals(12_000L, l.getDayListens(day).single().ms)
    }

    // ---- playlists -----------------------------------------------------------------------------

    private fun playlistDoc(uid: String, s: PlState) = LibrarySync.RemoteDoc(
        uid,
        HashMap<String, Any?>(SyncCodec.playlistHeader(s)).apply {
            put("t", s.tracks.mapValues { SyncCodec.encodeEntry(it.value) })
        }
    )

    @Test
    fun playlistFromTheCloudMergesIntoTheSameNamedPlaylist() = runBlocking {
        val l = open()
        l.syncTracking = true
        val pid = l.createPlaylist("Gym")
        l.addTrackToPlaylist(pid, track("songAAAAAAA"))
        val uid = SyncIds.playlistUid("Gym")
        val sync = LibrarySync(l)
        val now = System.currentTimeMillis()
        val remote = PlState("Gym", 100L, 100L, 0L, false, mapOf(
            "songBBBBBBB" to PlEntry(now - 50_000L, now - 50_000L, false, SongMeta("B", "Artist", "", 1L)),
            "songAAAAAAA" to PlEntry(now - 60_000L, now - 60_000L, false, null)
        ))
        assertTrue(sync.applyPlaylists(listOf(playlistDoc(uid, remote))))
        assertEquals(1, l.getPlaylists().size)
        val songs = l.getPlaylistTracks(pid).map { it.id }
        assertEquals(setOf("songAAAAAAA", "songBBBBBBB"), songs.toSet())
        assertEquals(2, songs.size)
        // what the cloud has doesn't go up again; this phone's newer add of songA does
        val keys = outbox(l)
        assertFalse(keys.contains("T|$uid|songBBBBBBB"))
        assertTrue(keys.contains("T|$uid|songAAAAAAA"))

        assertFalse(sync.applyPlaylists(listOf(playlistDoc(uid, remote))))
        assertEquals(2, l.getPlaylistTracks(pid).size)
    }

    @Test
    fun removedSongStaysRemovedAgainstAnOlderAdd() = runBlocking {
        val l = open()
        l.syncTracking = true
        val pid = l.createPlaylist("Gym")
        l.addTrackToPlaylist(pid, track("songAAAAAAA"))
        l.removeTrackFromPlaylist(pid, "songAAAAAAA")
        val uid = SyncIds.playlistUid("Gym")
        val sync = LibrarySync(l)

        val olderAdd = PlState("Gym", 100L, 100L, 0L, false, mapOf("songAAAAAAA" to PlEntry(1_000L, 1_000L, false, null)))
        sync.applyPlaylists(listOf(playlistDoc(uid, olderAdd)))
        assertTrue(l.getPlaylistTracks(pid).isEmpty())
        assertTrue(outbox(l).contains("T|$uid|songAAAAAAA"))

        val newerAdd = PlState("Gym", 100L, 100L, 0L, false,
            mapOf("songAAAAAAA" to PlEntry(Long.MAX_VALUE / 2, Long.MAX_VALUE / 2, false, null)))
        assertTrue(sync.applyPlaylists(listOf(playlistDoc(uid, newerAdd))))
        assertEquals(listOf("songAAAAAAA"), l.getPlaylistTracks(pid).map { it.id })
        assertEquals(0, count(l.readableDatabase, "SELECT COUNT(*) FROM playlist_removed"))
    }

    @Test
    fun deleteFromTheCloudRemovesThePlaylistAndRemakingItStartsEmpty() = runBlocking {
        val l = open()
        l.syncTracking = true
        val pid = l.createPlaylist("Gym")
        l.addTrackToPlaylist(pid, track("songAAAAAAA"))
        val uid = SyncIds.playlistUid("Gym")
        val sync = LibrarySync(l)

        val deletedAt = System.currentTimeMillis() + 1_000L
        val tomb = PlState("Gym", 100L, deletedAt, 0L, true, emptyMap())
        assertTrue(sync.applyPlaylists(listOf(playlistDoc(uid, tomb))))
        assertTrue(l.getPlaylists().isEmpty())
        assertFalse(outbox(l).any { it.startsWith("T|$uid|") })
        assertFalse(sync.applyPlaylists(listOf(playlistDoc(uid, tomb))))

        // made again: it starts empty, and the cloud's old songs (still in its map) stay out
        val pid2 = l.createPlaylist("Gym")
        assertTrue(pid2 > 0L)
        val staleCloud = PlState("Gym", 100L, 100L, 0L, false, mapOf("songAAAAAAA" to PlEntry(500L, 500L, false, null)))
        sync.applyPlaylists(listOf(playlistDoc(uid, staleCloud)))
        assertEquals(1, l.getPlaylists().size)
        assertTrue(l.getPlaylistTracks(pid2).isEmpty())
    }

    // ---- a round trip through the cloud format -------------------------------------------------

    @Test
    fun whatOnePhoneWritesTheOtherReadsBackTheSame() = runBlocking {
        // phone A
        var l = open()
        LibrarySync(l).ensureDevice("dPHONEA")
        l.syncTracking = true
        l.toggleLike(track("songAAAAAAA"))
        val pid = l.createPlaylist("Gym")
        l.addTrackToPlaylist(pid, track("songBBBBBBB"))
        val rowId = l.recordCountedPlay(track("songBBBBBBB"), 45_000L)
        assertNotNull(rowId)
        l.markNotInterested(track("songCCCCCCC"))
        val syncA = LibrarySync(l)
        syncA.ensureDevice("dPHONEA")
        val writes = syncA.buildWrites(syncA.readOutbox(1000))
        val paths = writes.map { it.path }
        assertEquals(paths.size, paths.toSet().size) // one write per document
        assertTrue("likes/songAAAAAAA" in paths)
        assertTrue("hidden/songCCCCCCC" in paths)
        assertTrue("playlists/${SyncIds.playlistUid("Gym")}" in paths)
        assertTrue(paths.any { it.startsWith("history/") })
        l.close()
        context.deleteDatabase(LibraryDatabase.DB_NAME)

        // phone B: a fresh install reads those documents
        l = open()
        val syncB = LibrarySync(l)
        syncB.ensureDevice("dPHONEB")
        fun docs(col: String) = writes.filter { it.path.startsWith("$col/") }
            .map { LibrarySync.RemoteDoc(it.path.substringAfter('/'), it.data) }
        assertTrue(syncB.applyLikes(docs("likes")))
        assertTrue(syncB.applyHidden(docs("hidden")))
        assertTrue(syncB.applyPlaylists(docs("playlists")))
        assertTrue(syncB.applyHistory(docs("history")))
        l.refreshLikedCache()

        assertEquals(listOf("songAAAAAAA"), l.getLikedTracks().map { it.id })
        val gym = l.getPlaylists().single()
        assertEquals("Gym", gym.name)
        assertEquals(listOf("songBBBBBBB"), l.getPlaylistTracks(gym.id).map { it.id })
        assertEquals("Song songBBBBBBB", l.getPlaylistTracks(gym.id).single().title)
        assertEquals(1, count(l.readableDatabase, "SELECT COUNT(*) FROM plays"))
        assertEquals(1, count(l.readableDatabase, "SELECT COUNT(*) FROM not_interested"))

        // reading them all again changes nothing
        assertFalse(syncB.applyLikes(docs("likes")))
        assertFalse(syncB.applyHidden(docs("hidden")))
        assertFalse(syncB.applyPlaylists(docs("playlists")))
        assertFalse(syncB.applyHistory(docs("history")))
    }
}
