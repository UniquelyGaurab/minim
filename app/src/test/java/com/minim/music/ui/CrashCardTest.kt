package com.minim.music.ui

import android.app.Activity
import android.content.ClipboardManager
import android.content.Context
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import com.minim.music.R
import com.minim.music.util.CrashReporter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Hotfix 4.9.10: the "Minim crashed last time" card shows once, copies the report, never throws. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class CrashCardTest {

    private fun activity(): Activity = Robolectric.buildActivity(Activity::class.java).setup().get().apply {
        setTheme(R.style.Theme_Minim)
    }

    private fun content(a: Activity) = a.findViewById<FrameLayout>(android.R.id.content)

    private fun allTexts(v: android.view.View, out: MutableList<TextView> = mutableListOf()): List<TextView> {
        if (v is TextView) out += v
        if (v is ViewGroup) for (i in 0 until v.childCount) allTexts(v.getChildAt(i), out)
        return out
    }

    @Test
    fun nothingSavedShowsNothing() {
        val a = activity()
        CrashReporter.clear(a.filesDir)
        val before = content(a).childCount
        CrashCard.showIfPending(a, Pal.build(0, false))
        assertEquals(before, content(a).childCount)
    }

    @Test
    fun savedReportShowsOnceAndCopies() {
        val a = activity()
        val report = CrashReporter.format(IllegalStateException("boom"), "main", "4.9.10", "test", 0L)
        CrashReporter.save(a.filesDir, report)
        val before = content(a).childCount
        CrashCard.showIfPending(a, Pal.build(0, true))
        assertEquals(before + 1, content(a).childCount)
        // Shown once: the file is gone
        assertNull(CrashReporter.pending(a.filesDir))

        val texts = allTexts(content(a).getChildAt(before))
        assertTrue(texts.any { it.text == a.getString(R.string.crash_card_title) })
        assertTrue(texts.any { it.text.toString().startsWith("IllegalStateException: boom") })
        texts.first { it.text == a.getString(R.string.crash_card_copy) }.performClick()
        val cm = a.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        assertEquals(report, cm.primaryClip!!.getItemAt(0).text.toString())
        // Copy closes the card
        assertEquals(before, content(a).childCount)

        // Next launch: nothing
        CrashCard.showIfPending(a, Pal.build(0, true))
        assertEquals(before, content(a).childCount)
    }

    @Test
    fun dismissCloses() {
        val a = activity()
        CrashReporter.save(a.filesDir, "Minim crash report\nThread: main\n\njava.lang.RuntimeException: x")
        val before = content(a).childCount
        CrashCard.showIfPending(a, Pal.build(0, false))
        allTexts(content(a).getChildAt(before)).first { it.text == a.getString(R.string.crash_card_dismiss) }.performClick()
        assertEquals(before, content(a).childCount)
    }
}
