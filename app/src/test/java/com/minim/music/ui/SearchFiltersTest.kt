package com.minim.music.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 6.8.0 b72: the search filter pills: when the row shows, All on a new search, cache, stale guard. */
class SearchFiltersTest {

    private fun main(): File = listOf(File("src/main"), File("app/src/main")).first { it.isDirectory }

    @Test fun rowHiddenOnEmptyBarSoDiscoverShows() {
        assertFalse(SearchFilters.rowVisible("", hasResults = false))
        assertFalse(SearchFilters.rowVisible("   ", hasResults = false))
    }

    @Test fun rowShowsWithQueryOrResults() {
        assertTrue(SearchFilters.rowVisible("arijit", hasResults = false))
        assertTrue(SearchFilters.rowVisible("arijit", hasResults = true))
        assertTrue(SearchFilters.rowVisible("", hasResults = true))
    }

    @Test fun pillOrderHasNoYoursPill() {
        assertEquals(
            listOf(SearchPill.ALL, SearchPill.SONGS, SearchPill.ARTISTS, SearchPill.ALBUMS, SearchPill.TAPES),
            SearchFilters.ORDER
        )
    }

    @Test fun eachPillUsesItsOwnFilter() {
        assertNull(SearchFilters.contentFilter(SearchPill.ALL))
        assertEquals("music_songs", SearchFilters.contentFilter(SearchPill.SONGS))
        assertEquals("music_artists", SearchFilters.contentFilter(SearchPill.ARTISTS))
        assertEquals("music_albums", SearchFilters.contentFilter(SearchPill.ALBUMS))
        assertEquals("videos", SearchFilters.contentFilter(SearchPill.TAPES))
    }

    @Test fun tapesUseYouTubeNotMusic() {
        assertFalse(SearchFilters.usesYouTubeMusic(SearchPill.TAPES))
        for (p in listOf(SearchPill.SONGS, SearchPill.ARTISTS, SearchPill.ALBUMS)) assertTrue(SearchFilters.usesYouTubeMusic(p))
        // And the source: searchTapes asks NewPipe for YouTube videos, never a music_ filter
        val src = File(main(), "java/com/minim/music/data/source/MusicSourceAdapter.kt").readText()
        val body = src.substringAfter("suspend fun searchTapes(").substringBefore("\n    }\n")
        assertTrue(body.contains("YoutubeSearchQueryHandlerFactory.VIDEOS"))
        assertFalse(body.contains("MUSIC_"))
        val albums = src.substringAfter("suspend fun searchAlbums(").substringBefore("\n    }\n")
        assertTrue(albums.contains("YoutubeSearchQueryHandlerFactory.MUSIC_ALBUMS"))
    }

    @Test fun newSearchGoesBackToAll() {
        val st = SearchFilterState()
        st.onSubmitted("arijit")
        st.select(SearchPill.ALBUMS)
        st.onSubmitted("anirudh")
        assertEquals(SearchPill.ALL, st.selected)
    }

    @Test fun sameSearchAgainKeepsThePill() {
        val st = SearchFilterState()
        st.onSubmitted("arijit")
        st.select(SearchPill.TAPES)
        st.onSubmitted("  Arijit ")
        assertEquals(SearchPill.TAPES, st.selected)
    }

    @Test fun clearingTheBarGoesBackToAll() {
        val st = SearchFilterState()
        st.onTyped("ari")
        st.select(SearchPill.ARTISTS)
        st.onCleared()
        assertEquals(SearchPill.ALL, st.selected)
        assertEquals("", st.query)
    }

    @Test fun typingKeepsThePillAndSearchesThatType() {
        val st = SearchFilterState()
        st.onTyped("ari")
        st.select(SearchPill.ALBUMS)
        st.onTyped("arij")
        assertEquals(SearchPill.ALBUMS, st.selected)
        assertTrue(st.needsFetch(SearchPill.ALBUMS))
    }

