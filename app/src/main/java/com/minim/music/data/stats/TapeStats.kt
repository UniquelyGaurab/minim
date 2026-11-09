package com.minim.music.data.stats

import java.time.ZoneId
import java.util.Locale

/**
 * 5.7.0 u51: listening stats for tapes (channel uploads), next to the song stats. Plain Kotlin, unit
 * tested. Tapes are saved in their own tables, so song stats, Recents, Discover and greetings
 * never see them; the Stats tab adds the two together.
 *
 * A tape counts as played once 30 seconds of it are heard, the same rule songs use. Time is real
 * time heard (at 1.5x, ten minutes heard covers fifteen minutes of the tape).
 */

/** One counted tape play (30+ s heard). */
data class TapePlayRow(
    val videoId: String,
    val title: String,
    val channel: String,
    val channelId: String,
    val thumbnailUrl: String,
    val durationSec: Long,
    val playedAt: Long
)

/** Real time heard of one tape in one hour of the day, at one speed (100 = 1x, 150 = 1.5x). */
data class TapeListenRow(
    val videoId: String,
    val title: String,
    val channel: String,
    val channelId: String,
    val thumbnailUrl: String,
    val durationSec: Long,
    val hour: Int,
    val speedPct: Int,
    val ms: Long
)

/** A tape played that day: time heard and how many counted plays. */
data class TapeItem(
    val videoId: String,
    val title: String,
    val channel: String,
    val channelId: String,
    val thumbnailUrl: String,
    val durationSec: Long,
    val ms: Long,
    val plays: Int,
    val lastAt: Long
)

/** A channel listened to that day. [channelId] is blank for tapes saved before u47. */
data class ChannelItem(
    val key: String,
    val name: String,
    val channelId: String,
    val ms: Long,
    val tapes: Int
)

/** What one day's tapes add to the Stats tab and the recap. */
data class TapeDay(
    /** Different tapes played (30+ s). */
    val tapes: Int,
    /** Channels listened to: a played tape, or 30+ s heard. */
    val channels: Int,
    /** Real time heard. */
    val ms: Long,
    /** Time saved by listening above 1x. */
    val savedMs: Long,
    /** The speed that saved the most (150 = 1.5x), or 100 when nothing was sped up. */
    val speedPct: Int,
    /** More than one speed above 1x was used. */
    val mixedSpeeds: Boolean,
    val topChannel: ChannelItem?,
    val topTape: TapeItem?,
    /** Most of the day's tapes started after 11 PM (or before 4 AM). */
    val nightOwl: Boolean
) {
    /** The tapes, channels and top channel cards show. */
    val shows: Boolean get() = tapes > 0 || channels > 0

    companion object {
        val NONE = TapeDay(0, 0, 0L, 0L, 100, false, null, null, false)
    }
}

object TapeStats {
    /** Same as songs: a tape counts as played after 30 seconds heard. */
    const val COUNT_AFTER_MS = 30_000L

    /** A channel counts from listening alone (no counted play) after this much. */
    const val CHANNEL_MIN_MS = 30_000L

    /** "Night owl" hours: 11 PM to 3:59 AM. */
    fun isLate(hour: Int): Boolean = hour >= 23 || hour in 0..3

    fun channelKey(name: String): String =
        name.trim().removeSuffix(" - Topic").lowercase(Locale.ROOT).filter { it.isLetterOrDigit() }

    /** Real time heard x speed = time of tape covered; what's above the real time was saved. */
    fun savedMs(ms: Long, speedPct: Int): Long =
        if (speedPct <= 100 || ms <= 0L) 0L else ms * (speedPct - 100) / 100

    /** The tapes played that day (counted ones), most time first. */
    fun tapes(plays: List<TapePlayRow>, listens: List<TapeListenRow>): List<TapeItem> {
        val counted = plays.groupBy { it.videoId }
        if (counted.isEmpty()) return emptyList()
        val msBy = HashMap<String, Long>()
        for (l in listens) if (l.ms > 0L) msBy[l.videoId] = (msBy[l.videoId] ?: 0L) + l.ms
        return counted.map { (id, ps) ->
            val last = ps.maxByOrNull { it.playedAt }!!
            val withId = ps.firstOrNull { it.channelId.isNotBlank() }?.channelId
                ?: listens.firstOrNull { it.videoId == id && it.channelId.isNotBlank() }?.channelId.orEmpty()
            TapeItem(id, last.title, last.channel, withId, last.thumbnailUrl, last.durationSec,
                msBy[id] ?: 0L, ps.size, last.playedAt)
        }.sortedWith(compareByDescending<TapeItem> { it.ms }.thenByDescending { it.lastAt }.thenBy { it.title })
    }

