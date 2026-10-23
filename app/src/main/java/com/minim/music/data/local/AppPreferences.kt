package com.minim.music.data.local

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import com.minim.music.data.model.Track
import org.json.JSONArray
import org.json.JSONObject

class AppPreferences(context: Context) {

    private val prefs = context.getSharedPreferences("minim_desk_prefs", Context.MODE_PRIVATE)

    /** The app's own Light/Dark setting (it does not follow the phone's dark mode). */
    var themeMode: Int
        get() = if (prefs.getInt(KEY_THEME_MODE, THEME_LIGHT) == THEME_DARK) THEME_DARK else THEME_LIGHT
        set(value) {
            prefs.edit().putInt(KEY_THEME_MODE, if (value == THEME_DARK) THEME_DARK else THEME_LIGHT).apply()
            applySavedTheme()
        }

    val isDarkTheme: Boolean get() = themeMode == THEME_DARK

    /** v4.9.4: Dynamic (colours from the playing song's cover) or Default (black and white always). */
    var colourMode: Int
        get() = if (prefs.getInt(KEY_COLOUR_MODE, COLOURS_DYNAMIC) == COLOURS_DEFAULT) COLOURS_DEFAULT else COLOURS_DYNAMIC
        set(value) {
            prefs.edit().putInt(KEY_COLOUR_MODE, if (value == COLOURS_DEFAULT) COLOURS_DEFAULT else COLOURS_DYNAMIC).apply()
        }

    val isDynamicColours: Boolean get() = colourMode == COLOURS_DYNAMIC

    var gestureSwipeAction: String
        get() = validateGesture(
            prefs.getString(KEY_GESTURE_SWIPE, GESTURE_SWIPE_SKIP),
            VALID_SWIPE_ACTIONS,
            GESTURE_SWIPE_SKIP
        )
        set(value) {
            val valid = validateGesture(value, VALID_SWIPE_ACTIONS, GESTURE_SWIPE_SKIP)
            prefs.edit().putString(KEY_GESTURE_SWIPE, valid).apply()
        }

    var gestureDoubleTapAction: String
        get() = validateGesture(
            prefs.getString(KEY_GESTURE_DOUBLE_TAP, GESTURE_DTAP_PLAY_PAUSE),
            VALID_DTAP_ACTIONS,
            GESTURE_DTAP_PLAY_PAUSE
        )
        set(value) {
            val valid = validateGesture(value, VALID_DTAP_ACTIONS, GESTURE_DTAP_PLAY_PAUSE)
            prefs.edit().putString(KEY_GESTURE_DOUBLE_TAP, valid).apply()
        }

    var gestureLongPressAction: String
        get() = validateGesture(
            prefs.getString(KEY_GESTURE_LONG_PRESS, GESTURE_LPRESS_QUEUE),
            VALID_LPRESS_ACTIONS,
            GESTURE_LPRESS_QUEUE
        )
        set(value) {
            val valid = validateGesture(value, VALID_LPRESS_ACTIONS, GESTURE_LPRESS_QUEUE)
            prefs.edit().putString(KEY_GESTURE_LONG_PRESS, valid).apply()
        }

    var crossfadeSeconds: Int
        get() = prefs.getInt(KEY_CROSSFADE_SEC, 0).let { if (it in listOf(0, 2, 4)) it else 0 }
        set(value) {
            val valid = if (value in listOf(0, 2, 4)) value else 0
            prefs.edit().putInt(KEY_CROSSFADE_SEC, valid).apply()
            audioSettingsListeners.forEach { it() }
        }

    /** v4.4: volume boost (0 = Off, 1 = Normal, 2 = Loud). Default Normal: songs felt too quiet. */
    var volumeBoost: Int
        get() = prefs.getInt(KEY_VOLUME_BOOST, BOOST_NORMAL).let { if (it in BOOST_OFF..BOOST_LOUD) it else BOOST_NORMAL }
        set(value) {
            val valid = if (value in BOOST_OFF..BOOST_LOUD) value else BOOST_NORMAL
            prefs.edit().putInt(KEY_VOLUME_BOOST, valid).apply()
            audioSettingsListeners.forEach { it() }
        }

    private val audioSettingsListeners = java.util.concurrent.CopyOnWriteArraySet<() -> Unit>()

    /** Called (on the caller's thread, the main thread for Settings) when volume boost or track fade changes. */
    fun addAudioSettingsListener(listener: () -> Unit) {
        audioSettingsListeners.add(listener)
    }

    fun removeAudioSettingsListener(listener: () -> Unit) {
        audioSettingsListeners.remove(listener)
    }

    // ---- v4.9: Stats tab and the daily recap ----

