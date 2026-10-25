package com.minim.music.data.config

import com.minim.music.data.greetings.DateRange
import com.minim.music.data.greetings.RemoteGreeting
import com.minim.music.data.greetings.RemoteGreetingFile
import com.minim.music.data.greetings.RemoteGreetingsParser
import com.minim.music.data.update.AppVersion
import java.util.Calendar

/**
 * Minim 5.0.0 part 3: the app's "vibe" from the cloud, without an app update. It lives in ONE
 * Firestore document, `config/app` (public read, no writes, per firestore.rules). It is never under
 * users/{uid}, so Delete account never touches it.
 *
 * Document fields (all optional; anything missing or broken falls back to the built-in behaviour):
 *
 *   switches       map of name -> true/false. Known names: festivals, announcements, greetings,
 *                  sync, update_prompt. A missing switch is ON.
 *   events         list of festivals: { id, name, from, to, day, accent, banner, link, greetings }
 *                  from/to: "MM-DD" (every year) or "YYYY-MM-DD" (that year only). to defaults to from.
 *                  accent: "#RRGGBB", the app colour while nothing is playing (Dynamic colours only).
 *                  banner: a short line shown under the search pill on Home (can be closed).
 *                  link: an https:// link the banner opens. greetings: list of greeting texts
 *                  ("line 1\nline 2") that show on Home during the festival.
 *   announcements  list of { id, title, text, button, link, from, to, min_version, max_version }.
 *                  Shown once as a sheet; closing it means it never shows again.
 *                  min_version / max_version: "5.0.0" or a version code (27); only those versions see it.
 *   greetings      list of greetings in the greetings.json format ({ text, from, to, day, year }).
 *   update_lock    { min_version, message }: versions older than min_version must update before
 *                  using the app. Only kept when a newer release really exists on GitHub (the app checks).
 *
 * Pure Kotlin (no Android, no Firebase), so it is unit-tested. Nothing in here throws.
 */
data class FestivalEvent(
    val id: String,
    val name: String,
    val range: DateRange,
    /** ARGB, or null for no colour change. */
    val accent: Int?,
    val banner: String?,
    val link: String?,
    val greetings: List<RemoteGreeting>
)

data class Announcement(
    val id: String,
    val title: String,
    val text: String,
    val button: String?,
    val link: String?,
    val range: DateRange?,
    val minVersion: VersionBound?,
    val maxVersion: VersionBound?
) {
    fun showsFor(installed: InstalledVersion, now: Calendar): Boolean {
        if (range != null && !range.matches(now)) return false
        if (minVersion != null && minVersion.isAbove(installed)) return false
        if (maxVersion != null && maxVersion.isBelow(installed)) return false
        return true
    }
}

/** The installed app: versionName ("4.9.9-debug" ok) and versionCode. */
data class InstalledVersion(val name: AppVersion?, val code: Long) {
    companion object {
        fun of(versionName: String?, versionCode: Long) = InstalledVersion(AppVersion.installed(versionName), versionCode)
    }
}

/** A version written as "5.0.0" or as a version code (27). */
sealed class VersionBound {
    data class Name(val version: AppVersion) : VersionBound()
    data class Code(val code: Long) : VersionBound()

    /** Whether this bound is newer than the installed app (so the app is older than it). */
    fun isAbove(v: InstalledVersion): Boolean = when (this) {
        is Name -> v.name != null && v.name < version
        is Code -> v.code in 1 until code
    }

    /** Whether this bound is older than the installed app (so the app is newer than it). */
    fun isBelow(v: InstalledVersion): Boolean = when (this) {
        is Name -> v.name != null && v.name > version
        is Code -> v.code > code
    }
}

data class UpdateLock(val minVersion: VersionBound, val message: String?) {
    fun applies(installed: InstalledVersion): Boolean = minVersion.isAbove(installed)
}