    /** The channels listened to that day, most time first. */
    fun channels(plays: List<TapePlayRow>, listens: List<TapeListenRow>): List<ChannelItem> {
        val name = HashMap<String, String>()
        val id = HashMap<String, String>()
        val ms = HashMap<String, Long>()
        val tapes = HashMap<String, HashSet<String>>()
        fun note(channel: String, channelId: String): String? {
            val k = channelKey(channel)
            if (k.isEmpty()) return null
            name.putIfAbsent(k, channel.trim().removeSuffix(" - Topic"))
            if (channelId.isNotBlank()) id.putIfAbsent(k, channelId)
            return k
        }
        for (l in listens) {
            if (l.ms <= 0L) continue
            val k = note(l.channel, l.channelId) ?: continue
            ms[k] = (ms[k] ?: 0L) + l.ms
        }
        for (p in plays) {
            val k = note(p.channel, p.channelId) ?: continue
            tapes.getOrPut(k) { HashSet() }.add(p.videoId)
        }
        val heard = name.keys.filter { (tapes[it]?.size ?: 0) > 0 || (ms[it] ?: 0L) >= CHANNEL_MIN_MS }
        return heard.map { k -> ChannelItem(k, name[k] ?: k, id[k].orEmpty(), ms[k] ?: 0L, tapes[k]?.size ?: 0) }
            .sortedWith(compareByDescending<ChannelItem> { it.ms }.thenByDescending { it.tapes }.thenBy { it.name })
    }

    /** Tape listening per hour of the day (for the peak hour, with songs). */
    fun hourMs(listens: List<TapeListenRow>): LongArray {
        val out = LongArray(24)
        for (l in listens) if (l.ms > 0L && l.hour in 0..23) out[l.hour] += l.ms
        return out
    }

    fun compute(
        plays: List<TapePlayRow>,
        listens: List<TapeListenRow>,
        zone: ZoneId = ZoneId.systemDefault()
    ): TapeDay {
        if (plays.isEmpty() && listens.none { it.ms > 0L }) return TapeDay.NONE
        val tapeList = tapes(plays, listens)
        val channelList = channels(plays, listens)
        val total = listens.sumOf { it.ms.coerceAtLeast(0L) }
        val savedBySpeed = HashMap<Int, Long>()
        for (l in listens) {
            val s = savedMs(l.ms, l.speedPct)
            if (s > 0L) savedBySpeed[l.speedPct] = (savedBySpeed[l.speedPct] ?: 0L) + s
        }
        val saved = savedBySpeed.values.sum()
        val topSpeed = savedBySpeed.entries.sortedWith(
            compareByDescending<Map.Entry<Int, Long>> { it.value }.thenByDescending { it.key }
        ).firstOrNull()?.key ?: 100
        // Night owl: when each tape was first played that day
        val starts = plays.groupBy { it.videoId }.values.map { ps -> ps.minOf { it.playedAt } }
        val late = starts.count { isLate(Days.hourOf(it, zone)) }
        return TapeDay(
            tapes = tapeList.size,
            channels = channelList.size,
            ms = total,
            savedMs = saved,
            speedPct = topSpeed,
            mixedSpeeds = savedBySpeed.size > 1,
            topChannel = channelList.firstOrNull(),
            topTape = tapeList.firstOrNull(),
            nightOwl = starts.isNotEmpty() && late * 2 > starts.size
        )
    }

    /**
     * The day's stats with its tapes added: tape counts and time ride along in [DayStats.tapes],
     * and the peak hour is worked out from songs and tapes together.
     */
    fun combine(music: DayStats, tapes: TapeDay, musicHourMs: LongArray, tapeHourMs: LongArray): DayStats {
        if (tapes == TapeDay.NONE) return music
        var peak = -1
        var best = 0L
        for (h in 0..23) {
            val ms = musicHourMs.getOrElse(h) { 0L } + tapeHourMs.getOrElse(h) { 0L }
            if (ms > 0L && ms >= best) { best = ms; peak = h }
        }
        return music.copy(tapes = tapes, peakHour = if (peak >= 0) peak else music.peakHour)
    }

    /** "1.5x", "1.25x", "2x". */
    fun speedLabel(speedPct: Int): String {
        val whole = speedPct / 100
        val frac = speedPct % 100
        return when {
            frac == 0 -> "${whole}x"
            frac % 10 == 0 -> "$whole.${frac / 10}x"
            else -> "$whole.${frac.toString().padStart(2, '0')}x"
        }
    }

    /** "22 min", "1h 5 min", "40s". */
    fun savedText(ms: Long): String {
        val sec = (ms / 1000L).coerceAtLeast(0L)
        if (sec < 60L) return "${sec}s"
        val min = sec / 60L
        if (min < 60L) return "$min min"
        val h = min / 60L
        val m = min % 60L
        return if (m == 0L) "${h}h" else "${h}h $m min"
    }

    /** "1.5x saved you 22 min", or null when nothing played above 1x. */
    fun savedLine(t: TapeDay): String? {
        if (t.savedMs < 1000L) return null
        val who = if (t.mixedSpeeds) "speeding up" else speedLabel(t.speedPct)
        return "$who saved you ${savedText(t.savedMs)}"
    }
}
