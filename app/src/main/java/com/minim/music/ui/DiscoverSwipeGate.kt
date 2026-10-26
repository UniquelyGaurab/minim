package com.minim.music.ui

/**
 * 5.3.0: decides whether a swipe-up on Home may open Discover while the Gemini chat is around.
 *
 * 5.2.x opened Discover behind the chat when you swiped up on the chat sheet, because the
 * screen-wide gesture detector saw every touch. Now:
 * - while the chat is open or still closing, the swipe-up is ignored completely;
 * - a gesture that started while the chat was showing stays ignored to its end, even if the chat
 *   finishes closing in the middle of it (a swipe on the chat only ever moves the chat);
 * - Discover listens again from the first touch that starts after the chat is fully closed.
 */
class DiscoverSwipeGate {

    private var startedOverChat = false

    /** Call on every ACTION_DOWN with whether the chat is open or still closing. */
    fun onDown(chatShowing: Boolean) {
        startedOverChat = chatShowing
    }

    /** True when a swipe-up that is ending now may open Discover. */
    fun allows(chatShowingNow: Boolean): Boolean = !chatShowingNow && !startedOverChat
}
