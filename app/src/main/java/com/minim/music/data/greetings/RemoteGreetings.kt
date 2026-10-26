package com.minim.music.data.greetings

import com.minim.music.util.SafeRegex
import java.util.Calendar

/**
 * v4.9.6: greetings that live in greetings/greetings.json in the public GitHub repo, so new lines
 * and festival greetings can be added from GitHub without a new build.
 *
 * File format (documented for humans in the repo's README, "Editing greetings"):
 *
 * {
 *   "version": 1,
 *   "greetings": [
 *     { "text": "Sunday mode.\nNo skips allowed." },
 *     { "name": "Diwali", "text": "Happy Diwali!\nLights up, volume up.",
 *       "from": "11-06", "to": "11-10", "day": "11-08", "year": 2026 }
 *   ]
 * }
 *
 * - text: required. "\n" splits the two lines; without one the text is split at a good point.
 *   "{name}" is replaced with the saved name, and a line that uses it only shows when a name is saved.
 * - from / to: "MM-DD". With them the greeting is an occasion one that only shows on those days
 *   (to defaults to from; a range like 12-31 to 01-01 crosses the new year). Without them it is an
 *   "anytime" line mixed into the normal rotation.
 * - day: "MM-DD", the main day inside the range, when the greeting comes up most (defaults to from).
 * - year: only that year (the range starts in that year; a range crossing the new year ends in the next).
 * - Anything else (like "name") is ignored, so it can be used for notes.
 */
data class DateRange(
    val fromMonth: Int,
    val fromDay: Int,
    val toMonth: Int,
    val toDay: Int,
    val year: Int? = null,
    val mainMonth: Int = fromMonth,
    val mainDay: Int = fromDay
) {
    private val from = fromMonth * 100 + fromDay
    private val to = toMonth * 100 + toDay
    private val main = mainMonth * 100 + mainDay

    val crossesNewYear: Boolean get() = from > to

    /** Whether [y]-[m]-[d] (month 1..12) is inside the range. */
    fun matches(y: Int, m: Int, d: Int): Boolean {
        val md = m * 100 + d
        return if (!crossesNewYear) {
            md in from..to && (year == null || y == year)
        } else {
            when {
                year == null -> md >= from || md <= to
                md >= from -> y == year
                md <= to -> y == year + 1
                else -> false
            }
        }
    }

    /** Whether [y]-[m]-[d] is the main day (the day itself) of the range. */
    fun isMainDay(y: Int, m: Int, d: Int): Boolean = matches(y, m, d) && m * 100 + d == main

    fun matches(c: Calendar): Boolean =
        matches(c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH))

    fun isMainDay(c: Calendar): Boolean =
        isMainDay(c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH))

    /** Whether month/day [m]-[d] lies inside the range, ignoring the year. */
    internal fun containsMonthDay(m: Int, d: Int): Boolean {
        val md = m * 100 + d
        return if (!crossesNewYear) md in from..to else md >= from || md <= to
    }
}

/**
 * 5.1.0: what a greeting can talk about besides {name}. [value] gives one token's data right now:
 * a String (a song, an artist...), an Int (a count) or a Boolean (the [GreetingTokens.PLAYING] and
 * [GreetingTokens.SONG_LIKED] flags), or null when there's nothing to say.
 */
fun interface GreetingData {
    fun value(token: String): Any?

    companion object {
        val NONE = GreetingData { null }
    }
}

/** 5.1.0: the placeholders a greeting may use, written as {token} in its text. */
object GreetingTokens {
    const val NAME = "name"

    /** Words, replaced with a short name. */
    val WORDS: Set<String> = setOf(
        "song", "artist", "last", "lastartist", "topsong", "topartist",
        "likedsong", "likedartist", "playlist", "bigplaylist", "weekday"
    )

    /** Counts, replaced with the number. A line that uses one needs at least 1 unless "min" says less. */
    val COUNTS: Set<String> = setOf(
        "plays", "today", "liked", "topsongplays", "topartistplays", "artists",
        "likedartistsongs", "playlists", "bigplaylistsongs"
    )

    val STATS: Set<String> = WORDS + COUNTS
    val KNOWN: Set<String> = STATS + NAME

    /** Flags (not placeholders): whether the song in the player is playing, and whether it's liked. */
    const val PLAYING = "playing"
    const val SONG_LIKED = "songliked"

    val TOKEN = SafeRegex.of("\\{([A-Za-z_]+)\\}")

    fun tokensIn(text: String): Set<String> {
        if (text.indexOf('{') < 0) return emptySet()
        return TOKEN.findAll(text).map { it.groupValues[1] }.toSet()
    }
}

