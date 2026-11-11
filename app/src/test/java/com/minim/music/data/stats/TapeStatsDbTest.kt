package com.minim.music.data.stats

import android.content.ContentValues
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 5.7.0 u51: tape stats saved on the phone, and the update from u50's database. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class TapeStatsDbTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private var lib: LibraryDatabase? = null

    @Before fun clean() { com.minim.music.data.local.TestLibraryFiles.wipe(context) }

    @After fun close() {
        lib?.close()
        com.minim.music.data.local.TestLibraryFiles.wipe(context)
    }

    private fun open(): LibraryDatabase = LibraryDatabase(context).also { lib = it }

    private val today = Days.keyOf(System.currentTimeMillis())
    private fun hourStart(h: Int) = Days.startMs(today) + h * 3_600_000L

    private fun tape(id: String) = StoryTracks.track(id, "Tape $id", "SR PAY STORIES", 3600L, "UCsrpay000001")
    private fun song(id: String) =
        Track(id, "Song $id", "Arijit Singh", 200L, "https://img/$id", "https://www.youtube.com/watch?v=$id")

    @Test fun tapesAreSavedApartAndAddedToTheDay() = runBlocking {
        val db = open()
        val s = song("s1")
        val row = db.recordCountedPlay(s, 200_000L)
        db.addListenTime(listOf(LibraryDatabase.ListenAdd(ListenSlice(today, 9, 200_000L), s)), row, 200_000L)
        val songOnly = db.getDayStats(today)
        assertFalse(songOnly.tapes.shows)

        val t = tape("v1")
        db.addTapeListenTime(listOf(
            LibraryDatabase.TapeListenAdd(ListenSlice(today, 21, 30 * 60_000L), t, "UCsrpay000001", 150),
            LibraryDatabase.TapeListenAdd(ListenSlice(today, 21, 10 * 60_000L), t, "UCsrpay000001", 150)
        ))
        db.recordTapePlay(t, "UCsrpay000001", hourStart(21))
        val both = db.getDayStats(today)
        // songs stay exactly as they were
        assertEquals(songOnly.songs, both.songs)
        assertEquals(songOnly.timeMs, both.timeMs)
        assertEquals(songOnly.artists, both.artists)
        assertEquals(songOnly.topSong, both.topSong)
        // tapes ride along
        assertEquals(1, both.tapes.tapes)
        assertEquals(1, both.tapes.channels)
        assertEquals(40 * 60_000L, both.tapes.ms)
        assertEquals(20 * 60_000L, both.tapes.savedMs)
        assertEquals(songOnly.timeMs + 40 * 60_000L, both.totalMs)
        assertEquals("SR PAY STORIES", both.tapes.topChannel!!.name)
        assertEquals("UCsrpay000001", both.tapes.topChannel!!.channelId)
        assertEquals(21, both.peakHour)
        // tapes don't show up as songs anywhere
        assertTrue(db.getDayPlays(today).none { it.trackId == "v1" })
        assertEquals(mapOf(today to 40 * 60_000L), db.getTapeDayTotals(today, today))
    }

    @Test fun aTapeOnlyDayBecomesTheRecap() = runBlocking {
        val db = open()
        val yesterday = Days.previous(today)
        db.addTapeListenTime(listOf(
            LibraryDatabase.TapeListenAdd(ListenSlice(yesterday, 22, 25 * 60_000L), tape("v2"), "", 100)
        ))
        db.recordTapePlay(tape("v2"), "", Days.startMs(yesterday) + 22 * 3_600_000L)
        assertEquals(yesterday, db.latestPastHistoryDay(today))
        assertTrue(db.pastHistoryDays(today, 7).contains(yesterday))
    }

    @Test fun updatingFromU50KeepsSongStatsAndAddsTapeTables() = runBlocking {
        // a version 5 database, as u47 to u50 left it, with a song play in it
        val path = context.getDatabasePath(LibraryDatabase.DB_NAME)
        path.parentFile?.mkdirs()
        com.minim.music.data.local.TestLibraryFiles.legacy(context).also { lib = it }.apply {
            val row = recordCountedPlay(song("old"), 60_000L)
            addListenTime(listOf(LibraryDatabase.ListenAdd(ListenSlice(today, 8, 60_000L), song("old"))), row, 60_000L)
            close()
        }
        lib = null
        SQLiteDatabase.openDatabase(path.path, null, SQLiteDatabase.OPEN_READWRITE).use { raw ->
            raw.execSQL("DROP TABLE IF EXISTS tape_listen_time")
            raw.execSQL("DROP TABLE IF EXISTS tape_plays")
            raw.version = 5
        }
        val db = open()
        val s = db.getDayStats(today)
        assertEquals(1, s.songs)
        assertEquals(60_000L, s.timeMs)
        assertFalse(s.tapes.shows)
        db.recordTapePlay(tape("new"), "")
        assertEquals(1, db.getDayTapePlays(today).size)
        assertEquals(8, db.readableDatabase.version)
    }
}
