package com.minim.music.data.profile

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import com.minim.music.R
import com.minim.music.data.local.AppPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File

/** 5.10.1 (V6): the profile in Settings: name, round photo, removal, and staying off backups. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class ProfileStoreTest {

    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var prefs: AppPreferences
    private lateinit var store: ProfileStore

    @Before
    fun setUp() {
        prefs = AppPreferences(context)
        File(context.filesDir, ProfileStore.DIR).deleteRecursively()
        store = ProfileStore(context.filesDir, prefs)
    }

    private fun picture(w: Int, h: Int, color: Int = Color.RED): Bitmap =
        Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }

    @Test
    fun theNameIsTheGreetingNameTrimmed() {
        store.name = "  Gaurab "
        assertEquals("Gaurab", store.name)
        assertEquals("G", ProfileStore.initialOf(store.name))
        assertNull(ProfileStore.initialOf("   "))
        assertNull(ProfileStore.initialOf(null))
    }

    @Test
    fun aSavedPhotoIsA256PxCircle() {
        assertFalse(store.hasPhoto)
        val before = store.photoVersion
        assertTrue(store.savePhoto(picture(1200, 800)))
        assertTrue(store.hasPhoto)
        assertTrue(store.photoVersion > before)
        val saved = BitmapFactory.decodeFile(store.photoFile.path)
        assertEquals(ProfileStore.SIZE, saved.width)
        assertEquals(ProfileStore.SIZE, saved.height)
        assertEquals("corners are cut away", 0, Color.alpha(saved.getPixel(0, 0)))
        assertEquals(0, Color.alpha(saved.getPixel(255, 255)))
        val centre = saved.getPixel(128, 128)
        assertEquals(255, Color.alpha(centre))
        assertEquals(255, Color.red(centre))
        assertFalse("no temporary file left", File(store.photoFile.parentFile, ProfileStore.PHOTO + ".tmp").exists())
    }

    @Test
    fun aPickedFileIsDecodedCroppedAndRotated() {
        val out = ByteArrayOutputStream()
        picture(900, 300, Color.BLUE).compress(Bitmap.CompressFormat.PNG, 100, out)
        val png = out.toByteArray()
        assertTrue(store.savePhoto({ ByteArrayInputStream(png) }, orientation = 6))
        val saved = store.loadPhoto()!!
        assertEquals(ProfileStore.SIZE, saved.width)
        assertEquals(255, Color.blue(saved.getPixel(128, 128)))
    }

    @Test
    fun anUnreadablePictureKeepsTheOldPhoto() {
        assertTrue(store.savePhoto(picture(400, 400, Color.GREEN)))
        val old = store.photoFile.readBytes()
        assertFalse(store.savePhoto({ ByteArrayInputStream("not a picture".toByteArray()) }))
        assertFalse(store.savePhoto({ null }))
        assertTrue(store.photoFile.readBytes().contentEquals(old))
    }

    @Test
    fun removingThePhotoGoesBackToTheInitial() {
        assertTrue(store.savePhoto(picture(300, 300)))
        val v = store.photoVersion
        store.removePhoto()
        assertFalse(store.hasPhoto)
        assertNull(store.loadPhoto())
        assertTrue(store.photoVersion > v)
        store.removePhoto()
    }

    @Test
    fun cropMathKeepsTheCentredSquare() {
        val r = ProfileStore.centerSquare(1200, 800)
        assertEquals(200, r.left)
        assertEquals(0, r.top)
        assertEquals(800, r.width())
        assertEquals(800, r.height())
        assertEquals(8, ProfileStore.sampleSize(4000, 3000, 256))
        assertEquals(1, ProfileStore.sampleSize(300, 300, 256))
    }

    @Test
    fun thePhotoStaysOnThisPhone() {
        assertTrue(store.savePhoto(picture(300, 300)))
        // it lives in files/profile, outside the library database Fermata backs up
        assertEquals(File(context.filesDir, "profile"), store.photoFile.parentFile)
        // and Android's own backups and phone-to-phone transfer leave that folder out
        assertEquals(1, excludeCount(R.xml.backup_rules))
        assertEquals(2, excludeCount(R.xml.data_extraction_rules))
    }

    private fun excludeCount(xml: Int): Int {
        val p = context.resources.getXml(xml)
        var n = 0
        while (p.next() != XmlPullParser.END_DOCUMENT) {
            if (p.eventType == XmlPullParser.START_TAG && p.name == "exclude" &&
                p.getAttributeValue(null, "domain") == "file" && p.getAttributeValue(null, "path") == "profile/") n++
        }
        return n
    }
}
