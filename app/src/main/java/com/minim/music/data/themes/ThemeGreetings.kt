package com.minim.music.data.themes

import com.minim.music.data.greetings.DateRange
import com.minim.music.data.greetings.GreetingTokens
import com.minim.music.data.greetings.MiniJson
import com.minim.music.data.greetings.RemoteGreeting
import com.minim.music.data.greetings.RemoteGreetingFile
import com.minim.music.data.greetings.RemoteGreetingsParser

/**
 * 5.5.2: a festival's own greetings file (themes/<id>/greetings.json), or the older inline
 * "greetings" object in themes.json. Same lines and conditions as greetings.json, in four groups:
 *
 * - "anytime": plain lines (with "hours" for the time-of-day ones)
 * - "stats": lines about the listening
 * - "days": `[{ "date": "2026-11-08", "greetings": [...] }]`, lines that only show on that date,
 *   mixed in with the rest (every 3rd greeting that day)
 * - "topArtist": the top artist of the day takeover lines, with `{topartist}`
 */
data class ThemeGreetingSet(
    /** The rotation's lines; [RemoteGreetingFile.exclusive] is set so the regular ones pause. */
    val file: RemoteGreetingFile,
    /** Takeover lines, still holding `{topartist}` (and maybe `{name}`). */
    val topArtist: List<Pair<String, String>> = emptyList(),
    /** The dates in "days" as written (before any preview override). */
    val specialDays: List<ThemeDate> = emptyList(),
    val skipped: Int = 0
) {
    /** Whether the rotation has anything at all (else the regular greetings carry on). */
    val hasRotation: Boolean get() = file.size > 0

    /**
     * Takeover line [index] (wraps around) for [artist], or null when there are none or it can't
     * show (it needs a name and none is saved, or it's too long once filled in).
     */
    fun takeoverLine(index: Int, artist: String, name: String = "", ms: Long = 0L): Pair<String, String>? {
        if (topArtist.isEmpty() || artist.isBlank()) return null
        val n = name.trim()
        for (k in topArtist.indices) {
            val (a, b) = topArtist[Math.floorMod(index + k, topArtist.size)]
            if ((a.contains(NAME) || b.contains(NAME)) && n.isEmpty()) continue
            val mins = maxOf(1L, ms / 60_000L).toString()
            fun fill(s: String) = s.replace(TOP, artist.trim()).replace(NAME, n).replace(MINUTES, mins)
            val l1 = fill(a)
            val l2 = fill(b)
            if (l1.length > RemoteGreeting.MAX_FILLED_LINE || l2.length > RemoteGreeting.MAX_FILLED_LINE) continue
            return l1 to l2
        }
        return null
    }

    companion object {
        private const val TOP = "{topartist}"
        private const val NAME = "{name}"
        private const val MINUTES = "{minutes}"
    }
}

object ThemeGreetingsParser {
    const val SUPPORTED_VERSION = 1
    private val TAKEOVER_TOKENS = setOf("topartist", "minutes", GreetingTokens.NAME)
    private val DATE_KEYS = listOf("from", "to", "day", "year")

    /**
     * The file's greetings, or null when there's nothing usable (not JSON, a version this app
     * can't read, or no line that parses). [dayOverride] moves every "days" date to that day: the
     * long-press preview of the festival's big day.
     */
    fun parse(text: String?, dayOverride: ThemeDate? = null): ThemeGreetingSet? {
        if (text.isNullOrBlank()) return null
        val root = try {
            MiniJson.parse(text)
        } catch (e: Exception) {
            return null
        } catch (e: StackOverflowError) {
            return null
        }
        return (root as? Map<*, *>)?.let { parseMap(it, dayOverride) }
    }

    fun parseMap(root: Map<*, *>, dayOverride: ThemeDate? = null): ThemeGreetingSet? {
        when (val v = root["version"]) {
            null -> Unit
            is Long -> if (v.toInt() != SUPPORTED_VERSION) return null
            is Double -> if (v != SUPPORTED_VERSION.toDouble()) return null
            else -> return null
        }
        val max = RemoteGreetingsParser.MAX_ENTRIES
        var skipped = 0
        fun lines(list: Any?): List<RemoteGreeting> {
            val items = list as? List<*> ?: run {
                if (list != null) skipped++
                return emptyList()
            }
            val out = ArrayList<RemoteGreeting>()
            for (item in items.take(RemoteGreetingsParser.MAX_SCANNED)) {
                val g = entry(item)
                if (g == null || out.size >= max) skipped++ else out.add(g)
            }
            return out
        }
        val anytime = lines(root["anytime"])
        val stats = lines(root["stats"])
        val occasions = ArrayList<RemoteGreeting>()
        val specialDays = ArrayList<ThemeDate>()
        (root["days"] as? List<*>)?.forEach { d ->
            val m = d as? Map<*, *>
            val date = ThemeDate.parse(m?.get("date"))
            if (m == null || date == null) {
                skipped++
                return@forEach
            }
            specialDays.add(date)
            val on = dayOverride ?: date
            val range = DateRange(on.month, on.day, on.month, on.day, on.year)
            for (g in lines(m["greetings"])) {
                if (occasions.size < max) occasions.add(g.copy(range = range)) else skipped++
            }
        } ?: run { if (root["days"] != null) skipped++ }
        val takeover = ArrayList<Pair<String, String>>()
        (root["topArtist"] as? List<*>)?.forEach { item ->
            val t = takeoverEntry(item)
            if (t == null || takeover.size >= 50) skipped++ else takeover.add(t)
        } ?: run { if (root["topArtist"] != null) skipped++ }
        val file = RemoteGreetingFile(
            version = SUPPORTED_VERSION,
            anytime = anytime,
            occasions = occasions,
            skipped = skipped,
            stats = stats,
            exclusive = true
        )
        if (file.size == 0 && takeover.isEmpty()) return null
        return ThemeGreetingSet(file, takeover, specialDays, skipped)
    }

    /** One rotation line. Dates don't belong in a festival's lines (its own dates apply), so they make it invalid. */
    fun entry(item: Any?): RemoteGreeting? {
        val m = item as? Map<*, *> ?: return null
        if (DATE_KEYS.any { m[it] != null }) return null
        return try {
            RemoteGreetingsParser.parseEntry(m)
        } catch (e: Exception) {
            null
        }
    }

    /** One takeover line: `{ "text": "..." }` or just the text. Only `{topartist}` and `{name}` work here. */
    fun takeoverEntry(item: Any?): Pair<String, String>? {
        val raw = when (item) {
            is String -> item
            is Map<*, *> -> item["text"] as? String
            else -> null
        } ?: return null
        val (l1, l2) = RemoteGreetingsParser.cleanLines(raw) ?: return null
        val tokens = GreetingTokens.tokensIn(l1) + GreetingTokens.tokensIn(l2)
        if (tokens.any { it !in TAKEOVER_TOKENS }) return null
        return l1 to l2
    }
}
