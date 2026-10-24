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

    private var now = 1_000_000L
    private val hour = 60L * 60L * 1000L
    private fun gate() = UpdatePromptGate(clock = { now })

    private fun opened(result: UpdateCheckResult) = gate().apply {
        onAppOpened(0L)
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
        assertEquals(Action.NONE, gate().action(false, false))
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
        g.markShown(update)
        assertEquals(Action.NONE, g.action(false, false))
        // Another check in the same open (e.g. it ran twice) doesn't bring it back
        g.onCheckResult(available)
        assertEquals(Action.NONE, g.action(false, false))
    }

    @Test
    fun laterIsNotAskedAgainInTheSameSession() {
        val g = opened(available)
        g.markShown(update) // then "Later"
        // Back from a short trip to another app, even hours later in total: same session
        now += 4 * hour
        g.onAppOpened(awayMs = 10L * 60L * 1000L)
        g.onCheckResult(available)
        assertEquals(Action.NONE, g.action(false, false))
    }

    @Test
    fun laterAsksAgainInANewSessionAfterAFewHours() {
        val g = opened(available)
        g.markShown(update)
        // New session (away 40 min) but only 1 hour after it was shown: still quiet
        now += hour
        g.onAppOpened(awayMs = 40L * 60L * 1000L)
        g.onCheckResult(available)
        assertEquals(Action.NONE, g.action(false, false))
        // Another new session, 3+ hours after it was shown: asks again
        now += 2 * hour + 1
        g.onAppOpened(awayMs = 40L * 60L * 1000L)
        assertEquals(Action.SHOW, g.action(false, false))
    }

    @Test
    fun aNewerVersionShowsEvenRightAfterLater() {
        val g = opened(available)
        g.markShown(update)
        val newer = update.copy(version = AppVersion.parse("4.9.8")!!, tag = "v4.9.8")
        g.onCheckResult(UpdateCheckResult.Available(newer))
        assertEquals(Action.SHOW, g.action(false, false))
        g.markShown(newer)
        // ...and the older one never comes back on top of it
        g.onCheckResult(available)
        assertEquals(Action.NONE, g.action(false, false))
    }

    @Test
    fun failedCheckKeepsAnUpdateFoundEarlier() {
        val g = opened(UpdateCheckResult.Failed("offline", true))
        assertEquals(Action.NONE, g.action(false, false))
        g.onCheckResult(available)
        g.onCheckResult(UpdateCheckResult.Failed("rate limited"))
        assertEquals(Action.SHOW, g.action(false, false))
    }

    @Test
    fun deferredPopupStillShowsOnTheNextOpenWithoutACheck() {
        // Found while busy (another sheet up), the app went to the background before it showed
        val g = opened(available)
        assertEquals(Action.WAIT, g.action(busy = true, flowActive = false))
        g.onAppOpened(awayMs = 5_000L)
        assertEquals(Action.SHOW, g.action(false, false))
    }

    @Test
    fun updatedAppStopsAsking() {
        val g = opened(available)
        g.onAppOpened(0L)
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
        g.markShown(update)
        assertEquals(Action.NONE, g.action(false, false))
        g.onManualCheck(available)
        assertEquals(Action.SHOW, g.action(false, false))
        assertEquals(Action.SHOW, g.action(false, flowActive = true))
        assertEquals(Action.WAIT, g.action(true, false))
        g.markShown(update)
        assertEquals(Action.NONE, g.action(false, false))
    }

    @Test
    fun manualCheckWithNothingNewShowsNothing() {
        val g = opened(available)
        g.markShown(update)
        g.onManualCheck(UpdateCheckResult.UpToDate("v4.9.7"))
        assertEquals(Action.NONE, g.action(false, false))
        g.onManualCheck(UpdateCheckResult.Failed("x"))
        assertEquals(Action.NONE, g.action(false, false))
    }
}
