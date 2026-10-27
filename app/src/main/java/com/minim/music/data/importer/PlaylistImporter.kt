package com.minim.music.data.importer

import com.minim.music.data.model.Track
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Where an imported playlist came from: shown as "from [logo] · 25 songs" under its name
 * (5.3.2: logos for Spotify, YouTube Music and YouTube; a pasted list keeps the words).
 */
enum class ImportOrigin(val code: String, val label: String) {
    SPOTIFY("spotify", "from Spotify"),
    YOUTUBE("youtube", "from YouTube"),
    /** 5.3.2: a music.youtube.com link. Older YouTube Music imports stay [YOUTUBE]. */
    YOUTUBE_MUSIC("ytmusic", "from YouTube Music"),
    TEXT("text", "from a list");

    companion object {
        fun fromCode(code: String?): ImportOrigin? = values().firstOrNull { it.code == code }
    }
}

/** One row of the review screen. */
data class ReviewItem(
    /** Null for YouTube playlists: those are read exactly, nothing to match. */
    val source: SourceSong?,
    val options: List<Scored>,
    val chosen: Track?,
    val confidence: Confidence,
    val flags: Set<MatchFlag>,
    val included: Boolean
)

sealed class ImportState {
    abstract val name: String

    data class Reading(override val name: String, val origin: ImportOrigin, val read: Int) : ImportState()
    data class Matching(override val name: String, val origin: ImportOrigin, val done: Int, val total: Int) : ImportState()
    data class Ready(
        override val name: String,
        val origin: ImportOrigin,
        val items: List<ReviewItem>,
        /** A plain sentence for the top of the review screen (a limit hit, repeats removed), or null. */
        val note: String?
    ) : ImportState() {
        val included: Int get() = items.count { it.included && it.chosen != null }
        val doubtful: Int get() = items.count { it.confidence != Confidence.SURE }
    }
    data class Failed(override val name: String, val message: String) : ImportState()
}

/** What the importer needs from the outside world (NewPipe, Spotify pages); faked in tests. */
interface ImportBackend {
    data class YouTubeList(val name: String, val tracks: List<Track>, val total: Long, val cutAtLimit: Boolean)

    /** Throws [ImportFailure] with a friendly message when the playlist can't be read. */
    suspend fun readYouTube(url: String, max: Int, onProgress: (Int) -> Unit): YouTubeList
    suspend fun readSpotify(kind: String, id: String, max: Int, onProgress: (Int) -> Unit): SpotifyReader.Result
    suspend fun resolveSpotifyShort(url: String): String?
    /** Search results, or null when the search failed (offline, blocked). */
    suspend fun search(query: String, videos: Boolean): List<Track>?
}

class ImportFailure(message: String) : Exception(message)

/**
 * 5.3.0: runs one playlist import in the background so the app stays usable. Library shows its
 * [state] as a slim card ("Importing Chill Mix · 84/200"); when it's Ready the review screen
 * edits it with [toggle] / [choose] and saving takes [tracksToSave].
 */
