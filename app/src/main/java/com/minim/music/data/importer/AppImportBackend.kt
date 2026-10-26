package com.minim.music.data.importer

import com.minim.music.data.model.SourceResult
import com.minim.music.data.source.MusicSourceAdapter
import okhttp3.OkHttpClient

/** 5.3.0: the real [ImportBackend]: NewPipe for YouTube, Spotify's public pages for Spotify. */
class AppImportBackend(
    private val source: MusicSourceAdapter,
    http: OkHttpClient
) : ImportBackend {

    private val spotify = SpotifyReader(http)

    override suspend fun readYouTube(url: String, max: Int, onProgress: (Int) -> Unit): ImportBackend.YouTubeList =
        when (val r = source.fetchPlaylistForImport(url, max, onProgress)) {
            is SourceResult.Success -> ImportBackend.YouTubeList(r.data.name, r.data.tracks, r.data.total, r.data.cutAtLimit)
            is SourceResult.Error -> throw ImportFailure(
                if (r.message.startsWith("Network")) "Couldn't reach YouTube. Check your connection and try again."
                else "Minim can't read this YouTube playlist. It may be private or deleted."
            )
        }

    override suspend fun readSpotify(kind: String, id: String, max: Int, onProgress: (Int) -> Unit) =
        spotify.read(kind, id, max, onProgress)

    override suspend fun resolveSpotifyShort(url: String): String? = spotify.resolveShort(url)

    override suspend fun search(query: String, videos: Boolean) = source.searchForImport(query, videos)
}
