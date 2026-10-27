package com.minim.music.ui

import com.minim.music.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 5.4.1: Save offline in the Now Playing 3 dot menu. */
class NowPlayingOfflineRowTest {

    @Test fun notSavedOffersSaveOfflineWithNoRing() {
        val l = SongMenuLogic.offlineRowLook(saved = false, saving = null)
        assertEquals(SongMenuLogic.OfflineRowState.SAVE, SongMenuLogic.offlineRowState(false, null))
        assertEquals(R.string.offline_save, l.label)
        assertEquals(R.drawable.ic_offline, l.icon)
        assertNull(l.ring)
    }

    @Test fun savingShowsTheRingAndRemoveStopsIt() {
        val l = SongMenuLogic.offlineRowLook(saved = false, saving = 0.4f)
        assertEquals(SongMenuLogic.OfflineRowState.SAVING, SongMenuLogic.offlineRowState(false, 0.4f))
        assertEquals(R.string.offline_remove, l.label)
        assertEquals(R.drawable.ic_offline_filled, l.icon)
        assertEquals(0.4f, l.ring!!, 0.0001f)
        // waiting to start is a ring too (0 = the spinning arc)
        assertEquals(0f, SongMenuLogic.offlineRowLook(false, 0f).ring!!, 0f)
        // saving again over a saved copy still shows the ring
        assertEquals(SongMenuLogic.OfflineRowState.SAVING, SongMenuLogic.offlineRowState(true, 0.9f))
    }

    @Test fun savedOffersRemoveWithNoRing() {
        val l = SongMenuLogic.offlineRowLook(saved = true, saving = null)
        assertEquals(SongMenuLogic.OfflineRowState.SAVED, SongMenuLogic.offlineRowState(true, null))
        assertEquals(R.string.offline_remove, l.label)
        assertEquals(R.drawable.ic_offline_filled, l.icon)
        assertNull(l.ring)
    }

    @Test fun menuHasSaveOfflineRightUnderAddToPlaylist() {
        val src = listOf(File("src/main/java"), File("app/src/main/java")).first { it.isDirectory }
        val main = File(src, "com/minim/music/ui/MainActivity.kt").readText()
        val start = main.indexOf("private fun showNowPlayingMenu()")
        val body = main.substring(start, main.indexOf("private fun sleepRemainingLabel()", start))
        val add = body.indexOf("R.string.add_to_playlist")
        val offline = body.indexOf("nowPlayingOfflineRow(box, track)")
        val artist = body.indexOf("R.string.sheet_go_to_artist")
        val sleep = body.indexOf("R.string.settings_sleep")
        assertTrue(add in 0 until offline)
        assertTrue(offline < artist && artist < sleep)
    }

    @Test fun sheetRowHasTheRingFromTheSongLists() {
        val dir = listOf(File("src/main/res/layout"), File("app/src/main/res/layout")).first { it.isDirectory }
        val row = File(dir, "item_sheet_action.xml").readText()
        assertTrue(row.contains("com.minim.music.ui.ProgressRing"))
        assertTrue(row.contains("@+id/action_ring"))
    }
}
