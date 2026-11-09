package com.minim.music.data.stories

import android.content.Context
import android.content.SharedPreferences
import com.minim.music.data.model.Track
import org.json.JSONObject
import java.util.Locale

/*
 * 5.7.0 u47: "stories" became "tapes". The data files keep their old names (minim_stories) so
 * your channels, places and saved uploads carry over; only what you see changed.
 */

/** Liked and Offline show songs or tapes, picked by the filter at the top. */
/** u48: ARTISTS is Liked only (liked artists aren't tracks, so [LibraryFilter.split] gives none). */
enum class LibraryKind { SONGS, TAPES, ARTISTS }

object LibraryFilter {
    fun split(tracks: List<Track>, kind: LibraryKind): List<Track> = when (kind) {
        LibraryKind.SONGS -> tracks.filterNot { StoryTracks.isStory(it) }
        LibraryKind.TAPES -> tracks.filter { StoryTracks.isStory(it) }
        LibraryKind.ARTISTS -> emptyList()
    }

    /** Songs only: Discover, greetings, the Gemini shelf and "shuffle Liked" never get a tape. */
    fun songsOnly(tracks: List<Track>): List<Track> = split(tracks, LibraryKind.SONGS)
}

/** "played": heard to the end (or past 90%), the same mark that dims a card. */
object PlayedMark {
    fun isPlayed(p: StoryPlayback?): Boolean = p?.done == true

    fun playedIds(all: Map<String, StoryPlayback>): Set<String> =
        all.filterValues { it.done }.keys
}

/**
 * The small dot on a channel's photo in the tapes row: it has an upload from the last week that
 * you haven't started.
 */
object NewUploadRing {
    const val WINDOW_MS = 7L * 24 * 60 * 60 * 1000

    fun channelsWithNew(uploads: List<StoryUpload>, progress: Map<String, StoryPlayback>, now: Long): Set<String> =
        uploads.asSequence()
            .filter { it.publishedAt > 0L && now - it.publishedAt in 0..WINDOW_MS }
            .filter { progress[it.videoId] == null }
            .map { it.channelId }
            .toSet()
}

/**
 * New to You: uploads from channels like yours that you don't follow, picked from YouTube's
 * related videos for what you play on tapes.
 */
object NewToYou {
    const val MAX_SEEDS = 6
    const val MAX_ITEMS = 40
    const val PER_CHANNEL = 3
    private const val YEAR_MS = 365L * 24 * 60 * 60 * 1000

    /**
     * Which uploads to ask YouTube about: the tapes you played most recently first, then each
     * followed channel's newest upload.
     */
    fun seeds(feed: List<StoryUpload>, progress: Map<String, StoryPlayback>, max: Int = MAX_SEEDS): List<String> {
        val out = LinkedHashSet<String>()
        val feedIds = feed.map { it.videoId }.toSet()
        progress.entries.sortedByDescending { it.value.at }
            .map { it.key }
            .filter { it in feedIds }
            .take(max / 2)
            .forEach { out.add(it) }
        val newestPerChannel = feed.sortedByDescending { it.publishedAt }.distinctBy { it.channelId }
        for (u in newestPerChannel) {
            if (out.size >= max) break
            out.add(u.videoId)
        }
        if (out.size < max) {
            for (u in feed.sortedByDescending { it.publishedAt }) {
                if (out.size >= max) break
                out.add(u.videoId)
            }
        }
        return out.toList().take(max)
    }

    fun normName(name: String): String = name.trim().lowercase(Locale.ROOT).removeSuffix(" - topic")

    /**
     * Joins the related lists into one: no channel you follow, nothing you've played, no repeats,
     * at most [PER_CHANNEL] per channel. Uploads several of your tapes point at come first, then
     * the newest. Anything older than a year only fills in when there's too little else.
     */
    fun build(
        related: List<List<StoryUpload>>,
        followed: List<StoryChannel>,
        progress: Map<String, StoryPlayback>,
        now: Long,
        max: Int = MAX_ITEMS
    ): List<StoryUpload> {
        val followedIds = followed.map { it.id }.toSet()
        val followedNames = followed.map { normName(it.name) }.toSet()
        val count = HashMap<String, Int>()
        val first = LinkedHashMap<String, StoryUpload>()
        for (list in related) {
            for (u in list.distinctBy { it.videoId }) {
                if (u.channelId in followedIds || normName(u.channelName) in followedNames) continue
                if (PlayedMark.isPlayed(progress[u.videoId])) continue
                count[u.videoId] = (count[u.videoId] ?: 0) + 1
                if (u.videoId !in first) first[u.videoId] = u
            }
        }
        val ranked = first.values.sortedWith(
            compareByDescending<StoryUpload> { count[it.videoId] ?: 0 }.thenByDescending { it.publishedAt }
        )
        val recent = ranked.filter { it.publishedAt <= 0L || now - it.publishedAt <= YEAR_MS }
        val pool = if (recent.size >= 8) recent else ranked
        val perChannel = HashMap<String, Int>()
        val out = ArrayList<StoryUpload>()
        for (u in pool) {
            val key = u.channelId.ifBlank { normName(u.channelName) }
            val n = perChannel[key] ?: 0
            if (n >= PER_CHANNEL) continue
            perChannel[key] = n + 1
            out.add(u)
            if (out.size >= max) break
        }
        return out
    }

    /** What the list shows right now: played ones drop off as soon as they're finished. */
    fun visible(items: List<StoryUpload>, followed: List<StoryChannel>, progress: Map<String, StoryPlayback>): List<StoryUpload> {
        val ids = followed.map { it.id }.toSet()
        val names = followed.map { normName(it.name) }.toSet()
        return items.filter {
            !PlayedMark.isPlayed(progress[it.videoId]) && it.channelId !in ids && normName(it.channelName) !in names
        }
    }
}

