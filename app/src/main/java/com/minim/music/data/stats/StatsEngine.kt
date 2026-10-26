package com.minim.music.data.stats

import com.minim.music.util.SafeRegex
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.util.Locale

/**
 * v4.9: listening stats. Everything in here is plain Kotlin (no Android), so the rules are unit
 * tested: day keys, splitting listening at midnight, the old-data estimate, the quiet-day rule,
 * which day the recap shows, and the longest streak.
 *
 * A day runs 12:00 AM to 11:59 PM local time and is stored as one number, yyyymmdd (20261022).
 */
object Days {
    fun key(d: LocalDate): Int = d.year * 10000 + d.monthValue * 100 + d.dayOfMonth

    fun date(key: Int): LocalDate = LocalDate.of(key / 10000, (key / 100) % 100, key % 100)

    fun keyOf(ms: Long, zone: ZoneId = ZoneId.systemDefault()): Int =
        key(Instant.ofEpochMilli(ms).atZone(zone).toLocalDate())

    fun hourOf(ms: Long, zone: ZoneId = ZoneId.systemDefault()): Int =
        Instant.ofEpochMilli(ms).atZone(zone).hour

    fun startMs(key: Int, zone: ZoneId = ZoneId.systemDefault()): Long =
        date(key).atStartOfDay(zone).toInstant().toEpochMilli()

    /** The first millisecond of the next day. */
    fun endMs(key: Int, zone: ZoneId = ZoneId.systemDefault()): Long =
        date(key).plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()

    fun previous(key: Int): Int = key(date(key).minusDays(1))

    /** "Thursday, 22 Oct": day first, then the date in Indian day-month order. */
    fun label(key: Int): String {
        val d = date(key)
        val day = d.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.ENGLISH)
        val month = d.month.getDisplayName(TextStyle.SHORT, Locale.ENGLISH)
        return "$day, ${d.dayOfMonth} $month"
    }
}

/** A piece of listening that falls inside one hour of one day. */
data class ListenSlice(val day: Int, val hour: Int, val ms: Long)

object ListenSplit {
    /**
     * Splits listening between [startMs] and [endMs] at every hour boundary, so a song started at
     * 11:58 PM gives its first two minutes to that day and the rest to the new day.
     */
    fun split(startMs: Long, endMs: Long, zone: ZoneId = ZoneId.systemDefault()): List<ListenSlice> {
        if (endMs <= startMs) return emptyList()
        val out = ArrayList<ListenSlice>(2)
        var t = startMs
        var guard = 0
        while (t < endMs && guard++ < 1000) {
            val z = Instant.ofEpochMilli(t).atZone(zone)
            val nextHour = z.truncatedTo(ChronoUnit.HOURS).plusHours(1).toInstant().toEpochMilli()
            val end = minOf(endMs, nextHour)
            val slice = ListenSlice(Days.key(z.toLocalDate()), z.hour, end - t)
            val last = out.lastOrNull()
            if (last != null && last.day == slice.day && last.hour == slice.hour) {
                out[out.size - 1] = last.copy(ms = last.ms + slice.ms)
            } else out.add(slice)
            t = end
        }
        return out
    }
}

/** One counted play (30+ s heard) with its song. [tracked] = recorded by 4.9.0 or later. */
data class PlayRow(
    val trackId: String,
    val title: String,
    val artist: String,
    val thumbnailUrl: String,
    val durationSec: Long,
    val playedAt: Long,
    val listenedMs: Long,
    val tracked: Boolean
)

/** Real listening time (4.9.0+) of one song in one hour of the day. */
data class ListenRow(
    val trackId: String,
    val title: String,
    val artist: String,
    val thumbnailUrl: String,
    val hour: Int,
    val ms: Long
)

data class TopSong(
    val trackId: String,
    val title: String,
    val artist: String,
    val thumbnailUrl: String,
    val plays: Int,
    val ms: Long
)

data class TopArtist(val name: String, val ms: Long, val plays: Int)

data class Streak(val artist: String, val count: Int)

data class DayStats(
    val day: Int,
    val timeMs: Long,
    val songs: Int,
    val artists: Int,
    val topSong: TopSong?,
    val topArtist: TopArtist?,
    /** 0-23, or -1 when there was no listening. */
    val peakHour: Int,
    val streak: Streak?,
    /** Part of the time is the old-data estimate (plays from before 4.9.0). */
    val estimated: Boolean
) {
    val isQuiet: Boolean get() = StatsEngine.isQuiet(timeMs, songs)
    val hasHistory: Boolean get() = StatsEngine.hasHistory(timeMs, songs)

    companion object {
        fun empty(day: Int) = DayStats(day, 0L, 0, 0, null, null, -1, null, false)
    }
}

object StatsEngine {
    /** Under 5 minutes or no counted song: no popup (it still shows in the Stats tab). */
    const val QUIET_MS = 5L * 60L * 1000L

