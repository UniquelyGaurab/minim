package com.minim.music.playback

import android.media.audiofx.LoudnessEnhancer
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer

/**
 * v4.4: applies the Volume boost setting with Android's LoudnessEnhancer, attached to the
 * player's audio session. Re-attaches when the session ID changes, re-asserts the gain when
 * playback starts, and fully releases the effect when the boost is Off (audio path untouched).
 * Any failure to create the effect (some phones don't offer it) just means no boost, never a crash.
 */
class VolumeBoostController(
    private val player: ExoPlayer,
    private val settingProvider: () -> Int
) : Player.Listener {

    private var enhancer: LoudnessEnhancer? = null
    private var attachedSessionId = C.AUDIO_SESSION_ID_UNSET
    private var released = false

    fun start() {
        player.addListener(this)
        apply()
    }

    /** Re-read the setting and apply it right away (called when the user changes it). */
    fun apply() {
        if (released) return
        val gainMb = VolumeBoost.gainMbForSetting(settingProvider())
        if (gainMb <= 0) {
            releaseEnhancer()
            return
        }
        val sessionId = player.audioSessionId
        if (sessionId == C.AUDIO_SESSION_ID_UNSET) {
            releaseEnhancer()
            return
        }
        if (enhancer == null || attachedSessionId != sessionId) {
            releaseEnhancer()
            enhancer = try {
                LoudnessEnhancer(sessionId)
            } catch (e: Exception) {
                Log.w(TAG, "LoudnessEnhancer unavailable for session $sessionId", e)
                null
            }
            attachedSessionId = if (enhancer != null) sessionId else C.AUDIO_SESSION_ID_UNSET
        }
        try {
            enhancer?.apply {
                setTargetGain(gainMb)
                if (!enabled) enabled = true
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not set boost gain", e)
            releaseEnhancer()
        }
    }

    override fun onAudioSessionIdChanged(audioSessionId: Int) {
        apply()
    }

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        if (isPlaying) apply()
    }

    fun release() {
        released = true
        player.removeListener(this)
        releaseEnhancer()
    }

    private fun releaseEnhancer() {
        try {
            enhancer?.release()
        } catch (_: Exception) {
        }
        enhancer = null
        attachedSessionId = C.AUDIO_SESSION_ID_UNSET
    }

    companion object {
        private const val TAG = "VolumeBoost"
    }
}
