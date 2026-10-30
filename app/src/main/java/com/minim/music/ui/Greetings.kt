package com.minim.music.ui

import com.minim.music.util.SafeRegex
import com.minim.music.data.greetings.GreetingData
import com.minim.music.data.greetings.GreetingTokens
import com.minim.music.data.greetings.RemoteGreeting
import com.minim.music.data.greetings.RemoteGreetingFile
import com.minim.music.data.greetings.RemoteGreetingsParser
import java.util.Calendar
import java.util.Locale
import kotlin.random.Random

/**
 * Everything the stat greetings can talk about. The library part comes from one cheap read of the
 * local database ([com.minim.music.data.local.LibraryDatabase.getGreetingFacts]); the "now" part is
 * filled in from the player at the moment a greeting is shown. Missing data = null / 0.
 */
data class GreetingFacts(
    // The song in the player right now
    val nowId: String? = null,
    val nowTitle: String? = null,
    val nowArtist: String? = null,
    val nowPlaying: Boolean = false,
    val nowLiked: Boolean = false,
    // Recents
    val lastId: String? = null,
    val lastTitle: String? = null,
    val lastArtist: String? = null,
    val playsToday: Int = 0,
    val topTrackWeekTitle: String? = null,
    val topTrackWeekPlays: Int = 0,
    val topArtistWeek: String? = null,
    val topArtistWeekPlays: Int = 0,
    val artistsWeek: Int = 0,
    // Liked
    val likedCount: Int = 0,
    val recentLikedTitle: String? = null,
    val topLikedArtist: String? = null,
    val topLikedArtistCount: Int = 0,
    // Playlists
    val playlistCount: Int = 0,
    val newestPlaylist: String? = null,
    val biggestPlaylist: String? = null,
    val biggestPlaylistSongs: Int = 0,
    // 5.1.0: plays in the last 7 days per song id, for {plays} (the song in the player)
    val weekPlays: Map<String, Int> = emptyMap()
) {
    val hasHistory: Boolean get() = !lastTitle.isNullOrBlank()

    /**
     * 5.1.0: the facts as greeting placeholders ([GreetingTokens]), shortened the way people say
     * them. A token with nothing to say is null, so a line that uses it doesn't show.
     */
    fun toData(now: Calendar = Calendar.getInstance()): GreetingData {
        val song = nowTitle?.let { Greetings.shortTitle(it, nowArtist) }?.takeIf { it.isNotEmpty() }
        val last = if (lastId != null && lastId == nowId) null
            else lastTitle?.let { Greetings.shortTitle(it, lastArtist) }?.takeIf { it.isNotEmpty() }
        val topSong = if (topTrackWeekPlays > 0) topTrackWeekTitle?.let { Greetings.shortTitle(it, null) } else null
        return GreetingData { token ->
            when (token) {
                "song" -> song
                "artist" -> if (song != null) Greetings.artistOrNull(nowArtist) else null
                "plays" -> if (song != null && nowId != null) weekPlays[nowId] ?: 0 else null
                GreetingTokens.PLAYING -> if (song != null) nowPlaying else null
                GreetingTokens.SONG_LIKED -> if (song != null) nowLiked else null
                "last" -> last
                "lastartist" -> if (last != null) Greetings.artistOrNull(lastArtist) else null
                "today" -> playsToday
                "topsong" -> topSong?.takeIf { it.isNotEmpty() }
                "topsongplays" -> topTrackWeekPlays
                "topartist" -> if (topArtistWeekPlays > 0) Greetings.artistOrNull(topArtistWeek) else null
                "topartistplays" -> topArtistWeekPlays
                "artists" -> artistsWeek
                "liked" -> likedCount
                "likedsong" -> recentLikedTitle?.let { Greetings.shortTitle(it, null) }?.takeIf { it.isNotEmpty() }
                "likedartist" -> if (topLikedArtistCount > 0) Greetings.artistOrNull(topLikedArtist) else null
                "likedartistsongs" -> topLikedArtistCount
                "playlists" -> playlistCount
                "playlist" -> newestPlaylist?.let { Greetings.cut(it.trim(), 18) }?.takeIf { it.isNotEmpty() }
                "bigplaylist" -> if (biggestPlaylistSongs > 0)
                    biggestPlaylist?.let { Greetings.cut(it.trim(), 18) }?.takeIf { it.isNotEmpty() } else null
                "bigplaylistsongs" -> biggestPlaylistSongs
                "weekday" -> now.getDisplayName(Calendar.DAY_OF_WEEK, Calendar.LONG, Locale.ENGLISH)
                else -> null
            }
        }
    }
}