    /** A day only becomes "the recap" with at least one counted song or a minute of listening. */
    const val HISTORY_MS = 60_000L

    /** "3 songs by Anirudh back to back" needs at least this many in a row. */
    const val MIN_STREAK = 3

    /** Songs count as back to back when the next one starts within the song's length + this. */
    const val STREAK_GAP_MS = 15L * 60L * 1000L

    /** Song length for a back-to-back check when it's unknown. */
    private const val UNKNOWN_LENGTH_MS = 10L * 60L * 1000L

    /** Artists that count toward "artists listened" from listening alone (no counted play). */
    internal const val ARTIST_MIN_MS = 30_000L

    fun isQuiet(timeMs: Long, songs: Int): Boolean = songs <= 0 || timeMs < QUIET_MS

    fun hasHistory(timeMs: Long, songs: Int): Boolean = songs > 0 || timeMs >= HISTORY_MS

    /**
     * Time listened for a play from before 4.9.0, when only the play itself was saved: the song's
     * full length (runs a bit high when it was skipped). Unknown length: what was saved (30 s+).
     */
    fun estimateMs(play: PlayRow): Long =
        if (play.durationSec > 0) play.durationSec * 1000L else play.listenedMs.coerceAtLeast(0L)

    /** "Arijit Singh, Pritam & Shreya" -> "Arijit Singh"; "" for unknown. */
    fun primaryArtist(line: String): String {
        val t = line.trim()
        if (t.isEmpty() || t.equals("Unknown Artist", ignoreCase = true)) return ""
        val first = t.split(SafeRegex.of("""\s*(,|&|\bfeat\.?\s|\bft\.?\s|•)\s*""", RegexOption.IGNORE_CASE))
            .firstOrNull { it.isNotBlank() }?.trim().orEmpty()
        return first.removeSuffix(" - Topic").trim()
    }

    internal fun artistKey(name: String): String =
        name.lowercase(Locale.ROOT).filter { it.isLetterOrDigit() }

    /** Everything one day's stats show, from its counted plays and its real listening time. */
    fun compute(
        day: Int,
        plays: List<PlayRow>,
        listens: List<ListenRow>,
        zone: ZoneId = ZoneId.systemDefault()
    ): DayStats {
        val sorted = plays.sortedBy { it.playedAt }
        val legacy = sorted.filter { !it.tracked }

        val songMs = HashMap<String, Long>()
        val hourMs = LongArray(24)
        val artistMs = HashMap<String, Long>()
        val artistName = HashMap<String, String>()
        val artistPlays = HashMap<String, Int>()
        val artistsHeard = HashSet<String>()

        fun addArtist(line: String, ms: Long) {
            val name = primaryArtist(line)
            if (name.isEmpty()) return
            val k = artistKey(name)
            if (k.isEmpty()) return
            artistName.putIfAbsent(k, name)
            artistMs[k] = (artistMs[k] ?: 0L) + ms
        }

        var total = 0L
        for (l in listens) {
            if (l.ms <= 0L) continue
            total += l.ms
            songMs[l.trackId] = (songMs[l.trackId] ?: 0L) + l.ms
            if (l.hour in 0..23) hourMs[l.hour] += l.ms
            addArtist(l.artist, l.ms)
        }
        for (p in legacy) {
            val ms = estimateMs(p)
            total += ms
            songMs[p.trackId] = (songMs[p.trackId] ?: 0L) + ms
            hourMs[Days.hourOf(p.playedAt, zone)] += ms
            addArtist(p.artist, ms)
        }
        for (p in sorted) {
            val name = primaryArtist(p.artist)
            if (name.isEmpty()) continue
            val k = artistKey(name)
            if (k.isEmpty()) continue
            artistName.putIfAbsent(k, name)
            artistPlays[k] = (artistPlays[k] ?: 0) + 1
            artistsHeard.add(k)
        }
        for ((k, ms) in artistMs) if (ms >= ARTIST_MIN_MS) artistsHeard.add(k)

        val topArtist = artistsHeard
            .map { k -> TopArtist(artistName[k] ?: k, artistMs[k] ?: 0L, artistPlays[k] ?: 0) }
            .sortedWith(compareByDescending<TopArtist> { it.ms }.thenByDescending { it.plays }.thenBy { it.name })
            .firstOrNull()

        val topSong = sorted.groupBy { it.trackId }
            .map { (id, ps) ->
                val last = ps.last()
                TopSong(id, last.title, last.artist, last.thumbnailUrl, ps.size, songMs[id] ?: 0L) to last.playedAt
            }
            .sortedWith(
                compareByDescending<Pair<TopSong, Long>> { it.first.plays }
                    .thenByDescending { it.first.ms }
                    .thenByDescending { it.second }
            )
            .firstOrNull()?.first

        var peak = -1
        var best = 0L
        for (h in 0..23) if (hourMs[h] > 0L && hourMs[h] >= best) { best = hourMs[h]; peak = h }

        return DayStats(
            day = day,
            timeMs = total,
            songs = sorted.size,
            artists = artistsHeard.size,
            topSong = topSong,
            topArtist = topArtist,
            peakHour = peak,
            streak = longestStreak(sorted),
            estimated = legacy.isNotEmpty()
        )
    }