data class VibeConfig(
    val events: List<FestivalEvent> = emptyList(),
    val announcements: List<Announcement> = emptyList(),
    val switches: Map<String, Boolean> = emptyMap(),
    val greetings: List<RemoteGreeting> = emptyList(),
    val lock: UpdateLock? = null,
    /** Entries thrown away as invalid (for the log and tests). */
    val skipped: Int = 0
) {
    fun isOn(switch: String): Boolean = switches[switch] ?: true

    /** The festival on today (the first in the list when two overlap), or null. */
    fun activeEvent(now: Calendar): FestivalEvent? =
        if (!isOn(SWITCH_FESTIVALS)) null else events.firstOrNull { it.range.matches(now) }

    /** The next announcement to show: on, inside its dates and versions, and not closed before. */
    fun nextAnnouncement(installed: InstalledVersion, now: Calendar, dismissed: Set<String>): Announcement? =
        if (!isOn(SWITCH_ANNOUNCEMENTS)) null
        else announcements.firstOrNull { it.id !in dismissed && it.showsFor(installed, now) }

    /**
     * The greetings to mix into Home: the plain ones (when greetings are on) and every festival's
     * own lines, dated to the festival (when festivals are on).
     */
    fun greetingFile(): RemoteGreetingFile {
        val all = ArrayList<RemoteGreeting>()
        if (isOn(SWITCH_GREETINGS)) all.addAll(greetings)
        if (isOn(SWITCH_FESTIVALS)) for (e in events) all.addAll(e.greetings)
        if (all.isEmpty()) return RemoteGreetingFile.EMPTY
        // 5.1.0: lines with stat placeholders or conditions go to the stats slots
        return RemoteGreetingFile.of(1, all)
    }

    fun lockApplies(installed: InstalledVersion): Boolean = lock?.applies(installed) == true

    companion object {
        val DEFAULT = VibeConfig()

        const val SWITCH_FESTIVALS = "festivals"
        const val SWITCH_ANNOUNCEMENTS = "announcements"
        const val SWITCH_GREETINGS = "greetings"
        const val SWITCH_SYNC = "sync"
        const val SWITCH_UPDATE_PROMPT = "update_prompt"
    }
}

/**
 * Reads the `config/app` document's data (the map Firestore hands over: String, Long, Double,
 * Boolean, List, Map; timestamps are turned into "YYYY-MM-DD" strings before this). Unknown fields
 * are ignored, bad entries are skipped and the rest is kept. Never throws.
 */
object VibeConfigParser {

    const val MAX_EVENTS = 40
    const val MAX_ANNOUNCEMENTS = 20
    const val MAX_GREETINGS = 100
    const val MAX_EVENT_GREETINGS = 12
    const val MAX_ID = 60
    const val MAX_NAME = 40
    const val MAX_BANNER = 60
    const val MAX_TITLE = 60
    const val MAX_TEXT = 400
    const val MAX_BUTTON = 24
    const val MAX_LINK = 500

    private val FULL_DATE = Regex("^\\s*(\\d{4})\\s*-\\s*(\\d{1,2})\\s*-\\s*(\\d{1,2})\\s*$")
    private val HEX = Regex("^#?([0-9a-fA-F]{6})$")
    private val ID = Regex("^[A-Za-z0-9_.-]{1,$MAX_ID}$")

    fun parse(data: Map<*, *>?): VibeConfig {
        if (data == null) return VibeConfig.DEFAULT
        return try {
            parseOrThrow(data)
        } catch (e: Exception) {
            VibeConfig.DEFAULT
        } catch (e: StackOverflowError) {
            VibeConfig.DEFAULT
        }
    }

    private fun parseOrThrow(data: Map<*, *>): VibeConfig {
        var skipped = 0
        val switches = HashMap<String, Boolean>()
        (data["switches"] as? Map<*, *>)?.forEach { (k, v) ->
            val key = (k as? String)?.trim()?.lowercase() ?: return@forEach
            when (v) {
                is Boolean -> switches[key] = v
                is String -> when (v.trim().lowercase()) {
                    "true", "on", "yes" -> switches[key] = true
                    "false", "off", "no" -> switches[key] = false
                }
                is Number -> switches[key] = v.toDouble() != 0.0
            }
        }

        val events = ArrayList<FestivalEvent>()
        for (item in (data["events"] as? List<*>).orEmpty()) {
            if (events.size >= MAX_EVENTS) { skipped++; continue }
            val e = try { parseEvent(item) } catch (_: Exception) { null }
            if (e == null || events.any { it.id == e.id }) skipped++ else events.add(e)
        }

        val announcements = ArrayList<Announcement>()
        for (item in (data["announcements"] as? List<*>).orEmpty()) {
            if (announcements.size >= MAX_ANNOUNCEMENTS) { skipped++; continue }
            val a = try { parseAnnouncement(item) } catch (_: Exception) { null }
            if (a == null || announcements.any { it.id == a.id }) skipped++ else announcements.add(a)
        }

        val greetings = ArrayList<RemoteGreeting>()
        for (item in (data["greetings"] as? List<*>).orEmpty()) {
            if (greetings.size >= MAX_GREETINGS) { skipped++; continue }
            val entry = if (item is String) mapOf("text" to item) else item
            val g = try { RemoteGreetingsParser.parseEntry(entry) } catch (_: Exception) { null }
            if (g == null) skipped++ else greetings.add(g)
        }

        val lock = (data["update_lock"] as? Map<*, *>)?.let { m ->
            val min = parseVersion(m["min_version"])
            if (min == null) { skipped++; null }
            else UpdateLock(min, cleanText(m["message"], MAX_TEXT))
        }

        return VibeConfig(events, announcements, switches, greetings, lock, skipped)
    }