/**
 * The Home greetings, two lines each.
 *
 * 5.1.0: the only greetings built into the app are the time-based ones: [TIME_BASED] (shown first
 * whenever the app opens, with or without a name) and [TIME_NAMED] (a second time-of-day line that
 * needs a saved name). Everything else comes from greetings/greetings.json in the public repo, kept
 * in [remote] by RemoteGreetingsSync:
 * - "anytime" lines (ids from [ANYTIME_BASE]) join the regular bag with the time-based ones
 * - stat lines (ids from [STAT_BASE]) talk about the user's listening ({song}, {plays}, {today}...)
 *   and take every second slot when one fits
 * - occasion lines (ids from [OCCASION_BASE]) only fit inside their dates and get their own slots
 *
 * [Rotation] mixes them. Nothing repeats twice in a row unless it's the only greeting there is.
 */
object Greetings {

    const val TIME_BASED = 0
    const val TIME_NAMED = 1

    /** The built-in greetings, as the regular bag hands them out. */
    val REGULAR_IDS: List<Int> = listOf(TIME_BASED, TIME_NAMED)

    // ---- Remote greetings (v4.9.6, stats 5.1.0) --------------------------------------------------

    const val ANYTIME_BASE = 1000
    const val STAT_BASE = 3000
    const val OCCASION_BASE = 5000

    /** The greetings file from GitHub currently in use (empty until one is loaded). */
    @Volatile
    var remote: RemoteGreetingFile = RemoteGreetingFile.EMPTY

    /**
     * 5.5.2: the festival theme's greetings while one is on (or previewed), else null. While set,
     * the regular greetings (built-in and from GitHub) pause and only these rotate; they come back
     * on their own when the festival ends. Only set once its file is on the phone, so Home never
     * goes blank.
     */
    @Volatile
    var theme: RemoteGreetingFile? = null

    /** 5.5.2: the festival's whole greetings set (its top artist takeover lines too), or null. */
    @Volatile
    var themeSet: com.minim.music.data.themes.ThemeGreetingSet? = null

    /** The greetings in use: the festival's while one is on, else the regular file. */
    val active: RemoteGreetingFile get() = theme ?: remote

    /** Whether a festival's greetings are in use (the regular ones paused). */
    val themeOn: Boolean get() = theme != null

    fun isAnytime(index: Int): Boolean = index in ANYTIME_BASE until ANYTIME_BASE + RemoteGreetingsParser.MAX_ENTRIES

    fun isStat(index: Int): Boolean = index in STAT_BASE until STAT_BASE + RemoteGreetingsParser.MAX_ENTRIES

    fun isOccasion(index: Int): Boolean = index in OCCASION_BASE until OCCASION_BASE + RemoteGreetingsParser.MAX_ENTRIES

    fun isRemote(index: Int): Boolean = isAnytime(index) || isStat(index) || isOccasion(index)

    fun remoteGreeting(id: Int, file: RemoteGreetingFile = active): RemoteGreeting? = when {
        isAnytime(id) -> file.anytime.getOrNull(id - ANYTIME_BASE)
        isStat(id) -> file.stats.getOrNull(id - STAT_BASE)
        isOccasion(id) -> file.occasions.getOrNull(id - OCCASION_BASE)
        else -> null
    }

    /**
     * Line 1 and line 2 of remote greeting [id], or null when it can't show now: it's gone from the
     * file, it's outside its dates, it uses {name} and no name is saved, or (5.1.0) one of its
     * placeholders or conditions has no data in [facts].
     */
    fun remoteLines(
        id: Int,
        name: String,
        now: Calendar = Calendar.getInstance(),
        file: RemoteGreetingFile = active,
        facts: GreetingFacts = GreetingFacts()
    ): Pair<String, String>? {
        val g = remoteGreeting(id, file) ?: return null
        return g.render(name, facts.toData(now), now)
    }