    /**
     * The longest run of counted plays by the same (first) artist, back to back: each next song
     * started within the previous song's length + 15 minutes. Null under [MIN_STREAK].
     */
    fun longestStreak(plays: List<PlayRow>): Streak? {
        val sorted = plays.sortedBy { it.playedAt }
        var bestName = ""
        var bestCount = 0
        var runKey = ""
        var runName = ""
        var run = 0
        var prev: PlayRow? = null
        for (p in sorted) {
            val name = primaryArtist(p.artist)
            val k = artistKey(name)
            val p0 = prev
            val joined = p0 != null && k.isNotEmpty() && k == runKey && run > 0 &&
                p.playedAt - p0.playedAt <= lengthMs(p0) + STREAK_GAP_MS
            if (joined) run++ else {
                runKey = k
                runName = name
                run = if (k.isEmpty()) 0 else 1
            }
            if (run > bestCount) { bestCount = run; bestName = runName }
            prev = p
        }
        return if (bestCount >= MIN_STREAK) Streak(bestName, bestCount) else null
    }

    private fun lengthMs(p: PlayRow): Long =
        if (p.durationSec > 0) p.durationSec * 1000L else UNKNOWN_LENGTH_MS
}

/**
 * Which day the recap shows, and when it pops up. The recap is always the most recent past day
 * with listening history: midnight alone never wipes it, only a newer day with history does.
 */
object RecapRules {
    /** The latest day before [today] that has listening history, or null. */
    fun recapDay(today: Int, daysWithHistory: Collection<Int>): Int? =
        daysWithHistory.filter { it < today }.maxOrNull()

    /** A newer day with history replaces the recap; an older one, today, or nothing never does. */
    fun replaces(current: Int?, candidate: Int?, today: Int): Boolean =
        candidate != null && candidate < today && (current == null || candidate > current)

    /**
     * The same rule with the quiet-day part: a quiet day (under 5 min or 0 songs) never replaces a
     * better (non-quiet) recap. It only becomes the recap when there's nothing better to keep.
     */
    fun replaces(current: DayStats?, candidate: DayStats?, today: Int): Boolean {
        if (candidate == null || !candidate.hasHistory) return false
        if (!replaces(current?.day, candidate.day, today)) return false
        return !candidate.isQuiet || current == null || current.isQuiet
    }

    /**
     * What the Stats tab shows from past days. [recap] is the most recent non-quiet day (story,
     * popup, dot), or the most recent quiet one when no day was big enough. [quietDay] is the most
     * recent day when it was quiet and newer than the recap: its stats still show in the tab, but it
     * doesn't take the recap's place.
     */
    data class Pick(val recap: DayStats?, val quietDay: DayStats?)

    /** [pastDays] = stats of past days with history, any order (days from today on are ignored). */
    fun pick(today: Int, pastDays: List<DayStats>): Pick {
        val days = pastDays.filter { it.day < today && it.hasHistory }.sortedByDescending { it.day }
        val latest = days.firstOrNull() ?: return Pick(null, null)
        val best = days.firstOrNull { !it.isQuiet }
        val recap = best ?: latest
        val quiet = if (latest.isQuiet && latest.day > recap.day) latest else null
        return Pick(recap, quiet)
    }

    /** The popup shows once per recap, never for a quiet day, and not after the recap was viewed. */
    fun shouldPopup(recap: DayStats?, popupShownDay: Int, viewedDay: Int): Boolean =
        recap != null && recap.hasHistory && !recap.isQuiet &&
            recap.day != popupShownDay && recap.day != viewedDay

    /** The dot next to "Stats": a recap is there that hasn't been looked at yet. */
    fun showDot(recapDay: Int?, viewedDay: Int): Boolean = recapDay != null && recapDay != viewedDay
}

object StatsFormat {
    /** "45s", "12m", "2h 14m", "3h". */
    fun duration(ms: Long): String {
        val sec = (ms.coerceAtLeast(0L)) / 1000L
        if (sec < 60L) return "${sec}s"
        val min = sec / 60L
        if (min < 60L) return "${min}m"
        val h = min / 60L
        val m = min % 60L
        return if (m == 0L) "${h}h" else "${h}h ${m}m"
    }

    /** "11 PM", "12 AM", "12 PM". */
    fun hour(h: Int): String {
        val hh = ((h % 24) + 24) % 24
        val shown = if (hh % 12 == 0) 12 else hh % 12
        return "$shown ${if (hh < 12) "AM" else "PM"}"
    }
}
