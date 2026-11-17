package com.minim.music.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 6.8.0 b72: Up Next (the queue screen) gets the Haze look like every other screen. */
class QueueHazeTest {

    private fun main(): File = listOf(File("src/main"), File("app/src/main")).first { it.isDirectory }

    @Test fun sheetFadesOntoTheHazeWithTheColour() {
        assertEquals(0f, QueueMotion.hazeAmount(true, 0f), 0.0001f)
        assertEquals(1f, QueueMotion.hazeAmount(true, 1f), 0.0001f)
        assertEquals(QueueMotion.color(0.5f), QueueMotion.hazeAmount(true, 0.5f), 0.0001f)
    }

    @Test fun lightAndDarkStayFlat() {
        for (p in listOf(0f, 0.3f, 1f)) assertEquals(0f, QueueMotion.hazeAmount(false, p), 0.0001f)
    }

    @Test fun queueScreenIsTaggedForTheBackdrop() {
        val xml = File(main(), "res/layout/view_queue.xml").readText()
        val root = xml.substringAfter("@+id/screen_queue").substringBefore(">")
        assertTrue(root.contains("android:tag=\"bg\""))
    }

    @Test fun openingUpNextDoesNotWipeTheHaze() {
        val src = File(main(), "java/com/minim/music/ui/QueueMorph.kt").readText()
        // The resting background goes back through Painter.paintBg (Haze or the colour), not a flat colour
        assertFalse(src.contains("screen.setBackgroundColor(colors().second)"))
        assertTrue(src.contains("paintBg(screen)"))
        val main = File(main(), "java/com/minim/music/ui/MainActivity.kt").readText()
        assertTrue(main.contains("paintBg = { Painter.paintBg(it, pal) }"))
    }
}
