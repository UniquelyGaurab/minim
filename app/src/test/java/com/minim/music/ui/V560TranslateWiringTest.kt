package com.minim.music.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 5.6.0: the translate button and translations are wired into Full Screen Lyrics. */
class V560TranslateWiringTest {

    private fun src(path: String): String {
        val candidates = listOf(File("src/main/$path"), File("app/src/main/$path"))
        return candidates.first { it.exists() }.readText()
    }

    @Test
    fun button_sits_top_right_and_note_says_retry() {
        val xml = src("res/layout/view_fullscreen_lyrics.xml")
        val btn = xml.substringAfter("@+id/fs_translate\"").substringBefore("/>")
        assertTrue(btn.contains("top|end"))
        assertTrue(btn.contains("visibility=\"gone\""))
        val strings = src("res/values/strings.xml")
        assertTrue(strings.contains("Couldn\\'t translate, tap to retry"))
    }

    @Test
    fun fullscreen_lyrics_uses_the_plan_haptic_saved_copy_and_gemini() {
        val fs = src("java/com/minim/music/ui/FullscreenLyrics.kt")
        for (must in listOf(
            "LyricLanguage.plan(", "showButton", "host.tapHaptic(translateBtn)", "host.savedTranslation(data)",
            "host.translateLyrics(data)", "adapter.setLoading(", "adapter.setReady(", "showTranslateNote()",
            "overlay.moreExcluded = listOf(translateBtn, translateNote, captionPill, ai.panel, ai.redo, shareBtn, selectBar, selectHint)", "resetTranslation()",
        )) assertTrue(must, fs.contains(must))
        val main = src("java/com/minim/music/ui/MainActivity.kt")
        assertTrue(main.contains("app.lyricsTranslator.translate("))
        assertTrue(main.contains("GeminiRoute.endpoint(ctx, settings)"))
        val app = src("java/com/minim/music/MinimApp.kt")
        assertTrue(app.contains("File(noBackupFilesDir, \"lyric_translations\")"))
    }

    @Test
    fun bar_widths_vary_but_stay_in_range() {
        val shares = (0 until 40).map { TranslationLineView.barShareFor(it, it * 3) }
        assertTrue(shares.all { it in 0.42f..0.78f })
        assertTrue(shares.toSet().size > 4)
        assertEquals(TranslationLineView.barShareFor(3, 20), TranslationLineView.barShareFor(3, 20))
    }
}
