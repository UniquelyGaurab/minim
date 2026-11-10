package com.minim.music.ui

import com.minim.music.data.local.AppPreferences

/**
 * v4.2: the rotating tips at the bottom of Home. Index 0 is shown first when the app opens.
 * Every tip was checked against the code. Album-art tips are skipped while that gesture is
 * changed or turned off in Settings, so a tip is never wrong.
 */
object Tips {

    const val FIRST = 0
    /** 5.7.0: the tips that open the Search page (where Discover's shelves are now) when tapped. */
    const val SEARCH_PICKS = 0
    const val OPEN_PICKS = 11

    val all: List<String> = listOf(
        "Tap search for songs picked for you.",         // 0  5.7.0 (was the Home swipe-up): tap opens Search
        "Tap the greeting for a surprise song.",        // 1  greeting tap -> random Liked song
        "Double-tap the album art to play or pause.",   // 2  default double-tap action
        "Swipe the album art to skip.",                 // 3  sideways fling on Now Playing art
        "Tap Up Next on Now Playing for the queue.",    // 4  v4.9.8: Up Next pill (was long-press)
        "Tap the lyric line for full screen lyrics.",   // 5  v4.9.8: lyric line tap (was swipe up)
        "Long-press a playlist song to remove it.",     // 6  song menu inside your own playlists
        "Long-press a song to add it to a playlist.",   // 7  song menu: Search, Recents, lists
        "Change gestures and track fade in Settings.",  // 8
        "Switch Light or Dark in Settings.",            // 9
        "Set a sleep timer on Now Playing.",            // 10 5.8.4: was "in Settings", it lives on Now Playing
        "Search has songs picked from what you play.",   // 11 5.7.0: tap opens Search (Discover's shelves)
        "Not into a picked song? Long-press it.",        // 12 v4.3: "Not interested" on a shelf song
        "In full screen lyrics, tap a line to jump there."  // 13 5.1.0: tap a synced line to seek
    )

    val count: Int get() = all.size

    /** Indexes that open Settings when the tip is tapped. */
    val settingsTips = setOf(8, 9)

    fun isTrue(index: Int, p: AppPreferences): Boolean = when (index) {
        2 -> p.gestureDoubleTapAction == AppPreferences.GESTURE_DTAP_PLAY_PAUSE
        3 -> p.gestureSwipeAction == AppPreferences.GESTURE_SWIPE_SKIP
        else -> true
    }
}
