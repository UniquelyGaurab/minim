package com.minim.music.data.local

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import androidx.test.core.app.ApplicationProvider
import com.minim.music.ui.PlaylistLook
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** Minim 6.8.0: each playlist's cover and sort are kept on the phone, per playlist. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class PlaylistCoverStoreTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var dir: File

    @Before fun clean() {
        dir = File(context.cacheDir, "covers-test").apply { deleteRecursively(); mkdirs() }
        context.getSharedPreferences("cover-test", Context.MODE_PRIVATE).edit().clear().commit()
    }

    private fun store() = PlaylistCoverStore(dir, context.getSharedPreferences("cover-test", Context.MODE_PRIVATE))

    @Test fun coverChoicesRoundTripThroughStorage() {
        val covers = listOf(
            PlaylistLook.Cover.AUTO,
            PlaylistLook.Cover(mode = PlaylistLook.Mode.MOSAIC),
            PlaylistLook.Cover(mode = PlaylistLook.Mode.TYPE, text = "🔥", color = 0xFF2E7D5B.toInt()),
            PlaylistLook.Cover(mode = PlaylistLook.Mode.BLUR, url = "https://img/a", trackId = "a"),
            PlaylistLook.Cover(mode = PlaylistLook.Mode.PHOTO, file = "p3_1.jpg")
        )
        for (c in covers) assertEquals(c, PlaylistLook.Cover.decode(c.encode()))
        assertEquals(PlaylistLook.Cover.AUTO, PlaylistLook.Cover.decode(null))
        assertEquals(PlaylistLook.Cover.AUTO, PlaylistLook.Cover.decode("{broken"))
    }

    @Test fun aNewPlaylistIsAutoAndCustom() {
        assertEquals(PlaylistLook.Cover.AUTO, store().cover(7L))
        assertEquals(PlaylistLook.Sort.CUSTOM, store().sort(7L))
    }

    @Test fun choicesAreKeptPerPlaylistAndSurviveARestart() {
        val type = PlaylistLook.Cover(mode = PlaylistLook.Mode.TYPE, text = "Chill", color = 0xFF1E88E5.toInt())
        store().apply {
            setCover(1L, type)
            setSort(1L, PlaylistLook.Sort.ARTIST)
            setCover(2L, PlaylistLook.Cover(mode = PlaylistLook.Mode.MOSAIC))
        }
        val again = store()
        assertEquals(type, again.cover(1L))
        assertEquals(PlaylistLook.Sort.ARTIST, again.sort(1L))
        assertEquals(PlaylistLook.Mode.MOSAIC, again.cover(2L).mode)
        assertEquals(PlaylistLook.Sort.CUSTOM, again.sort(2L))
    }

    @Test fun switchingBackToAutoAndCustomClearsTheChoice() {
        store().apply {
            setCover(1L, PlaylistLook.Cover(mode = PlaylistLook.Mode.MOSAIC))
            setSort(1L, PlaylistLook.Sort.TITLE)
            setCover(1L, PlaylistLook.Cover.AUTO)
            setSort(1L, PlaylistLook.Sort.CUSTOM)
        }
        assertEquals(PlaylistLook.Cover.AUTO, store().cover(1L))
        assertEquals(PlaylistLook.Sort.CUSTOM, store().sort(1L))
    }

    @Test fun aGalleryPhotoIsSavedSquareInsideMinimAndReplacedCleanly() {
        val s = store()
        val photo = Bitmap.createBitmap(1200, 800, Bitmap.Config.ARGB_8888)
        val first = s.savePhoto(3L, photo, Rect(100, 0, 900, 800))
        assertNotNull(first)
        s.setCover(3L, first!!)
        assertTrue(s.photoFile(first).isFile)
        assertEquals(PlaylistLook.Mode.PHOTO, s.cover(3L).mode)
        Thread.sleep(2)
        val second = s.savePhoto(3L, photo, Rect(0, 0, 800, 800))!!
        s.setCover(3L, second)
        assertFalse("the old photo is deleted", s.photoFile(first).isFile)
        assertTrue(s.photoFile(second).isFile)
    }

    @Test fun aMissingPhotoFallsBackToAuto() {
        val s = store()
        val c = s.savePhoto(4L, Bitmap.createBitmap(400, 400, Bitmap.Config.ARGB_8888), Rect(0, 0, 400, 400))!!
        s.setCover(4L, c)
        s.photoFile(c).delete()
        assertEquals(PlaylistLook.Cover.AUTO, s.cover(4L))
    }

    @Test fun deletingAPlaylistForgetsItsCoverSortAndPhoto() {
        val s = store()
        val c = s.savePhoto(5L, Bitmap.createBitmap(300, 300, Bitmap.Config.ARGB_8888), Rect(0, 0, 300, 300))!!
        s.setCover(5L, c)
        s.setSort(5L, PlaylistLook.Sort.RECENT)
        s.forget(5L)
        assertFalse(s.photoFile(c).isFile)
        assertEquals(PlaylistLook.Cover.AUTO, store().cover(5L))
        assertEquals(PlaylistLook.Sort.CUSTOM, store().sort(5L))
    }
}
