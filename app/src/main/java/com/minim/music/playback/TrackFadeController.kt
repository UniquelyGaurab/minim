package com.minim.music.playback

import android.os.Handler
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import kotlin.math.abs
import kotlin.math.min

/**
 * Simple track fade driven by AppPreferences.crossfadeSeconds (0 = Off, 2 s, 4 s).
 *
 * The player volume is derived purely from the current playback position on every tick:
 * it fades in over the first N seconds of a track and fades out over the last N seconds.
 * Because nothing is accumulated, skips, seeks, pause/resume and the sleep timer can never
 * leave the volume stuck low: the next tick (or the next play/seek event) recomputes it.
 * With the fade Off the volume is simply held at full, i.e. no change in behaviour.
 */
class TrackFadeController(
    private val player: Player,
    private val fadeSecondsProvider: () -> Int
) : Player.Listener {

    private val handler = Handler(Looper.getMainLooper())
    private var released = false

    private val tick = object : Runnable {
        override fun run() {
            if (released) return
            applyVolume()
            if (player.isPlaying) {
                handler.postDelayed(this, TICK_MS)
            }
        }
    }

    fun start() {
        player.addListener(this)
        restartTicking()
    }

    fun release() {
        released = true
        handler.removeCallbacks(tick)
        player.removeListener(this)
    }

    /** Re-apply right away after the fade setting changes (so Off restores full volume even while paused). */
    fun refresh() {
        if (released) return
        applyVolume()
    }

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        restartTicking()
    }

    override fun onPlaybackStateChanged(playbackState: Int) {
        applyVolume()
    }

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        applyVolume()
    }

    override fun onPositionDiscontinuity(
        oldPosition: Player.PositionInfo,
        newPosition: Player.PositionInfo,
        reason: Int
    ) {
        // Seeks and skips: recompute right away instead of waiting for the next tick
        applyVolume()
    }

    private fun restartTicking() {
        handler.removeCallbacks(tick)
        if (released) return
        applyVolume()
        if (player.isPlaying) {
            handler.postDelayed(tick, TICK_MS)
        }
    }

    private fun applyVolume() {
        val target = targetVolume(fadeSecondsProvider(), player.duration, player.currentPosition)
        val current = player.volume
        // v4.4 fix: the end points (full and silent) are always set exactly. Before, the last fade-in
        // step could be skipped by the 0.005 threshold, leaving the volume stuck at e.g. 0.998.
        val atEndPoint = target >= 1f || target <= 0f
        if ((atEndPoint && current != target) || abs(current - target) > 0.005f) {
            player.volume = target
        }
    }

    companion object {
        private const val TICK_MS = 100L

        /** Player volume for this moment of the track. Fade Off (0 s) is always exactly 1. */
        fun targetVolume(fadeSeconds: Int, durationMs: Long, rawPositionMs: Long): Float {
            val fadeMs = fadeSeconds.coerceAtLeast(0) * 1000L
            if (fadeMs <= 0L) return 1f

            val knownDuration = durationMs != C.TIME_UNSET && durationMs > 0L
            // Very short tracks: fading in and out would swallow most of the song, so play at full volume
            if (knownDuration && durationMs <= fadeMs * 3) return 1f

            val positionMs = rawPositionMs.coerceAtLeast(0L)
            val fadeIn = (positionMs.toFloat() / fadeMs).coerceIn(0f, 1f)
            val fadeOut = if (knownDuration) {
                ((durationMs - positionMs).toFloat() / fadeMs).coerceIn(0f, 1f)
            } else {
                1f
            }
            return min(fadeIn, fadeOut)
        }
    }
}