    /** The Stats tab has been opened once (the small "new" tag goes away). */
    var statsTabSeen: Boolean
        get() = prefs.getBoolean("stats_tab_seen", false)
        set(value) = prefs.edit().putBoolean("stats_tab_seen", value).apply()

    /** The recap day (yyyymmdd) the user has looked at; the dot on Stats shows until then. */
    var recapViewedDay: Int
        get() = prefs.getInt("recap_viewed_day", 0)
        set(value) = prefs.edit().putInt("recap_viewed_day", value).apply()

    /** The recap day the popup was shown for (it shows once per recap). */
    var recapPopupDay: Int
        get() = prefs.getInt("recap_popup_day", 0)
        set(value) = prefs.edit().putInt("recap_popup_day", value).apply()

    /** Artist photo found for an artist name, cached so Stats doesn't search every time. */
    fun artistPhoto(name: String): String? =
        prefs.getString("artist_photo_" + name.lowercase().trim(), null)

    fun setArtistPhoto(name: String, url: String) {
        prefs.edit().putString("artist_photo_" + name.lowercase().trim(), url).apply()
    }

    var userName: String
        get() = prefs.getString(KEY_USER_NAME, "")?.trim().orEmpty()
        set(value) {
            prefs.edit().putString(KEY_USER_NAME, value.trim().take(NAME_MAX)).apply()
        }

    /** v4.7: the welcome screens are done (or were never needed: an update with data already saved). */
    var welcomeDone: Boolean
        get() = prefs.getBoolean(KEY_WELCOME_DONE, true)
        set(value) {
            prefs.edit().putBoolean(KEY_WELCOME_DONE, value).apply()
        }

    /**
     * v4.7: decides once whether this is a first launch. Called before anything else saves a
     * setting. A fresh install (or cleared app data) has no settings and no library database yet;
     * an update over 4.6.1 has at least one of them, so it skips the welcome screens.
     */
    fun resolveWelcome(context: Context) {
        if (prefs.contains(KEY_WELCOME_DONE)) return
        val existingUser = prefs.all.isNotEmpty() || context.getDatabasePath(LIBRARY_DB_FILE).exists()
        prefs.edit().putBoolean(KEY_WELCOME_DONE, existingUser).apply()
    }

    /** v4.2: light haptic ticks on taps and gestures (the phone's own haptics setting still applies). */
    var hapticsEnabled: Boolean
        get() = prefs.getBoolean(KEY_HAPTICS, true)
        set(value) {
            prefs.edit().putBoolean(KEY_HAPTICS, value).apply()
        }

    /** v4.2.5: lyrics timing offset in ms, in 50 ms steps from -2000 to +2000 (+ = lyrics earlier). */
    var lyricsOffsetMs: Int
        get() = snapLyricsOffset(prefs.getInt(KEY_LYRICS_OFFSET, LYRICS_OFFSET_DEFAULT))
        set(value) {
            prefs.edit().putInt(KEY_LYRICS_OFFSET, snapLyricsOffset(value)).apply()
        }

    private fun snapLyricsOffset(v: Int): Int {
        val clamped = v.coerceIn(LYRICS_OFFSET_MIN, LYRICS_OFFSET_MAX)
        return Math.round(clamped / LYRICS_OFFSET_STEP.toFloat()) * LYRICS_OFFSET_STEP
    }

    fun resetGestureDefaults() {
        prefs.edit()
            .putString(KEY_GESTURE_SWIPE, GESTURE_SWIPE_SKIP)
            .putString(KEY_GESTURE_DOUBLE_TAP, GESTURE_DTAP_PLAY_PAUSE)
            .putString(KEY_GESTURE_LONG_PRESS, GESTURE_LPRESS_QUEUE)
            .apply()
    }

    private fun validateGesture(raw: String?, allowed: Set<String>, fallback: String): String {
        return if (raw != null && allowed.contains(raw)) raw else fallback
    }

    /** v4.3: true while the queue is a radio (similar songs that keep topping up). */
    var radioQueue: Boolean
        get() = prefs.getBoolean(KEY_RADIO_QUEUE, false)
        set(value) = prefs.edit().putBoolean(KEY_RADIO_QUEUE, value).apply()

    fun applySavedTheme() {
        AppCompatDelegate.setDefaultNightMode(
            if (isDarkTheme) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO
        )
    }

