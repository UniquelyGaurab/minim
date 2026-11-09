package com.minim.music.ui

import com.minim.music.data.stats.DayStats
import com.minim.music.data.stats.Days
import com.minim.music.data.stats.ListenRow
import com.minim.music.data.stats.PlayRow
import com.minim.music.data.stats.StatsEngine
import com.minim.music.data.stats.StatsFormat
import com.minim.music.data.stats.TapeListenRow
import com.minim.music.data.stats.TapeStats
import com.minim.music.data.stats.TopSong
import java.time.Instant
import java.time.ZoneId

/**
 * 5.8.2 (update 54): the extra things the reworked recap story shows, worked out once when it
 * opens. Plain Kotlin (unit tested): the 24 hours of the day (songs and tapes together), the first
 * and last song, and the top five songs, ranked exactly like the top song.
 */
class RecapDay(
    /** Listening per hour, 0-23, songs and tapes together. */
    val hours: LongArray,
    /** The day's first and last counted songs (null with no songs). */
    val first: PlayRow?,
    val last: PlayRow?,
    /** Up to [TOP] songs, best first. The first one is always the day's top song. */
    val top: List<TopSong>,
    /** Every song of the day in rank order, for "save as playlist" (ids only). */
    val ranked: List<String>,
    /** The day's own peak hour (DayStats), used when it has listening, so the story and Stats agree. */
    private val statsPeak: Int = -1
) {
    /** The hour with the most listening (the same hour the Stats tab calls the peak), or -1. */
    val peakHour: Int
        get() {
            if (statsPeak in 0..23 && hours[statsPeak] > 0L) return statsPeak
            var peak = -1
            var best = 0L
            for (h in 0..23) if (hours[h] > 0L && hours[h] >= best) { best = hours[h]; peak = h }
            return peak
        }

    /** The first-and-last slide needs two counted songs. */
    val hasFirstLast: Boolean get() = first != null && last != null && first !== last

    /** Started and ended the day with the same song. */
    val sameFirstLast: Boolean get() = hasFirstLast && first!!.trackId == last!!.trackId

    companion object {
        const val TOP = 5
        /** Songs saved by "save as playlist". */
        const val PLAYLIST_MAX = 25

        val NONE = RecapDay(LongArray(24), null, null, emptyList(), emptyList())

        fun compute(
            stats: DayStats,
            plays: List<PlayRow>,
            listens: List<ListenRow>,
            tapeListens: List<TapeListenRow>,
            zone: ZoneId = ZoneId.systemDefault()
        ): RecapDay {
            val music = StatsEngine.hourMs(plays, listens, zone)
            val tapes = TapeStats.hourMs(tapeListens)
            val hours = LongArray(24) { music[it] + tapes[it] }
            val sorted = plays.sortedBy { it.playedAt }
            val ranked = StatsEngine.rankSongs(plays, listens)
            // The top song is DayStats' own (same ranking), so the countdown's #1 always matches
            val top = ArrayList<TopSong>()
            stats.topSong?.let { top.add(it) }
            for (s in ranked) {
                if (top.size >= TOP) break
                if (top.none { it.trackId == s.trackId }) top.add(s)
            }
            val ids = ArrayList<String>()
            for (s in top) ids.add(s.trackId)
            for (s in ranked) if (s.trackId !in ids) ids.add(s.trackId)
            return RecapDay(hours, sorted.firstOrNull(), sorted.lastOrNull(), top, ids.take(PLAYLIST_MAX), stats.peakHour)
        }

        /** "8:12 AM" for a play's start. */
        fun clock(ms: Long, zone: ZoneId = ZoneId.systemDefault()): String {
            val t = Instant.ofEpochMilli(ms).atZone(zone)
            val h = t.hour % 12
            val m = t.minute
            return "${if (h == 0) 12 else h}:${if (m < 10) "0$m" else "$m"} ${if (t.hour < 12) "AM" else "PM"}"
        }

        /** "Sunday" for the day being recapped. */
        fun weekday(day: Int): String =
            Days.date(day).dayOfWeek.getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.ENGLISH)

        /** The intro line: "Your Sunday, in music." */
        fun introLine(day: Int): String = "Your ${weekday(day)}, in music."

        /** The playlist "save as playlist" makes: "Sunday, 8 Nov · top songs". */
        fun playlistName(day: Int): String = "${Days.label(day)} · top songs"
    }
}

