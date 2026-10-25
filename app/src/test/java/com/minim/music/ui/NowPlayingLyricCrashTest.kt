package com.minim.music.ui

import androidx.test.core.app.ApplicationProvider
import android.content.Context
import com.minim.music.R
import com.minim.music.data.model.LyricsData
import com.minim.music.data.model.LyricsLine
import android.widget.TextView
import androidx.core.widget.TextViewCompat
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Hotfix 4.9.10: Minim 4.9.9 crashed whenever the current song turned out to have no lyrics
 * ("Lyrics not available"), even with Now Playing closed. Runs the real Android TextView code.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class NowPlayingLyricCrashTest {

    private val context: Context = ApplicationProvider.getApplicationContext<Context>().apply {
        setTheme(R.style.Theme_Minim)
    }

    private fun noLyrics() = LyricsData("t1", emptyList(), isSynced = false, hasWordTimestamps = false, providerName = "none")

    private fun synced() = LyricsData(
        "t2",
        listOf(LyricsLine(0L, 2000L, "Hello there"), LyricsLine(2000L, 4000L, "Second line")),
        isSynced = true, hasWordTimestamps = false, providerName = "lrclib"
    )

    private fun lineViews(v: NowPlayingLyricView) = (0 until v.childCount).map { v.getChildAt(it) as TextView }

    @Test
    fun songWithoutLyricsDoesNotCrash() {
        val v = NowPlayingLyricView(context)
        v.setLyrics(noLyrics(), "Lyrics not available", animate = true)
        v.setLyrics(noLyrics(), "Lyrics not available", animate = false)
        // "Lyrics not available" is a fixed 15 sp, without auto-size
        val msg = lineViews(v).first { it.text.toString() == "Lyrics not available" }
        assertEquals(TextViewCompat.AUTO_SIZE_TEXT_TYPE_NONE, TextViewCompat.getAutoSizeTextType(msg))
        assertEquals(15f * context.resources.displayMetrics.scaledDensity, msg.textSize, 0.01f)
    }

    @Test
    fun realLinesGetAutoSizeBack() {
        val v = NowPlayingLyricView(context)
        v.setLyrics(noLyrics(), "Lyrics not available", animate = false)
        v.setLyrics(synced(), "Lyrics not available", animate = false)
        v.onPosition(0, 100L, playing = true, animate = false)
        val line = lineViews(v).first { it.text.toString() == "Hello there" }
        assertEquals(TextViewCompat.AUTO_SIZE_TEXT_TYPE_UNIFORM, TextViewCompat.getAutoSizeTextType(line))
        val d = context.resources.displayMetrics.scaledDensity
        assertEquals(18f * d, TextViewCompat.getAutoSizeMinTextSize(line).toFloat(), 1f)
        assertEquals(22f * d, TextViewCompat.getAutoSizeMaxTextSize(line).toFloat(), 1f)
    }

    @Test
    fun switchingBetweenLyricsAndNoneDoesNotCrash() {
        val v = NowPlayingLyricView(context)
        v.setLyrics(null, "Lyrics not available", animate = true)
        v.setLyrics(synced(), "Lyrics not available", animate = true)
        v.onPosition(0, 100L, playing = true, animate = true)
        v.setLyrics(noLyrics(), "Lyrics not available", animate = true)
        v.setLyrics(synced(), "Lyrics not available", animate = true)
        v.onPosition(1, 2100L, playing = true, animate = true)
        v.setLyrics(noLyrics(), "Lyrics not available", animate = false)
    }
}
