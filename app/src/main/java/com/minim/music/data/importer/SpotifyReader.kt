package com.minim.music.data.importer

import com.minim.music.util.SafeRegex
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/**
 * 5.3.0: downloads a public Spotify playlist's song list with no login and no key.
 * See [SpotifyPages] for where each part comes from.
 */
class SpotifyReader(private val http: OkHttpClient) {

    data class Result(
        val name: String,
        val songs: List<SourceSong>,
        /** How many songs Spotify says the playlist has; 0 when unknown. */
        val total: Int,
        /** True when songs past the first 100 couldn't be read (Spotify changed its page). */
        val stoppedEarly: Boolean
    )

    class SpotifyException(val why: Why) : Exception(why.name)

    enum class Why { NOT_PUBLIC, NETWORK }

    /** Follows a spotify.link short link to the open.spotify.com link it stands for. */
    suspend fun resolveShort(url: String): String? = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder().url(url).header("User-Agent", UA).build()
            http.newCall(req).execute().use { resp ->
                val final = resp.request.url.toString()
                if (final.contains("open.spotify.com")) return@withContext final
                // app.link pages put the real link in the page instead of redirecting
                val body = resp.body?.string().orEmpty()
                SafeRegex.of("""https://open\.spotify\.com/(?:playlist|album)/[A-Za-z0-9]+""").find(body)?.value
            }
        } catch (_: Exception) {
            null
        }
    }

    suspend fun read(kind: String, id: String, max: Int, onProgress: (Int) -> Unit): Result =
        withContext(Dispatchers.IO) {
            val html = get("https://open.spotify.com/embed/$kind/$id", null)
                ?: throw SpotifyException(Why.NOT_PUBLIC)
            val embed = SpotifyPages.parseEmbed(html) ?: throw SpotifyException(Why.NOT_PUBLIC)
            if (embed.songs.isEmpty()) throw SpotifyException(Why.NOT_PUBLIC)
            val songs = ArrayList(embed.songs.take(max))
            onProgress(songs.size)
            var total = 0
            var stoppedEarly = false
            // Albums fit in the embed page. A playlist showing a full first 100 may have more.
            if (kind == "playlist" && embed.songs.size >= SpotifyPages.EMBED_LIMIT && songs.size < max) {
                var offset = SpotifyPages.EMBED_LIMIT
                while (songs.size < max) {
                    val page = embed.token?.let { fetchPage(id, offset, it) }
                    if (page == null) {
                        stoppedEarly = true
                        break
                    }
                    total = page.total
                    if (page.songs.isEmpty()) break
                    songs.addAll(page.songs.take(max - songs.size))
                    onProgress(songs.size)
                    offset += PAGE
                    if (offset >= page.total) break
                }
            }
            if (stoppedEarly && total == 0) {
                total = try {
                    get("https://open.spotify.com/playlist/$id", null)?.let { SpotifyPages.parseTotalFromPage(it) } ?: 0
                } catch (_: SpotifyException) {
                    0
                }
            }
            Result(embed.name, songs, total, stoppedEarly)
        }

    private fun fetchPage(id: String, offset: Int, token: String): SpotifyPages.Page? {
        for (q in QUERIES) {
            val url = "https://api-partner.spotify.com/pathfinder/v1/query".toHttpUrl().newBuilder()
                .addQueryParameter("operationName", "fetchPlaylist")
                .addQueryParameter(
                    "variables",
                    """{"uri":"spotify:playlist:$id","offset":$offset,"limit":$PAGE${q.extraVariables}}"""
                )
                .addQueryParameter("extensions", """{"persistedQuery":{"version":1,"sha256Hash":"${q.hash}"}}""")
                .build().toString()
            val body = try {
                get(url, token)
            } catch (_: SpotifyException) {
                null
            } ?: continue
            SpotifyPages.parsePage(body)?.let { return it }
        }
        return null
    }

    /** Body of a 200 answer, null for 4xx (private, gone). Network trouble throws. */
    private fun get(url: String, token: String?): String? {
        val b = Request.Builder().url(url).header("User-Agent", UA).header("Accept-Language", "en")
        if (token != null) {
            b.header("Authorization", "Bearer $token").header("app-platform", "WebPlayer")
        }
        try {
            http.newCall(b.build()).execute().use { resp ->
                if (resp.isSuccessful) return resp.body?.string()
                if (resp.code in 400..499) return null
                throw SpotifyException(Why.NETWORK)
            }
        } catch (e: IOException) {
            throw SpotifyException(Why.NETWORK)
        }
    }

    private class Query(val hash: String, val extraVariables: String)

    companion object {
        private const val PAGE = 100
        private const val UA =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Mobile Safari/537.36"

        // The web player's own playlist query, as checked in October 2026. Two known versions:
        // if Spotify retires both, the import keeps the first 100 songs and says so.
        private val QUERIES = listOf(
            Query("19ff1327c29e99c208c86d7a9d8f1929cfdf3d3202a0ff4253c821f1901aa94d", ""),
            Query("bb67e0af06e8d6f52b531f97468ee4acd44cd0f82b988e15c2ea47b1148efc77", ""","enableWatchFeedEntrypoint":false""")
        )
    }
}