/**
 * 5.8.2: "Guess your top song": three covers, the top song and the next two, in an order that
 * depends on the day (so the answer isn't always in the same place, but the same recap always
 * looks the same). Left out on days with fewer than three songs.
 */
object RecapGuess {
    fun options(d: RecapDay, day: Int): List<TopSong>? {
        if (d.top.size < 3) return null
        val three = d.top.take(3)
        val at = Math.floorMod(day * 7 + 3, 3) // where the answer goes: 0, 1 or 2
        val others = three.drop(1).let { if (Math.floorMod(day, 2) == 0) it else it.reversed() }
        val out = others.toMutableList()
        out.add(at, three[0])
        return out
    }

    fun reply(right: Boolean): String = if (right) "You know yourself." else "Not quite. It was this one."
}

/**
 * 5.8.2: the listening personality on the second-to-last slide and on the card. The first type
 * the day matches wins; the others it also matched show as small tags under it.
 */
object RecapPersonality {

    enum class Type(val label: String) {
        STORY_MODE("Story mode"),
        ON_REPEAT("On repeat"),
        LOYALIST("Loyalist"),
        EXPLORER("Explorer"),
        NIGHT_OWL("Night owl"),
        EARLY_BIRD("Early bird"),
        MARATHONER("Marathoner"),
        SNACKER("Snacker"),
        MOOD_MIXER("Mood mixer")
    }

    class Result(val type: Type, val line: String, val also: List<Type>) {
        val label: String get() = type.label
    }

    private const val MIN = 60_000L

    /** Share of the day's listening in [hours] (inclusive, wrapping past midnight). */
    internal fun share(hours: LongArray, from: Int, to: Int): Float {
        val total = hours.sum()
        if (total <= 0L) return 0f
        var part = 0L
        var h = from
        while (true) {
            part += hours[h]
            if (h == to) break
            h = (h + 1) % 24
        }
        return part.toFloat() / total
    }

    fun matches(s: DayStats, d: RecapDay): List<Pair<Type, String>> = buildList {
        val song = s.topSong
        val artist = s.topArtist
        if (s.tapes.ms > 0L && s.tapes.ms > s.timeMs) {
            add(Type.STORY_MODE to "More tapes than tunes today.")
        }
        if (song != null && song.plays >= 4 && song.plays * 100 >= s.songs * 35) {
            val title = Greetings.shortTitle(song.title, song.artist, 22)
            add(Type.ON_REPEAT to "$title, ${song.plays} times. You know what you like.")
        }
        if (artist != null && artist.name.isNotBlank() && s.songs >= 4 && s.timeMs > 0L &&
            artist.ms * 2 >= s.timeMs && s.artists <= 4
        ) {
            add(Type.LOYALIST to "Most of your day belonged to ${Greetings.shortArtist(artist.name, 20)}.")
        }
        if (s.artists >= 8 || (s.songs >= 8 && s.artists * 10 >= s.songs * 6)) {
            add(Type.EXPLORER to "${s.artists} artists. Always after the next one.")
        }
        if (s.totalMs >= 20 * MIN && share(d.hours, 23, 4) >= 0.5f) {
            add(Type.NIGHT_OWL to "Most of it happened after dark.")
        }
        if (s.totalMs >= 20 * MIN && share(d.hours, 5, 9) >= 0.5f) {
            add(Type.EARLY_BIRD to "Your day started with music.")
        }
        if (s.totalMs >= 3L * 60L * MIN) {
            add(Type.MARATHONER to "${StatsFormat.duration(s.totalMs)} of listening. Big day.")
        }
        if (s.totalMs in 1 until 20 * MIN) {
            add(Type.SNACKER to "A quick bite of music.")
        }
    }

    /** Null only when nothing was played. */
    fun pick(s: DayStats, d: RecapDay): Result? {
        if (!s.hasHistory) return null
        val all = matches(s, d)
        if (all.isEmpty()) return Result(Type.MOOD_MIXER, "A bit of everything.", emptyList())
        return Result(all[0].first, all[0].second, all.drop(1).map { it.first }.take(2))
    }
}
