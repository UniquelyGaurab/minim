package com.minim.music.playback

import androidx.test.core.app.ApplicationProvider
import com.minim.music.data.local.AppPreferences
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class BassBoostPrefsTest {

    private val context get() = ApplicationProvider.getApplicationContext<android.app.Application>()

    @Test
    fun defaultsToOff() {
        assertEquals(AppPreferences.BASS_OFF, AppPreferences(context).bassBoost)
    }

    @Test
    fun choiceIsRememberedAcrossRestarts() {
        AppPreferences(context).bassBoost = AppPreferences.BASS_PUNCHY
        // A new instance reads it back from storage, like after the app restarts
        assertEquals(AppPreferences.BASS_PUNCHY, AppPreferences(context).bassBoost)
        AppPreferences(context).bassBoost = AppPreferences.BASS_HEAVY
        assertEquals(AppPreferences.BASS_HEAVY, AppPreferences(context).bassBoost)
    }

    @Test
    fun changingItTellsPlaybackAndRejectsBadValues() {
        val p = AppPreferences(context)
        var told = 0
        val l: () -> Unit = { told++ }
        p.addAudioSettingsListener(l)
        p.bassBoost = AppPreferences.BASS_WARM
        assertEquals(1, told)
        p.removeAudioSettingsListener(l)
        p.bassBoost = 42
        assertEquals(AppPreferences.BASS_OFF, AppPreferences(context).bassBoost)
    }

    @Test
    fun stagesLineUpWithTheDsp() {
        assertEquals(BassBoost.OFF, AppPreferences.BASS_OFF)
        assertEquals(BassBoost.WARM, AppPreferences.BASS_WARM)
        assertEquals(BassBoost.PUNCHY, AppPreferences.BASS_PUNCHY)
        assertEquals(BassBoost.HEAVY, AppPreferences.BASS_HEAVY)
    }
}
