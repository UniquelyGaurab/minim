package com.minim.music.ui

import com.minim.music.data.model.Track
import com.minim.music.ui.PlaylistLook.ListType
import com.minim.music.ui.PlaylistLook.Sort
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Minim 6.8.0: sort, search, total time, drag order, covers and which controls each list shows. */
class PlaylistLookTest {

    private fun t(id: String, title: String, artist: String, sec: Long = 200L, art: String = "https://img/$id") =
        Track(id, title, artist, sec, art, "https://www.youtube.com/watch?v=$id")

    // your order: c, a, b; added: a first, then b, then c (c was dragged to the top)
    private val a = t("a", "Ánkh", "Zed")
    private val b = t("b", "bloom", "arijit Singh")
    private val c = t("c", "Cold", "Anirudh")
    private val entries = listOf(
        PlaylistLook.Entry(a, position = 2, addedAt = 1_000),
        PlaylistLook.Entry(c, position = 1, addedAt = 3_000),
        PlaylistLook.Entry(b, position = 3, addedAt = 2_000)
    )

    // ---- sort ----

    @Test fun customIsYourOwnOrder() =
        assertEquals(listOf("c", "a", "b"), PlaylistLook.sort(entries, Sort.CUSTOM).map { it.id })

    @Test fun recentlyAddedIsNewestFirstWhateverTheOrder() =
        assertEquals(listOf("c", "b", "a"), PlaylistLook.sort(entries, Sort.RECENT).map { it.id })

    @Test fun titleIgnoresCaseAndAccents() =
        assertEquals(listOf("a", "b", "c"), PlaylistLook.sort(entries, Sort.TITLE).map { it.id })

    @Test fun artistIgnoresCase() =
        assertEquals(listOf("c", "b", "a"), PlaylistLook.sort(entries, Sort.ARTIST).map { it.id })

    @Test fun switchingBackToCustomGivesTheSameOrderAgain() {
        PlaylistLook.sort(entries, Sort.TITLE)
        PlaylistLook.sort(entries, Sort.ARTIST)
        assertEquals(listOf("c", "a", "b"), PlaylistLook.sort(entries, Sort.CUSTOM).map { it.id })
    }

    @Test fun sortCodesRoundTripAndUnknownIsCustom() {
        for (s in Sort.values()) assertEquals(s, Sort.fromCode(s.code))
        assertEquals(Sort.CUSTOM, Sort.fromCode(null))
        assertEquals(Sort.CUSTOM, Sort.fromCode("nonsense"))
    }

    // ---- drag ----

    @Test fun aDragKeepsThePlaceNumbersAndOnlyChangesMovedSongs() {
        val current = listOf("a" to 10L, "b" to 20L, "c" to 30L, "d" to 40L)
        val moved = PlaylistLook.reorderedPositions(current, listOf("a", "c", "b", "d"))
        assertEquals(mapOf("c" to 20L, "b" to 30L), moved)
    }

    @Test fun aDragOnAChangedPlaylistDoesNothing() {
        val current = listOf("a" to 1L, "b" to 2L)
        assertTrue(PlaylistLook.reorderedPositions(current, listOf("b", "a", "x")).isEmpty())
        assertTrue(PlaylistLook.reorderedPositions(current, listOf("b")).isEmpty())
    }

    @Test fun sharedPlacesBecomeUniqueAfterADrag() {
        val moved = PlaylistLook.reorderedPositions(listOf("a" to 5L, "b" to 5L, "c" to 5L), listOf("c", "a", "b"))
        val after = mapOf("a" to 5L, "b" to 5L, "c" to 5L) + moved
        assertEquals(listOf("c", "a", "b"), after.entries.sortedBy { it.value }.map { it.key })
        assertEquals(3, after.values.toSet().size)
    }

    // ---- search ----

    @Test fun searchMatchesTitleOrArtistIgnoringCaseAndAccents() {
        val all = listOf(a, b, c)
        assertEquals(listOf("a"), PlaylistLook.filter(all, "ankh").map { it.id })
        assertEquals(listOf("b"), PlaylistLook.filter(all, "ARIJIT").map { it.id })
        assertEquals(listOf("b"), PlaylistLook.filter(all, "singh bloom").map { it.id })
        assertTrue(PlaylistLook.filter(all, "nothing here").isEmpty())
    }

