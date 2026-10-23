package com.minim.music

import androidx.media3.common.C
import com.minim.music.data.local.AppPreferences
import com.minim.music.playback.TrackFadeController
import com.minim.music.playback.VolumeBoost
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioLevelsTest {

    @Test
    fun boostGains() {
        assertEquals(0, VolumeBoost.gainMbForSetting(AppPreferences.BOOST_OFF))
        assertEquals(263, VolumeBoost.gainMbForSetting(AppPreferences.BOOST_NORMAL))
        assertEquals(433, VolumeBoost.gainMbForSetting(AppPreferences.BOOST_LOUD))
        assertEquals(VolumeBoost.MAX_GAIN_MB, VolumeBoost.gainMbForPercent(500))
    }

    @Test
    fun fadeOffIsAlwaysFull() {
        for (pos in listOf(0L, 50L, 1_000L, 179_900L, 180_000L, 999_999L)) {
            assertEquals(1f, TrackFadeController.targetVolume(0, 180_000L, pos))
            assertEquals(1f, TrackFadeController.targetVolume(0, C.TIME_UNSET, pos))
        }
    }

    @Test
    fun fadeReachesExactlyFull() {
        // After the fade-in (2 s and 4 s), mid-song, after a seek, unknown duration: exactly 1
        for (fade in listOf(2, 4)) {
            assertEquals(1f, TrackFadeController.targetVolume(fade, 180_000L, fade * 1000L))
            assertEquals(1f, TrackFadeController.targetVolume(fade, 180_000L, 90_000L))
            assertEquals(1f, TrackFadeController.targetVolume(fade, C.TIME_UNSET, 90_000L))
            assertEquals(0f, TrackFadeController.targetVolume(fade, 180_000L, 0L))
            assertTrue(TrackFadeController.targetVolume(fade, 180_000L, 179_000L) < 1f)
        }
        // Short tracks are not faded at all
        assertEquals(1f, TrackFadeController.targetVolume(4, 12_000L, 0L))
    }
}
