package com.minim.music.data.source

import android.util.LruCache
import com.minim.music.data.model.Album
import com.minim.music.data.model.ArtistCountKind
import com.minim.music.data.model.ArtistPage
import com.minim.music.data.model.ArtistRef
import com.minim.music.data.model.SourceResult
import com.minim.music.data.model.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.schabi.newpipe.extractor.Image
import org.schabi.newpipe.extractor.InfoItem
import org.schabi.newpipe.extractor.channel.ChannelInfo
import org.schabi.newpipe.extractor.channel.ChannelInfoItem
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabInfo
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabs
import org.schabi.newpipe.extractor.playlist.PlaylistInfoItem
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeSearchQueryHandlerFactory
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.stream.StreamType
import java.io.IOException
import java.util.Locale

class MusicSourceAdapter(okHttpClient: OkHttpClient) {

    private data class CachedStreams(
        val options: List<StreamOption>,
        val fallbackUrl: String?,
        val expiresAtMs: Long
    )

    private val streamCache = LruCache<String, CachedStreams>(60)

    init {
        synchronized(INIT_LOCK) {
            if (!isInitialized) {
                NewPipe.init(
                    OkHttpNewPipeDownloader(okHttpClient),
                    Localization.fromLocale(Locale.US),
                    ContentCountry.DEFAULT
                )
                isInitialized = true
            }
        }
    }

    suspend fun searchTracks(query: String): SourceResult<List<Track>> =
        withContext(Dispatchers.IO) {
            val cleanQuery = query.trim()
            if (cleanQuery.isEmpty()) {
                return@withContext SourceResult.Success(emptyList())
            }

            try {
                val ytService = ServiceList.YouTube
                var tracks = executeSearchFilter(
                    ytService.searchQHFactory.fromQuery(
                        cleanQuery,
                        listOf(YoutubeSearchQueryHandlerFactory.MUSIC_SONGS),
                        ""
                    )
                )
                if (tracks.isEmpty()) {
                    tracks = executeSearchFilter(
                        ytService.searchQHFactory.fromQuery(
                            cleanQuery,
                            listOf(YoutubeSearchQueryHandlerFactory.VIDEOS),
                            ""
                        )
                    )
                }
                SourceResult.Success(tracks)
            } catch (e: IOException) {
                SourceResult.Error("Network unavailable or timed out. Check connection.", e)
            } catch (e: Exception) {
                SourceResult.Error("Search extraction failed: ${e.localizedMessage ?: "Unknown error"}", e)
            }
        }

    private fun executeSearchFilter(
        queryHandler: org.schabi.newpipe.extractor.linkhandler.SearchQueryHandler
    ): List<Track> {
        val extractor = ServiceList.YouTube.getSearchExtractor(queryHandler)
        extractor.fetchPage()
        val items = extractor.initialPage.items
        val results = ArrayList<Track>(items.size)

        for (item in items) {
            if (item is StreamInfoItem) toTrack(item)?.let { results.add(it) }
        }
        return results
    }

    /** One search / mix / related item as a playable song (null for live streams, shorts etc.). */
    private fun toTrack(item: StreamInfoItem): Track? {
        if (item.streamType != StreamType.AUDIO_STREAM &&
            item.streamType != StreamType.VIDEO_STREAM
        ) {
            return null
        }
        val id = extractVideoId(item.url) ?: return null
        val thumb = item.thumbnails
            .minByOrNull { kotlin.math.abs(it.width - 240) }
            ?.url
            ?: item.thumbnails.firstOrNull()?.url.orEmpty()
        val duration = if (item.duration > 0) item.duration else 0L
        return Track(
            id = id,
            title = item.name?.ifBlank { "Untitled" } ?: "Untitled",
            artist = item.uploaderName?.removeSuffix(" - Topic")?.ifBlank { "Unknown Artist" }
                ?: "Unknown Artist",
            durationSec = duration,
            thumbnailUrl = thumb,
            sourceUrl = "https://www.youtube.com/watch?v=$id"
        )
    }

    // ---- v4.3: radio / mix ----------------------------------------------------------------------

    private data class CachedMix(val tracks: List<Track>, val atMs: Long)
    private val mixCache = LruCache<String, CachedMix>(48)