/** A channel page's Latest and Popular lists. */
enum class ChannelSort { LATEST, POPULAR }

object ChannelVideos {
    fun sort(uploads: List<StoryUpload>, sort: ChannelSort): List<StoryUpload> = when (sort) {
        ChannelSort.LATEST -> uploads.sortedByDescending { it.publishedAt }
        ChannelSort.POPULAR -> uploads.sortedWith(compareByDescending<StoryUpload> { it.viewCount }.thenByDescending { it.publishedAt })
    }

    /** The "Follow" pill toggles the channel in your tapes row. */
    fun isFollowed(channelId: String, channelName: String, followed: List<StoryChannel>): Boolean =
        followed.any { it.id == channelId || (channelId.isBlank() && NewToYou.normName(it.name) == NewToYou.normName(channelName)) }

    /** A tape's channel, from its id when the tape has one, else by name among the ones you follow. */
    /**
     * 5.7.0 u51: a channel from the Stats tab (its name, and its id when the tape had one).
     * Without an id, only a followed channel with that name can open.
     */
    fun channelFor(name: String, channelId: String, followed: List<StoryChannel>): StoryChannel? {
        val id = channelId.takeIf { it.startsWith("UC") && it.length >= 12 }
        if (id != null) {
            return followed.firstOrNull { it.id == id }
                ?: StoryChannel(id = id, name = name, url = "https://www.youtube.com/channel/$id")
        }
        val n = NewToYou.normName(name)
        if (n.isEmpty()) return null
        return followed.firstOrNull { NewToYou.normName(it.name) == n }
    }

    fun channelFor(track: Track, followed: List<StoryChannel>): StoryChannel? {
        val id = StoryTracks.channelIdOf(track)
        if (id != null) {
            return followed.firstOrNull { it.id == id }
                ?: StoryChannel(id = id, name = track.artist, url = "https://www.youtube.com/channel/$id")
        }
        val n = NewToYou.normName(track.artist)
        return followed.firstOrNull { NewToYou.normName(it.name) == n }
    }
}

/**
 * 5.7.0 u47: tapes remember a speed per channel; songs always play at 1x. Keyed by channel name,
 * which every tape has (old saved ones have no channel id).
 */
object TapeSpeed {
    val OPTIONS = listOf(1f, 1.25f, 1.5f, 2f)

    fun label(speed: Float): String = when (speed) {
        1f -> "1x"
        2f -> "2x"
        else -> "${speed}x".replace(".0x", "x")
    }

    fun key(channelName: String): String = NewToYou.normName(channelName)

    /** The speed to play [track] at: its channel's speed for tapes, 1x for songs. */
    fun forTrack(track: Track?, speeds: Map<String, Float>): Float {
        if (!StoryTracks.isStory(track)) return 1f
        val s = speeds[key(track!!.artist)] ?: return 1f
        return if (s in OPTIONS) s else 1f
    }
}

class TapeSpeedStore(private val prefs: SharedPreferences) {
    constructor(context: Context) : this(context.getSharedPreferences(FILE, Context.MODE_PRIVATE))

    @Volatile
    private var cache: Map<String, Float> = decode(prefs.getString(KEY, null))

    fun all(): Map<String, Float> = cache

    fun speedFor(track: Track?): Float = TapeSpeed.forTrack(track, cache)

    @Synchronized
    fun set(channelName: String, speed: Float) {
        val m = HashMap(cache)
        if (speed == 1f) m.remove(TapeSpeed.key(channelName)) else m[TapeSpeed.key(channelName)] = speed
        cache = m
        prefs.edit().putString(KEY, encode(m)).apply()
    }

    companion object {
        const val FILE = "minim_stories"
        const val KEY = "tape_speeds"

        fun encode(m: Map<String, Float>): String {
            val o = JSONObject()
            for ((k, v) in m) o.put(k, v.toDouble())
            return o.toString()
        }

        fun decode(raw: String?): Map<String, Float> {
            if (raw.isNullOrBlank()) return emptyMap()
            return try {
                val o = JSONObject(raw)
                val out = HashMap<String, Float>()
                for (k in o.keys()) {
                    val v = o.optDouble(k, 1.0).toFloat()
                    if (v in TapeSpeed.OPTIONS) out[k] = v
                }
                out
            } catch (e: Exception) {
                emptyMap()
            }
        }
    }
}

/** The sleep timer's choices in Now Playing's menu. */
object SleepOptions {
    val PRESET_MINUTES = listOf(15, 30, 45, 60)
    const val CUSTOM_MIN = 1
    const val CUSTOM_MAX = 720

    fun parseCustom(input: String?): Int? {
        val n = input?.trim()?.toIntOrNull() ?: return null
        return n.takeIf { it in CUSTOM_MIN..CUSTOM_MAX }
    }

    /** "12:05" or "1:02:05" for the countdown in Now Playing's top left. */
    fun countdown(ms: Long): String {
        val total = ((ms.coerceAtLeast(0L) + 999L) / 1000L)
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) String.format(Locale.ROOT, "%d:%02d:%02d", h, m, s) else String.format(Locale.ROOT, "%d:%02d", m, s)
    }

    /** Real time left in a track at [speed]. */
    fun remainingAtSpeed(durationMs: Long, positionMs: Long, speed: Float): Long {
        if (durationMs <= 0L) return 0L
        val left = (durationMs - positionMs).coerceAtLeast(0L)
        return (left / speed.coerceAtLeast(0.1f)).toLong()
    }
}