    @Test fun blankSearchShowsEverythingInOrder() {
        val all = listOf(c, a, b)
        assertEquals(all, PlaylistLook.filter(all, ""))
        assertEquals(all, PlaylistLook.filter(all, "   "))
        assertEquals(all, PlaylistLook.filter(all, null))
        assertFalse(PlaylistLook.isActive("  "))
        assertTrue(PlaylistLook.isActive("a"))
    }

    @Test fun searchKeepsTheSortedOrder() {
        val sorted = PlaylistLook.sort(entries, Sort.RECENT)
        assertEquals(listOf("c", "a"), PlaylistLook.filter(sorted, "n").filter { it.id != "b" }.map { it.id })
    }

    // ---- total time ----

    @Test fun totalTimeReadsLikeASubtitle() {
        assertEquals("1 hr 32 min", PlaylistLook.totalTime(listOf(t("x", "x", "y", 92 * 60L))))
        assertEquals("45 min", PlaylistLook.totalTime(listOf(t("x", "x", "y", 20 * 60L), t("z", "z", "y", 25 * 60L))))
        assertEquals("2 hr", PlaylistLook.totalTime(listOf(t("x", "x", "y", 120 * 60L))))
        assertEquals("1 min", PlaylistLook.totalTime(listOf(t("x", "x", "y", 10L))))
    }

    @Test fun totalTimeRoundsToTheNearestMinute() {
        assertEquals("4 min", PlaylistLook.totalTime(listOf(t("x", "x", "y", 3 * 60L + 31L))))
        assertEquals("3 min", PlaylistLook.totalTime(listOf(t("x", "x", "y", 3 * 60L + 29L))))
    }

    @Test fun noLengthsMeansNoTime() {
        assertNull(PlaylistLook.totalTime(emptyList()))
        assertNull(PlaylistLook.totalTime(listOf(t("x", "x", "y", 0L), t("z", "z", "y", -1L))))
    }

    // ---- covers ----

    @Test fun mosaicNeedsFourDifferentCovers() {
        assertNull(PlaylistLook.mosaicUrls(listOf("1", "2", "2", "3", null)))
        assertEquals(listOf("1", "2", "3", "4"), PlaylistLook.mosaicUrls(listOf("1", "", "2", "1", "3", "4", "5")))
    }

    @Test fun typeTakesOneWordOrOneEmoji() {
        assertEquals("Chill", PlaylistLook.typeText("  Chill evening "))
        assertEquals("🔥", PlaylistLook.typeText("🔥"))
        assertEquals("", PlaylistLook.typeText("   "))
        assertEquals(PlaylistLook.TYPE_MAX_CHARS, PlaylistLook.typeText("abcdefghijklmnopqrstuvwxyz").length)
    }

    @Test fun typeColoursAlwaysOfferAtLeastOne() {
        assertEquals(listOf(PlaylistLook.DEFAULT_TYPE_COLOR), PlaylistLook.typeColors(emptyList()))
        assertTrue(PlaylistLook.typeColors(listOf(0xFFE53935.toInt(), 0xFF1E88E5.toInt())).size >= 2)
    }

    // ---- which controls show ----

    @Test fun ownPlaylistsGetEverything() {
        val c = PlaylistLook.controls(ListType.OWN)
        assertTrue(c.play && c.shuffle && c.search && c.more && c.rename && c.share && c.addSongs && c.reorder)
    }

    @Test fun albumsAndLikedGetPlayShuffleAndSearchOnly() {
        for (type in listOf(ListType.ALBUM, ListType.LIKED)) {
            val c = PlaylistLook.controls(type)
            assertTrue("$type", c.play && c.shuffle && c.search)
            assertFalse("$type", c.more || c.rename || c.share || c.addSongs || c.reorder)
        }
    }

    @Test fun likedArtistsHasNoSongPills() {
        val c = PlaylistLook.controls(ListType.LIKED_ARTISTS)
        assertFalse(c.play || c.shuffle || c.search || c.more || c.reorder)
    }

    @Test fun dragOnlyInYourOwnOrderWithNoSearch() {
        assertTrue(PlaylistLook.canDrag(ListType.OWN, Sort.CUSTOM, ""))
        assertFalse(PlaylistLook.canDrag(ListType.OWN, Sort.TITLE, ""))
        assertFalse(PlaylistLook.canDrag(ListType.OWN, Sort.CUSTOM, "bloom"))
        assertFalse(PlaylistLook.canDrag(ListType.ALBUM, Sort.CUSTOM, null))
        assertFalse(PlaylistLook.canDrag(ListType.LIKED, Sort.CUSTOM, null))
    }
}
