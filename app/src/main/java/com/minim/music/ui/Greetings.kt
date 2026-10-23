package com.minim.music.ui

import java.util.Calendar
import java.util.Locale
import kotlin.random.Random

/**
 * Everything the fun (data-based) greetings can talk about. The library part comes from one cheap
 * read of the local database ([com.minim.music.data.local.LibraryDatabase.getGreetingFacts]); the
 * "now" part is filled in from the player at the moment a greeting is shown. Missing data = null / 0.
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
    val biggestPlaylistSongs: Int = 0
) {
    val hasHistory: Boolean get() = !lastTitle.isNullOrBlank()
}

/**
 * The Home greetings, two lines each.
 *
 * Regular: 15 greetings (indexes 0..14). Index 0 is the time-based one, always shown first when the
 * app opens. Each works with or without a name.
 *
 * Fun (v4.5.0): 27 greetings built from the user's own data (ids in [DATA_IDS]). One only shows when
 * its data exists: [dataLines] returns null otherwise.
 *
 * Personal (v4.7.0): 6 greetings that use the user's name (ids in [NAME_IDS], only when a name is
 * saved) and 4 for users who skipped it (ids in [NO_NAME_IDS], only when no name is saved). They
 * share the regular greetings' bag: [personalLines] returns null when one doesn't fit.
 *
 * [Rotation] mixes them: every second greeting is a fun one when one fits, else a regular one.
 * Nothing repeats twice in a row.
 */
object Greetings {

    const val COUNT = 15
    const val TIME_BASED = 0

    /** Ids of the fun greetings (gaps keep the groups readable). */
    val DATA_IDS: List<Int> = listOf(
        100, 101, 102, 103, 104, 105,                  // now playing
        110, 111, 112, 113, 114, 115, 116, 117, 118,   // recents
        120, 121, 122, 123, 124,                       // liked
        130, 131, 132,                                 // playlists
        140, 141, 142, 143                             // time of day / week
    )

    /** v4.7: greetings that use the saved name, and ones for users who skipped it. */
    val NAME_IDS: List<Int> = listOf(200, 201, 202, 203, 204, 205)
    val NO_NAME_IDS: List<Int> = listOf(210, 211, 212, 213)

    /** Regular greetings plus the personal ones, as the regular bag hands them out. */
    val REGULAR_IDS: List<Int> = (0 until COUNT).toList() + NAME_IDS + NO_NAME_IDS

    /** 15 regular + 27 fun + 10 personal = 52. */
    val TOTAL: Int get() = REGULAR_IDS.size + DATA_IDS.size

    fun isData(index: Int): Boolean = index in 100..199

    fun isPersonal(index: Int): Boolean = index >= 200

    /**
     * Line 1 and line 2 of personal greeting [id], or null when it doesn't fit: name greetings
     * need a saved [name], the no-name ones need it blank.
     */
    fun personalLines(id: Int, name: String, now: Calendar = Calendar.getInstance()): Pair<String, String>? {
        val n = name.trim()
        val has = n.isNotEmpty()
        if (id in NAME_IDS && !has) return null
        if (id in NO_NAME_IDS && has) return null
        return when (id) {
            200 -> "$n's back." to "Press play."
            201 -> when (now.get(Calendar.HOUR_OF_DAY)) {
                in 0..4 -> "Still up, $n?" to "So is the music."
                in 5..11 -> "Morning, $n." to "Ease into it."
                in 12..16 -> "Afternoon, $n." to "Need a pick-me-up?"
                else -> "Evening, $n." to "What's on tonight?"
            }
            202 -> "Your call, $n." to "What are we playing?"
            203 -> "Psst, $n." to "Your music missed you."
            204 -> "Look who's here." to "Hi, $n."
            205 -> "$n on aux." to "Good choice."
            210 -> "Hey, you." to "Yeah, you."
            211 -> "No name, no problem." to "Just press play."
            212 -> "Mystery listener," to "welcome back."
            213 -> "Hello, stranger." to "Got a tune in mind?"
            else -> null
        }
    }

    /** Whether greeting [id] can show right now (regular ones always can). */
    fun fits(id: Int, facts: GreetingFacts, name: String, now: Calendar = Calendar.getInstance()): Boolean = when {
        isData(id) -> dataLines(id, facts, now) != null
        isPersonal(id) -> personalLines(id, name, now) != null
        else -> true
    }