    @Test fun resultsAreCachedPerPillForTheQuery() {
        val st = SearchFilterState()
        st.onSubmitted("arijit")
        val t = st.begin(SearchPill.ALBUMS)
        assertFalse(st.needsFetch(SearchPill.ALBUMS))
        assertTrue(st.deliver(SearchPill.ALBUMS, t, "arijit", PillState.Done(listOf("a", "b"))))
        st.select(SearchPill.SONGS)
        st.select(SearchPill.ALBUMS)
        // Switching back: still there, no new search
        assertFalse(st.needsFetch(SearchPill.ALBUMS))
        assertEquals(listOf("a", "b"), (st.stateOf<String>(SearchPill.ALBUMS) as PillState.Done).items)
    }

    @Test fun newQueryDropsTheCache() {
        val st = SearchFilterState()
        st.onSubmitted("arijit")
        val t = st.begin(SearchPill.TAPES)
        st.deliver(SearchPill.TAPES, t, "arijit", PillState.Done(listOf("x")))
        st.onTyped("arijit singh")
        assertNull(st.stateOf<Any>(SearchPill.TAPES))
        assertTrue(st.needsFetch(SearchPill.TAPES))
    }

    @Test fun resultForAnOldQueryIsThrownAway() {
        val st = SearchFilterState()
        st.onTyped("ari")
        val t = st.begin(SearchPill.ARTISTS)
        st.onTyped("arijit")
        assertFalse(st.deliver(SearchPill.ARTISTS, t, "ari", PillState.Done(listOf("old"))))
        assertNull(st.stateOf<Any>(SearchPill.ARTISTS))
    }

    @Test fun olderSearchForTheSamePillLoses() {
        val st = SearchFilterState()
        st.onTyped("arijit")
        val first = st.begin(SearchPill.SONGS)
        val second = st.begin(SearchPill.SONGS)
        assertTrue(st.deliver(SearchPill.SONGS, second, "arijit", PillState.Done(listOf("new"))))
        assertFalse(st.deliver(SearchPill.SONGS, first, "arijit", PillState.Done(listOf("old"))))
        assertEquals(listOf("new"), (st.stateOf<String>(SearchPill.SONGS) as PillState.Done).items)
    }

    @Test fun cancelledSearchIsAskedAgain() {
        val st = SearchFilterState()
        st.onTyped("arijit")
        val t = st.begin(SearchPill.ALBUMS)
        st.forget(SearchPill.ALBUMS, t)
        assertTrue(st.needsFetch(SearchPill.ALBUMS))
    }

    @Test fun failureIsKeptPerPill() {
        val st = SearchFilterState()
        st.onTyped("arijit")
        val t = st.begin(SearchPill.TAPES)
        assertTrue(st.deliver(SearchPill.TAPES, t, "arijit", PillState.Failed))
        assertTrue(st.stateOf<Any>(SearchPill.TAPES) is PillState.Failed)
        assertNull(st.stateOf<Any>(SearchPill.ALBUMS))
    }

    @Test fun allSeedsSongsButNeverOverAnotherQuery() {
        val st = SearchFilterState()
        st.onTyped("arijit")
        st.seed(SearchPill.SONGS, "ari", listOf("stale"))
        assertNull(st.stateOf<Any>(SearchPill.SONGS))
        st.seed(SearchPill.SONGS, "arijit", listOf("s"))
        assertFalse(st.needsFetch(SearchPill.SONGS))
    }

    @Test fun emptyQueryNeverFetches() {
        val st = SearchFilterState()
        assertFalse(st.needsFetch(SearchPill.SONGS))
    }

    @Test fun searchLayoutHasTheFivePills() {
        val xml = File(main(), "res/layout/view_search.xml").readText()
        for (id in listOf("all", "songs", "artists", "albums", "tapes")) assertTrue(xml.contains("@+id/search_filter_$id"))
        assertFalse(xml.contains("search_filter_yours"))
        // Hidden until there's a query (Discover shows on an empty bar)
        val scroll = xml.substringAfter("@+id/search_filter_scroll").substringBefore(">")
        assertTrue(scroll.contains("android:visibility=\"gone\""))
    }
}