class PlaylistImporter(
    private val backend: ImportBackend,
    private val scope: CoroutineScope,
    private val parallel: Int = 3
) {

    private val _state = MutableStateFlow<ImportState?>(null)
    val state: StateFlow<ImportState?> = _state
    private var job: Job? = null
    private val progressLock = Any()

    /** Waits for the running import, if any (tests). */
    suspend fun awaitIdle() {
        job?.join()
    }

    val isBusy: Boolean
        get() = _state.value.let { it is ImportState.Reading || it is ImportState.Matching }

    /** Starts importing [input]. False when another import is still running. */
    fun start(input: ImportInput): Boolean {
        if (isBusy) return false
        job?.cancel()
        val origin = when (input) {
            is ImportInput.YouTube -> if (input.music) ImportOrigin.YOUTUBE_MUSIC else ImportOrigin.YOUTUBE
            is ImportInput.Lines -> ImportOrigin.TEXT
            else -> ImportOrigin.SPOTIFY
        }
        val firstName = if (origin == ImportOrigin.TEXT) "your list" else "playlist"
        _state.value = ImportState.Reading(firstName, origin, 0)
        job = scope.launch(Dispatchers.Default) {
            try {
                _state.value = run(input, origin)
            } catch (e: CancellationException) {
                // The user's ✕ (or a newer import) cancels the job: stop quietly. A timeout or other
                // CancellationException from inside the work, while the job itself is still active,
                // is a failed import, not a card stuck on "Importing".
                if (!isActive) throw e
                fail(firstName, ImportMessages.UNEXPECTED, e)
            } catch (e: ImportFailure) {
                fail(firstName, e.message ?: ImportMessages.UNEXPECTED, null)
            } catch (t: Throwable) {
                // 5.3.1: Throwable, not Exception. 5.3.0's crash was an ExceptionInInitializerError
                // (an Error) from a class that failed to load, which slipped past `catch (Exception)`
                // and took the whole app down. Nothing in an import may crash the app: whatever goes
                // wrong ends as a failed import on the Library card ("tap to see why").
                fail(firstName, ImportMessages.UNEXPECTED, t)
            }
        }
        return true
    }

    private fun fail(firstName: String, message: String, cause: Throwable?) {
        if (cause != null) {
            try {
                android.util.Log.e("Minim", "Import failed", cause)
            } catch (_: Throwable) {
                // no Android Log in plain unit tests
            }
        }
        _state.value = ImportState.Failed(_state.value?.name ?: firstName, message)
    }

    fun cancel() {
        job?.cancel()
        job = null
        _state.value = null
    }

    /** Forget a finished, saved or failed import (the card goes away). */
    fun clear() {
        if (!isBusy) _state.value = null
    }

    fun toggle(index: Int) = edit(index) { it.copy(included = !it.included && it.chosen != null) }

    /** The user picked another result for a row: it counts as sure and is ticked. */
    fun choose(index: Int, track: Track) = edit(index) {
        it.copy(chosen = track, included = true, confidence = Confidence.SURE, flags = emptySet())
    }

    fun remove(index: Int) = edit(index) { it.copy(included = false) }

    private fun edit(index: Int, f: (ReviewItem) -> ReviewItem) {
        val ready = _state.value as? ImportState.Ready ?: return
        if (index !in ready.items.indices) return
        _state.value = ready.copy(items = ready.items.toMutableList().also { it[index] = f(it[index]) })
    }

    /** The ticked songs in playlist order, each once. */
    fun tracksToSave(): List<Track> {
        val ready = _state.value as? ImportState.Ready ?: return emptyList()
        return ImportDedupe.uniqueTracks(ready.items.filter { it.included }.mapNotNull { it.chosen })
    }

    // ---- the work --------------------------------------------------------------------------------

    private suspend fun run(input: ImportInput, origin: ImportOrigin): ImportState = when (input) {
        is ImportInput.YouTube -> readYouTube(input, origin)
        is ImportInput.Spotify -> readSpotify(input.kind, input.id)
        is ImportInput.SpotifyShort -> {
            val real = backend.resolveSpotifyShort(input.url)
                ?: throw ImportFailure("Couldn't open that Spotify link. Paste the full open.spotify.com link instead.")
            when (val again = ImportDetect.detect(real)) {
                is ImportInput.Spotify -> readSpotify(again.kind, again.id)
                else -> throw ImportFailure(ImportMessages.unsupported(ImportInput.Why.SPOTIFY_NOT_PLAYLIST))
            }
        }
        is ImportInput.Lines -> match("your list", origin, input.songs, null)
        is ImportInput.Unsupported -> throw ImportFailure(ImportMessages.unsupported(input.why))
        ImportInput.Empty -> throw ImportFailure(ImportMessages.EMPTY)
    }

    private suspend fun readYouTube(input: ImportInput.YouTube, origin: ImportOrigin): ImportState {
        val list = backend.readYouTube(input.url, MAX_SONGS) { n ->
            _state.value = ImportState.Reading("playlist", origin, n)
        }
        val tracks = ImportDedupe.uniqueTracks(list.tracks)
        if (tracks.isEmpty()) throw ImportFailure(ImportMessages.EMPTY_PLAYLIST)
        val items = tracks.map { ReviewItem(null, emptyList(), it, Confidence.SURE, emptySet(), true) }
        val repeats = list.tracks.size - tracks.size
        val note = listOfNotNull(
            if (list.cutAtLimit) ImportMessages.limit(MAX_SONGS, list.total.toInt()) else null,
            ImportMessages.repeats(repeats)
        ).joinToString(" ").ifBlank { null }
        return ImportState.Ready(list.name.ifBlank { "YouTube playlist" }, origin, items, note)
    }

    private suspend fun readSpotify(kind: String, id: String): ImportState {
        val r = try {
            backend.readSpotify(kind, id, MAX_SONGS) { n ->
                _state.value = ImportState.Reading("playlist", ImportOrigin.SPOTIFY, n)
            }
        } catch (e: SpotifyReader.SpotifyException) {
            throw ImportFailure(
                if (e.why == SpotifyReader.Why.NOT_PUBLIC) ImportMessages.SPOTIFY_PRIVATE else ImportMessages.OFFLINE
            )
        }
        val name = r.name.ifBlank { "Spotify playlist" }
        val note = when {
            r.stoppedEarly -> ImportMessages.spotifyStopped(r.songs.size, r.total)
            r.total > MAX_SONGS -> ImportMessages.limit(MAX_SONGS, r.total)
            else -> null
        }
        return match(name, ImportOrigin.SPOTIFY, r.songs, note)
    }

    private suspend fun match(name: String, origin: ImportOrigin, all: List<SourceSong>, note: String?): ImportState {
        val songs = ImportDedupe.uniqueSongs(all)
        if (songs.isEmpty()) throw ImportFailure(ImportMessages.EMPTY_PLAYLIST)
        val done = AtomicInteger(0)
        val failures = AtomicInteger(0)
        _state.value = ImportState.Matching(name, origin, 0, songs.size)
        val gate = Semaphore(parallel)
        val results: List<ReviewItem> = coroutineScope {
            songs.map { song ->
                async {
                    gate.withPermit {
                        val item = matchOne(song) { failures.incrementAndGet() }
                        synchronized(progressLock) {
                            // counts only go up, even when two searches finish together
                            val n = done.incrementAndGet()
                            val cur = _state.value
                            if (cur !is ImportState.Matching || cur.done < n) {
                                _state.value = ImportState.Matching(name, origin, n, songs.size)
                            }
                        }
                        item
                    }
                }
            }.awaitAll()
        }
        if (failures.get() == songs.size) throw ImportFailure(ImportMessages.SEARCH_FAILED)
        val (items, sameVideo) = ImportDedupe.dropSameVideo(results)
        val repeats = (all.size - songs.size) + sameVideo
        val fullNote = listOfNotNull(note, ImportMessages.repeats(repeats)).joinToString(" ").ifBlank { null }
        return ImportState.Ready(name, origin, items, fullNote)
    }

    private suspend fun matchOne(song: SourceSong, onFail: () -> Unit): ReviewItem {
        val songsFirst = backend.search(song.query, videos = false)
        var result = SongMatcher.match(song, songsFirst.orEmpty())
        if (result.confidence != Confidence.SURE) {
            // not sure from YouTube Music songs: look at videos too and pick from both
            val videos = backend.search(song.query, videos = true)
            if (songsFirst == null && videos == null) onFail()
            if (!videos.isNullOrEmpty()) result = SongMatcher.match(song, songsFirst.orEmpty() + videos)
        }
        val best = result.best
        return when (result.confidence) {
            Confidence.NONE -> ReviewItem(song, result.options, null, Confidence.NONE, emptySet(), false)
            else -> ReviewItem(song, result.options, best!!.track, result.confidence, best.flags, true)
        }
    }

    companion object {
        /** Biggest import. Matching 1000 songs takes several minutes already. */
        const val MAX_SONGS = 1000
    }
}

