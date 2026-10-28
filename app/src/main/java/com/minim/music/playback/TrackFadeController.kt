package com.minim.music.playback

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.minim.music.ui.SeekMotion
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
        active = this
    }

    fun release() {
        released = true
        handler.removeCallbacks(tick)
        handler.removeCallbacks(dipTick)
        player.removeListener(this)
        if (active === this) active = null
    }

    // 5.5.0: the seek dip. The volume goes down quickly, the player jumps, waits for the new spot
    // to be ready (at most SeekMotion.DIP_MAX_HOLD_MS) and comes back up. It multiplies the fade's
    // own volume, so fades in and out still apply on top.
    private var dipFactor = 1f
    private var dipPhase = DIP_NONE
    private var dipPhaseAt = 0L
    private var dipTarget = 0L

    /** Starts a dip that ends at [positionMs]. False when it can't (released). */
    fun dipAndSeek(positionMs: Long): Boolean {
        if (released) return false
        dipTarget = positionMs
        val now = SystemClock.uptimeMillis()
        when (dipPhase) {
            // Already down or waiting: jump straight to the newer spot
            DIP_HOLD -> { player.seekTo(positionMs); dipPhaseAt = now }
            DIP_OUT -> Unit // the jump at the bottom uses the newest target
            else -> {
                // From full (or on the way back up): go down from where the volume is now
                dipPhase = DIP_OUT
                dipPhaseAt = now - outElapsedFor(dipFactor)
            }
        }
        handler.removeCallbacks(dipTick)
        handler.post(dipTick)
        return true
    }

    private fun outElapsedFor(factor: Float): Long {
        val t = ((1f - factor) / (1f - SeekMotion.DIP_FLOOR)).coerceIn(0f, 1f)
        return (t * SeekMotion.DIP_OUT_MS).toLong()
    }

    private val dipTick = object : Runnable {
        override fun run() {
            if (released) return
            val now = SystemClock.uptimeMillis()
            val ms = now - dipPhaseAt
            when (dipPhase) {
                DIP_OUT -> {
                    dipFactor = SeekMotion.dipOut(ms)
                    if (ms >= SeekMotion.DIP_OUT_MS) {
                        player.seekTo(dipTarget)
                        dipPhase = DIP_HOLD
                        dipPhaseAt = now
                    }
                }
                DIP_HOLD -> {
                    dipFactor = SeekMotion.DIP_FLOOR
                    val ready = player.playbackState == Player.STATE_READY && ms >= 40L
                    if (ready || ms >= SeekMotion.DIP_MAX_HOLD_MS) {
                        dipPhase = DIP_IN
                        dipPhaseAt = now
                    }
                }
                DIP_IN -> {
                    dipFactor = SeekMotion.dipIn(ms)
                    if (ms >= SeekMotion.DIP_IN_MS) {
                        dipFactor = 1f
                        dipPhase = DIP_NONE
                    }
                }
                else -> dipFactor = 1f
            }
            applyVolume()
            if (dipPhase != DIP_NONE) handler.postDelayed(this, 16L)
        }
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
        val target = targetVolume(fadeSecondsProvider(), player.duration, player.currentPosition) * dipFactor
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
        private const val DIP_NONE = 0
        private const val DIP_OUT = 1
        private const val DIP_HOLD = 2
        private const val DIP_IN = 3

        /** 5.5.0: the running service's fade (same process), for the seek dip. */
        @Volatile
        var active: TrackFadeController? = null
            private set

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