    /** Whether greeting [id] can show right now ([TIME_BASED] always can). */
    fun fits(
        id: Int,
        facts: GreetingFacts,
        name: String,
        now: Calendar = Calendar.getInstance(),
        file: RemoteGreetingFile = active
    ): Boolean = when {
        isRemote(id) -> remoteLines(id, name, now, file, facts) != null
        id == TIME_NAMED -> name.isNotBlank()
        else -> id == TIME_BASED
    }

    /**
     * Line 1 and line 2 of built-in greeting [index] for [name] (blank = no name set). [TIME_NAMED]
     * without a name gives the [TIME_BASED] lines.
     */
    fun lines(index: Int, name: String, now: Calendar = Calendar.getInstance()): Pair<String, String> {
        val n = name.trim()
        val has = n.isNotEmpty()
        val hour = now.get(Calendar.HOUR_OF_DAY)
        if (index == TIME_NAMED && has) {
            return when (hour) {
                in 0..4 -> "Still up, $n?" to "So is the music."
                in 5..11 -> "Morning, $n." to "Ease into it."
                in 12..16 -> "Afternoon, $n." to "Need a pick-me-up?"
                else -> "Evening, $n." to "What's on tonight?"
            }
        }
        val hi = if (has) "Hi $n!" else "Hi there!"
        return when (hour) {
            in 0..4 -> (if (has) "Up late, $n?" else "Up late?") to "Night tunes it is."
            in 5..11 -> hi to "Good Morning."
            in 12..16 -> hi to "Good Afternoon."
            else -> hi to "Good Evening."
        }
    }

    /** A short artist name for a greeting, or null for none / "Unknown Artist". */
    fun artistOrNull(raw: String?): String? {
        if (raw.isNullOrBlank() || raw.trim().equals("Unknown Artist", ignoreCase = true)) return null
        return shortArtist(raw).takeIf { it.isNotEmpty() && !it.equals("Unknown Artist", ignoreCase = true) }
    }

    // ---- Shortening long names ------------------------------------------------------------------

    private val BRACKETS = SafeRegex.of("\\s*[(\\[][^)\\]]*[)\\]]")
    private val FEAT = SafeRegex.of("\\s+(feat\\.?|ft\\.?|featuring)\\s+.*$", RegexOption.IGNORE_CASE)
    private val SPACES = SafeRegex.of("\\s+")

    /**
     * A song title as people say it: no "(Official Video)", "[NCS Release]" or "feat. ...", no
     * "Artist - " in front, then at most [max] characters, cut at a word with "…".
     */
    fun shortTitle(raw: String, artist: String?, max: Int = 22): String {
        var s = raw.trim()
        s = s.replace(BRACKETS, "")
        s = s.substringBefore(" | ")
        // "Cartoon, Jéja - On & On": drop the artist part when the bit before the dash names them
        val dash = s.indexOf(" - ")
        if (dash > 0) {
            val before = s.substring(0, dash).lowercase()
            val first = artist?.let { firstArtist(it) }?.lowercase().orEmpty()
            if (first.isNotEmpty() && (before.contains(first) || first.contains(before))) {
                s = s.substring(dash + 3)
            }
        }
        s = s.replace(FEAT, "").replace(SPACES, " ").trim().trim('-', '|', ':', ' ')
        if (s.isEmpty()) s = raw.trim()
        return cut(s, max)
    }

    /**
     * An artist name for a greeting (v4.7): the first artist only ("Cartoon, Jéja & Daniel Levi"
     * -> "Cartoon"), no " - Topic". Then, when it is longer than 12 characters, only its first
     * word ("Anirudh Ravichander" -> "Anirudh"), except names that start with "The" or with
     * initials ("The Local Train", "A.R. Rahman"), which stay whole. 12 characters or fewer stay
     * whole. A first word longer than [max] is still cut with "…" as a safety net.
     * Greetings only: everywhere else in the app shows the full name.
     */
    fun shortArtist(raw: String, max: Int = 18): String {
        val s = firstArtist(raw)
        if (s.length <= GREETING_ARTIST_MAX) return s
        val words = s.split(' ')
        if (words.size < 2) return cut(s, max)
        if (words[0].equals("The", ignoreCase = true) || isInitials(words[0])) return s
        return cut(words[0], max)
    }

