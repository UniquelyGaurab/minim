package com.minim.music.data.themes

import com.minim.music.data.greetings.MiniJson
import com.minim.music.util.SafeRegex
import java.util.Calendar

/**
 * 5.5.2: festival themes from themes/themes.json in the public repo (format: themes/README.md and
 * themes.schema.json there). Everything in here is plain Kotlin so it can be unit tested; nothing
 * throws, a broken entry is just skipped.
 */

/** A calendar day (the phone's own date). */
data class ThemeDate(val year: Int, val month: Int, val day: Int) : Comparable<ThemeDate> {
    val key: Int get() = year * 10000 + month * 100 + day

    /** Days since 1970-01-01 (proleptic Gregorian), for "2 days before" maths. */
    val epochDay: Long
        get() {
            val y = if (month <= 2) year - 1 else year
            val era = (if (y >= 0) y else y - 399) / 400
            val yoe = y - era * 400
            val mp = (month + 9) % 12
            val doy = (153 * mp + 2) / 5 + day - 1
            val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
            return era * 146097L + doe - 719468L
        }

    override fun compareTo(other: ThemeDate): Int = key.compareTo(other.key)

    /** "2026-11-08" */
    override fun toString(): String = "%04d-%02d-%02d".format(year, month, day)

    companion object {
        private val PATTERN = SafeRegex.of("^(\\d{4})-(\\d{2})-(\\d{2})$")

        fun parse(v: Any?): ThemeDate? {
            val s = (v as? String)?.trim() ?: return null
            val m = PATTERN.matchEntire(s) ?: return null
            val y = m.groupValues[1].toInt()
            val mo = m.groupValues[2].toInt()
            val d = m.groupValues[3].toInt()
            if (y !in 2000..2200 || mo !in 1..12 || d < 1 || d > daysIn(y, mo)) return null
            return ThemeDate(y, mo, d)
        }

        fun of(c: Calendar): ThemeDate =
            ThemeDate(c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH))

        fun today(): ThemeDate = of(Calendar.getInstance())

        fun daysIn(y: Int, m: Int): Int = when (m) {
            2 -> if ((y % 4 == 0 && y % 100 != 0) || y % 400 == 0) 29 else 28
            4, 6, 9, 11 -> 30
            else -> 31
        }
    }
}

/** The like burst during a festival. [colors] empty = the cover's colour, like normal. */
data class ThemeBurst(val shape: String, val colors: List<Int>) {
    companion object {
        val SHAPES = setOf("dots", "sparks", "petals", "snow", "hearts")
    }
}

/** Backup colours for Home (null = picked from the wallpaper). */
data class ThemeColors(val text: Int? = null, val pill: Int? = null, val pillText: Int? = null) {
    companion object {
        val NONE = ThemeColors()
    }
}

data class FestivalTheme(
    val id: String,
    val name: String,
    val start: ThemeDate,
    val end: ThemeDate,
    val priority: Int = 0,
    /** Its place in the list (ties in priority go to the one higher up). */
    val order: Int = 0,
    /** "themes/diwali-2026/wallpaper.webp", or null for none. */
    val wallpaper: String? = null,
    val colors: ThemeColors = ThemeColors.NONE,
    val burst: ThemeBurst? = null,
    /** "themes/diwali-2026/greetings.json": the festival's own greetings file. */
    val greetingsPath: String? = null,
    /** Greetings written straight into the entry (the older way); used when there's no file. */
    val inlineGreetings: Map<*, *>? = null
) {
    fun isOn(today: ThemeDate): Boolean = today >= start && today <= end

    val hasGreetings: Boolean get() = greetingsPath != null || inlineGreetings != null
}

data class FestivalThemeFile(val version: Int, val festivals: List<FestivalTheme>, val skipped: Int = 0)

object FestivalThemesParser {
    const val SUPPORTED_VERSION = 1
    const val MAX_FESTIVALS = 50

    private val ID = SafeRegex.of("^[a-z0-9][a-z0-9-]{0,39}$")
    private val WALLPAPER = SafeRegex.of("^themes/[A-Za-z0-9_-]+/[A-Za-z0-9_.-]+\\.webp$")
    private val GREETINGS_FILE = SafeRegex.of("^themes/[A-Za-z0-9_-]+/[A-Za-z0-9_.-]+\\.json$")
    private val COLOR = SafeRegex.of("^#[0-9A-Fa-f]{6}$")

