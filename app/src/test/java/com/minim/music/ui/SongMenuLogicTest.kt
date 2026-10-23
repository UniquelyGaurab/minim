package com.minim.music.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SongMenuLogicTest {

    @Test fun laterSongMovesRightAfterCurrent() {
        // queue a b C d e, playing C (2): e (4) goes to 3
        assertEquals(3, SongMenuLogic.playNextTarget(4, 2, 5))
    }

    @Test fun earlierSongLandsAfterCurrentBecauseCurrentShiftsUp() {
        // a b C d, move a (0): after removing a, C is at 1, so a goes to 2
        assertEquals(2, SongMenuLogic.playNextTarget(0, 2, 4))
    }

    @Test fun alreadyNextOrPlayingIsNothingToDo() {
        assertNull(SongMenuLogic.playNextTarget(3, 2, 5))
        assertNull(SongMenuLogic.playNextTarget(2, 2, 5))
    }

    @Test fun nothingPlayingOrOutOfRange() {
        assertNull(SongMenuLogic.playNextTarget(1, -1, 5))
        assertNull(SongMenuLogic.playNextTarget(9, 2, 5))
    }

    @Test fun shareUsesYouTubeMusicLinkForVideoIds() {
        assertEquals("https://music.youtube.com/watch?v=dQw4w9WgXcQ", SongMenuLogic.shareUrl("dQw4w9WgXcQ", ""))
    }

    @Test fun shareFallsBackToSourceUrl() {
        assertEquals("https://example.com/x", SongMenuLogic.shareUrl("not-a-video-id", "https://example.com/x"))
    }

    @Test fun shareTextHasTitleArtistAndLink() {
        assertEquals("Suroor · Neha Kakkar\nhttps://l", SongMenuLogic.shareText("Suroor", "Neha Kakkar", "https://l"))
        assertEquals("Suroor\nhttps://l", SongMenuLogic.shareText("Suroor", "", "https://l"))
    }
}