/** 5.3.0: repeats are removed only inside the one imported playlist, never against other playlists. */
object ImportDedupe {
    fun uniqueSongs(songs: List<SourceSong>): List<SourceSong> = songs.distinctBy { it.key }

    fun uniqueTracks(tracks: List<Track>): List<Track> = tracks.distinctBy { it.id }

    /** Two rows that found the same video: the later one goes. Returns the rows and how many went. */
    fun dropSameVideo(items: List<ReviewItem>): Pair<List<ReviewItem>, Int> {
        val seen = HashSet<String>()
        val out = ArrayList<ReviewItem>(items.size)
        var dropped = 0
        for (it in items) {
            val id = it.chosen?.id
            if (id != null && !seen.add(id)) {
                dropped++
                continue
            }
            out.add(it)
        }
        return out to dropped
    }
}

/** The words the user sees. Kept in one place so tests can check them. */
object ImportMessages {
    const val EMPTY = "Paste a YouTube or Spotify playlist link, or a list of songs, one per line."
    const val EMPTY_PLAYLIST = "This playlist has no songs Minim can import."
    const val SPOTIFY_PRIVATE =
        "Minim can't read this Spotify playlist. Only public playlists work: make it public in Spotify and try again."
    const val OFFLINE = "Couldn't reach Spotify. Check your connection and try again."
    const val SEARCH_FAILED = "Couldn't search YouTube for these songs. Check your connection and try again."
    /** Anything unexpected (a bug, not the network): the card shows this instead of the app crashing. */
    const val UNEXPECTED = "Couldn't import this playlist. Try again."

    fun unsupported(why: ImportInput.Why): String = when (why) {
        ImportInput.Why.SPOTIFY_NOT_PLAYLIST -> "That Spotify link isn't a playlist or an album."
        ImportInput.Why.YOUTUBE_NOT_PLAYLIST -> "That YouTube link is a single video. Share the playlist instead."
        ImportInput.Why.YOUTUBE_PRIVATE_LIST -> "That's a private YouTube list (like Liked music), so Minim can't read it."
        ImportInput.Why.UNKNOWN_LINK -> "Minim can import YouTube, YouTube Music and Spotify playlists."
    }

    fun limit(max: Int, total: Int): String =
        if (total > max) "This playlist has $total songs. Minim imports the first $max."
        else "Minim imports up to $max songs, so the rest were left out."

    fun spotifyStopped(got: Int, total: Int): String =
        if (total > got) "Spotify only shared the first $got of $total songs."
        else "Spotify only shared the first $got songs."

    fun repeats(n: Int): String? = when {
        n <= 0 -> null
        n == 1 -> "1 repeated song was removed."
        else -> "$n repeated songs were removed."
    }
}
