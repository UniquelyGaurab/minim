package com.minim.music.data.share

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.minim.music.data.local.LibraryDatabase
import com.minim.music.data.model.Track
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

/** 6.6.0: Save in the "Shared with you" pop-up. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class SharedSaveDbTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private var lib: LibraryDatabase? = null

    @Before fun clean() { com.minim.music.data.local.TestLibraryFiles.wipe(context) }

    @After fun close() {
        lib?.close()
        com.minim.music.data.local.TestLibraryFiles.wipe(context)
    }

    private fun open(): LibraryDatabase = LibraryDatabase(context).also { lib = it }

    private val songs = listOf(
        ShareLinks.Song("RA4jeB5zk9A", "Suroor", "Neha Kakkar & Bilal Saeed"),
        ShareLinks.Song("GWNrPJyRTcA", "Chuttamalle", "Anirudh Ravichander, Shilpa Rao"),
        ShareLinks.Song("em9fQdHmKh4", "Hukum", "Anirudh Ravichander")
    )

    @Test fun nameClashBecomesNumbered() = runBlocking {
        val db = open()
        db.createPlaylist("Road trip")
        val first = SharedSave.savePlaylist(db, "Road trip", songs)!!
        assertEquals("Road trip (2)", first.second)
        val second = SharedSave.savePlaylist(db, "Road trip", songs)!!
        assertEquals("Road trip (3)", second.second)
        val fresh = SharedSave.savePlaylist(db, "Brand new", songs)!!
        assertEquals("Brand new", fresh.second)
        // the songs go in, in order
        assertEquals(songs.map { it.id }, db.getPlaylistTracks(first.first).map { it.id })
        assertEquals("Suroor", db.getPlaylistTracks(first.first)[0].title)
        assertTrue(db.getPlaylistTracks(first.first)[0].thumbnailUrl.contains("RA4jeB5zk9A"))
    }

    @Test fun clashIgnoresCase() = runBlocking {
        val db = open()
        db.createPlaylist("road TRIP")
        val saved = SharedSave.savePlaylist(db, "Road trip", songs)!!
        assertEquals("Road trip (2)", saved.second)
    }

    @Test fun emptyPlaylistIsNotSaved() = runBlocking {
        assertEquals(null, SharedSave.savePlaylist(open(), "x", emptyList()))
    }

    @Test fun saveSongLikesItOnce() = runBlocking {
        val db = open()
        assertTrue(SharedSave.saveSong(db, songs[0]))
        assertTrue(db.getLikedTracks().any { it.id == "RA4jeB5zk9A" })
        // tapping Save again doesn't unlike it
        assertFalse(SharedSave.saveSong(db, songs[0]))
        assertTrue(db.getLikedTracks().any { it.id == "RA4jeB5zk9A" })
    }

    @Test fun aSongYouHaveKeepsItsOwnCover() = runBlocking {
        val db = open()
        val mine = Track("RA4jeB5zk9A", "Suroor", "Neha Kakkar", 201L, "https://lh3.googleusercontent.com/real=w544", "https://www.youtube.com/watch?v=RA4jeB5zk9A")
        val pl = db.createPlaylist("Mine")
        db.addTracksToPlaylist(pl, listOf(mine))
        val resolved = SharedSave.resolve(db, songs)
        assertEquals("https://lh3.googleusercontent.com/real=w544", resolved[0].thumbnailUrl)
        assertEquals(201L, resolved[0].durationSec)
        assertEquals("https://i.ytimg.com/vi/GWNrPJyRTcA/hqdefault.jpg", resolved[1].thumbnailUrl)
    }
}