/**
 * 5.1.0: the extra conditions a greeting can have. Every one that is set must hold.
 * - [min] / [max]: a count must be at least / at most this
 * - [needs]: placeholders that must have data even though the text doesn't use them
 * - [fromHour]..[toHour]: hours of the day (0-23, may wrap past midnight like 22-3); -1 = any hour
 * - [days]: days of the week ([Calendar.DAY_OF_WEEK] values); empty = any day
 * - [playing]: the song in the player is playing (true) or paused (false)
 * - [liked]: the song in the player is liked (true) or not (false)
 */
data class GreetingRule(
    val min: Map<String, Int> = emptyMap(),
    val max: Map<String, Int> = emptyMap(),
    val needs: Set<String> = emptySet(),
    val fromHour: Int = -1,
    val toHour: Int = -1,
    val days: Set<Int> = emptySet(),
    val playing: Boolean? = null,
    val liked: Boolean? = null
) {
    val isEmpty: Boolean get() = this == NONE

    fun hourOk(hour: Int): Boolean = when {
        fromHour < 0 -> true
        fromHour <= toHour -> hour in fromHour..toHour
        else -> hour >= fromHour || hour <= toHour
    }

    companion object {
        val NONE = GreetingRule()
    }
}

/**
 * One remote greeting, already split into the two Home lines. [range] null = an anytime line.
 * 5.1.0: [rule] holds extra conditions, and the text may use the [GreetingTokens] placeholders.
 */
data class RemoteGreeting(
    val line1: String,
    val line2: String,
    val range: DateRange? = null,
    val rule: GreetingRule = GreetingRule.NONE
) {
    val isOccasion: Boolean get() = range != null
    val usesName: Boolean get() = line1.contains(NAME_TOKEN) || line2.contains(NAME_TOKEN)

    /** Every placeholder in the text. */
    val tokens: Set<String> get() = GreetingTokens.tokensIn(line1) + GreetingTokens.tokensIn(line2)

    /** 5.1.0: whether it talks about the user's listening (a stat placeholder or any condition). */
    val usesStats: Boolean get() = !rule.isEmpty || tokens.any { it != GreetingTokens.NAME }

    /**
     * The lines for [name] (blank = none saved), or null when the greeting needs a name and has none.
     * A greeting that needs listening data can't show without it: use [render].
     */
    fun linesFor(name: String): Pair<String, String>? = render(name, GreetingData.NONE, null)

    /**
     * 5.1.0: the lines with every placeholder filled in, or null when the greeting can't show now:
     * a placeholder has no data, a count is below 1 (or its "min") or above its "max", or the hour,
     * day, playing or liked condition doesn't hold. [now] null skips the date, hour and day checks.
     */
    fun render(name: String, data: GreetingData, now: Calendar?): Pair<String, String>? {
        val n = name.trim()
        if (usesName && n.isEmpty()) return null
        if (now != null) {
            if (range != null && !range.matches(now)) return null
            if (!rule.hourOk(now.get(Calendar.HOUR_OF_DAY))) return null
            if (rule.days.isNotEmpty() && now.get(Calendar.DAY_OF_WEEK) !in rule.days) return null
        } else if (rule.fromHour >= 0 || rule.days.isNotEmpty()) {
            return null
        }
        rule.playing?.let { if (data.value(GreetingTokens.PLAYING) as? Boolean != it) return null }
        rule.liked?.let { if (data.value(GreetingTokens.SONG_LIKED) as? Boolean != it) return null }
        val values = HashMap<String, String>()
        for (t in tokens + rule.needs) {
            if (t == GreetingTokens.NAME) continue
            when (val v = data.value(t)) {
                is String -> if (v.isBlank()) return null else values[t] = v.trim()
                is Int -> if (v < (rule.min[t] ?: 1)) return null else values[t] = v.toString()
                else -> return null
            }
        }
        for ((k, m) in rule.min) if ((data.value(k) as? Int ?: return null) < m) return null
        for ((k, m) in rule.max) if ((data.value(k) as? Int ?: return null) > m) return null
        if (values.isEmpty() && !usesName) return line1 to line2
        fun fill(s: String): String = if (s.indexOf('{') < 0) s else GreetingTokens.TOKEN.replace(s) { m ->
            val t = m.groupValues[1]
            if (t == GreetingTokens.NAME) n else values[t] ?: m.value
        }
        val l1 = fill(line1)
        val l2 = fill(line2)
        if (l1.length > MAX_FILLED_LINE || l2.length > MAX_FILLED_LINE) return null
        return l1 to l2
    }

    companion object {
        const val NAME_TOKEN = "{name}"

        /** 5.1.0: a line longer than this once the placeholders are filled in doesn't show. */
        const val MAX_FILLED_LINE = 48
    }
}

