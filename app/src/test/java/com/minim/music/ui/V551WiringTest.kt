package com.minim.music.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 5.5.1: the Up Next and Settings morphs are wired where the user meets them, and only there. */
class V551WiringTest {
    private fun source(name: String): String {
        val candidates = listOf("src/main/java/com/minim/music/ui/$name", "app/src/main/java/com/minim/music/ui/$name")
        return File(candidates.first { File(it).exists() }).readText()
    }

    @Test fun upNextPillOpensWithTheMorphAndBackClosesIntoIt() {
        val src = source("MainActivity.kt")
        assertTrue(src.contains("openQueue(from = v)"))
        assertTrue(src.contains("isQueueOpen -> closeQueue(animate = true)"))
        assertTrue(src.contains("closeQueue(animate = true) }"))
        // The sheet's drag is checked before Now Playing's swipe-down, so the two never both move
        val dispatch = src.substringAfter("if (handleQueueSwipe(ev)) return true")
        assertTrue(dispatch.trimStart().startsWith("if (handleNowPlayingSwipe(ev)) return true"))
        val npSwipe = src.substringAfter("private fun canSwipeNowPlayingDown").substringBefore("\n    }")
        assertTrue(npSwipe.contains("isQueueOpen"))
    }

    @Test fun avatarGrowsIntoSettingsAndOtherWaysStillOpenIt() {
        val src = source("MainActivity.kt")
        assertTrue(src.contains("row.gear.setOnClickListener { v -> openSettings(from = v) }"))
        assertTrue(src.contains("isSettingsOpen -> closeSettings(animate = true)"))
        // The tip and the other routes still open Settings, just without the circle
        assertTrue(src.contains("tipIndex in Tips.settingsTips -> openSettings()"))
        val open = src.substringAfter("private fun openSettings(from: View? = null) {").substringBefore("private fun closeSettings(")
        assertTrue(open.contains("screenSettings.visibility = View.VISIBLE"))
        assertTrue(open.contains("animationsOn()"))
    }

    @Test fun settingsRevealIsAClipNotARelayout() {
        val src = source("SettingsReveal.kt")
        assertTrue(src.contains("ViewAnimationUtils.createCircularReveal"))
        assertFalse(src.contains("requestLayout"))
        assertFalse(src.contains("layoutParams"))
    }

    @Test fun queueMorphUsesTransformsNotLayout() {
        val src = source("QueueMorph.kt")
        assertFalse(src.contains("requestLayout"))
        assertFalse(src.contains("layoutParams"))
        assertTrue(src.contains("translationY"))
    }

    @Test fun noSwipeToRemoveInUpNext() {
        val src = source("adapter/Adapters.kt")
        val queue = src.substringAfter("class QueueAdapter(").substringBefore("// 5.3.0 import review")
        assertTrue(queue.contains("override fun isItemViewSwipeEnabled() = false"))
        assertTrue(queue.contains("makeMovementFlags(ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0)"))
        assertTrue(queue.contains("override fun isLongPressDragEnabled() = false"))
    }
}
