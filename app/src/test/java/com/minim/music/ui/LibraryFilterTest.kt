package com.minim.music.ui

import com.minim.music.data.model.Playlist
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryFilterTest {

    private fun pl(id: Long, name: String) = GridTile.PlaylistTile(Playlist(id = id, name = name, trackCount = 0), null)

    private val tiles = listOf(
        GridTile.Liked(12, null),
        GridTile.RecentsTile(50),
        GridTile.OfflineTile(3),
        pl(1, "Road Trip"),
        pl(2, "Chill Evening Mix"),
        pl(3, "Arijit Favourites"),
        pl(4, "Café Hindi"),
        pl(5, "Mix Tape 2026")
    )

    private fun names(list: List<GridTile>) = list.map { (it as GridTile.PlaylistTile).playlist.name }

    @Test
    fun nothingTypedShowsEverything() {
        assertEquals(tiles, LibraryFilter.apply(tiles, ""))
        assertEquals(tiles, LibraryFilter.apply(tiles, "   "))
        assertFalse(LibraryFilter.isActive("  "))
    }

    @Test
    fun onlyPlaylistsMatchAndPinnedTilesHide() {
        val out = LibraryFilter.apply(tiles, "mix")
        assertEquals(listOf("Mix Tape 2026", "Chill Evening Mix"), names(out))
        assertTrue(LibraryFilter.isActive("mix"))
        // "liked" is a pinned tile, not a playlist: nothing matches
        assertTrue(LibraryFilter.apply(tiles, "liked").isEmpty())
    }

    @Test
    fun caseAccentsAndWordsDontMatter() {
        assertEquals(listOf("Café Hindi"), names(LibraryFilter.apply(tiles, "CAFE")))
        assertEquals(listOf("Chill Evening Mix"), names(LibraryFilter.apply(tiles, "chill mix")))
        assertEquals(listOf("Road Trip"), names(LibraryFilter.apply(tiles, "roadtrip")))
        assertTrue(LibraryFilter.apply(tiles, "zzz").isEmpty())
    }
}
