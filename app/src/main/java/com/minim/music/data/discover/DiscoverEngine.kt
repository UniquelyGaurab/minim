package com.minim.music.data.discover

import android.content.Context
import com.minim.music.data.local.LibraryDatabase
import com.minim.music.data.model.SourceResult
import com.minim.music.data.model.Track
import com.minim.music.data.source.MusicSourceAdapter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale
import kotlin.random.Random

/** One Discover shelf: a title and its songs (about 10). */
data class Shelf(
    val key: String,
    val title: String,
    val tracks: List<Track>,
    /** v4.6: an Artist page "Albums" shelf shows these instead of songs. */
    val albums: List<com.minim.music.data.model.Album> = emptyList()
)

/** What Discover shows, built once a day (and again when the time of day changes). */
data class DiscoverSnapshot(
    val day: Long,
    val bucket: Int,
    val builtAt: Long,
    val shelves: List<Shelf>,
    /** False when the listening history is still too small for any shelf (fresh install). */
    val hasHistory: Boolean
)

/**
 * v4.3 Discover: shelves picked from what the user plays, built from the play history and
 * YouTube mixes of their songs.
 *
 * - 12 shelf templates; only those with enough data are used, and 6-8 of them show per day,
 *   chosen with a seed made from the date (so the same day gives the same choice).
 * - No song shows on two shelves. Mix shelves leave out songs already heard, liked or in a
 *   playlist, "not interested" songs and anything over 10 minutes.
 * - The result is saved to a file, so opening the tab is instant; it is rebuilt once a day,
 *   when the time-of-day bucket changes, or on pull-to-refresh.
 * - Mixes are fetched 3 at a time. A failed mix just means fewer shelves; if a rebuild finds
 *   nothing at all, the saved shelves are kept.
 */