    fun saveQueueState(queue: List<Track>, currentIndex: Int, positionMs: Long) {
        try {
            val arr = JSONArray()
            queue.take(100).forEach { track ->
                val obj = JSONObject().apply {
                    put("id", track.id)
                    put("title", track.title)
                    put("artist", track.artist)
                    put("durationSec", track.durationSec)
                    put("thumbnailUrl", track.thumbnailUrl)
                    put("sourceUrl", track.sourceUrl)
                }
                arr.put(obj)
            }
            prefs.edit()
                .putString(KEY_SAVED_QUEUE, arr.toString())
                .putInt(KEY_SAVED_INDEX, currentIndex)
                .putLong(KEY_SAVED_POSITION, positionMs)
                .apply()
        } catch (_: Exception) {
        }
    }

    fun restoreQueueState(): Triple<List<Track>, Int, Long> {
        val raw = prefs.getString(KEY_SAVED_QUEUE, null) ?: return Triple(emptyList(), -1, 0L)
        return try {
            val arr = JSONArray(raw)
            val list = ArrayList<Track>(arr.length())
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                list.add(
                    Track(
                        id = obj.optString("id"),
                        title = obj.optString("title", "Unknown Track"),
                        artist = obj.optString("artist", "Unknown Artist"),
                        durationSec = obj.optLong("durationSec", 0L),
                        thumbnailUrl = obj.optString("thumbnailUrl", ""),
                        sourceUrl = obj.optString("sourceUrl", "")
                    )
                )
            }
            val index = prefs.getInt(KEY_SAVED_INDEX, 0).coerceIn(0, (list.size - 1).coerceAtLeast(0))
            val pos = prefs.getLong(KEY_SAVED_POSITION, 0L).coerceAtLeast(0L)
            Triple(list, if (list.isEmpty()) -1 else index, pos)
        } catch (_: Exception) {
            Triple(emptyList(), -1, 0L)
        }
    }

    companion object {
        private const val KEY_THEME_MODE = "theme_mode_v4"
        private const val KEY_COLOUR_MODE = "colour_mode_v494"
        const val COLOURS_DYNAMIC = 1
        const val COLOURS_DEFAULT = 2
        private const val KEY_GESTURE_SWIPE = "gesture_swipe_action"
        private const val KEY_GESTURE_DOUBLE_TAP = "gesture_dtap_action"
        private const val KEY_GESTURE_LONG_PRESS = "gesture_lpress_action"
        private const val KEY_CROSSFADE_SEC = "crossfade_sec"
        private const val KEY_USER_NAME = "user_name"
        private const val KEY_WELCOME_DONE = "welcome_done_v47"
        /** The library database file (LibraryDatabase); its presence means the app was used before. */
        private const val LIBRARY_DB_FILE = "minim_library.db"
        /** v4.7: names are at most 20 characters (welcome screen and Settings). */
        const val NAME_MAX = 20
        private const val KEY_VOLUME_BOOST = "volume_boost"

        const val BOOST_OFF = 0
        const val BOOST_NORMAL = 1
        const val BOOST_LOUD = 2
        private const val KEY_HAPTICS = "haptics_enabled"
        private const val KEY_LYRICS_OFFSET = "lyrics_offset_ms"

        /** v4.2.5 lyrics timing: + shows lines earlier, - later. */
        const val LYRICS_OFFSET_DEFAULT = 300
        const val LYRICS_OFFSET_STEP = 50
        const val LYRICS_OFFSET_MIN = -2000
        const val LYRICS_OFFSET_MAX = 2000
        private const val KEY_RADIO_QUEUE = "radio_queue"
        private const val KEY_SAVED_QUEUE = "saved_queue"
        private const val KEY_SAVED_INDEX = "saved_index"
        private const val KEY_SAVED_POSITION = "saved_position"

        const val THEME_LIGHT = 1
        const val THEME_DARK = 2

        const val GESTURE_DISABLED = "DISABLED"
        const val GESTURE_SWIPE_SKIP = "SWIPE_SKIP"
        const val GESTURE_DTAP_PLAY_PAUSE = "DTAP_PLAY_PAUSE"
        const val GESTURE_DTAP_LIKE = "DTAP_LIKE"
        const val GESTURE_LPRESS_QUEUE = "LPRESS_QUEUE"
        const val GESTURE_LPRESS_LYRICS = "LPRESS_LYRICS"

        private val VALID_SWIPE_ACTIONS = setOf(GESTURE_SWIPE_SKIP, GESTURE_DISABLED)
        private val VALID_DTAP_ACTIONS = setOf(GESTURE_DTAP_PLAY_PAUSE, GESTURE_DTAP_LIKE, GESTURE_DISABLED)
        private val VALID_LPRESS_ACTIONS = setOf(GESTURE_LPRESS_QUEUE, GESTURE_LPRESS_LYRICS, GESTURE_DISABLED)
    }
}
