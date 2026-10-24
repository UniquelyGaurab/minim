package com.minim.music.ui

import com.minim.music.data.local.AppPreferences

/**
 * v4.2: the rotating tips at the bottom of Home. Index 0 is shown first when the app opens.
 * Every tip was checked against the code. Album-art tips are skipped while that gesture is
 * changed or turned off in Settings, so a tip is never wrong.
 */
object Tips {

    const val FIRST = 0
    const val SWIPE_UP_DISCOVER = 0
    const val OPEN_DISCOVER = 11

    val all: List<String> = listOf(
        "Swipe up to open Discover.",                   // 0  v4.3.1: Home swipe-up / hint tap -> Discover
        "Tap the greeting for a surprise song.",        // 1  greeting tap -> random Liked song
        "Double-tap the album art to play or pause.",   // 2  default double-tap action
        "Swipe the album art to skip.",                 // 3  sideways fling on Now Playing art
        "Tap Up Next on Now Playing for the queue.",    // 4  v4.9.8: Up Next pill (was long-press)
        "Tap the lyric line for full screen lyrics.",   // 5  v4.9.8: lyric line tap (was swipe up)
        "Long-press a playlist song to remove it.",     // 6  song menu inside your own playlists
        "Long-press a song to add it to a playlist.",   // 7  song menu: Search, Recents, lists
        "Change gestures and track fade in Settings.",  // 8
        "Switch Light or Dark in Settings.",            // 9
        "Set a sleep timer in Settings.",               // 10
        "Discover has songs picked from what you play.",  // 11 v4.3: tap opens Discover
        "Not into a Discover song? Long-press it."       // 12 v4.3: "Not interested"
    )

    val count: Int get() = all.size

    /** Indexes that open Settings when the tip is tapped. */
    val settingsTips = setOf(8, 9, 10)

    fun isTrue(index: Int, p: AppPreferences): Boolean = when (index) {
        2 -> p.gestureDoubleTapAction == AppPreferences.GESTURE_DTAP_PLAY_PAUSE
        3 -> p.gestureSwipeAction == AppPreferences.GESTURE_SWIPE_SKIP
        else -> true
    }
}
