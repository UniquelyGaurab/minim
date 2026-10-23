package com.minim.music.ui

import com.minim.music.data.update.AppVersion
import com.minim.music.data.update.AvailableUpdate
import com.minim.music.data.update.ReleaseAsset
import com.minim.music.data.update.UpdateCheckResult
import com.minim.music.ui.UpdatePromptGate.Action
import org.junit.Assert.assertEquals
import org.junit.Test

class UpdatePromptGateTest {

    private val update = AvailableUpdate(
        AppVersion.parse("4.9.7")!!, "v4.9.7", "• New",
        ReleaseAsset("Minim-v4.9.7.apk", "https://x/Minim.apk", 100, null, "uploaded"), null
    )
    private val available = UpdateCheckResult.Available(update)

    private fun opened(result: UpdateCheckResult) = UpdatePromptGate().apply {
        onAppOpened()
        onCheckResult(result)
    }

    @Test
    fun showsOnOpenWhenThereIsAnUpdate() {
        assertEquals(Action.SHOW, opened(available).action(busy = false, flowActive = false))
    }

    @Test
    fun failedOrEmptyCheckShowsNothing() {
        assertEquals(Action.NONE, opened(UpdateCheckResult.Failed("offline", true)).action(false, false))
        assertEquals(Action.NONE, opened(UpdateCheckResult.Failed("rate limited")).action(false, false))
        assertEquals(Action.NONE, opened(UpdateCheckResult.NoRelease).action(false, false))
        assertEquals(Action.NONE, opened(UpdateCheckResult.UpToDate("v4.9.5")).action(false, false))
        assertEquals(Action.NONE, UpdatePromptGate().action(false, false))
    }

    @Test
    fun busyQueuesItUntilClear() {
        val g = opened(available)
        assertEquals(Action.WAIT, g.action(busy = true, flowActive = false))
        assertEquals(Action.WAIT, g.action(busy = true, flowActive = false))
        assertEquals(Action.SHOW, g.action(busy = false, flowActive = false))
    }

    @Test
    fun onceShownNotAgainThisOpen() {
        val g = opened(available)
        g.markShown()
        assertEquals(Action.NONE, g.action(false, false))
        // Another check in the same open (e.g. it ran twice) doesn't bring it back
        g.onCheckResult(available)
        assertEquals(Action.NONE, g.action(false, false))
    }

    @Test
    fun laterAsksAgainOnTheNextOpen() {
        val g = opened(available)
        g.markShown() // then "Later"
        g.onAppOpened()
        g.onCheckResult(available)
        assertEquals(Action.SHOW, g.action(false, false))
    }

    @Test
    fun nextOpenWithFailedCheckShowsNothing() {
        val g = opened(available)
        g.markShown()
        g.onAppOpened()
        g.onCheckResult(UpdateCheckResult.Failed("offline", true))
        assertEquals(Action.NONE, g.action(false, false))
    }

    @Test
    fun updatedAppStopsAsking() {
        val g = opened(available)
        g.markShown()
        g.onAppOpened()
        g.onCheckResult(UpdateCheckResult.UpToDate("v4.9.7"))
        assertEquals(Action.NONE, g.action(false, false))
    }

    @Test
    fun noNewPopupWhileADownloadOrInstallIsGoing() {
        val g = opened(available)
        assertEquals(Action.NONE, g.action(busy = false, flowActive = true))
        assertEquals(Action.NONE, g.action(busy = true, flowActive = true))
        assertEquals(Action.SHOW, g.action(busy = false, flowActive = false))
    }

    @Test
    fun manualCheckShowsEvenAfterLater() {
        val g = opened(available)
        g.markShown()
        assertEquals(Action.NONE, g.action(false, false))
        g.onManualCheck(available)
        assertEquals(Action.SHOW, g.action(false, false))
        assertEquals(Action.SHOW, g.action(false, flowActive = true))
        assertEquals(Action.WAIT, g.action(true, false))
        g.markShown()
        assertEquals(Action.NONE, g.action(false, false))
    }

    @Test
    fun manualCheckWithNothingNewShowsNothing() {
        val g = opened(available)
        g.markShown()
        g.onManualCheck(UpdateCheckResult.UpToDate("v4.9.7"))
        assertEquals(Action.NONE, g.action(false, false))
        g.onManualCheck(UpdateCheckResult.Failed("x"))
        assertEquals(Action.NONE, g.action(false, false))
    }
}
