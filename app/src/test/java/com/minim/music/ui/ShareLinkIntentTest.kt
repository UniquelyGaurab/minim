package com.minim.music.ui

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.minim.music.data.share.ShareLinks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** 6.6.0: the App Link intent filter, and reading the payload out of the intent's #fragment. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class ShareLinkIntentTest {

    private val suroor = ShareLinks.Song("RA4jeB5zk9A", "Suroor", "Neha Kakkar & Bilal Saeed")

    private fun view(url: String) = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        .addCategory(Intent.CATEGORY_BROWSABLE)
        .setPackage(ApplicationProvider.getApplicationContext<android.content.Context>().packageName)

    @Test fun manifestRoutesMinimLinksToShareLinkActivity() {
        val pm = ApplicationProvider.getApplicationContext<android.content.Context>().packageManager
        for (url in listOf(
            ShareLinks.songLink(suroor, "Gaurab"),
            ShareLinks.playlistLink("Mix", null, listOf(suroor)),
            "https://uniquelygaurab.github.io/m/s"
        )) {
            val hits = pm.queryIntentActivities(view(url), PackageManager.MATCH_DEFAULT_ONLY)
            assertEquals(url, listOf(ShareLinkActivity::class.java.name), hits.map { it.activityInfo.name })
        }
        for (url in listOf(
            "https://uniquelygaurab.github.io/minim/",
            "https://example.com/m/s#1.x",
            "http://uniquelygaurab.github.io/m/s#1.x"
        )) {
            val hits = pm.queryIntentActivities(view(url), PackageManager.MATCH_DEFAULT_ONLY)
            assertTrue(url, hits.none { it.activityInfo.name == ShareLinkActivity::class.java.name })
        }
    }

    @Test fun manifestFilterIsAutoVerifiedHttpsOnly() {
        // the merged manifest is what goes in the APK; the source one is what we wrote
        val xml = File("src/main/AndroidManifest.xml").readText()
        val block = xml.substringAfter("android:name=\".ui.ShareLinkActivity\"").substringBefore("</activity>")
        assertTrue(block.contains("android:autoVerify=\"true\""))
        assertTrue(block.contains("android:scheme=\"https\""))
        assertTrue(!block.contains("android:scheme=\"http\""))
        assertTrue(block.contains("android:host=\"uniquelygaurab.github.io\""))
        assertTrue(block.contains("android:pathPrefix=\"/m/\""))
        assertTrue(block.contains("android.intent.category.BROWSABLE"))
        assertTrue(block.contains("android:exported=\"true\""))
    }

    @Test fun linkFromIntentKeepsTheFragmentExactly() {
        val link = ShareLinks.playlistLink("मेरी 🎶", "गौरव", listOf(suroor))
        val back = ShareLinkActivity.linkFrom(Intent(Intent.ACTION_VIEW, Uri.parse(link)))
        assertEquals(link, back)
        val d = ShareLinks.decodeLink(back) as ShareLinks.Decoded.SharedPlaylist
        assertEquals("मेरी 🎶", d.name)
        assertEquals("गौरव", d.sharer)
        // a link a chat app wrapped with ?query and a trailing slash still lands
        val wrapped = link.replace("/m/p#", "/m/p/?from=wa#")
        assertEquals(link, ShareLinkActivity.linkFrom(Intent(Intent.ACTION_VIEW, Uri.parse(wrapped))))
    }

    @Test fun linkFromIgnoresOtherIntents() {
        assertNull(ShareLinkActivity.linkFrom(null))
        assertNull(ShareLinkActivity.linkFrom(Intent(Intent.ACTION_SEND)))
        assertNull(ShareLinkActivity.linkFrom(Intent(Intent.ACTION_VIEW)))
        assertNull(ShareLinkActivity.linkFrom(Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com/m/s#1.x"))))
        assertNull(ShareLinkActivity.linkFrom(Intent(Intent.ACTION_VIEW, Uri.parse("https://uniquelygaurab.github.io/m/zz#1.x"))))
        // no fragment: still ours, decodes as broken (the pop-up shows the friendly message)
        val bare = ShareLinkActivity.linkFrom(Intent(Intent.ACTION_VIEW, Uri.parse("https://uniquelygaurab.github.io/m/s")))
        assertEquals("https://uniquelygaurab.github.io/m/s#", bare)
        assertTrue(ShareLinks.decodeLink(bare) is ShareLinks.Decoded.Broken)
    }
}
