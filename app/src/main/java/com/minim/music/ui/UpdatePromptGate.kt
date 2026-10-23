package com.minim.music.ui

import com.minim.music.data.update.AvailableUpdate
import com.minim.music.data.update.UpdateCheckResult

/**
 * v4.9.6: when the update pop-up may show. Pure, so it's unit-tested.
 *
 * - Every app open (cold start, or coming back from the background) runs a quiet check
 *   ([onAppOpened]). A newer release with an APK is offered once per open.
 * - "Later" (or swiping the pop-up away) is the answer for this open only: the next open asks again,
 *   until the app is updated.
 * - A failed or empty check on open shows nothing ([onCheckResult] with anything but Available).
 * - [busy] (another sheet or the recap popup, the recap story, the welcome screens, the artist
 *   takeover, the app in the background) only waits: the pop-up is queued and shows once it's clear.
 * - While a download / install that the user started is going on, no new pop-up is offered
 *   ([flowActive]); coming back from the installer or the "install unknown apps" setting isn't a
 *   reason to ask again.
 * - From Settings ([onManualCheck]) the answer always comes through, even after "Later".
 */
class UpdatePromptGate {
    enum class Action { NONE, WAIT, SHOW }

    /** The update waiting to be offered in this open, if any. */
    var pending: AvailableUpdate? = null
        private set
    private var answeredThisOpen = false
    private var manual = false

    /** A new app open: forget the last answer (the check result comes later). */
    fun onAppOpened() {
        answeredThisOpen = false
        manual = false
    }

    /** A finished quiet check. Anything but an update clears what was waiting. */
    fun onCheckResult(result: UpdateCheckResult) {
        pending = (result as? UpdateCheckResult.Available)?.update
        if (pending == null) manual = false
    }

    /** A check from the Settings row found [result]: an update shows even if "Later" was tapped. */
    fun onManualCheck(result: UpdateCheckResult) {
        onCheckResult(result)
        if (pending != null) {
            manual = true
            answeredThisOpen = false
        }
    }

    fun action(busy: Boolean, flowActive: Boolean): Action {
        val u = pending ?: return Action.NONE
        if (answeredThisOpen) return Action.NONE
        if (flowActive && !manual) return Action.NONE
        if (busy) return Action.WAIT
        return if (u.apk.url.isNotEmpty()) Action.SHOW else Action.NONE
    }

    /** The pop-up is on screen: it won't be offered again in this open (whatever they answer). */
    fun markShown() {
        answeredThisOpen = true
        manual = false
    }
}
