package com.minim.music.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 5.3.0: swiping up on the Gemini chat must never open Discover behind it. */
class DiscoverSwipeGateTest {

    @Test fun `no chat means the swipe opens Discover`() {
        val g = DiscoverSwipeGate()
        g.onDown(chatShowing = false)
        assertTrue(g.allows(chatShowingNow = false))
    }

    @Test fun `swipe while the chat is open is ignored`() {
        val g = DiscoverSwipeGate()
        g.onDown(chatShowing = true)
        assertFalse(g.allows(chatShowingNow = true))
    }

    @Test fun `swipe while the chat is still closing is ignored`() {
        val g = DiscoverSwipeGate()
        // closing: isOpen is false but the sheet is still on screen, so it counts as showing
        g.onDown(chatShowing = true)
        assertFalse(g.allows(chatShowingNow = true))
    }

    @Test fun `gesture that started on the chat stays ignored after the chat finishes closing`() {
        val g = DiscoverSwipeGate()
        g.onDown(chatShowing = true)
        assertFalse(g.allows(chatShowingNow = false))
    }

    @Test fun `chat opening mid gesture blocks the swipe`() {
        val g = DiscoverSwipeGate()
        g.onDown(chatShowing = false)
        assertFalse(g.allows(chatShowingNow = true))
    }

    @Test fun `Discover listens again on the next touch once the chat is fully closed`() {
        val g = DiscoverSwipeGate()
        g.onDown(chatShowing = true)
        assertFalse(g.allows(chatShowingNow = false))
        g.onDown(chatShowing = false)
        assertTrue(g.allows(chatShowingNow = false))
    }
}