class DiscoverEngine(
    context: Context,
    private val db: LibraryDatabase,
    private val source: MusicSourceAdapter
) {

    private val cacheFile = File(context.filesDir, "discover_cache_v1.json")
    private val buildLock = Mutex()

    @Volatile
    private var memory: DiscoverSnapshot? = null

    /** How many mixes the last build got back (0 with history = probably offline). */
    @Volatile
    var lastMixSuccesses: Int = -1
        private set

    /** The saved shelves (memory, then file), or null. Instant. */
    suspend fun cached(): DiscoverSnapshot? = withContext(Dispatchers.IO) {
        memory ?: readCache()?.also { memory = it }
    }

    /** True when the saved shelves are from an earlier day or another time of day. */
    fun isStale(s: DiscoverSnapshot?): Boolean {
        if (s == null) return true
        return s.day != today() || s.bucket != bucketOf(currentHour())
    }

    /** Removes a song from the saved shelves (after "Not interested"). Shelves left too small go. */
    suspend fun hideTrack(trackId: String): DiscoverSnapshot? = withContext(Dispatchers.IO) {
        val s = cached() ?: return@withContext null
        val shelves = s.shelves.map { sh -> sh.copy(tracks = sh.tracks.filter { it.id != trackId }) }
            .filter { it.tracks.size >= MIN_SHELF }
        val updated = s.copy(shelves = shelves)
        memory = updated
        writeCache(updated)
        updated
    }

    /**
     * 5.0.0: drops every song in [hidden] ("not interested", maybe marked on another phone) from the
     * saved shelves. Returns the updated shelves, or null when nothing was removed.
     */
    suspend fun dropHidden(hidden: Set<String>): DiscoverSnapshot? = withContext(Dispatchers.IO) {
        if (hidden.isEmpty()) return@withContext null
        val s = cached() ?: return@withContext null
        if (s.shelves.none { sh -> sh.tracks.any { it.id in hidden } }) return@withContext null
        val shelves = s.shelves.map { sh -> sh.copy(tracks = sh.tracks.filter { it.id !in hidden }) }
            .filter { it.tracks.size >= MIN_SHELF }
        val updated = s.copy(shelves = shelves)
        memory = updated
        writeCache(updated)
        updated
    }

    /**
     * 5.0.0: the library changed a lot (a whole-library merge after signing in, or Start fresh), so
     * the saved shelves count as stale and the next look at Discover rebuilds them. They still show
     * until then. Kept in the saved file too, so it survives the app closing.
     */
    suspend fun markStale() = withContext(Dispatchers.IO) {
        val s = cached() ?: return@withContext
        if (s.bucket == STALE_BUCKET) return@withContext
        val updated = s.copy(bucket = STALE_BUCKET)
        memory = updated
        writeCache(updated)
    }

    /** Builds fresh shelves. Only one build runs at a time; a second caller gets its result. */
    suspend fun rebuild(): DiscoverSnapshot = buildLock.withLock {
        val previous = cached()
        val built = try {
            build()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
        val result = when {
            built == null -> previous ?: DiscoverSnapshot(today(), bucketOf(currentHour()), 0L, emptyList(), true)
            // Nothing came back (offline?) but we had shelves: keep showing those
            built.shelves.isEmpty() && built.hasHistory && previous != null && previous.shelves.isNotEmpty() -> previous
            else -> built
        }
        if (result === built && built != null) {
            memory = built
            withContext(Dispatchers.IO) { writeCache(built) }
        }
        result
    }

    // ==========================================================================================
    // Building
    // ==========================================================================================

    private class Candidate(
        val key: String,
        val title: String,
        val seeds: List<Track> = emptyList(),
        val local: List<Track> = emptyList(),
        val isMix: Boolean = true,
        /** For "If you like": keep only songs by artists the user has never played. */
        val newArtistsOnly: Boolean = false
    )

    private suspend fun build(): DiscoverSnapshot = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val zone = ZoneId.systemDefault()
        val day = today()
        val hour = currentHour()
        val bucket = bucketOf(hour)
        val rng = Random(day * 31 + 7)

        val blocked = db.notInterestedIds.value
        val stats = db.getAllTrackStats()
        val events = db.getPlayEvents(now - 90L * DAY_MS)
        val lastCounted = db.getLastCountedPlays()
        val playlists = db.getPlaylists()
        val playlistTracks = playlists.associateWith { db.getPlaylistTracks(it.id) }

        val played = stats.filter { it.playCount > 0 || it.lastPlayedAt > 0 }
        val liked = stats.filter { it.isLiked }
        val known = HashSet<String>().apply {
            stats.forEach { add(it.track.id) } // played, liked or in a playlist: all in tracks
            playlistTracks.values.forEach { list -> list.forEach { add(it.id) } }
        }
        val historyArtists = HashSet<String>().apply {
            stats.forEach { add(norm(it.track.artist)) }
        }
        val hasHistory = played.isNotEmpty() || liked.isNotEmpty() || playlistTracks.values.any { it.isNotEmpty() }
        if (!hasHistory) return@withContext DiscoverSnapshot(day, bucket, now, emptyList(), false)

        fun ok(t: Track) = t.id !in blocked
        fun songLength(t: Track) = t.durationSec <= MAX_SONG_SEC

        val candidates = ArrayList<Candidate>()

        // 1. Because you played {song}: one of the 8 most recently played songs
        val recent = played.filter { ok(it.track) }.sortedByDescending { it.lastPlayedAt }.take(8)
        val becauseSeed = recent.randomOrNull(rng)?.track
        if (becauseSeed != null) {
            candidates += Candidate("because", "Because you played ${becauseSeed.title}", listOf(becauseSeed))
        }

        // 2. Since you liked {song}
        val likedSeed = liked.filter { ok(it.track) && it.track.id != becauseSeed?.id }.randomOrNull(rng)?.track
        if (likedSeed != null) {
            candidates += Candidate("liked", "Since you liked ${likedSeed.title}", listOf(likedSeed))
        }

        // Artists by how much they are played (counted plays; older counts from before 4.3 too)
        val byArtist = played.filter { ok(it.track) }.groupBy { norm(it.track.artist) }
        val artistRank = byArtist.entries
            .map { (_, list) -> list to list.sumOf { maxOf(it.playCount, 1) } }
            .sortedByDescending { it.second }
            .map { it.first }

        // 3. More like {artist}: mixes of a few of that artist's played songs
        val topFew = artistRank.take(5).shuffled(rng)
        val moreLike = topFew.getOrNull(0)
        if (moreLike != null) {
            val seeds = moreLike.sortedByDescending { it.playCount }.take(3).map { it.track }
            candidates += Candidate("artist", "More like ${moreLike.first().track.artist}", seeds)
        }

        // 4. If you like {artist}, try these: only artists never in the history
        val ifLike = topFew.getOrNull(1)
        if (ifLike != null) {
            val seeds = ifLike.sortedByDescending { it.playCount }.take(3).map { it.track }
            candidates += Candidate(
                "try", "If you like ${ifLike.first().track.artist}, try these", seeds, newArtistsOnly = true
            )
        }

        // 5. Inspired by {playlist}
        val plPick = playlistTracks.entries.filter { e -> e.value.count { ok(it) } >= 2 }.randomOrNull(rng)
        if (plPick != null) {
            val seeds = plPick.value.filter { ok(it) }.shuffled(rng).take(3)
            candidates += Candidate("playlist", "Inspired by ${plPick.key.name}", seeds)
        }

        // 6. Your {time of day} mix: songs usually played at this time of day
        val inBucket = events.filter { bucketOf(Instant.ofEpochMilli(it.playedAt).atZone(zone).hour) == bucket }
        val bucketSeeds = topSongs(inBucket.map { it.track }).filter { ok(it) }.take(3)
        if (bucketSeeds.size >= 2) {
            candidates += Candidate("time", "Your ${BUCKET_NAMES[bucket]} mix", bucketSeeds)
        }

        // 7. Your {weekday} mix
        val weekday = LocalDate.ofEpochDay(day).dayOfWeek
        val onWeekday = events.filter { Instant.ofEpochMilli(it.playedAt).atZone(zone).dayOfWeek == weekday }
        val weekdaySeeds = topSongs(onWeekday.map { it.track }).filter { ok(it) }.take(3)
        if (weekdaySeeds.size >= 2) {
            candidates += Candidate("weekday", "Your ${dayName(weekday)} mix", weekdaySeeds)
        }

        // 8. On repeat: most played this week (history only)
        val week = events.filter { it.playedAt >= now - 7L * DAY_MS }
        val repeat = week.groupBy { it.track.id }.filter { it.value.size >= 2 }
            .entries.sortedByDescending { it.value.size }.map { it.value.first().track }.filter { ok(it) }
        if (repeat.size >= MIN_SHELF) {
            candidates += Candidate("repeat", "On repeat", local = repeat, isMix = false)
        }

        // 9. Haven't played in a while: liked, not played (or liked) in the last 2 weeks
        val cutoff = now - 14L * DAY_MS
        val stale = liked.filter {
            val last = maxOf(lastCounted[it.track.id] ?: 0L, it.lastPlayedAt)
            ok(it.track) && last < cutoff && it.likedAt < cutoff
        }.shuffled(rng).map { it.track }
        if (stale.size >= MIN_SHELF) {
            candidates += Candidate("while", "Haven't played in a while", local = stale, isMix = false)
        }

        // 10. Your top artists: each artist's most played song
        val topArtists = artistRank.take(SHELF_SIZE).map { list -> list.maxByOrNull { it.playCount }!!.track }
        if (topArtists.size >= MIN_SHELF) {
            candidates += Candidate("artists", "Your top artists", local = topArtists, isMix = false)
        }

        // 11 + 12 merge every fetched mix; they need a few seeds of their own if nothing else has any
        val extraSeeds = recent.map { it.track }.shuffled(rng).take(3)
        candidates += Candidate("new", "New for you", extraSeeds)
        candidates += Candidate("different", "Something different", extraSeeds.reversed(), newArtistsOnly = true)

        // ---- choose today's shelves: 6-8, by the daily seed --------------------------------------
        val target = 6 + rng.nextInt(3)
        val shuffled = candidates.shuffled(rng)
        val picked = shuffled.take(target + 2) // two spares in case a mix comes back thin

        // ---- fetch the mixes (3 at a time) --------------------------------------------------------
        val seedIds = picked.flatMap { c -> c.seeds.map { it.id } }.distinct().take(MAX_SEEDS)
        val gate = Semaphore(PARALLEL)
        val mixes: Map<String, List<Track>> = coroutineScope {
            seedIds.map { id ->
                async {
                    gate.withPermit {
                        val r = try {
                            source.fetchRadio(id)
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            null
                        }
                        id to ((r as? SourceResult.Success)?.data ?: emptyList())
                    }
                }
            }.awaitAll().toMap()
        }
        lastMixSuccesses = mixes.values.count { it.isNotEmpty() }

        fun fresh(t: Track) = t.id !in known && t.id !in blocked && songLength(t)

        // Interleave the seeds' mixes so each seed gets a share near the top
        fun mixOf(seeds: List<Track>): List<Track> {
            val lists = seeds.mapNotNull { mixes[it.id] }.filter { it.isNotEmpty() }
            val out = ArrayList<Track>()
            val max = lists.maxOfOrNull { it.size } ?: 0
            for (i in 0 until max) for (l in lists) l.getOrNull(i)?.let { out.add(it) }
            return out.distinctBy { it.id }
        }

        // ---- fill shelves in display order, no song twice ------------------------------------------
        val used = HashSet<String>()
        val shelves = ArrayList<Shelf>()
        val ordered = picked.sortedBy { DISPLAY_ORDER.indexOf(it.key).let { i -> if (i < 0) 99 else i } }
            .let { list -> list.filter { it.key != "new" && it.key != "different" } + list.filter { it.key == "new" || it.key == "different" } }
        val chosenKeys = ArrayList<String>()

        for (c in ordered) {
            if (shelves.size >= target) break
            val pool: List<Track> = when {
                !c.isMix -> c.local.filter { songLength(it) || it.durationSec == 0L }
                c.key == "new" -> {
                    // Songs that turn up in several mixes first, then by how high they sit
                    val score = HashMap<String, Double>()
                    val byId = HashMap<String, Track>()
                    for (list in mixes.values) list.forEachIndexed { i, t ->
                        byId[t.id] = t
                        score[t.id] = (score[t.id] ?: 0.0) + 1.0 + 1.0 / (i + 2)
                    }
                    score.entries.sortedByDescending { it.value }.mapNotNull { byId[it.key] }.filter { fresh(it) }
                }
                c.key == "different" -> {
                    // Artists nowhere in the history, taken from deeper in the mixes
                    mixes.values.flatMap { it.drop(it.size / 3) + it.take(it.size / 3) }
                        .filter { fresh(it) && norm(it.artist) !in historyArtists }
                        .distinctBy { it.id }
                        .shuffled(rng)
                }
                else -> mixOf(c.seeds).filter { fresh(it) }
                    .filter { !c.newArtistsOnly || norm(it.artist) !in historyArtists }
            }
            val songs = ArrayList<Track>()
            val artistsOnShelf = HashMap<String, Int>()
            for (t in pool) {
                if (songs.size >= SHELF_SIZE) break
                if (t.id in used) continue
                // Variety: at most 3 songs by one artist on a shelf (top artists: 1 each anyway)
                val a = norm(t.artist)
                if (c.isMix && (artistsOnShelf[a] ?: 0) >= 3) continue
                artistsOnShelf[a] = (artistsOnShelf[a] ?: 0) + 1
                songs.add(t)
            }
            if (songs.size < MIN_SHELF) continue
            songs.forEach { used.add(it.id) }
            shelves.add(Shelf(c.key, c.title, songs))
            chosenKeys.add(c.key)
        }

        DiscoverSnapshot(day, bucket, now, shelves, true)
    }

    /** Songs ordered by how often they appear (most played first), each once. */
    private fun topSongs(tracks: List<Track>): List<Track> =
        tracks.groupBy { it.id }.entries.sortedByDescending { it.value.size }.map { it.value.first() }

    // ==========================================================================================
    // Cache file
    // ==========================================================================================

    private fun writeCache(s: DiscoverSnapshot) {
        try {
            val shelves = JSONArray()
            for (sh in s.shelves) {
                val tracks = JSONArray()
                for (t in sh.tracks) {
                    tracks.put(JSONObject().apply {
                        put("id", t.id); put("title", t.title); put("artist", t.artist)
                        put("d", t.durationSec); put("th", t.thumbnailUrl); put("src", t.sourceUrl)
                    })
                }
                shelves.put(JSONObject().apply {
                    put("key", sh.key); put("title", sh.title); put("tracks", tracks)
                })
            }
            val obj = JSONObject().apply {
                put("day", s.day); put("bucket", s.bucket); put("builtAt", s.builtAt)
                put("hasHistory", s.hasHistory); put("shelves", shelves)
            }
            val tmp = File(cacheFile.parentFile, cacheFile.name + ".tmp")
            tmp.writeText(obj.toString())
            tmp.renameTo(cacheFile)
        } catch (_: Exception) {
        }
    }

    private fun readCache(): DiscoverSnapshot? = try {
        if (!cacheFile.exists()) null else {
            val obj = JSONObject(cacheFile.readText())
            val arr = obj.getJSONArray("shelves")
            val shelves = ArrayList<Shelf>()
            for (i in 0 until arr.length()) {
                val sh = arr.getJSONObject(i)
                val ta = sh.getJSONArray("tracks")
                val tracks = ArrayList<Track>()
                for (j in 0 until ta.length()) {
                    val t = ta.getJSONObject(j)
                    tracks.add(
                        Track(
                            id = t.getString("id"), title = t.optString("title"), artist = t.optString("artist"),
                            durationSec = t.optLong("d"), thumbnailUrl = t.optString("th"), sourceUrl = t.optString("src")
                        )
                    )
                }
                shelves.add(Shelf(sh.getString("key"), sh.getString("title"), tracks))
            }
            DiscoverSnapshot(
                obj.getLong("day"), obj.optInt("bucket", -1), obj.optLong("builtAt"), shelves,
                obj.optBoolean("hasHistory", true)
            )
        }
    } catch (_: Exception) {
        null
    }

    // ==========================================================================================

    companion object {
        private const val DAY_MS = 24L * 60L * 60L * 1000L
        const val SHELF_SIZE = 10
        private const val MIN_SHELF = 4
        /** A bucket no hour maps to: shelves marked stale by [markStale]. */
        const val STALE_BUCKET = -2
        private const val MAX_SONG_SEC = 600L
        private const val PARALLEL = 3
        private const val MAX_SEEDS = 14

        /** Late night 22-5, morning 5-12, afternoon 12-17, evening 17-22. */
        private val BUCKET_NAMES = listOf("late night", "morning", "afternoon", "evening")

        private val DISPLAY_ORDER = listOf(
            "because", "repeat", "time", "liked", "artist", "weekday", "playlist",
            "try", "artists", "while", "new", "different"
        )

        fun bucketOf(hour: Int): Int = when (hour) {
            in 5..11 -> 1
            in 12..16 -> 2
            in 17..21 -> 3
            else -> 0
        }

        private fun currentHour(): Int = java.time.LocalTime.now().hour
        private fun today(): Long = LocalDate.now().toEpochDay()
        private fun dayName(d: DayOfWeek): String = d.getDisplayName(TextStyle.FULL, Locale.US)

        /** Artist names compared loosely: "Artist - Topic", "ArtistVEVO" and case don't matter. */
        fun norm(artist: String): String = artist.lowercase(Locale.US)
            .removeSuffix(" - topic").removeSuffix("vevo").removeSuffix(" official")
            .filter { it.isLetterOrDigit() }
    }
}