    /**
     * Songs similar to [videoId]: the YouTube Music mix (RDAMVM{id}), then the YouTube mix
     * (RD{id}), then the watch page's related songs. The seed itself is left out.
     * Results are kept for 30 minutes. Never throws.
     */
    suspend fun fetchRadio(videoId: String): SourceResult<List<Track>> = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        mixCache.get(videoId)?.let { cached ->
            if (now - cached.atMs < MIX_CACHE_MS) return@withContext SourceResult.Success(cached.tracks)
        }
        var lastError: Throwable? = null
        for (listId in listOf("RDAMVM$videoId", "RD$videoId")) {
            try {
                val url = "https://www.youtube.com/watch?v=$videoId&list=$listId"
                val extractor = ServiceList.YouTube.getPlaylistExtractor(url)
                extractor.fetchPage()
                val page = extractor.initialPage
                val tracks = ArrayList<Track>()
                page.items.mapNotNullTo(tracks) { toTrack(it) }
                val clean = tracks.filter { it.id != videoId }.distinctBy { it.id }
                if (clean.size >= 3) {
                    mixCache.put(videoId, CachedMix(clean, now))
                    return@withContext SourceResult.Success(clean)
                }
            } catch (e: Exception) {
                lastError = e
            }
        }
        try {
            val info = StreamInfo.getInfo(ServiceList.YouTube, "https://www.youtube.com/watch?v=$videoId")
            val clean = info.relatedItems.orEmpty()
                .filterIsInstance<StreamInfoItem>()
                .mapNotNull { toTrack(it) }
                .filter { it.id != videoId }
                .distinctBy { it.id }
            if (clean.isNotEmpty()) {
                mixCache.put(videoId, CachedMix(clean, now))
                return@withContext SourceResult.Success(clean)
            }
        } catch (e: Exception) {
            lastError = e
        }
        SourceResult.Error("No similar songs found.", lastError)
    }

    /**
     * v4.9.5: resolves a song to a playable stream.
     *
     * - [excludedItags]: formats that already got a 403 for this song (fallback to another format)
     * - [preferItag]: keep this format if it is still offered (a link refresh mid-song)
     * - [forceFresh]: skip the cache and ask YouTube again (new link, new n / cpn)
     */
    @Throws(IOException::class)
    fun resolveStreamBlocking(
        videoIdOrUrl: String,
        excludedItags: Set<Int> = emptySet(),
        preferItag: Int? = null,
        forceFresh: Boolean = false
    ): ResolvedStream {
        val videoId = extractVideoId(videoIdOrUrl) ?: videoIdOrUrl
        val now = System.currentTimeMillis()
        val cached = if (forceFresh) null else streamCache.get(videoId)?.takeIf { it.expiresAtMs > now }
        val entry = cached ?: fetchStreams(videoId, videoIdOrUrl, now).also { streamCache.put(videoId, it) }

        val pick = StreamSelection.pick(entry.options, excludedItags, preferItag)
        if (pick != null) {
            return ResolvedStream(
                videoId = videoId,
                url = pick.url,
                itag = pick.itag,
                contentLength = pick.contentLength,
                chunked = YoutubeStreamRequest.isVideoPlaybackUrl(pick.url),
                otherOptions = StreamSelection.remaining(entry.options, excludedItags, pick.itag)
            )
        }
        if (excludedItags.isNotEmpty() && StreamSelection.order(entry.options).isNotEmpty()) {
            throw IOException("YouTube refused every audio format of this song.")
        }
        entry.fallbackUrl?.let { url ->
            return ResolvedStream(videoId, url, -1, -1L, chunked = false, otherOptions = 0)
        }
        throw IOException("No compatible audio stream available for this track.")
    }

    /** Old entry point, kept for anything that only needs a URL. */
    @Throws(IOException::class)
    fun resolveAudioStreamUrlBlocking(videoIdOrUrl: String): String =
        resolveStreamBlocking(videoIdOrUrl).url

    @Throws(IOException::class)
    private fun fetchStreams(videoId: String, videoIdOrUrl: String, now: Long): CachedStreams {
        val watchUrl = if (videoIdOrUrl.startsWith("http")) {
            videoIdOrUrl
        } else {
            "https://www.youtube.com/watch?v=$videoId"
        }
        return try {
            val streamInfo = StreamInfo.getInfo(ServiceList.YouTube, watchUrl)
            val audio = streamInfo.audioStreams.orEmpty().filter { it.content.isNotBlank() }
            val options = audio.map { toOption(it) }
            // Not chunkable (DASH-only formats or HLS): played as one plain request, like before
            val fallbackUrl = audio.firstOrNull { it.deliveryMethod != DeliveryMethod.PROGRESSIVE_HTTP }?.content
                ?: streamInfo.hlsUrl?.takeIf { it.isNotBlank() }
            // Links carry their own expiry; refresh 5 minutes early, never keep one past 45 minutes
            val linkExpiry = options.map { YoutubeStreamRequest.expiresAtMs(it.url) }
                .filter { it > 0 }.minOrNull()
            val maxKeep = now + 45L * 60L * 1000L
            val expiresAt = linkExpiry?.let { minOf(it - 5L * 60L * 1000L, maxKeep) } ?: maxKeep
            if (options.isEmpty() && fallbackUrl == null) {
                throw IOException("No compatible audio stream available for this track.")
            }
            CachedStreams(options, fallbackUrl, expiresAt)
        } catch (e: IOException) {
            throw e
        } catch (e: Exception) {
            throw IOException("Stream resolution error: ${e.localizedMessage ?: "Unavailable"}", e)
        }
    }

    private fun toOption(stream: AudioStream): StreamOption {
        val url = stream.content
        val fromUrl = YoutubeStreamRequest.contentLength(url)
        val fromItag = runCatching { stream.itagItem?.contentLength ?: -1L }.getOrDefault(-1L)
        return StreamOption(
            itag = stream.itag.takeIf { it > 0 } ?: YoutubeStreamRequest.itagOf(url),
            url = url,
            formatName = stream.format?.name.orEmpty(),
            bitrate = stream.averageBitrate,
            progressive = stream.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP,
            contentLength = if (fromUrl > 0) fromUrl else fromItag
        )
    }

    private fun extractVideoId(url: String?): String? {
        if (url.isNullOrBlank()) return null
        val vIndex = url.indexOf("v=")
        if (vIndex != -1) {
            val sub = url.substring(vIndex + 2)
            return sub.substringBefore('&').substringBefore('#').ifBlank { null }
        }
        val shortIndex = url.indexOf("youtu.be/")
        if (shortIndex != -1) {
            val sub = url.substring(shortIndex + 9)
            return sub.substringBefore('?').substringBefore('&').ifBlank { null }
        }
        return if (url.length in 10..15 && !url.contains('/')) url else null
    }

    // ---- v4.6: artists ---------------------------------------------------------------------------

    private data class CachedArtist(val page: ArtistPage, val atMs: Long)
    private val artistCache = LruCache<String, CachedArtist>(12)
    private val albumCache = LruCache<String, CachedMix>(12)

    /**
     * YouTube Music artist results for [query] (artist channel, photo, and the number under the
     * name: monthly listeners for most artists, subscribers for some). Never throws.
     */
    suspend fun searchArtists(query: String): SourceResult<List<ArtistRef>> = withContext(Dispatchers.IO) {
        val q = query.trim()
        if (q.isEmpty()) return@withContext SourceResult.Success(emptyList())
        try {
            val qh = ServiceList.YouTube.searchQHFactory.fromQuery(
                q, listOf(YoutubeSearchQueryHandlerFactory.MUSIC_ARTISTS), ""
            )
            val extractor = ServiceList.YouTube.getSearchExtractor(qh)
            extractor.fetchPage()
            // NewPipe keeps only the number; the words ("monthly audience" / "subscribers")
            // come from the raw response
            val subtitles = try {
                ArtistAudience.subtitlesById(ArtistAudience.rawResponse(extractor))
            } catch (e: Throwable) {
                emptyMap()
            }
            val artists = extractor.initialPage.items
                .filterIsInstance<ChannelInfoItem>()
                .mapNotNull { item ->
                    val url = item.url?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    val name = item.name?.removeSuffix(" - Topic")?.trim().orEmpty()
                    if (name.isEmpty()) return@mapNotNull null
                    val subtitle = ArtistAudience.channelId(url)?.let { subtitles[it] }
                    ArtistRef(
                        url = url.replace("music.youtube.com", "www.youtube.com"),
                        name = name,
                        count = item.subscriberCount,
                        thumbnailUrl = bigImage(item.thumbnails, 900),
                        countKind = ArtistAudience.kindOf(subtitle)
                    )
                }
                .distinctBy { it.url }
            SourceResult.Success(artists)
        } catch (e: Exception) {
            SourceResult.Error("Artist search failed", e)
        }
    }

    /**
     * The artist for a Now Playing artist line such as "Arijit Singh, Pritam": searches the first
     * name and prefers an exact name match. Success(null) when YouTube has no such artist,
     * Error when the search itself failed (offline, blocked).
     */
    suspend fun findArtist(artistLine: String): SourceResult<ArtistRef?> {
        val full = artistLine.trim()
        if (full.isEmpty() || full.equals("Unknown Artist", ignoreCase = true)) return SourceResult.Success(null)
        val first = firstArtistName(full).ifBlank { full }
        val firstResult = searchArtists(first)
        val found = (firstResult as? SourceResult.Success)?.data.orEmpty()
        found.firstOrNull { normName(it.name) == normName(first) }?.let { return SourceResult.Success(it) }
        if (first != full) {
            val all = (searchArtists(full) as? SourceResult.Success)?.data.orEmpty()
            all.firstOrNull { normName(it.name) == normName(full) || normName(it.name) == normName(first) }
                ?.let { return SourceResult.Success(it) }
        }
        found.firstOrNull { normName(it.name).contains(normName(first)) || normName(first).contains(normName(it.name)) }
            ?.let { return SourceResult.Success(it) }
        if (firstResult is SourceResult.Error) return SourceResult.Error(firstResult.message, firstResult.cause)
        return SourceResult.Success(null)
    }

    /**
     * Everything the Artist page needs, fetched in parallel: the channel (photo, subscriber count,
     * Releases tab for albums) and a YouTube Music song search for the artist's top songs.
     * Each part falls back on its own; only when nothing at all loads is it an error.
     * Kept for 30 minutes.
     */
    suspend fun fetchArtist(ref: ArtistRef): SourceResult<ArtistPage> = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        artistCache.get(ref.url)?.let {
            if (now - it.atMs < MIX_CACHE_MS) return@withContext SourceResult.Success(it.page)
        }
        val key = normName(ref.name)
        var firstError: Throwable? = null

        val (channel, searchSongs) = coroutineScope {
            val ch = async {
                try {
                    ChannelInfo.getInfo(ServiceList.YouTube, ref.url)
                } catch (e: Exception) {
                    firstError = firstError ?: e
                    null
                }
            }
            val songs = async {
                try {
                    executeSearchFilter(
                        ServiceList.YouTube.searchQHFactory.fromQuery(
                            ref.name, listOf(YoutubeSearchQueryHandlerFactory.MUSIC_SONGS), ""
                        )
                    ).filter { normName(it.artist).contains(key) }
                } catch (e: Exception) {
                    firstError = firstError ?: e
                    emptyList()
                }
            }
            Pair(ch.await(), songs.await())
        }

        // Songs: YouTube Music's top songs for the name; topped up from the channel's videos.
        val songs = ArrayList<Track>(searchSongs)
        if (songs.size < MIN_ARTIST_SONGS && channel != null) {
            val videos = tabItems(channel, ChannelTabs.VIDEOS)
                .filterIsInstance<StreamInfoItem>()
                .mapNotNull { toTrack(it) }
                .filter { it.durationSec in 1..MAX_ARTIST_SONG_SEC }
            songs.addAll(videos)
        }
        val topSongs = songs.distinctBy { it.id }.take(MAX_ARTIST_SONGS)

        // Albums: the channel's Releases tab, else a YouTube Music album search for the name.
        var albums = if (channel != null) {
            tabItems(channel, ChannelTabs.ALBUMS)
                .filterIsInstance<PlaylistInfoItem>()
                .mapNotNull { toAlbum(it, ref.name) }
        } else emptyList()
        if (albums.isEmpty()) {
            albums = try {
                val qh = ServiceList.YouTube.searchQHFactory.fromQuery(
                    ref.name, listOf(YoutubeSearchQueryHandlerFactory.MUSIC_ALBUMS), ""
                )
                val ex = ServiceList.YouTube.getSearchExtractor(qh)
                ex.fetchPage()
                ex.initialPage.items.filterIsInstance<PlaylistInfoItem>()
                    .filter { normName(it.uploaderName.orEmpty()).contains(key) }
                    .mapNotNull { toAlbum(it, ref.name) }
            } catch (e: Exception) {
                emptyList()
            }
        }
        albums = albums.distinctBy { it.url }.take(MAX_ARTIST_ALBUMS)

        if (channel == null && topSongs.isEmpty() && albums.isEmpty()) {
            return@withContext SourceResult.Error("Couldn't load this artist", firstError)
        }
        val photo = channel?.avatars?.takeIf { it.isNotEmpty() }?.let { bigImage(it, 900) }
            ?.takeIf { it.isNotBlank() } ?: ref.thumbnailUrl
        // The number: monthly listeners from the search result when YouTube Music gave them
        // (artist "Topic" channels show no subscriber count), else the channel's real
        // subscriber count, else whatever the search result said.
        val channelSubs = try {
            channel?.subscriberCount ?: -1L
        } catch (e: Exception) {
            -1L
        }
        val (count, countKind) = when {
            ref.countKind == ArtistCountKind.MONTHLY_LISTENERS && ref.count >= 0 -> ref.count to ref.countKind
            channelSubs >= 0 -> channelSubs to ArtistCountKind.SUBSCRIBERS
            else -> ref.count to ref.countKind
        }
        val page = ArtistPage(
            url = ref.url,
            name = ref.name,
            count = count,
            countKind = countKind,
            photoUrl = upscaleYtImage(photo),
            songs = topSongs,
            albums = albums
        )
        artistCache.put(ref.url, CachedArtist(page, now))
        SourceResult.Success(page)
    }

    /** The songs of an album (its playlist), in order. Kept for 30 minutes. */
    suspend fun fetchAlbumTracks(album: Album): SourceResult<List<Track>> = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        albumCache.get(album.url)?.let {
            if (now - it.atMs < MIX_CACHE_MS) return@withContext SourceResult.Success(it.tracks)
        }
        try {
            val extractor = ServiceList.YouTube.getPlaylistExtractor(album.url)
            extractor.fetchPage()
            val tracks = ArrayList<Track>()
            var page = extractor.initialPage
            page.items.mapNotNullTo(tracks) { toTrack(it) }
            var more = 0
            while (page.hasNextPage() && more < 3 && tracks.size < 300) {
                page = extractor.getPage(page.nextPage)
                page.items.mapNotNullTo(tracks) { toTrack(it) }
                more++
            }
            val clean = tracks.distinctBy { it.id }
            albumCache.put(album.url, CachedMix(clean, now))
            SourceResult.Success(clean)
        } catch (e: Exception) {
            SourceResult.Error("Couldn't load this album", e)
        }
    }

    private fun tabItems(channel: ChannelInfo, filter: String): List<InfoItem> {
        val tab = channel.tabs.firstOrNull { it.contentFilters.contains(filter) } ?: return emptyList()
        return try {
            ChannelTabInfo.getInfo(ServiceList.YouTube, tab).relatedItems.orEmpty()
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun toAlbum(item: PlaylistInfoItem, artistName: String): Album? {
        val url = item.url?.takeIf { it.isNotBlank() } ?: return null
        val title = item.name?.trim().orEmpty()
        if (title.isEmpty()) return null
        val count = item.streamCount
        val subtitle = when {
            count == 1L -> "1 song"
            count > 1L -> "$count songs"
            else -> item.uploaderName?.removeSuffix(" - Topic")?.ifBlank { null } ?: artistName
        }
        return Album(
            url = url.replace("music.youtube.com", "www.youtube.com"),
            title = title,
            subtitle = subtitle,
            thumbnailUrl = upscaleYtImage(bigImage(item.thumbnails, 544), 544)
        )
    }

    /** The largest image, or the first one when sizes are unknown. */
    private fun bigImage(images: List<Image>?, cap: Int): String {
        if (images.isNullOrEmpty()) return ""
        val known = images.filter { it.width > 0 }
        val pick = known.filter { it.width <= cap }.maxByOrNull { it.width }
            ?: known.minByOrNull { it.width }
            ?: images.last()
        return pick.url.orEmpty()
    }

    companion object {
        private val INIT_LOCK = Any()
        private const val MIN_ARTIST_SONGS = 6
        private const val MAX_ARTIST_SONGS = 30
        private const val MAX_ARTIST_ALBUMS = 30
        private const val MAX_ARTIST_SONG_SEC = 600L

        /** "Arijit Singh, Pritam & Shreya" -> "Arijit Singh". */
        fun firstArtistName(line: String): String {
            val t = line.trim()
            if (t.isEmpty() || t.equals("Unknown Artist", ignoreCase = true)) return ""
            return t.split(Regex("""\s*(,|&|\bfeat\.?\s|\bft\.?\s|•)\s*""", RegexOption.IGNORE_CASE))
                .firstOrNull { it.isNotBlank() }?.trim().orEmpty()
        }

        /** Lower case, letters and digits only (any script), " - Topic" and VEVO dropped. */
        fun normName(s: String): String =
            s.lowercase(Locale.ROOT).removeSuffix(" - topic").removeSuffix("vevo")
                .filter { it.isLetterOrDigit() }

        /** YouTube photo URLs carry their size (=s120, =w120-h120): ask for a sharp one instead. */
        fun upscaleYtImage(url: String, size: Int = 900): String {
            if (url.isBlank()) return url
            if (!url.contains("ggpht.com") && !url.contains("googleusercontent.com")) return url
            return url
                .replace(Regex("""=s\d+"""), "=s$size")
                .replace(Regex("""=w\d+-h\d+"""), "=w$size-h$size")
        }

        private const val MIX_CACHE_MS = 30L * 60L * 1000L
        @Volatile
        private var isInitialized = false
    }
}