/**
 * A parsed greetings file. [skipped] counts entries that were thrown away as invalid.
 * 5.1.0: [stats] holds the greetings built from the user's listening (the file's "stats" list, plus
 * any line in "greetings" that uses a stat placeholder or a condition).
 */
data class RemoteGreetingFile(
    val version: Int,
    val anytime: List<RemoteGreeting>,
    val occasions: List<RemoteGreeting>,
    val skipped: Int = 0,
    val stats: List<RemoteGreeting> = emptyList()
) {
    val size: Int get() = anytime.size + occasions.size + stats.size

    companion object {
        val EMPTY = RemoteGreetingFile(0, emptyList(), emptyList(), 0)

        /**
         * 5.0.0 part 3: the GitHub file plus the cloud config's greetings ([b] after [a]), each list
         * capped so every id stays inside its id block. The same inputs give an equal result.
         */
        fun merge(a: RemoteGreetingFile, b: RemoteGreetingFile): RemoteGreetingFile {
            if (b.size == 0) return a
            if (a.size == 0) return b
            val max = RemoteGreetingsParser.MAX_ENTRIES
            return RemoteGreetingFile(
                maxOf(a.version, b.version),
                (a.anytime + b.anytime).take(max),
                (a.occasions + b.occasions).take(max),
                a.skipped + b.skipped,
                (a.stats + b.stats).take(max)
            )
        }

        /** 5.1.0: sorts parsed entries into anytime, occasion and stats lists (stats first). */
        fun of(version: Int, entries: List<RemoteGreeting>, skipped: Int = 0): RemoteGreetingFile {
            val max = RemoteGreetingsParser.MAX_ENTRIES
            return RemoteGreetingFile(
                version,
                entries.filter { !it.isOccasion && !it.usesStats }.take(max),
                entries.filter { it.isOccasion }.take(max),
                skipped,
                entries.filter { !it.isOccasion && it.usesStats }.take(max)
            )
        }
    }
}

/**
 * Reads greetings.json. [parse] returns null when the file as a whole is unusable (not JSON, not an
 * object, no "greetings" list, a nonsense version); otherwise every bad entry is skipped and the good
 * ones are kept. Nothing in here throws.
 */
object RemoteGreetingsParser {

    /** 5.1.0: version 2 adds the "stats" list. Older apps (4.9.6-5.0.0) accept any version 1-1000. */
    const val SUPPORTED_VERSION = 2
    const val MAX_ENTRIES = 300
    const val MAX_SCANNED = 2000
    const val MAX_TEXT = 80
    const val MAX_LINE = 40
    const val MIN_YEAR = 2000
    const val MAX_YEAR = 2200

    private val DATE = SafeRegex.of("^\\s*(\\d{1,2})\\s*[-/]\\s*(\\d{1,2})\\s*$")
    private val SPACES = SafeRegex.of("[ \\t\\u00A0]+")

    fun parse(text: String?): RemoteGreetingFile? {
        if (text.isNullOrBlank()) return null
        val root = try {
            MiniJson.parse(text)
        } catch (e: Exception) {
            return null
        } catch (e: StackOverflowError) {
            return null
        }
        if (root !is Map<*, *>) return null
        val version = when (val v = root["version"]) {
            null -> 1
            is Long -> if (v in 1..1000) v.toInt() else return null
            is Double -> if (v >= 1 && v <= 1000 && v == Math.floor(v)) v.toInt() else return null
            else -> return null
        }
        val list = root["greetings"] as? List<*> ?: return null
        val anytime = ArrayList<RemoteGreeting>()
        val occasions = ArrayList<RemoteGreeting>()
        val stats = ArrayList<RemoteGreeting>()
        var skipped = 0
        for ((i, item) in list.withIndex()) {
            if (i >= MAX_SCANNED || anytime.size + occasions.size >= MAX_ENTRIES) {
                skipped += list.size - i
                break
            }
            val g = try { parseEntry(item) } catch (e: Exception) { null }
            when {
                g == null -> skipped++
                g.isOccasion -> occasions.add(g)
                // 5.1.0: a line that talks about the listening belongs with the stats ones
                g.usesStats -> if (stats.size < MAX_ENTRIES) stats.add(g) else skipped++
                else -> anytime.add(g)
            }
        }
        // 5.1.0: the "stats" list. Older apps never read it, so its placeholders can't reach them.
        // A "stats" that isn't a list is ignored (the greetings above still count).
        val statList = root["stats"] as? List<*>
        if (statList != null) {
            for ((i, item) in statList.withIndex()) {
                if (i >= MAX_SCANNED || stats.size >= MAX_ENTRIES) {
                    skipped += statList.size - i
                    break
                }
                val g = try { parseEntry(item) } catch (e: Exception) { null }
                if (g == null) skipped++ else stats.add(g)
            }
        } else if (root["stats"] != null) {
            skipped++
        }
        return RemoteGreetingFile(version, anytime, occasions, skipped, stats)
    }

