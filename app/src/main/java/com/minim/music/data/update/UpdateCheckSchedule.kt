package com.minim.music.data.update

/**
 * v4.9.8: when the quiet update check runs. Pure, so it's unit-tested. Times are a monotonic clock
 * in ms (SystemClock.elapsedRealtime in the app).
 *
 * - A cold start always checks.
 * - Coming back to the foreground checks when the last good check is [checkEveryMs] old (3 hours).
 * - A check that failed (offline, rate limited) is tried again sooner, after [retryAfterMs] (15 min),
 *   so one bad moment doesn't hide an update for hours.
 * - Never two at once; a check that got cancelled (the screen closed) doesn't count.
 * - A check from Settings counts too.
 */
class UpdateCheckSchedule(
    private val checkEveryMs: Long = CHECK_EVERY_MS,
    private val retryAfterMs: Long = RETRY_AFTER_MS
) {
    private var lastSuccessAt: Long? = null
    private var lastAttemptAt: Long? = null
    private var attemptBefore: Long? = null
    private var inFlight = false

    fun shouldCheck(nowMs: Long, coldStart: Boolean): Boolean {
        if (inFlight) return false
        if (coldStart) return true
        val attempt = lastAttemptAt ?: return true
        val success = lastSuccessAt
        val lastFailed = success == null || attempt > success
        return if (lastFailed) nowMs - attempt >= retryAfterMs
        else nowMs - success!! >= checkEveryMs
    }

    fun onCheckStarted(nowMs: Long) {
        inFlight = true
        attemptBefore = lastAttemptAt
        lastAttemptAt = nowMs
    }

    /** [ok]: GitHub answered (an update, up to date or no release); false for a failed check. */
    fun onCheckFinished(nowMs: Long, ok: Boolean) {
        inFlight = false
        if (ok) {
            lastSuccessAt = nowMs
            if ((lastAttemptAt ?: 0L) < nowMs) lastAttemptAt = nowMs
        }
    }

    fun onCheckCancelled() {
        if (!inFlight) return
        inFlight = false
        lastAttemptAt = attemptBefore
    }

    companion object {
        const val CHECK_EVERY_MS = 3L * 60L * 60L * 1000L
        const val RETRY_AFTER_MS = 15L * 60L * 1000L

        fun isOk(result: UpdateCheckResult): Boolean = result !is UpdateCheckResult.Failed
    }
}
