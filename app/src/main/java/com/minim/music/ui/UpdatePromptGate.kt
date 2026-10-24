package com.minim.music.ui

import com.minim.music.data.update.AppVersion
import com.minim.music.data.update.AvailableUpdate
import com.minim.music.data.update.UpdateCheckResult

/**
 * When the update pop-up may show. Pure, so it's unit-tested. Times are a monotonic clock in ms.
 *
 * v4.9.8 (was "once per open" in 4.9.6):
 * - A quiet check runs on a cold start and when the app comes back to the front, at most every few
 *   hours (UpdateCheckSchedule). A newer release with an APK is then offered.
 * - Once shown ("Later", swiped away, anything), that version isn't offered again in the same
 *   session. A session ends with a cold start or when the app comes back after [sessionGapMs]
 *   (30 min) in the background ([onAppOpened]); even then it's asked again only once [reaskAfterMs]
 *   (3 hours) have passed since it was shown. A newer version than the one shown is offered right away.
 * - A failed check keeps an update that an earlier check found; up to date / no release clears it.
 * - [busy] (another sheet or the recap popup, the recap story, the welcome screens, the artist
 *   takeover, the app in the background) only waits: the pop-up is queued and shows once it's clear.
 * - While a download / install that the user started is going on, no new pop-up is offered
 *   ([flowActive]).
 * - From Settings ([onManualCheck]) the answer always comes through, even after "Later".
 */
class UpdatePromptGate(
    private val clock: () -> Long,
    private val sessionGapMs: Long = SESSION_GAP_MS,
    private val reaskAfterMs: Long = REASK_AFTER_MS
) {
    enum class Action { NONE, WAIT, SHOW }

    /** The update waiting to be offered, if any. */
    var pending: AvailableUpdate? = null
        private set
    private var manual = false
    private var session = 0
    private var shownVersion: AppVersion? = null
    private var shownAt = 0L
    private var shownSession = -1

    /** The app came to the front after [awayMs] in the background (a cold start is a new gate). */
    fun onAppOpened(awayMs: Long) {
        if (awayMs >= sessionGapMs) session++
        manual = false
    }

    /** A finished quiet check. */
    fun onCheckResult(result: UpdateCheckResult) {
        when (result) {
            is UpdateCheckResult.Available -> pending = result.update
            is UpdateCheckResult.UpToDate, UpdateCheckResult.NoRelease -> {
                pending = null
                manual = false
            }
            is UpdateCheckResult.Failed -> Unit
        }
    }

    /** A check from the Settings row found [result]: an update shows even if "Later" was tapped. */
    fun onManualCheck(result: UpdateCheckResult) {
        onCheckResult(result)
        if (pending != null && result is UpdateCheckResult.Available) manual = true
    }

    fun action(busy: Boolean, flowActive: Boolean): Action {
        val u = pending ?: return Action.NONE
        if (!manual && alreadyAnswered(u)) return Action.NONE
        if (flowActive && !manual) return Action.NONE
        if (busy) return Action.WAIT
        return if (u.apk.url.isNotEmpty()) Action.SHOW else Action.NONE
    }

    private fun alreadyAnswered(u: AvailableUpdate): Boolean {
        val shown = shownVersion ?: return false
        if (u.version > shown) return false
        if (shownSession == session) return true
        return clock() - shownAt < reaskAfterMs
    }

    /** The pop-up for [update] is on screen: not offered again in this session (whatever they answer). */
    fun markShown(update: AvailableUpdate) {
        val prev = shownVersion
        shownVersion = if (prev == null || update.version > prev) update.version else prev
        shownAt = clock()
        shownSession = session
        manual = false
    }

    companion object {
        const val SESSION_GAP_MS = 30L * 60L * 1000L
        const val REASK_AFTER_MS = 3L * 60L * 60L * 1000L
    }
}
