package com.minim.music

import com.minim.music.data.model.ArtistCountKind
import com.minim.music.data.source.ArtistAudience
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ArtistAudienceTest {

    @Test
    fun kinds() {
        assertEquals(ArtistCountKind.MONTHLY_LISTENERS, ArtistAudience.kindOf("647M monthly audience"))
        assertEquals(ArtistCountKind.MONTHLY_LISTENERS, ArtistAudience.kindOf("5.57M monthly listeners"))
        assertEquals(ArtistCountKind.SUBSCRIBERS, ArtistAudience.kindOf("155K subscribers"))
        assertEquals(ArtistCountKind.SUBSCRIBERS, ArtistAudience.kindOf("1 subscriber"))
        assertEquals(ArtistCountKind.UNKNOWN, ArtistAudience.kindOf("Artist"))
        assertEquals(ArtistCountKind.UNKNOWN, ArtistAudience.kindOf(null))
    }

    @Test
    fun channelIds() {
        assertEquals("UCDxKh1gFWeYsqePvgVzmPoQ",
            ArtistAudience.channelId("https://music.youtube.com/channel/UCDxKh1gFWeYsqePvgVzmPoQ"))
        assertEquals("UCabc", ArtistAudience.channelId("https://www.youtube.com/channel/UCabc?si=1"))
        assertNull(ArtistAudience.channelId("https://www.youtube.com/@someone"))
    }

    private fun row(id: String, vararg secondColumn: String): Map<String, Any> = mapOf(
        "musicResponsiveListItemRenderer" to mapOf(
            "navigationEndpoint" to mapOf("browseEndpoint" to mapOf("browseId" to id)),
            "flexColumns" to listOf(
                mapOf("musicResponsiveListItemFlexColumnRenderer" to mapOf("text" to mapOf(
                    "runs" to listOf(mapOf("text" to "Name"))))),
                mapOf("musicResponsiveListItemFlexColumnRenderer" to mapOf("text" to mapOf(
                    "runs" to secondColumn.map { mapOf("text" to it) })))
            )
        )
    )

    @Test
    fun readsSubtitlesFromResponse() {
        val tree = mapOf("contents" to listOf(mapOf("musicShelfRenderer" to mapOf("contents" to listOf(
            row("UC1", "Artist", " • ", "647M monthly audience"),
            row("UC2", "Artist", " • ", "155K subscribers")
        )))))
        val subs = ArtistAudience.subtitlesById(tree)
        assertEquals("647M monthly audience", subs["UC1"])
        assertEquals("155K subscribers", subs["UC2"])
    }
}
