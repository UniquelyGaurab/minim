package com.minim.music.data.local

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import com.minim.music.data.model.Track
import com.minim.music.ui.PlaylistLook
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Minim 6.8.0: updating from 6.7.0 (database version 8) adds when each song was added to a
 * playlist, keeps every row and every playlist's order, and drag-to-reorder then saves.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class PlaylistAddedAtMigrationTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private var lib: LibraryDatabase? = null

    @Before fun clean() = TestLibraryFiles.wipe(context)

    @After fun close() {
        lib?.close()
        TestLibraryFiles.wipe(context)
    }

    private fun song(id: String) =
        Track(id, "Song $id", "Arijit Singh", 200L, "https://img/$id", "https://www.youtube.com/watch?v=$id")

    private fun count(db: SQLiteDatabase, sql: String): Int = db.rawQuery(sql, null).use { if (it.moveToFirst()) it.getInt(0) else 0 }

    private fun tables(db: SQLiteDatabase): List<String> = db.rawQuery(
        "SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%' AND name <> 'android_metadata' ORDER BY name",
        null
    ).use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }

    private fun columns(db: SQLiteDatabase, table: String): List<String> =
        db.rawQuery("SELECT name FROM pragma_table_info('$table') ORDER BY cid", null).use { c ->
            buildList { while (c.moveToNext()) add(c.getString(0)) }
        }

    /** Puts playlist_tracks back the way 6.7.0 had it (no added_at) and marks the file version 8. */
    private fun downgradeTo67(path: String) {
        SQLiteDatabase.openDatabase(path, null, SQLiteDatabase.OPEN_READWRITE).use { raw ->
            val keep = columns(raw, "playlist_tracks").filter { it != "added_at" }
            val sql = raw.rawQuery("SELECT sql FROM sqlite_master WHERE name = 'playlist_tracks'", null).use { it.moveToFirst(); it.getString(0) }
            val cols = keep.joinToString(", ")
            raw.execSQL("ALTER TABLE playlist_tracks RENAME TO pt_old")
            // the same table as before minus the new column
            val create = sql.replace(Regex(",\\s*added_at[^,)]*"), "")
            raw.execSQL(create)
            raw.execSQL("INSERT INTO playlist_tracks ($cols) SELECT $cols FROM pt_old")
            raw.execSQL("DROP TABLE pt_old")
            raw.version = 8
        }
    }

    @Test fun updatingFrom67KeepsTheLibraryAndEveryPlaylistOrder() = runBlocking {
        val path = context.getDatabasePath(LibraryDatabase.DB_NAME)
        path.parentFile?.mkdirs()
        val chill: Long
        val road: Long
        TestLibraryFiles.legacy(context).apply {
            toggleLike(song("liked00001"))
            toggleLike(song("liked00002"))
            chill = createPlaylist("Chill Evening Mix")
            for (i in 1..5) { addTrackToPlaylist(chill, song("chill0000$i")); Thread.sleep(5) }
            road = createPlaylist("Road trip")
            addTrackToPlaylist(road, song("road000001"))
            addTrackToPlaylist(road, song("road000002"))
            recordPlayback(song("history001"))
            close()
        }
        downgradeTo67(path.path)
        val before = SQLiteDatabase.openDatabase(path.path, null, SQLiteDatabase.OPEN_READONLY).use { raw ->
            assertTrue("added_at" !in columns(raw, "playlist_tracks"))
            tables(raw).associateWith { count(raw, "SELECT COUNT(*) FROM \"$it\"") }
        }
        val beforeOrder = SQLiteDatabase.openDatabase(path.path, null, SQLiteDatabase.OPEN_READONLY).use { raw ->
            raw.rawQuery("SELECT playlist_id, track_id, position FROM playlist_tracks ORDER BY playlist_id, position", null).use { c ->
                buildList { while (c.moveToNext()) add(Triple(c.getLong(0), c.getString(1), c.getLong(2))) }
            }
        }

        val db = LibraryDatabase(context).also { lib = it }
        val w = db.writableDatabase
        assertEquals(9, w.version)
        assertTrue("added_at" in columns(w, "playlist_tracks"))
        // nothing lost from any table
        assertEquals(before, tables(w).filter { it in before }.associateWith { count(w, "SELECT COUNT(*) FROM \"$it\"") })
        assertEquals(2, db.getLikedTracks().size)
        // every playlist in the same order, and each song's added time is its old place
        assertEquals((1..5).map { "chill0000$it" }, db.getPlaylistTracks(chill).map { it.id })
        assertEquals(listOf("road000001", "road000002"), db.getPlaylistTracks(road).map { it.id })
        val after = w.rawQuery("SELECT playlist_id, track_id, position, added_at FROM playlist_tracks ORDER BY playlist_id, position", null).use { c ->
            buildList { while (c.moveToNext()) add(listOf(c.getLong(0), c.getString(1), c.getLong(2), c.getLong(3))) }
        }
        assertEquals(beforeOrder.map { listOf(it.first, it.second, it.third) }, after.map { it.take(3) })
        for (row in after) assertEquals("added_at of ${row[1]}", row[2], row[3])
        // "Recently added" is newest first, Custom is unchanged
        val entries = db.getPlaylistEntries(chill)
        assertEquals((5 downTo 1).map { "chill0000$it" }, PlaylistLook.sort(entries, PlaylistLook.Sort.RECENT).map { it.id })
        assertEquals((1..5).map { "chill0000$it" }, PlaylistLook.sort(entries, PlaylistLook.Sort.CUSTOM).map { it.id })
    }

    @Test fun dragSavesTheOrderAndKeepsTheAddedTimes() = runBlocking {
        val db = LibraryDatabase(context).also { lib = it }
        val pl = db.createPlaylist("Mix")
        for (id in listOf("aaaaaaaaaa1", "bbbbbbbbbb2", "cccccccccc3")) { db.addTrackToPlaylist(pl, song(id)); Thread.sleep(5) }
        val recentBefore = PlaylistLook.sort(db.getPlaylistEntries(pl), PlaylistLook.Sort.RECENT).map { it.id }
        assertTrue(db.reorderPlaylist(pl, listOf("cccccccccc3", "aaaaaaaaaa1", "bbbbbbbbbb2")))
        assertEquals(listOf("cccccccccc3", "aaaaaaaaaa1", "bbbbbbbbbb2"), db.getPlaylistTracks(pl).map { it.id })
        assertEquals(recentBefore, PlaylistLook.sort(db.getPlaylistEntries(pl), PlaylistLook.Sort.RECENT).map { it.id })
        // a stale drag (the playlist changed meanwhile) is ignored
        assertTrue(!db.reorderPlaylist(pl, listOf("aaaaaaaaaa1", "bbbbbbbbbb2")))
        // a song added after a drag still lands at the end
        db.addTrackToPlaylist(pl, song("dddddddddd4"))
        assertEquals("dddddddddd4", db.getPlaylistTracks(pl).last().id)
    }
}