    /** The whole file, or null when it can't be used at all (then the last good copy stays). */
    fun parse(text: String?): FestivalThemeFile? {
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
            is Long -> v.toInt()
            is Double -> if (v == Math.floor(v)) v.toInt() else return null
            else -> return null
        }
        // A higher version is a change this app can't read
        if (version != SUPPORTED_VERSION) return null
        val list = root["festivals"] as? List<*> ?: return null
        val out = ArrayList<FestivalTheme>()
        var skipped = 0
        val seen = HashSet<String>()
        for ((i, item) in list.withIndex()) {
            if (i >= MAX_FESTIVALS) {
                skipped += list.size - i
                break
            }
            val raw = item as? Map<*, *>
            val id = (raw?.get("id") as? String)?.trim()
            // Templates (id starting with _) and switched-off festivals never show; not an error
            if (id != null && id.startsWith("_")) continue
            if (raw != null && raw["enabled"] == false) continue
            val f = try { parseFestival(raw, i) } catch (e: Exception) { null }
            if (f == null || !seen.add(f.id)) skipped++ else out.add(f)
        }
        return FestivalThemeFile(version, out, skipped)
    }

    fun parseFestival(raw: Map<*, *>?, order: Int): FestivalTheme? {
        raw ?: return null
        val id = (raw["id"] as? String)?.trim()?.takeIf { ID.matches(it) } ?: return null
        val name = (raw["name"] as? String)?.trim()?.takeIf { it.isNotEmpty() && it.length <= 40 } ?: return null
        val enabled = raw["enabled"]
        if (enabled != null && enabled !is Boolean) return null
        val start = ThemeDate.parse(raw["start"]) ?: return null
        val end = ThemeDate.parse(raw["end"]) ?: return null
        if (end < start) return null
        val priority = when (val p = raw["priority"]) {
            null -> 0
            is Long -> p.toInt().takeIf { p in -100..100 } ?: return null
            is Double -> if (p == Math.floor(p) && p >= -100 && p <= 100) p.toInt() else return null
            else -> return null
        }
        // A broken wallpaper path means no wallpaper (normal Home), not a skipped festival
        val wallpaper = (raw["wallpaper"] as? String)?.trim()?.takeIf { WALLPAPER.matches(it) && !it.contains("..") }
        val colors = (raw["colors"] as? Map<*, *>)?.let {
            ThemeColors(color(it["text"]), color(it["pill"]), color(it["pillText"]))
        } ?: ThemeColors.NONE
        val burst = (raw["burst"] as? Map<*, *>)?.let { b ->
            val shape = (b["shape"] as? String)?.trim()?.lowercase()?.takeIf { it in ThemeBurst.SHAPES } ?: "dots"
            val cols = (b["colors"] as? List<*>)?.mapNotNull { color(it) }?.take(6).orEmpty()
            ThemeBurst(shape, cols)
        }
        var greetingsPath: String? = null
        var inline: Map<*, *>? = null
        when (val g = raw["greetings"]) {
            is String -> greetingsPath = g.trim().takeIf { GREETINGS_FILE.matches(it) && !it.contains("..") }
            is Map<*, *> -> inline = g
        }
        return FestivalTheme(id, name, start, end, priority, order, wallpaper, colors, burst, greetingsPath, inline)
    }

    private fun color(v: Any?): Int? {
        val s = (v as? String)?.trim() ?: return null
        if (!COLOR.matches(s)) return null
        return (0xFF000000L or s.substring(1).toLong(16)).toInt()
    }
}

object ThemePicker {
    /** The wallpaper downloads this many days before the start. */
    const val PREFETCH_DAYS = 2

    /** The festival on [today]: the highest priority, a tie going to the one higher up the list. */
    fun active(festivals: List<FestivalTheme>, today: ThemeDate): FestivalTheme? =
        festivals.filter { it.isOn(today) }
            .sortedWith(compareByDescending<FestivalTheme> { it.priority }.thenBy { it.order })
            .firstOrNull()

    /**
     * What the 7-tap preview turns on: the festival on now, else the next one to start (the
     * soonest start; same start = the same rule as [active]). Null when there's nothing ahead.
     */
    fun upcoming(festivals: List<FestivalTheme>, today: ThemeDate): FestivalTheme? {
        active(festivals, today)?.let { return it }
        val ahead = festivals.filter { it.start > today }
        val soonest = ahead.minOfOrNull { it.start } ?: return null
        return active(ahead.filter { it.start == soonest }, soonest)
    }

    /** Whether [f]'s files should be on the phone by [today]: from 2 days before its start to its end. */
    fun shouldPrefetch(f: FestivalTheme, today: ThemeDate): Boolean {
        val t = today.epochDay
        return t >= f.start.epochDay - PREFETCH_DAYS && t <= f.end.epochDay
    }
}