    /** Names up to this many characters stay whole in greetings. */
    const val GREETING_ARTIST_MAX = 12

    private val DOTTED_INITIALS = SafeRegex.of("^(\\p{L}\\.)+\\p{L}?\\.?$")

    /** "A.R.", "J.", "A", "SP": dotted initials, a single letter, or 2-3 capitals before the surname. */
    private fun isInitials(word: String): Boolean =
        DOTTED_INITIALS.matches(word) || word.length == 1 ||
            (word.length in 2..3 && word.all { it.isLetter() && it.isUpperCase() })

    /** The first artist only, no " - Topic", no brackets or "feat. ...", never cut. */
    private fun firstArtist(raw: String): String {
        var s = raw.trim()
        s = s.removeSuffix(" - Topic").replace(BRACKETS, "")
        s = s.replace(FEAT, "")
        for (sep in listOf(", ", " & ", " x ", " X ", " × ", " / ", " vs ", " vs. ")) {
            val i = s.indexOf(sep)
            if (i > 0) s = s.substring(0, i)
        }
        if (s.endsWith("VEVO") && s.length > 4) s = s.removeSuffix("VEVO")
        s = s.replace(SPACES, " ").trim()
        if (s.isEmpty()) s = raw.trim()
        return s
    }

    /** At most [max] characters; cut at the last space when there is one in the back half, then "…". */
    fun cut(s: String, max: Int): String {
        if (s.length <= max) return s
        val hard = s.substring(0, max - 1)
        val space = hard.lastIndexOf(' ')
        val base = if (space >= max / 2) hard.substring(0, space) else hard
        return base.trimEnd(' ', ',', '-', ':', '&', '.') + "…"
    }

    // ---- Picking the next greeting --------------------------------------------------------------