    /** One entry, or null when it is not usable. */
    fun parseEntry(item: Any?): RemoteGreeting? {
        if (item !is Map<*, *>) return null
        val raw = item["text"] as? String ?: return null
        val (l1, l2) = cleanLines(raw) ?: return null
        // 5.1.0: a placeholder this version doesn't know (a typo, or one from a newer Minim) would
        // show up raw, so the whole line is skipped
        val tokens = GreetingTokens.tokensIn(l1) + GreetingTokens.tokensIn(l2)
        if (tokens.any { it !in GreetingTokens.KNOWN }) return null
        val rule = parseRule(item) ?: return null

        val fromRaw = item["from"]
        val toRaw = item["to"]
        val dayRaw = item["day"]
        val yearRaw = item["year"]
        if (fromRaw == null) {
            // An anytime line: dates without a start make no sense, so they make the entry invalid
            if (toRaw != null || dayRaw != null || yearRaw != null) return null
            return RemoteGreeting(l1, l2, null, rule)
        }
        val year: Int? = when (yearRaw) {
            null -> null
            is Long -> yearRaw.toInt().takeIf { yearRaw in MIN_YEAR..MAX_YEAR } ?: return null
            is Double -> if (yearRaw == Math.floor(yearRaw) && yearRaw >= MIN_YEAR && yearRaw <= MAX_YEAR) yearRaw.toInt() else return null
            is String -> yearRaw.trim().toIntOrNull()?.takeIf { it in MIN_YEAR..MAX_YEAR } ?: return null
            else -> return null
        }
        val (fm, fd) = parseMonthDay(fromRaw) ?: return null
        val (tm, td) = if (toRaw == null) fm to fd else parseMonthDay(toRaw) ?: return null
        val crosses = fm * 100 + fd > tm * 100 + td
        // Impossible dates are rejected: 02-30, 04-31, and 02-29 in a year that has none
        if (!validDay(fm, fd, year)) return null
        if (!validDay(tm, td, if (year != null && crosses) year + 1 else year)) return null
        var range = DateRange(fm, fd, tm, td, year)
        if (dayRaw != null) {
            val (dm, dd) = parseMonthDay(dayRaw) ?: return null
            // A main day outside the range is ignored (the range's first day is used)
            if (validDay(dm, dd, null) && range.containsMonthDay(dm, dd)) {
                range = range.copy(mainMonth = dm, mainDay = dd)
            }
        }
        return RemoteGreeting(l1, l2, range, rule)
    }

    private val DAYS = mapOf(
        "sun" to Calendar.SUNDAY, "mon" to Calendar.MONDAY, "tue" to Calendar.TUESDAY,
        "wed" to Calendar.WEDNESDAY, "thu" to Calendar.THURSDAY, "fri" to Calendar.FRIDAY,
        "sat" to Calendar.SATURDAY
    )
    private val HOURS = SafeRegex.of("^\\s*(\\d{1,2})\\s*-\\s*(\\d{1,2})\\s*$")

