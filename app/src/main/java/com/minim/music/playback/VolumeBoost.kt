package com.minim.music.playback

import com.minim.music.data.local.AppPreferences
import kotlin.math.ln
import kotlin.math.roundToInt

/**
 * v4.4 volume boost levels, as the user asked for them: Normal = 20% louder, Loud = 35% louder.
 *
 * "X% louder" is read as perceived loudness. Perceived loudness doubles roughly every +10 dB
 * (the sone scale), so a loudness ratio r needs 10 * log2(r) dB of gain:
 *   Normal: 10 * log2(1.20) = 2.63 dB = 263 mB
 *   Loud:   10 * log2(1.35) = 4.33 dB = 433 mB
 * (Reading 20% as plain amplitude would only be 1.6 dB, which is barely audible.)
 * LoudnessEnhancer has its own compressor/limiter, and the gain is also capped at [MAX_GAIN_MB]
 * so loud, already-mastered-hot songs don't crackle.
 */
object VolumeBoost {

    const val NORMAL_PERCENT = 20
    const val LOUD_PERCENT = 35

    /** Hard ceiling for the target gain, whatever the setting (6 dB). */
    const val MAX_GAIN_MB = 600

    /** Millibels of gain for "[percent]% louder" in perceived loudness, capped at [MAX_GAIN_MB]. */
    fun gainMbForPercent(percent: Int): Int {
        if (percent <= 0) return 0
        val db = 10.0 * ln(1.0 + percent / 100.0) / ln(2.0)
        return (db * 100.0).roundToInt().coerceIn(0, MAX_GAIN_MB)
    }

    /** Target gain in millibels for a saved setting (AppPreferences.BOOST_*). 0 = no boost. */
    fun gainMbForSetting(setting: Int): Int = when (setting) {
        AppPreferences.BOOST_NORMAL -> gainMbForPercent(NORMAL_PERCENT)
        AppPreferences.BOOST_LOUD -> gainMbForPercent(LOUD_PERCENT)
        else -> 0
    }
}
