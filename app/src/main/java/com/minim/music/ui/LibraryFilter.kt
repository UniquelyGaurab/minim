package com.minim.music.ui

import com.minim.music.util.SafeRegex
import java.text.Normalizer
import java.util.Locale

/**
 * 5.8.5 u57: "Search Playlists" in Library. With nothing typed the grid is as always (Liked,
 * Recents, Offline, then your playlists). With a name typed only your playlists whose name
 * matches show: case, accents, spaces and punctuation don't matter, and every word you type has to
 * be in the name ("chill mix" finds "Chill Evening Mix"). Names that start with what you typed
 * come first, the rest keep their usual order.
 */
object LibraryFilter {

    fun apply(tiles: List<GridTile>, query: String): List<GridTile> {
        val words = words(query)
        if (words.isEmpty()) return tiles
        val whole = words.joinToString("")
        val matching = tiles.filterIsInstance<GridTile.PlaylistTile>().filter { tile ->
            val name = norm(tile.playlist.name)
            words.all { name.contains(it) }
        }
        return matching.sortedBy { if (norm(it.playlist.name).startsWith(whole)) 0 else 1 }
    }

    /** True when something is typed (pinned tiles hide, the empty note can show). */
    fun isActive(query: String): Boolean = words(query).isNotEmpty()

    private fun words(q: String): List<String> =
        q.trim().split(SafeRegex.of("\\s+")).map { norm(it) }.filter { it.isNotEmpty() }

    /** Lower case, accents dropped, letters and digits only (any script). */
    internal fun norm(s: String): String =
        Normalizer.normalize(s.lowercase(Locale.ROOT), Normalizer.Form.NFD)
            .replace(SafeRegex.of("\\p{Mn}+"), "")
            .filter { it.isLetterOrDigit() }
}