    /** Line 1 and line 2 of regular greeting [index] for [name] (blank = no name set). */
    fun lines(index: Int, name: String, now: Calendar = Calendar.getInstance()): Pair<String, String> {
        val n = name.trim()
        val has = n.isNotEmpty()
        val hi = if (has) "Hi $n!" else "Hi there!"
        return when (index) {
            0 -> {
                val hour = now.get(Calendar.HOUR_OF_DAY)
                when (hour) {
                    in 0..4 -> (if (has) "Up late, $n?" else "Up late?") to "Night tunes it is."
                    in 5..11 -> hi to "Good Morning."
                    in 12..16 -> hi to "Good Afternoon."
                    else -> hi to "Good Evening."
                }
            }
            1 -> hi to "Happy ${now.getDisplayName(Calendar.DAY_OF_WEEK, Calendar.LONG, Locale.ENGLISH)}."
            2 -> hi to "What do you wanna listen to?"
            3 -> hi to "Ready for a new favourite?"
            4 -> (if (has) "Hey $n," else "Hey there,") to "got a song stuck in your head?"
            5 -> if (has) "Welcome back," to "$n." else "Hey there," to "welcome back."
            6 -> (if (has) "$n, press play" else "Press play") to "on something good."
            7 -> if (has) "One more song," to "$n?" else "One more" to "song?"
            8 -> "What's the mood" to (if (has) "today, $n?" else "today?")
            9 -> if (has) "Turn it up," to "$n." else "Go on," to "turn it up."
            10 -> if (has) "Headphones on," to "$n?" else "Got your" to "headphones on?"
            11 -> "Find your next" to (if (has) "repeat, $n." else "repeat.")
            12 -> "Let the music" to (if (has) "take over, $n." else "take over.")
            13 -> if (has) "Good vibes only," to "$n." else "Good vibes" to "only."
            else -> "Your soundtrack" to (if (has) "starts here, $n." else "starts here.")
        }
    }

    /**
     * Line 1 and line 2 of fun greeting [id], or null when its data isn't there (nothing playing,
     * no history, 0 liked songs, no playlists, wrong time of day...).
     */
    fun dataLines(id: Int, f: GreetingFacts, now: Calendar = Calendar.getInstance()): Pair<String, String>? {
        val hour = now.get(Calendar.HOUR_OF_DAY)
        val nowT = f.nowTitle?.let { shortTitle(it, f.nowArtist) }?.takeIf { it.isNotEmpty() }
        val nowA = f.nowArtist?.let { shortArtist(it) }?.takeIf { it.isNotEmpty() }
        val lastT = f.lastTitle?.let { shortTitle(it, f.lastArtist) }?.takeIf { it.isNotEmpty() }
        val topT = f.topTrackWeekTitle?.let { shortTitle(it, null) }?.takeIf { it.isNotEmpty() }
        val topA = f.topArtistWeek?.let { shortArtist(it) }?.takeIf { it.isNotEmpty() }
        val likedT = f.recentLikedTitle?.let { shortTitle(it, null) }?.takeIf { it.isNotEmpty() }
        val likedA = f.topLikedArtist?.let { shortArtist(it) }?.takeIf { it.isNotEmpty() }
        val newestP = f.newestPlaylist?.let { cut(it.trim(), 18) }?.takeIf { it.isNotEmpty() }
        val biggestP = f.biggestPlaylist?.let { cut(it.trim(), 18) }?.takeIf { it.isNotEmpty() }
        return when (id) {
            // ---- The song in the player ----
            100 -> if (nowT != null && f.nowPlaying && !f.nowLiked) "Enjoying $nowT?" to "The heart's right there." else null
            101 -> if (nowT != null && f.nowLiked) "Ah, $nowT." to "A certified favourite." else null
            102 -> if (nowA != null && f.nowPlaying && !isUnknown(nowA)) "$nowA, huh?" to "Excellent taste." else null
            103 -> if (nowT != null && f.nowPlaying) "Now playing" to "$nowT. Carry on." else null
            104 -> if (nowT != null && !f.nowPlaying) "Paused on" to "$nowT. Ready?" else null
            105 -> if (nowT != null && f.nowPlaying && hour in 0..4) "At this hour?" to "$nowT? Bold." else null

            // ---- Recents ----
            110 -> if (lastT != null && f.lastId != f.nowId) "Last time it was" to "$lastT." else null
            111 -> if (topA != null && f.topArtistWeekPlays >= 3 && !isUnknown(topA)) "Back for more" to "$topA?" else null
            112 -> if (topA != null && f.topArtistWeekPlays >= 5 && !isUnknown(topA))
                "$topA: ${f.topArtistWeekPlays} plays" to "this week. A phase?" else null
            113 -> if (topT != null && f.topTrackWeekPlays >= 3) "Still on $topT?" to "Respect." else null
            114 -> if (topT != null && f.topTrackWeekPlays >= 4)
                "$topT, ${f.topTrackWeekPlays} plays." to "Not that we're counting." else null
            115 -> if (f.playsToday in 3..19) "${f.playsToday} songs today." to "Keep it going." else null
            116 -> if (f.playsToday >= 20) "${f.playsToday} songs today?" to "That's a marathon." else null
            117 -> if (f.hasHistory && f.playsToday == 0 && hour >= 8) "Nothing played today." to "Let's fix that." else null
            118 -> if (f.artistsWeek >= 5) "${f.artistsWeek} artists this week." to "Very well-rounded." else null

            // ---- Liked ----
            120 -> when {
                f.likedCount == 1 -> "One liked song." to "Picky. We like it."
                f.likedCount >= 2 -> "${f.likedCount} liked songs" to "and counting."
                else -> null
            }
            121 -> if (likedT != null) "Recently liked:" to "$likedT. Good call." else null
            122 -> if (f.likedCount >= 10) "${f.likedCount} liked songs." to "Tap here to shuffle one." else null
            123 -> if (likedA != null && f.topLikedArtistCount >= 3 && !isUnknown(likedA))
                "${f.topLikedArtistCount} liked songs by" to "$likedA. Noted." else null
            124 -> if (f.likedCount >= 50) "${f.likedCount} favourites?" to "That's commitment." else null

            // ---- Playlists ----
            130 -> when {
                f.playlistCount == 1 -> "One playlist." to "It's a start."
                f.playlistCount >= 2 -> "${f.playlistCount} playlists." to "Look at you, organised."
                else -> null
            }
            131 -> if (newestP != null) "How's \"$newestP\"" to "coming along?" else null
            132 -> if (biggestP != null && f.biggestPlaylistSongs >= 10)
                "\"$biggestP\" has" to "${f.biggestPlaylistSongs} songs now." else null

            // ---- Time of day / week ----
            140 -> if (topA != null && f.topArtistWeekPlays >= 2 && hour >= 17 && !isUnknown(topA))
                "An evening with" to "$topA?" else null
            141 -> if (lastT != null && hour in 5..11 && f.lastId != f.nowId) "Morning! Start with" to "$lastT again?" else null
            142 -> if (lastT != null && hour in 0..4) "Night owl mode." to "Last up: $lastT." else null
            143 -> {
                val a = topA ?: lastArtistShort(f)
                if (a != null && !isUnknown(a) && now.get(Calendar.DAY_OF_WEEK) == Calendar.FRIDAY)
                    "It's Friday." to "Turn $a up." else null
            }
            else -> null
        }
    }

