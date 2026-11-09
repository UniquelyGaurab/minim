package com.minim.music.playback

import androidx.media3.common.Player

/**
 * 5.8.3 (update 55): spotting a song starting over on repeat one, so every loop counts as a new
 * play (after 30 seconds heard, like any play) and gets its own listening time.
 *
 * Why this is needed: Minim talks to the player through a MediaController, and the controller
 * only reports a media item transition when the current item changes. A repeat-one loop keeps the
 * same item, so before this the loop never reset the "already counted" flag and only the first
 * time through counted. The controller does report the position jump (a discontinuity with reason
 * AUTO_TRANSITION), and as a backup the once-a-second listening tick spots the position falling
 * from the end of the song back to its start. Pure logic (unit tested).
 */
object RepeatLoop {
    /** A loop needs at least this much heard since the last one (one loop, one count). */
    const val DEDUP_MS = 5_000L

    /** The tick saw the song this close to its end... */
    const val END_WINDOW_MS = 8_000L

    /** ...and then this close to its start. */
    const val START_WINDOW_MS = 8_000L

    /** The player moved on by itself and landed on the same song again. */
    fun isLoopDiscontinuity(reason: Int, oldMediaId: String?, newMediaId: String?): Boolean =
        reason == Player.DISCONTINUITY_REASON_AUTO_TRANSITION &&
            !oldMediaId.isNullOrEmpty() && oldMediaId == newMediaId

    /**
     * Backup for a missed discontinuity: on repeat one, the position went from near the end of
     * the same song back to near its start. A seek back from the middle doesn't count.
     */
    fun isLoopByPosition(repeatOne: Boolean, lastPosMs: Long, posMs: Long, durMs: Long): Boolean {
        if (!repeatOne || lastPosMs < 0L || durMs <= 0L || posMs < 0L) return false
        if (posMs >= lastPosMs) return false
        val end = minOf(END_WINDOW_MS, durMs / 3)
        val start = minOf(START_WINDOW_MS, durMs / 3)
        return lastPosMs >= durMs - end && posMs <= start
    }

    /**
     * Start a new loop only for the song that's already playing, and only when this loop has had
     * real listening since the last restart, so the discontinuity and the tick spotting the same
     * loop can't count it twice.
     */
    fun shouldStartNewLoop(sameSong: Boolean, heardThisLoopMs: Long, durMs: Long): Boolean {
        if (!sameSong) return false
        val need = if (durMs > 0L) minOf(DEDUP_MS, durMs / 2) else DEDUP_MS
        return heardThisLoopMs >= need
    }
}