    fun parseEvent(item: Any?): FestivalEvent? {
        if (item !is Map<*, *>) return null
        val name = cleanText(item["name"], MAX_NAME)
        val id = parseId(item["id"]) ?: name?.let { slug(it) } ?: return null
        val range = parseRange(item["from"], item["to"], item["day"]) ?: return null
        val accentRaw = item["accent"]
        val accent = if (accentRaw == null) null else parseColour(accentRaw) ?: return null
        val banner = cleanText(item["banner"], MAX_BANNER)
        val link = parseLink(item["link"])
        val lines = ArrayList<RemoteGreeting>()
        val rawGreetings = when (val g = item["greetings"]) {
            is List<*> -> g
            is String -> listOf(g)
            else -> emptyList()
        }
        for (g in rawGreetings) {
            if (lines.size >= MAX_EVENT_GREETINGS) break
            val text = when (g) {
                is String -> g
                is Map<*, *> -> g["text"] as? String
                else -> null
            } ?: continue
            val (l1, l2) = RemoteGreetingsParser.cleanLines(text) ?: continue
            lines.add(RemoteGreeting(l1, l2, range))
        }
        return FestivalEvent(id, name ?: id, range, accent, banner, link, lines)
    }

    fun parseAnnouncement(item: Any?): Announcement? {
        if (item !is Map<*, *>) return null
        val id = parseId(item["id"]) ?: return null
        val title = cleanText(item["title"], MAX_TITLE) ?: return null
        val text = cleanText(item["text"], MAX_TEXT, keepNewlines = true) ?: ""
        val link = parseLink(item["link"])
        // A button without a link only closes the sheet, so it needs no label of its own
        val button = if (link != null) cleanText(item["button"], MAX_BUTTON) else null
        val range = if (item["from"] == null && item["to"] == null) null
        else parseRange(item["from"], item["to"], null) ?: return null
        val minRaw = item["min_version"]
        val maxRaw = item["max_version"]
        val min = if (minRaw == null) null else parseVersion(minRaw) ?: return null
        val max = if (maxRaw == null) null else parseVersion(maxRaw) ?: return null
        return Announcement(id, title, text, button, link, range, min, max)
    }

    /**
     * from/to (+ main day) into a [DateRange]. "MM-DD" repeats every year; "YYYY-MM-DD" is that year
     * only (both ends must then carry a year, the end the same year or the next for a range across
     * the new year).
     */
    fun parseRange(fromRaw: Any?, toRaw: Any?, dayRaw: Any?): DateRange? {
        val from = parseDate(fromRaw) ?: return null
        val to = if (toRaw == null) from else parseDate(toRaw) ?: return null
        val (fy, fm, fd) = from
        val (ty, tm, td) = to
        if ((fy == null) != (ty == null)) return null
        val crosses = fm * 100 + fd > tm * 100 + td
        if (fy != null && ty != null) {
            val expected = if (crosses) fy + 1 else fy
            if (ty != expected) return null
        }
        if (!RemoteGreetingsParser.validDay(fm, fd, fy)) return null
        if (!RemoteGreetingsParser.validDay(tm, td, ty)) return null
        var range = DateRange(fm, fd, tm, td, fy)
        if (dayRaw != null) {
            val d = parseDate(dayRaw)
            if (d != null && RemoteGreetingsParser.validDay(d.second, d.third, null) &&
                range.containsMonthDay(d.second, d.third)
            ) range = range.copy(mainMonth = d.second, mainDay = d.third)
        }
        return range
    }

    /** "MM-DD" -> (null, m, d); "YYYY-MM-DD" -> (y, m, d). */
    fun parseDate(v: Any?): Triple<Int?, Int, Int>? {
        val s = v as? String ?: return null
        FULL_DATE.matchEntire(s)?.let { m ->
            val y = m.groupValues[1].toInt()
            val mo = m.groupValues[2].toInt()
            val d = m.groupValues[3].toInt()
            if (y !in RemoteGreetingsParser.MIN_YEAR..RemoteGreetingsParser.MAX_YEAR || mo !in 1..12 || d < 1) return null
            return Triple(y, mo, d)
        }
        val (mo, d) = RemoteGreetingsParser.parseMonthDay(s) ?: return null
        return Triple(null, mo, d)
    }

    /** "#RRGGBB" / "RRGGBB" -> opaque ARGB, or null. */
    fun parseColour(v: Any?): Int? {
        val s = (v as? String)?.trim() ?: return null
        val m = HEX.matchEntire(s) ?: return null
        return (0xFF000000L or m.groupValues[1].toLong(16)).toInt()
    }

