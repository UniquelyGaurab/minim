package com.minim.music.playback

/**
 * v4.9.5: the 403 fallback rules, shared by the data source (loader thread), the player
 * listener in PlaybackService and the UI controller (main thread).
 *
 * Two levels, both capped so nothing can loop:
 * 1. Inside the data source: a 403 / 410 on a chunk gets a fresh link for the SAME format and
 *    carries on from the same byte. At most [MAX_CONSECUTIVE_REFRESHES] in a row and
 *    [MAX_REFRESHES_PER_OPEN] per open.
 * 2. In the player: if that is not enough, the failed format is set aside and the song restarts
 *    from the same spot with another format the extractor offered. At most
 *    [MAX_RESTARTS_PER_TRACK] times per song, then a clear error is shown.
 */
object StreamRecovery {

    const val MAX_CONSECUTIVE_REFRESHES = 2
    const val MAX_REFRESHES_PER_OPEN = 6
    const val MAX_RESTARTS_PER_TRACK = 2

    const val GIVE_UP_MESSAGE =
        "YouTube refused this song (403). Minim tried a fresh link and other formats. Try again in a bit."

    private val lock = Any()
    private val excluded = HashMap<String, MutableSet<Int>>()
    private val restarts = HashMap<String, Int>()
    private var recoveringId: String? = null
    private var recoveringSinceMs = 0L
    private val giveUp = HashMap<String, String>()

    /** Formats that already failed for this song during this play. */
    fun excludedItags(videoId: String): Set<Int> = synchronized(lock) {
        excluded[videoId]?.toSet() ?: emptySet()
    }

    /**
     * Called when the player hit a stream error for [videoId]. Returns true when the player
     * should restart the song from the same spot (with [failedItag] set aside), false to give up.
     */
    fun onStreamFailure(videoId: String, failedItag: Int, otherOptions: Int, nowMs: Long): Boolean =
        synchronized(lock) {
            val used = restarts[videoId] ?: 0
            val canRetry = used < MAX_RESTARTS_PER_TRACK && (otherOptions > 0 || used == 0)
            if (!canRetry) {
                giveUp[videoId] = GIVE_UP_MESSAGE
                if (recoveringId == videoId) recoveringId = null
                return false
            }
            restarts[videoId] = used + 1
            if (failedItag > 0 && otherOptions > 0) {
                excluded.getOrPut(videoId) { mutableSetOf() }.add(failedItag)
            }
            recoveringId = videoId
            recoveringSinceMs = nowMs
            true
        }

    /** True while a quiet restart is under way, so the UI shows buffering instead of an error. */
    fun isRecovering(videoId: String?, nowMs: Long): Boolean = synchronized(lock) {
        videoId != null && recoveringId == videoId && nowMs - recoveringSinceMs < 30_000L
    }

    /** Playback is going again: the restart worked. */
    fun onPlaying(videoId: String?) = synchronized(lock) {
        if (videoId != null && recoveringId == videoId) recoveringId = null
    }

    /** The clear message to show once every retry failed, read once. */
    fun takeGiveUpMessage(videoId: String?): String? = synchronized(lock) {
        videoId?.let { giveUp.remove(it) }
    }

    /** A new song (or the same song started again by the user) starts with a clean slate. */
    fun reset(videoId: String?) = synchronized(lock) {
        if (videoId == null) return@synchronized
        excluded.remove(videoId)
        restarts.remove(videoId)
        giveUp.remove(videoId)
        if (recoveringId == videoId) recoveringId = null
    }
}