    /**
     * Every second greeting is a stat one when one fits, else a regular one. Stat ones come from
     * their own shuffle bag and are skipped when their data isn't there; regular ones use the
     * regular bag (the time-based greetings plus the remote "anytime" lines). Never the same
     * greeting twice in a row, unless the time-based one is the only greeting that fits.
     *
     * Occasion lines (v4.9.6) take their own slots while their dates are on: every 6th greeting
     * inside the range, and on the day itself every 3rd one, starting with the first greeting after
     * the time-based one. [remoteSource] gives the remote file in use; when it changes the bags are
     * rebuilt, so new lines join without a restart.
     */
    class Rotation(
        private val random: Random = Random.Default,
        private val remoteSource: () -> RemoteGreetingFile = { active }
    ) {
        private var file: RemoteGreetingFile? = null
        private var regularIds: List<Int> = REGULAR_IDS
        private var regular = ShuffleBag(REGULAR_IDS.size, random)
        private var statIds: List<Int> = emptyList()
        private var stats: ShuffleBag? = null
        private val recentOccasions = ArrayList<Int>()
        private var slot = 0
        var last: Int = TIME_BASED
            private set

        /** Rebuilds the bags when the remote file changed. */
        private fun sync(): RemoteGreetingFile {
            val f = remoteSource()
            if (f !== file) {
                file = f
                // 5.5.2: a festival's lines replace the regular ones (time-based included)
                val anytimeIds = f.anytime.indices.map { ANYTIME_BASE + it }
                regularIds = if (f.exclusive && anytimeIds.isNotEmpty()) anytimeIds else REGULAR_IDS + anytimeIds
                regular = ShuffleBag(regularIds.size, random)
                statIds = f.stats.indices.map { STAT_BASE + it }
                stats = if (statIds.isEmpty()) null else ShuffleBag(statIds.size, random)
                recentOccasions.clear()
            }
            return f
        }

        /** The time-based greeting was shown on open: the next pick is a stat one (if any fits). */
        fun markShown(index: Int) {
            sync()
            regularIds.indexOf(index).takeIf { it >= 0 }?.let { regular.markShown(it) }
            last = index
            slot = 0
        }

        /**
         * The next greeting index; [fits] says whether a greeting can show right now (for occasion
         * ones that includes their dates). [now] decides the day itself.
         */
        fun next(now: Calendar = Calendar.getInstance(), fits: (Int) -> Boolean): Int {
            val f = sync()
            slot++
            if (f.occasions.isNotEmpty()) {
                val active = f.occasions.indices.map { OCCASION_BASE + it }.filter { fits(it) }
                if (active.isNotEmpty()) {
                    val mainDay = active.filter { f.occasions[it - OCCASION_BASE].range?.isMainDay(now) == true }
                    val due = if (mainDay.isNotEmpty()) slot % 3 == 1 else slot % 6 == 3
                    if (due) pickOccasion(active, mainDay)?.let { id ->
                        last = id
                        return id
                    }
                }
            }
            val bag = stats
            if (slot % 2 == 1 && bag != null) {
                repeat(statIds.size * 2) {
                    val id = statIds[bag.next()]
                    if (id != last && fits(id)) {
                        last = id
                        return id
                    }
                }
            }
            return nextRegular(fits)
        }

        /** An occasion line, preferring the day-itself ones and avoiding the most recent picks. */
        private fun pickOccasion(active: List<Int>, mainDay: List<Int>): Int? {
            val pool = if (mainDay.isNotEmpty() && random.nextInt(4) != 0) mainDay else active
            val fresh = pool.filter { it != last && it !in recentOccasions }
            val candidates = fresh.ifEmpty { pool.filter { it != last } }
            if (candidates.isEmpty()) return null
            val id = candidates[random.nextInt(candidates.size)]
            recentOccasions.add(id)
            while (recentOccasions.size > maxOf(1, minOf(8, active.size - 1))) recentOccasions.removeAt(0)
            return id
        }

        /**
         * A regular greeting (also used when a stat one stops fitting before it is drawn).
         * [TIME_BASED] always fits; the named time greeting and the remote anytime lines only when
         * [fits] says so. When nothing but the greeting already on screen fits, it's [TIME_BASED].
         */
        fun nextRegular(fits: (Int) -> Boolean = { false }): Int {
            sync()
            val ids = regularIds
            repeat(ids.size * 2) {
                val id = ids[regular.next()]
                if (id != last && (id == TIME_BASED || fits(id))) {
                    last = id
                    return id
                }
            }
            // 5.5.2: during a festival, the line on screen again beats leaving the festival
            if (file?.exclusive == true && last != TIME_BASED && last in ids && fits(last)) return last
            last = TIME_BASED
            return TIME_BASED
        }

        /**
         * 5.5.2: the greeting shown when the app opens. Normally the built-in time-based one; during
         * a festival one of its time-of-day lines (those with "hours") that fits now, else any of its
         * lines that fits, else the time-based one. Call [markShown] with it, like before.
         */
        fun opener(fits: (Int) -> Boolean): Int {
            val f = sync()
            if (!f.exclusive) return TIME_BASED
            val timed = f.anytime.indices.filter { f.anytime[it].rule.fromHour >= 0 }
                .map { ANYTIME_BASE + it }.filter { fits(it) }
            if (timed.isNotEmpty()) return timed[random.nextInt(timed.size)]
            val any = regularIds.filter { it != TIME_BASED && it != TIME_NAMED && fits(it) }
            return if (any.isNotEmpty()) any[random.nextInt(any.size)] else TIME_BASED
        }
    }

    /** Hands out greeting indexes in a random order with no repeats until the bag is empty. */
    class ShuffleBag(private val count: Int, private val random: Random = Random.Default) {
        private val bag = ArrayList<Int>()
        var last: Int = TIME_BASED
            private set

        init {
            require(count > 0) { "empty bag" }
        }

        /** Call when a greeting is shown outside the bag (the time-based one on open). */
        fun markShown(index: Int) {
            if (bag.isEmpty()) refill()
            bag.remove(index)
            last = index
        }

        fun next(): Int {
            if (bag.isEmpty()) refill()
            val pick = bag.removeAt(0)
            last = pick
            return pick
        }

        private fun refill() {
            bag.addAll((0 until count).shuffled(random))
            // Never the same greeting twice in a row across a refill
            if (bag.size > 1 && bag[0] == last) {
                val swap = 1 + random.nextInt(bag.size - 1)
                bag[0] = bag[swap].also { bag[swap] = bag[0] }
            }
        }
    }
}
