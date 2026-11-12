package com.minim.music.ui

import android.net.Uri
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.minim.fermata.sync.BackupError
import com.minim.fermata.sync.BackupStatus
import com.minim.fermata.sync.RestoreResult
import com.minim.music.MinimApp
import com.minim.music.R
import com.minim.music.data.fermata.BackupText
import com.minim.music.data.fermata.RestoreOfferGate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import java.time.Duration

/** 5.10.0: Settings → Storage backups, the restore wording, and the fresh-install restore offer. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = LaunchTestApp::class, qualifiers = "w411dp-h891dp-xxhdpi")
class BackupSettingsTest {

    private val app get() = ApplicationProvider.getApplicationContext<MinimApp>()

    private fun idle(seconds: Long = 1) {
        repeat(seconds.toInt() * 4) {
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(250))
            Thread.sleep(20)
        }
    }

    private fun launch(): MainActivity {
        val c = Robolectric.buildActivity(MainActivity::class.java)
        c.setup()
        idle(3)
        return c.get()
    }

    private fun texts(v: View, out: MutableList<String> = mutableListOf()): List<String> {
        if (v.visibility != View.VISIBLE) return out
        if (v is TextView) out += v.text.toString()
        if (v is ViewGroup) for (i in 0 until v.childCount) texts(v.getChildAt(i), out)
        return out
    }

    private fun findText(v: View, text: String): View? {
        if (v.visibility != View.VISIBLE) return null
        if (v is TextView && v.text.toString() == text) return v
        if (v is ViewGroup) for (i in 0 until v.childCount) findText(v.getChildAt(i), text)?.let { return it }
        return null
    }

    private fun clickText(a: MainActivity, text: String) {
        var v: View? = findText(a.window.decorView, text) ?: error("no \"$text\" on screen")
        while (v != null && !v.isClickable) v = v.parent as? View
        assertNotNull("\"$text\" is tappable", v)
        v!!.performClick()
    }

    private fun openSettings(a: MainActivity) {
        val m = MainActivity::class.java.declaredMethods.first { it.name == "openSettings" }
        m.isAccessible = true
        m.invoke(a, *arrayOfNulls<Any>(m.parameterCount))
        idle(1)
    }

    @Test
    fun storageSettingsShowTheBackupRowsBeforeAFolderIsPicked() {
        app.preferences.welcomeDone = true
        RestoreOfferGate(app).markAnswered()
        val a = launch()
        openSettings(a)
        assertNotNull(app.backups)
        val folder = a.findViewById<TextView>(R.id.set_backup_folder)
        assertEquals(View.VISIBLE, folder.visibility)
        assertEquals(a.getString(R.string.backup_folder_choose), folder.text.toString())
        assertEquals(a.getString(R.string.backup_folder_none), a.findViewById<TextView>(R.id.set_backup_folder_hint).text.toString())
        assertEquals(a.getString(R.string.backup_never), a.findViewById<TextView>(R.id.set_backup_status).text.toString())
        // nothing to back up to or restore from yet
        assertEquals(View.GONE, a.findViewById<View>(R.id.set_backup_now).visibility)
        assertEquals(View.GONE, a.findViewById<View>(R.id.set_backup_restore).visibility)
        // Choose opens the system folder picker
        folder.performClick()
        idle()
        val started = Shadows.shadowOf(a).nextStartedActivityForResult
        assertNotNull(started)
        assertEquals(android.content.Intent.ACTION_OPEN_DOCUMENT_TREE, started.intent.action)
        assertFalse(a.isFinishing)
    }

    @Test
    fun aFreshInstallOffersARestoreOnceAndNotNowIsRemembered() {
        app.preferences.welcomeDone = true
        val a = launch()
        idle(2)
        val title = a.getString(R.string.backup_offer_prompt_title)
        assertTrue("offer shown", title in texts(a.window.decorView))
        clickText(a, a.getString(R.string.backup_offer_prompt_no))
        idle(2)
        assertTrue(RestoreOfferGate(app).answered)
        // the next launch doesn't ask again
        val again = launch()
        idle(2)
        assertFalse(title in texts(again.window.decorView))
    }

    @Test
    fun aPhoneThatAlreadyHasALibraryIsNeverOffered() {
        kotlinx.coroutines.runBlocking {
            app.database.toggleLike(com.minim.music.data.model.Track("L", "Song", "Artist", 200L, "", ""))
        }
        app.preferences.welcomeDone = true
        val a = launch()
        idle(2)
        assertFalse(a.getString(R.string.backup_offer_prompt_title) in texts(a.window.decorView))
        assertTrue(RestoreOfferGate(app).answered)
    }

    @Test
    fun theOfferWaitsUntilTheWelcomeIsDone() {
        app.preferences.welcomeDone = false
        val a = launch()
        idle(2)
        assertFalse(a.getString(R.string.backup_offer_prompt_title) in texts(a.window.decorView))
        assertFalse(RestoreOfferGate(app).answered)
    }

    // ---- wording ------------------------------------------------------------------------------

    @Test
    fun restoreSaysMergedOnAPhoneThatAlreadyHasALibrary() {
        val ctx = app
        assertEquals(ctx.getString(R.string.backup_restore_sub), BackupText.restoreSub(ctx, phoneHasLibrary = false))
        val merge = BackupText.restoreSub(ctx, phoneHasLibrary = true)
        assertTrue(merge.contains("merged"))
        assertFalse(merge.contains("replaced"))
        val ok = RestoreResult.Success(added = 12, total = 40, fromDevice = "abc", createdAt = 0L, fermataVersion = "0.2.0")
        assertEquals("Restored 12 events", BackupText.restoreDone(ctx, ok, phoneHadLibrary = false))
        assertEquals("Merged 12 new events from the backup", BackupText.restoreDone(ctx, ok, phoneHadLibrary = true))
        assertEquals(ctx.getString(R.string.backup_merged_nothing), BackupText.restoreDone(ctx, ok.copy(added = 0), phoneHadLibrary = true))
        val bad = RestoreResult.Failure(BackupError.DAMAGED, "checksum")
        assertEquals("Nothing changed: the file is damaged or still syncing", BackupText.restoreDone(ctx, bad, true))
    }

    @Test
    fun statusLineShowsTheLastBackupOrWhyItFailed() {
        val ctx = app
        val now = 1_800_000_000_000L
        assertEquals(ctx.getString(R.string.backup_never), BackupText.statusLine(ctx, BackupStatus(), now, false))
        assertEquals(ctx.getString(R.string.backup_running), BackupText.statusLine(ctx, BackupStatus(), now, true))
        val ok = BackupText.statusLine(ctx, BackupStatus(lastSuccessAt = now - 5 * 60_000L, lastEventCount = 321), now, false)
        assertTrue(ok, ok.startsWith("Last backup ") && ok.endsWith("321 events"))
        val failed = BackupText.statusLine(ctx, BackupStatus(lastError = BackupError.FOLDER_ACCESS_LOST), now, false)
        assertEquals("Last backup failed: Minim lost access to the folder", failed)
        for (e in BackupError.values()) assertTrue(BackupText.errorText(ctx, e).isNotBlank())
    }

    /** 6.1.1: event counts read like "3.8K events", in the status line, the restore list and the notes. */
    @Test
    fun eventCountsAreCompact() {
        val ctx = app
        val now = 1_800_000_000_000L
        val line = BackupText.statusLine(ctx, BackupStatus(lastSuccessAt = now, lastEventCount = 3785), now, false)
        assertTrue(line, line.startsWith("Last backup ") && line.endsWith(" \u00B7 3.8K events"))
        assertEquals("1 event", BackupText.events(ctx, 1))
        assertEquals("999 events", BackupText.events(ctx, 999))
        assertEquals("1K events", BackupText.events(ctx, 1_000))
        assertEquals("1.3M events", BackupText.events(ctx, 1_250_000))
        val ok = RestoreResult.Success(added = 3785, total = 3785, fromDevice = "abc", createdAt = 0L, fermataVersion = "0.2.0")
        assertEquals("Restored 3.8K events", BackupText.restoreDone(ctx, ok, phoneHadLibrary = false))
        assertEquals("Merged 12.3K new events from the backup", BackupText.restoreDone(ctx, ok.copy(added = 12_345), phoneHadLibrary = true))
        assertEquals("Merged 1 new event from the backup", BackupText.restoreDone(ctx, ok.copy(added = 1), phoneHadLibrary = true))
    }

    @Test
    fun folderNameIsThePathInsideTheStorage() {
        val tree = Uri.parse("content://com.android.externalstorage.documents/tree/primary%3AMusic%2FMinim%20backups")
        assertEquals("Music/Minim backups", BackupText.folderName(tree))
    }

    @Test
    fun offerGateDecisions() {
        val gate = RestoreOfferGate(app)
        assertEquals(RestoreOfferGate.Offer.PICK_FOLDER, gate.decide(false, folderSet = false) { 0 })
        assertEquals(RestoreOfferGate.Offer.RESTORE, gate.decide(false, folderSet = true) { 2 })
        assertEquals(RestoreOfferGate.Offer.PICK_FOLDER, gate.decide(false, folderSet = true) { 0 })
        assertEquals(RestoreOfferGate.Offer.NONE, gate.decide(true, folderSet = true) { 2 })
        assertTrue(gate.answered)
        assertEquals(RestoreOfferGate.Offer.NONE, gate.decide(false, folderSet = true) { 2 })
    }
}
