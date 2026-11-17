package com.minim.music.ui

import java.util.EnumMap

/**
 * 6.8.0 b72: the filter pills under the search bar (All · Songs · Artists · Albums · Tapes).
 * All is today's results, unchanged. Songs, Artists and Albums search only that type on YouTube
 * Music; Tapes searches regular YouTube videos, the same way the Tapes tab and importing do.
 */
enum class SearchPill { ALL, SONGS, ARTISTS, ALBUMS, TAPES }

object SearchFilters {

    /** In the order they sit in the row. */
    val ORDER: List<SearchPill> = listOf(
        SearchPill.ALL, SearchPill.SONGS, SearchPill.ARTISTS, SearchPill.ALBUMS, SearchPill.TAPES
    )

    /** NewPipe's YouTube search filter names (YoutubeSearchQueryHandlerFactory). */
    const val MUSIC_SONGS = "music_songs"
    const val MUSIC_ARTISTS = "music_artists"
    const val MUSIC_ALBUMS = "music_albums"
    const val VIDEOS = "videos"

    /**
     * The row shows only once there's something typed or something found. With an empty bar,
     * Search still shows Discover.
     */
    fun rowVisible(query: String, hasResults: Boolean): Boolean = query.isNotBlank() || hasResults

    /** The search filter a pill uses. All runs today's combined search, so it has none of its own. */
    fun contentFilter(pill: SearchPill): String? = when (pill) {
        SearchPill.ALL -> null
        SearchPill.SONGS -> MUSIC_SONGS
        SearchPill.ARTISTS -> MUSIC_ARTISTS
        SearchPill.ALBUMS -> MUSIC_ALBUMS
        SearchPill.TAPES -> VIDEOS
    }

    /** Tapes are regular YouTube videos; everything else comes from YouTube Music. */
    fun usesYouTubeMusic(pill: SearchPill): Boolean = pill != SearchPill.TAPES

    /** Trimmed, runs of spaces made one, lower case (no regex: SafeRegex rule). */
    fun norm(query: String): String {
        val sb = StringBuilder(query.length)
        var space = false
        for (ch in query.trim()) {
            if (ch.isWhitespace()) { space = true; continue }
            if (space && sb.isNotEmpty()) sb.append(' ')
            space = false
            sb.append(ch)
        }
        return sb.toString().lowercase()
    }
}

/** One pill's answer for one query. */
sealed class PillState<out T> {
    object Loading : PillState<Nothing>()
    data class Done<T>(val items: List<T>) : PillState<T>()
    object Failed : PillState<Nothing>()
}

/**
 * 6.8.0 b72: which pill is on, and each pill's results for the query on screen, so switching
 * pills back and forth doesn't search again. Every fetch gets a ticket; a result whose ticket
 * isn't the latest for that pill and query is thrown away, so stale results never show.
 *
 * - Typing ([onTyped]) keeps the selected pill and searches that type.
 * - A new search ([onSubmitted] with a different query, or the bar emptied) goes back to All.
 */
class SearchFilterState {

    var selected: SearchPill = SearchPill.ALL
        private set

    /** The (normalised) query the cached results belong to. */
    var query: String = ""
        private set

    private val results = EnumMap<SearchPill, PillState<Any>>(SearchPill::class.java)
    private val tickets = EnumMap<SearchPill, Int>(SearchPill::class.java)
    private var nextTicket = 0

    /** Live search from typing: keeps the pill; a changed query drops the old results. */
    fun onTyped(raw: String) {
        val q = SearchFilters.norm(raw)
        if (q != query) {
            query = q
            dropResults()
        }
        if (q.isEmpty()) selected = SearchPill.ALL
    }

    /** The search key, the arrow or a suggestion: a different query is a new search, back to All. */
    fun onSubmitted(raw: String) {
        val q = SearchFilters.norm(raw)
        if (q != query) {
            query = q
            dropResults()
            selected = SearchPill.ALL
        }
    }

    /** The bar was emptied. */
    fun onCleared() {
        query = ""
        dropResults()
        selected = SearchPill.ALL
    }

    /** A pill was tapped. True when it changed. */
    fun select(pill: SearchPill): Boolean {
        if (pill == selected) return false
        selected = pill
        return true
    }

    /** What [pill] has for the current query (null: never asked). */
    @Suppress("UNCHECKED_CAST")
    fun <T> stateOf(pill: SearchPill): PillState<T>? = results[pill] as PillState<T>?

    /** True when [pill] needs a search for the current query (nothing cached and nothing on its way). */
    fun needsFetch(pill: SearchPill): Boolean = query.isNotEmpty() && results[pill] == null

    /** A search for [pill] starts: it's loading, and its ticket replaces any older one. */
    fun begin(pill: SearchPill): Int {
        val t = ++nextTicket
        tickets[pill] = t
        results[pill] = PillState.Loading
        return t
    }

    /**
     * A search came back. Kept only when [ticket] is still the latest for [pill] and [forQuery]
     * is still the query on screen. Returns true when it was kept (and so may be shown, if
     * [pill] is the selected one).
     */
    fun deliver(pill: SearchPill, ticket: Int, forQuery: String, state: PillState<Any>): Boolean {
        if (tickets[pill] != ticket) return false
        if (SearchFilters.norm(forQuery) != query) return false
        results[pill] = state
        return true
    }

    /** Fills a pill straight in (All's songs come from the same music_songs search as Songs). */
    fun seed(pill: SearchPill, forQuery: String, items: List<Any>) {
        if (SearchFilters.norm(forQuery) != query) return
        val cur = results[pill]
        if (cur is PillState.Done<*>) return
        tickets[pill] = ++nextTicket
        results[pill] = PillState.Done(items)
    }

    /** A search that was cancelled before it came back: ask again next time. */
    fun forget(pill: SearchPill, ticket: Int) {
        if (tickets[pill] == ticket && results[pill] === PillState.Loading) results.remove(pill)
    }

    private fun dropResults() {
        results.clear()
        // Old tickets stop matching, so anything still on its way is thrown away
        tickets.clear()
    }
}
