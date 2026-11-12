package com.minim.music.data.fermata

import android.content.Context
import androidx.core.content.FileProvider
import androidx.test.core.app.ApplicationProvider
import com.minim.music.R
import com.minim.music.data.local.LibraryFiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.xmlpull.v1.XmlPullParser
import java.io.File

/**
 * 5.10.0 review: the names 5.10.0 adds work on the shipped debug build (com.minim.music.debug):
 * the FileProvider authority follows the package, and every settings file 5.10.0 adds is left out
 * of Android's own backups under the name the code really uses.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class DebugVariantNamesTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun excludedPrefs(xml: Int): List<String> {
        val out = mutableListOf<String>()
        context.resources.getXml(xml).use { p ->
            while (p.next() != XmlPullParser.END_DOCUMENT) {
                if (p.eventType == XmlPullParser.START_TAG && p.name == "exclude" &&
                    p.getAttributeValue(null, "domain") == "sharedpref"
                ) out += p.getAttributeValue(null, "path")
            }
        }
        return out
    }

    @Test
    fun unitTestsRunAsTheDebugPackage_andTheFileProviderFollowsIt() {
        assertEquals("com.minim.music.debug", context.packageName)
        val f = File(context.cacheDir, "recap/x.png").apply { parentFile!!.mkdirs(); writeText("x") }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", f)
        assertEquals("com.minim.music.debug.fileprovider", uri.authority)
    }

    @Test
    fun everyNewSettingsFileIsLeftOutOfAndroidsBackups() {
        val names = listOf(
            "${LibraryFiles.PREFS}.xml",
            "${RestoreOfferGate.PREFS}.xml",
            "${FermataStore.DEVICE_PREFS}.xml",
            "${FermataStore.STATE_PREFS}.xml",
            "fermata_backup.xml", // FermataBackups.PREFS (internal to Fermata)
        )
        val fullBackup = excludedPrefs(R.xml.backup_rules)
        val extraction = excludedPrefs(R.xml.data_extraction_rules)
        for (n in names) {
            assertTrue("$n left out of backup_rules", n in fullBackup)
            // once for cloud backups, once for phone-to-phone transfers
            assertEquals("$n left out of both extraction rules", 2, extraction.count { it == n })
        }
    }
}
