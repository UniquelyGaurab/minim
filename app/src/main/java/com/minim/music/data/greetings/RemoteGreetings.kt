package com.minim.music.data.greetings

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

/** One remote greeting, already split into the two Home lines. [range] null = an anytime line. */
data class RemoteGreeting(
    val line1: String,
    val line2: String,
    val range: DateRange? = null
) {
    val isOccasion: Boolean get() = range != null
    val usesName: Boolean get() = line1.contains(NAME_TOKEN) || line2.contains(NAME_TOKEN)

    /** The lines for [name] (blank = none saved), or null when the greeting needs a name and has none. */
    fun linesFor(name: String): Pair<String, String>? {
        val n = name.trim()
        if (usesName && n.isEmpty()) return null
        return line1.replace(NAME_TOKEN, n) to line2.replace(NAME_TOKEN, n)
    }

    companion object {
        const val NAME_TOKEN = "{name}"
    }
}

/** A parsed greetings file. [skipped] counts entries that were thrown away as invalid. */
data class RemoteGreetingFile(
    val version: Int,
    val anytime: List<RemoteGreeting>,
    val occasions: List<RemoteGreeting>,
    val skipped: Int = 0
) {
    val size: Int get() = anytime.size + occasions.size

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
                a.skipped + b.skipped
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

    const val SUPPORTED_VERSION = 1
    const val MAX_ENTRIES = 300
    const val MAX_SCANNED = 2000
    const val MAX_TEXT = 80
    const val MAX_LINE = 40
    const val MIN_YEAR = 2000
    const val MAX_YEAR = 2200

    private val DATE = Regex("^\\s*(\\d{1,2})\\s*[-/]\\s*(\\d{1,2})\\s*$")
    private val SPACES = Regex("[ \\t\\u00A0]+")

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
            null -> SUPPORTED_VERSION
            is Long -> if (v in 1..1000) v.toInt() else return null
            is Double -> if (v >= 1 && v <= 1000 && v == Math.floor(v)) v.toInt() else return null
            else -> return null
        }
        val list = root["greetings"] as? List<*> ?: return null
        val anytime = ArrayList<RemoteGreeting>()
        val occasions = ArrayList<RemoteGreeting>()
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
                else -> anytime.add(g)
            }
        }
        return RemoteGreetingFile(version, anytime, occasions, skipped)
    }

    /** One entry, or null when it is not usable. */
    fun parseEntry(item: Any?): RemoteGreeting? {
        if (item !is Map<*, *>) return null
        val raw = item["text"] as? String ?: return null
        val (l1, l2) = cleanLines(raw) ?: return null

        val fromRaw = item["from"]
        val toRaw = item["to"]
        val dayRaw = item["day"]
        val yearRaw = item["year"]
        if (fromRaw == null) {
            // An anytime line: dates without a start make no sense, so they make the entry invalid
            if (toRaw != null || dayRaw != null || yearRaw != null) return null
            return RemoteGreeting(l1, l2, null)
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
        return RemoteGreeting(l1, l2, range)
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