    /** "5.0.0" / "v5.0.0" or a whole number version code. */
    fun parseVersion(v: Any?): VersionBound? = when (v) {
        is Long -> if (v in 1..Int.MAX_VALUE) VersionBound.Code(v) else null
        is Int -> if (v >= 1) VersionBound.Code(v.toLong()) else null
        is Double -> if (v >= 1 && v <= Int.MAX_VALUE && v == Math.floor(v)) VersionBound.Code(v.toLong()) else null
        is String -> {
            val t = v.trim()
            if (t.isNotEmpty() && t.all { it in '0'..'9' } && t.length <= 9) t.toLong().takeIf { it >= 1 }?.let { VersionBound.Code(it) }
            else AppVersion.parse(t)?.let { VersionBound.Name(it) }
        }
        else -> null
    }

    /** Only https links (no intents, no files, no javascript). */
    fun parseLink(v: Any?): String? {
        val s = (v as? String)?.trim() ?: return null
        if (s.length > MAX_LINK || !s.startsWith("https://", ignoreCase = true) || s.length <= 8) return null
        if (s.any { it.isWhitespace() || it.isISOControl() }) return null
        return s
    }

    private fun parseId(v: Any?): String? {
        val s = when (v) {
            is String -> v.trim()
            is Long -> v.toString()
            else -> return null
        }
        return s.takeIf { ID.matches(it) }
    }

    private fun slug(name: String): String? =
        name.lowercase().map { if (it.isLetterOrDigit() && it.code < 128) it else '-' }.joinToString("")
            .trim('-').replace(Regex("-+"), "-").take(MAX_ID).takeIf { it.isNotEmpty() }

    /**
     * Trimmed text without control characters, or null when empty or longer than [max].
     * Line breaks are kept (at most two in a row) when [keepNewlines], else they become spaces.
     */
    fun cleanText(v: Any?, max: Int, keepNewlines: Boolean = false): String? {
        val s = v as? String ?: return null
        val out = StringBuilder()
        for (c in s.replace("\r\n", "\n").replace('\r', '\n')) {
            when {
                c == '\n' -> out.append(if (keepNewlines) '\n' else ' ')
                c.isISOControl() || c == '\u2028' || c == '\u2029' -> out.append(' ')
                else -> out.append(c)
            }
        }
        var t = out.toString().split('\n').joinToString("\n") { it.replace(Regex("[ \\t\\u00A0]+"), " ").trim() }
        t = t.replace(Regex("\n{3,}"), "\n\n").trim()
        if (t.isEmpty() || t.length > max) return null
        return t
    }
}

/**
 * Firestore hands over its own types for some fields (a Timestamp for a date picked in the console).
 * This turns the document data into plain values the parser reads: [dateOf] returns the Date of a
 * timestamp-like value (null for anything else), which becomes "YYYY-MM-DD" on the phone's calendar.
 * Maps and lists are walked (to a sane depth); other values pass through.
 */
object VibeConfigValues {
    private const val MAX_DEPTH = 8

    fun normalize(value: Any?, dateOf: (Any) -> java.util.Date?, depth: Int = 0): Any? {
        if (depth > MAX_DEPTH) return null
        return when (value) {
            null, is String, is Boolean, is Long, is Int, is Double -> value
            is Map<*, *> -> value.entries.associate { (k, v) -> k.toString() to normalize(v, dateOf, depth + 1) }
            is List<*> -> value.map { normalize(it, dateOf, depth + 1) }
            is Number -> value.toDouble()
            else -> dateOf(value)?.let { day(it) }
        }
    }

    fun day(date: java.util.Date): String {
        val c = Calendar.getInstance()
        c.time = date
        return String.format(
            java.util.Locale.US, "%04d-%02d-%02d",
            c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH)
        )
    }
}

/** When to read `config/app` again: on open, then at most every [OK_MS] (sooner after a failure). */
class VibeRefreshSchedule(private val okMs: Long = OK_MS, private val failMs: Long = FAIL_MS) {
    private var lastAt = Long.MIN_VALUE
    private var lastOk = false

    fun shouldRefresh(now: Long, force: Boolean = false): Boolean {
        if (force || lastAt == Long.MIN_VALUE) return true
        val wait = if (lastOk) okMs else failMs
        return now - lastAt >= wait || now < lastAt
    }

    fun onFinished(now: Long, ok: Boolean) {
        lastAt = now
        lastOk = ok
    }

    companion object {
        const val OK_MS = 30L * 60_000L
        const val FAIL_MS = 5L * 60_000L
    }
}