    /**
     * 5.1.0: an entry's conditions ("min", "max", "needs", "hours", "days", "playing", "liked"), or
     * null when one of them is broken (then the entry is skipped). No conditions = [GreetingRule.NONE].
     */
    fun parseRule(item: Map<*, *>): GreetingRule? {
        fun counts(v: Any?): Map<String, Int>? {
            if (v == null) return emptyMap()
            if (v !is Map<*, *>) return null
            val out = LinkedHashMap<String, Int>()
            for ((k, x) in v) {
                val key = (k as? String)?.trim() ?: return null
                if (key !in GreetingTokens.COUNTS) return null
                val n = when (x) {
                    is Long -> x
                    is Double -> if (x == Math.floor(x)) x.toLong() else return null
                    else -> return null
                }
                if (n !in 0..1_000_000) return null
                out[key] = n.toInt()
            }
            return out
        }
        fun words(v: Any?): List<String>? = when (v) {
            null -> emptyList()
            is String -> listOf(v)
            is List<*> -> v.map { (it as? String) ?: return null }
            else -> null
        }
        val min = counts(item["min"]) ?: return null
        val max = counts(item["max"]) ?: return null
        val needs = (words(item["needs"]) ?: return null).map { it.trim().removePrefix("{").removeSuffix("}") }
        if (needs.any { it !in GreetingTokens.STATS }) return null
        var fromHour = -1
        var toHour = -1
        when (val h = item["hours"]) {
            null -> {}
            is Long -> if (h in 0..23) { fromHour = h.toInt(); toHour = h.toInt() } else return null
            is String -> {
                val m = HOURS.matchEntire(h) ?: return null
                fromHour = m.groupValues[1].toInt()
                toHour = m.groupValues[2].toInt()
                if (fromHour !in 0..23 || toHour !in 0..23) return null
            }
            else -> return null
        }
        val days = (words(item["days"]) ?: return null).map {
            DAYS[it.trim().lowercase(java.util.Locale.ROOT).take(3)] ?: return null
        }.toSet()
        val playing = when (val p = item["playing"]) { null -> null; is Boolean -> p; else -> return null }
        val liked = when (val p = item["liked"]) { null -> null; is Boolean -> p; else -> return null }
        return GreetingRule(min, max, needs.toSet(), fromHour, toHour, days, playing, liked)
    }

    /** "MM-DD" (also "M-D" or "MM/DD") to month and day, or null. Range checks happen in [validDay]. */
    fun parseMonthDay(v: Any?): Pair<Int, Int>? {
        val s = v as? String ?: return null
        val m = DATE.matchEntire(s) ?: return null
        val month = m.groupValues[1].toIntOrNull() ?: return null
        val day = m.groupValues[2].toIntOrNull() ?: return null
        if (month !in 1..12 || day < 1) return null
        return month to day
    }

    /** Whether [day] exists in [month] (Feb 29 only in a leap [year], or always when there's no year). */
    fun validDay(month: Int, day: Int, year: Int?): Boolean {
        if (month !in 1..12 || day < 1) return false
        val max = when (month) {
            2 -> if (year == null || isLeap(year)) 29 else 28
            4, 6, 9, 11 -> 30
            else -> 31
        }
        return day <= max
    }

    private fun isLeap(y: Int) = (y % 4 == 0 && y % 100 != 0) || y % 400 == 0

    /**
     * Tidies the text and splits it into two lines; null when it is empty or too long. Control
     * characters go, runs of spaces become one, a third line joins the second.
     */
    fun cleanLines(raw: String): Pair<String, String>? {
        val normalised = buildString {
            for (c in raw.replace("\r\n", "\n").replace('\r', '\n')) {
                if (c == '\n') append(c)
                else if (c.isISOControl() || c == '\u2028' || c == '\u2029') append(' ')
                else append(c)
            }
        }
        val parts = normalised.split('\n').map { it.replace(SPACES, " ").trim() }.filter { it.isNotEmpty() }
        if (parts.isEmpty()) return null
        val total = parts.sumOf { it.length } + parts.size - 1
        if (total > MAX_TEXT) return null
        val (l1, l2) = if (parts.size == 1) split(parts[0]) else parts[0] to parts.drop(1).joinToString(" ")
        if (l1.isEmpty()) return null
        if (l1.length > MAX_LINE || l2.length > MAX_LINE) return null
        return l1 to l2
    }

    /** One line of text into two: after a sentence or comma nearest the middle, else at the middle space. */
    fun split(text: String): Pair<String, String> {
        val t = text.trim()
        if (t.length <= 14 && !t.contains(". ") && !t.contains("! ") && !t.contains("? ")) return t to ""
        val mid = t.length / 2.0
        val punct = ArrayList<Int>()
        for (i in 0 until t.length - 1) {
            if (t[i] in ".!?,:;…" && t[i + 1] == ' ') punct.add(i + 1)
        }
        val cut = punct.minByOrNull { Math.abs(it - mid) }?.takeIf { Math.abs(it - mid) <= t.length * 0.35 }
            ?: t.indices.filter { t[it] == ' ' }.minByOrNull { Math.abs(it - mid) }
            ?: return t to ""
        val a = t.substring(0, cut).trim()
        val b = t.substring(cut).trim()
        return if (a.isEmpty() || b.isEmpty()) t to "" else a to b
    }
}
