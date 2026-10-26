package com.minim.music.data.importer

import com.minim.music.util.SafeRegex
import com.minim.music.data.greetings.MiniJson

/**
 * 5.3.0: reads a public Spotify playlist (or album) without the Spotify Web API, which since
 * February 2026 needs Premium, allows 5 users and no longer returns public playlist items.
 *
 * - The embed page (open.spotify.com/embed/playlist/ID) carries the name and the first 100 songs
 *   in its __NEXT_DATA__ JSON, plus an anonymous token the page itself uses.
 * - Songs after the first 100 come from the same paged query Spotify's own web player runs
 *   (fetchPlaylist, 100 at a time). If Spotify changes that query, the import keeps the first 100
 *   and says clearly how many it couldn't read.
 *
 * Only the parsing lives here (pure Kotlin, unit tested); [SpotifyReader] does the downloads.
 */
object SpotifyPages {

    const val EMBED_LIMIT = 100
    private const val MAX_JSON = 4 * 1024 * 1024

    data class Embed(
        val name: String,
        val kind: String,
        val songs: List<SourceSong>,
        val token: String?
    )

    data class Page(val songs: List<SourceSong>, val total: Int)

    private val NEXT_DATA = SafeRegex.of("""<script[^>]*id="__NEXT_DATA__"[^>]*>(.*?)</script>""", RegexOption.DOT_MATCHES_ALL)

    /** Null when the page has no playlist in it (private, deleted, or not a real ID). */
    fun parseEmbed(html: String): Embed? {
        val json = NEXT_DATA.find(html)?.groupValues?.get(1) ?: return null
        val root = try {
            MiniJson.parse(json, MAX_JSON)
        } catch (_: Exception) {
            return null
        }
        val pageProps = path(root, "props", "pageProps")
        val entity = path(pageProps, "state", "data", "entity") as? Map<*, *> ?: return null
        val list = entity["trackList"] as? List<*> ?: return null
        val songs = list.mapNotNull { item ->
            val m = item as? Map<*, *> ?: return@mapNotNull null
            val type = (m["entityType"] as? String) ?: "track"
            if (type != "track") return@mapNotNull null // podcast episodes in a playlist
            val title = (m["title"] as? String)?.trim().orEmpty()
            if (title.isEmpty()) return@mapNotNull null
            val artists = (m["subtitle"] as? String).orEmpty()
                .split(",").map { it.trim().replace('\u00a0', ' ') }.filter { it.isNotEmpty() }
            SourceSong(title, artists, (m["duration"] as? Number)?.toLong() ?: 0L)
        }
        val name = ((entity["name"] as? String) ?: (entity["title"] as? String)).orEmpty().trim()
        val kind = (entity["type"] as? String).orEmpty()
        val token = path(pageProps, "state", "settings", "session", "accessToken") as? String
        return Embed(name, kind, songs, token?.takeIf { it.isNotBlank() })
    }

    /** One page of the web player's playlist query. Null when the answer isn't one. */
    fun parsePage(json: String): Page? {
        val root = try {
            MiniJson.parse(json, MAX_JSON)
        } catch (_: Exception) {
            return null
        }
        val content = path(root, "data", "playlistV2", "content") as? Map<*, *> ?: return null
        val items = content["items"] as? List<*> ?: return null
        val total = (content["totalCount"] as? Number)?.toInt() ?: return null
        val songs = items.mapNotNull { item ->
            val data = path(item, "itemV2", "data") as? Map<*, *> ?: return@mapNotNull null
            if (data["__typename"] != "Track") return@mapNotNull null
            val title = (data["name"] as? String)?.trim().orEmpty()
            if (title.isEmpty()) return@mapNotNull null
            val artists = ((path(data, "artists", "items") as? List<*>) ?: emptyList<Any?>()).mapNotNull {
                (path(it, "profile", "name") as? String)?.trim()?.takeIf { n -> n.isNotEmpty() }
            }
            val ms = (path(data, "trackDuration", "totalMilliseconds") as? Number)?.toLong() ?: 0L
            SourceSong(title, artists, ms)
        }
        return Page(songs, total)
    }

    private val INITIAL_STATE = SafeRegex.of("""<script[^>]*id="initialState"[^>]*>(.*?)</script>""", RegexOption.DOT_MATCHES_ALL)
    private val TOTAL = SafeRegex.of(""""totalCount"\s*:\s*(\d+)""")

    /**
     * How many songs the playlist has, from the regular open.spotify.com/playlist/ID page (its
     * initialState is base64 JSON). Used only to say "first 100 of 150" when the rest can't be read.
     */
    fun parseTotalFromPage(html: String): Int {
        val raw = INITIAL_STATE.find(html)?.groupValues?.get(1)?.trim() ?: return 0
        val json = try {
            String(java.util.Base64.getDecoder().decode(raw), Charsets.UTF_8)
        } catch (_: Exception) {
            raw
        }
        return TOTAL.find(json)?.groupValues?.get(1)?.toIntOrNull() ?: 0
    }

    private fun path(root: Any?, vararg keys: String): Any? {
        var cur = root
        for (k in keys) cur = (cur as? Map<*, *>)?.get(k) ?: return null
        return cur
    }
}