    private fun lastArtistShort(f: GreetingFacts): String? =
        f.lastArtist?.let { shortArtist(it) }?.takeIf { it.isNotEmpty() }

    private fun isUnknown(artist: String): Boolean = artist.equals("Unknown Artist", ignoreCase = true)

    // ---- Shortening long names ------------------------------------------------------------------

    private val BRACKETS = Regex("\\s*[(\\[][^)\\]]*[)\\]]")
    private val FEAT = Regex("\\s+(feat\\.?|ft\\.?|featuring)\\s+.*$", RegexOption.IGNORE_CASE)
    private val SPACES = Regex("\\s+")

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

    private val DOTTED_INITIALS = Regex("^(\\p{L}\\.)+\\p{L}?\\.?$")

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
     * Every second greeting is a fun one when one fits, else a regular one. Fun ones come from
     * their own shuffle bag and are skipped when their data isn't there; regular ones use the
     * 15-greeting bag as before. Never the same greeting twice in a row.
     */
    class Rotation(private val random: Random = Random.Default) {
        private val regular = ShuffleBag(REGULAR_IDS.size, random)
        private val fun_ = ShuffleBag(DATA_IDS.size, random)
        private var slot = 0
        var last: Int = TIME_BASED
            private set

        /** The time-based greeting was shown on open: the next pick is a fun one (if any fits). */
        fun markShown(index: Int) {
            if (!isData(index)) REGULAR_IDS.indexOf(index).takeIf { it >= 0 }?.let { regular.markShown(it) }
            last = index
            slot = 0
        }

        /** The next greeting index; [fits] says whether a fun greeting's data is there right now. */
        fun next(fits: (Int) -> Boolean): Int {
            slot++
            if (slot % 2 == 1) {
                repeat(DATA_IDS.size * 2) {
                    val id = DATA_IDS[fun_.next()]
                    if (id != last && fits(id)) {
                        last = id
                        return id
                    }
                }
            }
            return nextRegular(fits)
        }

        /**
         * A regular greeting (also used when a fun one stops fitting before it is drawn). The 15
         * regular ones always fit; a personal one (v4.7) only when [fits] says so.
         */
        fun nextRegular(fits: (Int) -> Boolean = { false }): Int {
            repeat(REGULAR_IDS.size * 2) {
                val id = REGULAR_IDS[regular.next()]
                if (id != last && (!isPersonal(id) || fits(id))) {
                    last = id
                    return id
                }
            }
            // Can't happen (15 regular ones always fit), but never return a repeat
            val id = (0 until COUNT).first { it != last }
            last = id
            return id
        }
    }

    /** Hands out greeting indexes in a random order with no repeats until the bag is empty. */
    class ShuffleBag(private val count: Int = COUNT, private val random: Random = Random.Default) {
        private val bag = ArrayList<Int>()
        var last: Int = TIME_BASED
            private set

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
