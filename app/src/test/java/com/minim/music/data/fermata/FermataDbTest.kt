package com.minim.music.data.fermata

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import com.minim.music.data.local.LibraryDatabase
import com.minim.music.data.model.Track
import com.minim.music.data.stories.StoryTracks
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.Executor

/** 5.9.0 u59: the fermata_events table, the 6 to 7 update, backups and source tags on the phone side. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class FermataDbTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private var lib: LibraryDatabase? = null

    @Before fun clean() {
        com.minim.music.data.local.TestLibraryFiles.wipe(context)
        context.getSharedPreferences(FermataStore.STATE_PREFS, Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences(FermataStore.DEVICE_PREFS, Context.MODE_PRIVATE).edit().clear().commit()
    }

    @After fun close() {
        lib?.close()
        com.minim.music.data.local.TestLibraryFiles.wipe(context)
    }

    private fun open(): LibraryDatabase = LibraryDatabase(context).also { lib = it }

    private fun song(id: String) =
        Track(id, "Song $id", "Arijit Singh", 200L, "https://img/$id", "https://www.youtube.com/watch?v=$id")

    private fun count(db: SQLiteDatabase, sql: String): Int = db.rawQuery(sql, null).use { if (it.moveToFirst()) it.getInt(0) else 0 }

    private fun tableCounts(db: SQLiteDatabase): Map<String, Int> {
        val names = db.rawQuery(
            "SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%' AND name NOT IN ('android_metadata', 'fermata_events')",
            null
        ).use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }
        return names.associateWith { count(db, "SELECT COUNT(*) FROM \"$it\"") }
    }

    private fun dump(db: SQLiteDatabase, table: String): List<List<String?>> =
        db.rawQuery("SELECT * FROM \"$table\" ORDER BY 1, 2", null).use { c ->
            buildList { while (c.moveToNext()) add((0 until c.columnCount).map { if (c.isNull(it)) null else c.getString(it) }) }
        }

    @Test fun updatingFrom6To7KeepsEveryRowAndAddsTheEventTable() = runBlocking {
        val path = context.getDatabasePath(LibraryDatabase.DB_NAME)
        path.parentFile?.mkdirs()
        // a library with a bit of everything, as 5.8.6 left it
        com.minim.music.data.local.TestLibraryFiles.legacy(context).also { lib = it }.apply {
            toggleLike(song("likedSong01"))
            val pl = createPlaylist("Chill Evening Mix")
            addTrackToPlaylist(pl, song("plSong00001"))
            addTrackToPlaylist(pl, song("plSong00002"))
            recordPlayback(song("plainSong02"))
            recordCountedPlay(song("plainSong02"), 60_000L)
            recordTapePlay(StoryTracks.track("tapeVideo01", "Tape", "SR PAY STORIES", 3600L, "UCsrpay000001"), "UCsrpay000001")
            markNotInterested(song("hiddenSong4"))
            close()
        }
        lib = null
        val before: Map<String, Int>
        val beforeRows: Map<String, List<List<String?>>>
        SQLiteDatabase.openDatabase(path.path, null, SQLiteDatabase.OPEN_READWRITE).use { raw ->
            raw.execSQL("DROP TABLE IF EXISTS fermata_events")
            raw.version = 6
            before = tableCounts(raw)
            beforeRows = before.keys.associateWith { dump(raw, it) }
        }
        assertTrue(before.values.sum() >= 8)

        val db = open()
        val w = db.writableDatabase
        assertEquals(8, w.version)
        assertEquals(before, tableCounts(w).filterKeys { it in before })
        for (t in before.keys) assertEquals("table $t", beforeRows[t], dump(w, t))
        assertEquals(1, db.getLikedTracks().size)
        assertEquals(2, db.getPlaylistTracks(db.getAllPlaylistIdsForTest(w).single()).size)

        val cols = w.rawQuery("SELECT name, type, \"notnull\", pk FROM pragma_table_info('fermata_events') ORDER BY cid", null).use { c ->
            buildList { while (c.moveToNext()) add(listOf(c.getString(0), c.getString(1), c.getString(2), c.getString(3))) }
        }
        assertEquals(
            listOf("id", "type", "track_id", "title", "artist", "duration_ms", "ts", "hour", "dow", "session_id",
                "prev_track_id", "source", "listened_ms", "position_ms", "device_id", "speed_pct"),
            cols.map { it[0] }
        )
        assertEquals(listOf("id", "INTEGER", "0", "1"), cols[0])
        assertEquals(listOf("track_id", "TEXT", "1", "0"), cols[2])
        assertEquals(1, count(w, "SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name = 'fermata_events' AND sql LIKE '%AUTOINCREMENT%'"))
        assertEquals(1, count(w, "SELECT COUNT(*) FROM pragma_index_list('fermata_events') WHERE name = 'idx_fermata_events_ts'"))
        assertEquals("ts", w.rawQuery("SELECT name FROM pragma_index_info('idx_fermata_events_ts')", null).use { it.moveToFirst(); it.getString(0) })
        assertEquals(0, count(w, "SELECT COUNT(*) FROM fermata_events"))
    }

    private fun LibraryDatabase.getAllPlaylistIdsForTest(w: SQLiteDatabase): List<Long> =
        w.rawQuery("SELECT id FROM playlists", null).use { c -> buildList { while (c.moveToNext()) add(c.getLong(0)) } }

    @Test fun aFreshInstallHasTheTableToo() {
        val w = open().writableDatabase
        assertEquals(8, w.version)
        assertEquals(1, count(w, "SELECT COUNT(*) FROM sqlite_master WHERE name = 'fermata_events'"))
    }

    @Test fun theLoggerWritesRealRowsAndNothingIsQueuedForSync() = runBlocking {
        val db = open()
        val syncBefore = count(db.writableDatabase, "SELECT COUNT(*) FROM sync_outbox")
        val f = FermataLogger(
            FermataStore.sink(db), FermataStore.stateKv(context), Executor { it.run() },
            deviceIdKv = FermataStore.deviceKv(context)
        )
        f.startPlay("aaaaaaaaaaa", "Song a", "Arijit Singh", 200_000, false, FermataSource.RADIO)
        f.heard("aaaaaaaaaaa", 30_000)
        f.flush()
        f.leave(userMovedOn = true, positionMs = 30_000)
        f.queued("bbbbbbbbbbb", "Song b", "X", 100_000, FermataSource.QUEUE)
        val rows = db.getFermataEvents()
        assertEquals(listOf("play", "skip", "queued"), rows.map { it.type })
        assertEquals(listOf(1L, 2L, 3L), rows.map { it.id })
        assertEquals(30_000L, rows[0].listenedMs)
        assertNull(rows[0].positionMs)
        assertEquals(30_000L, rows[1].positionMs)
        assertEquals("radio", rows[0].source)
        val dev = context.getSharedPreferences(FermataStore.DEVICE_PREFS, Context.MODE_PRIVATE).getString(FermataDeviceId.KEY, null)
        assertTrue(FermataDeviceId.isValid(dev))
        assertTrue(rows.all { it.deviceId == dev })
        // the device id isn't in the backed-up settings file
        assertFalse(context.getSharedPreferences("minim_desk_prefs", Context.MODE_PRIVATE).all.containsKey(FermataDeviceId.KEY))
        // local only: no sync work, and existing tables untouched
        assertEquals(syncBefore, count(db.writableDatabase, "SELECT COUNT(*) FROM sync_outbox"))
        assertEquals(0, count(db.writableDatabase, "SELECT COUNT(*) FROM plays"))
    }

    @Test fun theSessionIsSavedAcrossARestartOnThePhone() {
        val db = open()
        val now = longArrayOf(1_794_410_100_000L)
        fun logger() = FermataLogger(
            FermataStore.sink(db), FermataStore.stateKv(context), Executor { it.run() }, clock = { now[0] },
            deviceIdKv = FermataStore.deviceKv(context)
        )
        logger().apply { startPlay("aaaaaaaaaaa", "a", "b", 200_000, false, null); heard("aaaaaaaaaaa", 10_000); flush() }
        now[0] += 10 * 60_000L
        logger().apply { startPlay("bbbbbbbbbbb", "a", "b", 200_000, false, null); leave(false, null) }
        val rows = db.getFermataEvents()
        assertEquals(rows[0].sessionId, rows[1].sessionId)
        assertEquals("aaaaaaaaaaa", rows[1].prevTrackId)
        assertEquals(36, rows[0].sessionId.length) // a random UUID
        assertEquals(rows[0].deviceId, rows[1].deviceId)
    }

    @Test fun clearingHistoryEmptiesPlaysTapePlaysAndFermataEvents() = runBlocking {
        val db = open()
        val w = db.writableDatabase
        db.recordPlayback(song("plainSong02"))
        db.recordCountedPlay(song("plainSong02"), 60_000L)
        db.recordTapePlay(StoryTracks.track("tapeVideo01", "Tape", "SR PAY STORIES", 3600L, "UCsrpay000001"), "UCsrpay000001")
        val f = FermataLogger(
            FermataStore.sink(db), FermataStore.stateKv(context), Executor { it.run() },
            deviceIdKv = FermataStore.deviceKv(context)
        )
        f.startPlay("aaaaaaaaaaa", "Song a", "Arijit Singh", 200_000, false, FermataSource.SEARCH)
        f.heard("aaaaaaaaaaa", 30_000)
        f.leave(userMovedOn = true, positionMs = 30_000)
        f.queued("bbbbbbbbbbb", "Song b", "X", 100_000, FermataSource.QUEUE)
        assertTrue(count(w, "SELECT COUNT(*) FROM plays") > 0)
        assertTrue(count(w, "SELECT COUNT(*) FROM tape_plays") > 0)
        assertTrue(count(w, "SELECT COUNT(*) FROM fermata_events") >= 3)

        db.clearHistory()

        assertEquals(0, count(w, "SELECT COUNT(*) FROM plays"))
        assertEquals(0, count(w, "SELECT COUNT(*) FROM tape_plays"))
        assertEquals(0, count(w, "SELECT COUNT(*) FROM fermata_events"))
        assertTrue(db.getFermataEvents().isEmpty())
        // logging carries on afterwards
        f.startPlay("ccccccccccc", "Song c", "Arijit Singh", 200_000, false, FermataSource.SEARCH)
        f.leave(false, null)
        assertEquals(1, count(w, "SELECT COUNT(*) FROM fermata_events WHERE track_id = 'ccccccccccc'"))
    }

    // ---- backups --------------------------------------------------------------------------------

    private fun res(name: String): String {
        val candidates = listOf(File("src/main/res/xml/$name"), File("app/src/main/res/xml/$name"))
        return candidates.first { it.exists() }.readText()
    }

    @Test fun theDeviceIdIsLeftOutOfCloudBackupAndDeviceTransfer() {
        val file = FermataStore.DEVICE_PREFS + ".xml"
        assertTrue(FermataStore.DEVICE_PREFS != "minim_desk_prefs")
        val full = res("backup_rules.xml")
        assertTrue(full.contains("<exclude domain=\"sharedpref\" path=\"$file\" />"))
        assertTrue(full.contains("<exclude domain=\"sharedpref\" path=\"minim_ai.xml\" />"))
        val extraction = res("data_extraction_rules.xml")
        val cloud = extraction.substringAfter("<cloud-backup").substringBefore("</cloud-backup>")
        val transfer = extraction.substringAfter("<device-transfer").substringBefore("</device-transfer>")
        for (section in listOf(cloud, transfer)) {
            assertTrue(section.contains("<exclude domain=\"sharedpref\" path=\"$file\" />"))
            assertTrue(section.contains("<exclude domain=\"sharedpref\" path=\"minim_ai.xml\" />"))
            assertTrue(section.contains("<exclude domain=\"sharedpref\" path=\"${FermataStore.STATE_PREFS}.xml\" />"))
        }
    }

    // ---- source tags ----------------------------------------------------------------------------

    @Test fun queueItemsCarryTheirSource() {
        val t = song("aaaaaaaaaaa")
        assertEquals("radio", Track.playSourceOf(t.toMediaItem(FermataSource.RADIO)))
        assertEquals("playlist", Track.playSourceOf(t.toMediaItem(FermataSource.PLAYLIST)))
        assertNull(Track.playSourceOf(t.toMediaItem()))
        assertEquals("unknown", FermataSource.normalize(Track.playSourceOf(t.toMediaItem())))
        // the tag doesn't change the track itself
        assertEquals(t, Track.fromMediaItem(t.toMediaItem(FermataSource.SEARCH)))
    }

    @Test fun savedQueueSourcesComeBackOnlyWhereTheSongStillMatches() {
        val enc = FermataStore.encodeQueueSources(listOf("a1" to "radio", "b2" to null, "c3" to "search"))
        assertEquals(listOf("radio", null, "search"), FermataStore.decodeQueueSources(enc, listOf("a1", "b2", "c3")))
        assertEquals(listOf("radio", null, null, null), FermataStore.decodeQueueSources(enc, listOf("a1", "b2", "zz", "d4")))
        assertEquals(listOf<String?>(null, null), FermataStore.decodeQueueSources(null, listOf("a1", "b2")))
        val kv = FermataStore.stateKv(context)
        FermataStore.saveQueueSources(kv, enc)
        assertEquals(enc, FermataStore.loadQueueSources(FermataStore.stateKv(context)))
    }

    // ---- what is and isn't logged ---------------------------------------------------------------

    private fun src(rel: String): String {
        val candidates = listOf(File("src/main/java/com/minim/music/$rel"), File("app/src/main/java/com/minim/music/$rel"))
        return candidates.first { it.exists() }.readText()
    }

    @Test fun recapMusicIsNeverLogged() {
        // the recap story's song plays on its own little player, which never reaches the logger
        val recap = src("playback/RecapMusic.kt")
        assertTrue(recap.contains("ExoPlayer.Builder"))
        assertFalse(recap.contains("Fermata", ignoreCase = true))
        assertFalse(recap.contains("playbackController"))
        // and the only logger is the main player's
        val root = listOf(File("src/main/java"), File("app/src/main/java")).first { it.exists() }
        val makers = root.walkTopDown().filter { it.isFile && it.extension == "kt" }
            .filter { it.readText().contains("FermataLogger(") && !it.path.endsWith("FermataLogger.kt") }
            .map { it.name }.toList()
        assertEquals(listOf("PlaybackController.kt"), makers)
    }

    @Test fun tapesAreLoggedAsTapePlaysNotSongs() {
        val pc = src("playback/PlaybackController.kt")
        val start = pc.substringAfter("private fun fermataStart(").substringBefore("\n    }\n")
        assertTrue(start.contains("StoryTracks.isStory(track)"))
        assertTrue(StoryTracks.isStory(StoryTracks.track("tapeVideo01", "T", "C", 60L)))
        assertFalse(StoryTracks.isStory(song("aaaaaaaaaaa")))
    }

    @Test fun everyLoggerCallInThePlayerIsGuarded() {
        // logging must never break playback: each call into the logger sits inside fermataSafe
        val pc = src("playback/PlaybackController.kt")
        val lines = pc.lines()
        val calls = lines.withIndex().filter { (_, l) ->
            (l.contains("fermata?.") || l.contains("fermataStart(") || l.contains("fermataOnTransition(")) &&
                !l.trimStart().startsWith("private fun") && !l.trimStart().startsWith("*") && !l.trimStart().startsWith("//")
        }
        assertTrue(calls.size >= 8)
        val helpers = setOf("fermataStart", "fermataOnTransition", "fermataPosition")
        for ((i, l) in calls) {
            // the function this call sits in
            val fnLine = (i downTo 0).first { Regex("^\\s+(private |internal |override )*fun ").containsMatchIn(lines[it]) }
            val fnName = Regex("fun ([A-Za-z0-9_]+)").find(lines[fnLine])!!.groupValues[1]
            val window = lines.subList(maxOf(fnLine, i - 6), i + 1).joinToString("\n")
            val guardedFn = lines[fnLine].contains("= fermataSafe {")
            assertTrue("line ${i + 1}: $l", window.contains("fermataSafe") || guardedFn || fnName in helpers)
        }
        // and the helpers are only called from guarded places
        for (h in helpers) {
            for ((i, l) in lines.withIndex()) {
                if (!l.contains("$h(") || l.contains("private fun $h(")) continue
                val fnLine = (i downTo 0).first { Regex("^\\s+(private |internal |override )*fun ").containsMatchIn(lines[it]) }
                val fnName = Regex("fun ([A-Za-z0-9_]+)").find(lines[fnLine])!!.groupValues[1]
                assertTrue("line ${i + 1}: $l", l.contains("fermataSafe") || fnName in helpers ||
                    lines.subList(maxOf(fnLine, i - 3), i + 1).any { it.contains("fermataSafe") })
            }
        }
    }
}
