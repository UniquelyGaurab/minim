package com.minim.music.ui

import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.minim.fermata.sync.BackupError
import com.minim.fermata.sync.BackupResult
import com.minim.fermata.sync.BackupStatus
import com.minim.music.MinimApp
import com.minim.music.R
import com.minim.music.data.fermata.BackupText
import com.minim.music.data.fermata.RestoreOfferGate
import com.minim.music.data.model.Track
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger

/**
 * 5.10.0 review fixes: Settings → Clear listening history (confirm, cancel, what it clears), and the
 * status line / Back up now note saying what a merge of other phones' backups left out.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = LaunchTestApp::class, qualifiers = "w411dp-h891dp-xxhdpi")
class ClearHistorySettingsTest {

    private val app get() = ApplicationProvider.getApplicationContext<MinimApp>()

    private fun idle(seconds: Long = 1) {
        repeat(seconds.toInt() * 4) {
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(250))
            Thread.sleep(20)
        }
    }

    private fun launch(): MainActivity {
        app.preferences.welcomeDone = true
        RestoreOfferGate(app).markAnswered()
        val c = Robolectric.buildActivity(MainActivity::class.java)
        c.setup()
        idle(3)
        return c.get()
    }

    private fun openSettings(a: MainActivity) {
        val m = MainActivity::class.java.declaredMethods.first { it.name == "openSettings" }
        m.isAccessible = true
        m.invoke(a, *arrayOfNulls<Any>(m.parameterCount))
        idle(1)
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

    private fun count(sql: String): Int =
        app.database.readableDatabase.rawQuery(sql, null).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }

    private fun track(id: String) = Track(id, "Song $id", "Artist", 200L, "", "https://www.youtube.com/watch?v=$id")

    /** Two counted plays and one like, written the way playback and the heart write them. */
    private fun seedLibrary() = runBlocking {
        app.database.recordCountedPlay(track("songAAAAAAA"), 60_000L)
        app.database.recordCountedPlay(track("songBBBBBBB"), 90_000L)
        app.database.toggleLike(track("songLLLLLLL"))
    }

    /** Waits (up to ~5 s) for the clear, which runs off the main thread. */
    private fun waitFor(check: () -> Boolean) {
        repeat(20) { if (check()) return; idle(1) }
    }

    @Test
    fun theClearRowSitsAloneAtTheBottomAndOpensAConfirmSheet() {
        val a = launch()
        openSettings(a)
        val clear = a.findViewById<TextView>(R.id.set_clear_history)
        assertEquals(View.VISIBLE, clear.visibility)
        assertEquals(a.getString(R.string.history_clear), clear.text.toString())
        // 5.10.1 (V6): no longer under Cache; alone in the last group, below a dividing line
        val danger = a.findViewById<ViewGroup>(R.id.settings_group_danger)
        var p: android.view.ViewParent? = clear.parent
        while (p != null && p !== danger) p = p.parent
        assertEquals(danger, p)
        val list = danger.parent as ViewGroup
        assertEquals(list.childCount - 1, list.indexOfChild(danger))
        assertTrue(a.findViewById<View>(R.id.set_clear_cache).let { c -> generateSequence(c.parent) { it.parent }.none { it === danger } })
        clear.performClick()
        idle(1)
        val shown = texts(a.window.decorView)
        assertTrue(a.getString(R.string.history_clear_title) in shown)
        assertTrue(a.getString(R.string.history_clear_ok) in shown)
    }

    @Test
    fun cancelLeavesTheHistoryAlone() {
        seedLibrary()
        assertEquals(2, count("SELECT COUNT(*) FROM plays"))
        val after = AtomicInteger()
        val a = launch()
        app.database.afterClearHistory = { after.incrementAndGet() }
        openSettings(a)
        a.findViewById<View>(R.id.set_clear_history).performClick()
        idle(1)
        a.findViewById<View>(R.id.sheet_confirm_cancel).performClick()
        idle(2)
        assertFalse(a.getString(R.string.history_clear_title) in texts(a.window.decorView))
        assertEquals(2, count("SELECT COUNT(*) FROM plays"))
        assertEquals(0, after.get())
    }

    @Test
    fun confirmingClearsPlaysButKeepsLikesAndBacksUp() {
        seedLibrary()
        val after = AtomicInteger()
        val a = launch()
        app.database.afterClearHistory = { after.incrementAndGet() }
        openSettings(a)
        a.findViewById<View>(R.id.set_clear_history).performClick()
        idle(1)
        clickText(a, a.getString(R.string.history_clear_ok))
        waitFor { after.get() > 0 }
        idle(1)
        assertEquals(0, count("SELECT COUNT(*) FROM plays"))
        assertEquals("likes stay", 1, count("SELECT COUNT(*) FROM tracks WHERE is_liked = 1"))
        assertEquals("the backup after a clear is asked for once", 1, after.get())
        assertFalse(a.getString(R.string.history_clear_title) in texts(a.window.decorView))
        // the note pill was given the "cleared" text (it may have faded out again by now)
        waitFor { a.findViewById<TextView>(R.id.minim_note).text.toString() == a.getString(R.string.history_cleared) }
        assertEquals(a.getString(R.string.history_cleared), a.findViewById<TextView>(R.id.minim_note).text.toString())
    }

    @Test
    fun aClearThatFailsShowsANoteAndKeepsTheHistory() {
        seedLibrary()
        val a = launch()
        val after = AtomicInteger()
        app.database.afterClearHistory = { after.incrementAndGet() }
        app.database.onClearHistory = { throw android.database.sqlite.SQLiteFullException("database or disk is full") }
        openSettings(a)
        a.findViewById<View>(R.id.set_clear_history).performClick()
        idle(1)
        clickText(a, a.getString(R.string.history_clear_ok))
        waitFor { a.findViewById<TextView>(R.id.minim_note).text.toString() == a.getString(R.string.history_clear_failed) }
        assertEquals(a.getString(R.string.history_clear_failed), a.findViewById<TextView>(R.id.minim_note).text.toString())
        assertEquals("rolled back: the plays are still there", 2, count("SELECT COUNT(*) FROM plays"))
        assertEquals("no backup for a clear that didn't happen", 0, after.get())
        assertFalse(a.isFinishing)
    }

    @Test
    fun aBackupThatCantBeAskedForAfterAClearStillSaysCleared() {
        seedLibrary()
        val a = launch()
        app.database.afterClearHistory = { throw IllegalStateException("WorkManager is not initialized") }
        openSettings(a)
        a.findViewById<View>(R.id.set_clear_history).performClick()
        idle(1)
        clickText(a, a.getString(R.string.history_clear_ok))
        waitFor { a.findViewById<TextView>(R.id.minim_note).text.toString() == a.getString(R.string.history_cleared) }
        assertEquals(a.getString(R.string.history_cleared), a.findViewById<TextView>(R.id.minim_note).text.toString())
        assertEquals(0, count("SELECT COUNT(*) FROM plays"))
    }

    @Test
    fun aSkippedLibraryCopyShowsTheLowStorageNoteOnce() {
        app.getSharedPreferences(com.minim.music.data.local.LibraryFiles.PREFS, 0).edit()
            .putString("note_pending", com.minim.music.data.local.LibraryFiles.Note.LOW_SPACE.name).commit()
        val c = Robolectric.buildActivity(MainActivity::class.java)
        app.preferences.welcomeDone = true
        RestoreOfferGate(app).markAnswered()
        c.setup()
        idle(1)
        val a = c.get()
        assertEquals(a.getString(R.string.library_copy_low_space), a.findViewById<TextView>(R.id.minim_note).text.toString())
        // once: the next resume shows nothing new
        a.findViewById<TextView>(R.id.minim_note).text = ""
        c.pause().resume()
        idle(1)
        assertEquals("", a.findViewById<TextView>(R.id.minim_note).text.toString())
        assertNull(com.minim.music.data.local.LibraryFiles.takeNote(app))
    }

    // ---- what a merge left out --------------------------------------------------------------

    @Test
    fun statusLineAddsWhatTheMergeLeftOut() {
        val ctx = app
        val now = 1_800_000_000_000L
        val ok = BackupStatus(lastSuccessAt = now - 5 * 60_000L, lastEventCount = 321)
        // nothing left out: one line, as before
        assertNull(BackupText.mergeText(ctx, ok))
        assertFalse(BackupText.statusLine(ctx, ok, now, false).contains("\n"))
        // one damaged file from another phone
        val one = ok.copy(lastMergeProblemCount = 1, lastMergeProblem = "fermata-dOTHER-latest.json: checksum mismatch")
        val line = BackupText.statusLine(ctx, one, now, false)
        val parts = line.split("\n")
        assertEquals(2, parts.size)
        assertTrue(parts[0].endsWith("321 events"))
        assertEquals("Couldn't merge 1 backup from another phone: checksum mismatch", parts[1])
        // several, with no reason saved: says damaged
        val many = ok.copy(lastMergeProblemCount = 3, lastMergeProblem = null)
        assertEquals(
            ctx.resources.getQuantityString(R.plurals.backup_merge_problems, 3, 3, ctx.getString(R.string.backup_err_damaged)),
            BackupText.mergeText(ctx, many)
        )
        // the folder could not be read at all
        val lost = ok.copy(lastMergeError = BackupError.FOLDER_ACCESS_LOST)
        assertEquals("Couldn't read other phones' backups: Minim lost access to the folder", BackupText.mergeText(ctx, lost))
        assertTrue(lost.mergeTroubled)
        // while running, only "running"
        assertEquals(ctx.getString(R.string.backup_running), BackupText.statusLine(ctx, one, now, true))
    }

    @Test
    fun backUpNowNoteListsEveryProblem() {
        val ctx = app
        val done = BackupResult.Success(at = 0L, fileName = "fermata-dMINE-latest.json", eventCount = 10L, bytes = 100L)
        assertEquals(ctx.getString(R.string.backup_done), BackupText.backUpNowNote(ctx, done, BackupStatus(), null))
        val troubled = BackupStatus(lastMergeProblemCount = 1, lastMergeProblem = "x.json: checksum mismatch")
        val note = BackupText.backUpNowNote(ctx, done, troubled, IllegalStateException("disk full"))
        assertEquals(
            listOf(
                ctx.getString(R.string.backup_done),
                "Couldn't merge 1 backup from another phone: checksum mismatch",
                "Plays in progress weren't saved first: disk full",
            ),
            note.split("\n")
        )
        // a merge that threw before saving its status still shows, and isn't called "plays in progress"
        val threw = BackupText.backUpNowNote(ctx, done, BackupStatus(), null, RuntimeException("boom"))
        assertEquals(listOf(ctx.getString(R.string.backup_done), "Couldn't read other phones' backups: boom"), threw.split("\n"))
        val failed = BackupText.backUpNowNote(ctx, BackupResult.Failure(0L, BackupError.NO_FOLDER, "no folder"), BackupStatus(), null)
        assertTrue(failed.startsWith("Last backup failed: "))
    }

    @Test
    fun startupBackupOnlyWhenNoneForADay() {
        val now = 1_800_000_000_000L
        val hour = 3_600_000L
        assertTrue(BackupText.startupBackupDue(null, now))
        assertFalse(BackupText.startupBackupDue(now - 23 * hour, now))
        assertTrue(BackupText.startupBackupDue(now - 24 * hour, now))
        assertTrue(BackupText.startupBackupDue(now - 72 * hour, now))
    }
}
