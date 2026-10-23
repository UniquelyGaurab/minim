package com.minim.music.data.stats

import java.time.ZoneId

/** v4.9.2: one song in today's songs sheet: how many counted plays it had today. */
data class SongCount(
    val trackId: String,
    val title: String,
    val artist: String,
    val thumbnailUrl: String,
    val plays: Int,
    val ms: Long
)

/**
 * v4.9.2: the lists behind today's tappable boxes (songs and artists sheets). Same rules as
 * [StatsEngine.compute], so the sheet adds up to the number in the box.
 */
object TodayLists {

    /** Every song with a counted play, most played first (then most listened, then latest). */
    fun songs(plays: List<PlayRow>, listens: List<ListenRow>): List<SongCount> {
        val songMs = HashMap<String, Long>()
        for (l in listens) if (l.ms > 0L) songMs[l.trackId] = (songMs[l.trackId] ?: 0L) + l.ms
        for (p in plays) if (!p.tracked) songMs[p.trackId] = (songMs[p.trackId] ?: 0L) + StatsEngine.estimateMs(p)
        return plays.sortedBy { it.playedAt }.groupBy { it.trackId }
            .map { (id, ps) ->
                val last = ps.last()
                SongCount(id, last.title, last.artist, last.thumbnailUrl, ps.size, songMs[id] ?: 0L) to last.playedAt
            }
            .sortedWith(
                compareByDescending<Pair<SongCount, Long>> { it.first.plays }
                    .thenByDescending { it.first.ms }
                    .thenByDescending { it.second }
            )
            .map { it.first }
    }

    /** Every artist heard (a counted play or 30 s+ of listening), most listened first. */
    fun artists(
        plays: List<PlayRow>,
        listens: List<ListenRow>,
        zone: ZoneId = ZoneId.systemDefault()
    ): List<TopArtist> {
        val artistMs = HashMap<String, Long>()
        val artistName = HashMap<String, String>()
        val artistPlays = HashMap<String, Int>()
        val heard = HashSet<String>()

        fun key(line: String): String? {
            val name = StatsEngine.primaryArtist(line)
            if (name.isEmpty()) return null
            val k = StatsEngine.artistKey(name)
            if (k.isEmpty()) return null
            artistName.putIfAbsent(k, name)
            return k
        }

        for (l in listens) {
            if (l.ms <= 0L) continue
            val k = key(l.artist) ?: continue
            artistMs[k] = (artistMs[k] ?: 0L) + l.ms
        }
        val sorted = plays.sortedBy { it.playedAt }
        for (p in sorted) if (!p.tracked) {
            val k = key(p.artist) ?: continue
            artistMs[k] = (artistMs[k] ?: 0L) + StatsEngine.estimateMs(p)
        }
        for (p in sorted) {
            val k = key(p.artist) ?: continue
            artistPlays[k] = (artistPlays[k] ?: 0) + 1
            heard.add(k)
        }
        for ((k, ms) in artistMs) if (ms >= StatsEngine.ARTIST_MIN_MS) heard.add(k)

        return heard
            .map { k -> TopArtist(artistName[k] ?: k, artistMs[k] ?: 0L, artistPlays[k] ?: 0) }
            .sortedWith(compareByDescending<TopArtist> { it.ms }.thenByDescending { it.plays }.thenBy { it.name })
    }
}

/**
 * v4.9.2: the day streak in the Stats tab: days in a row with at least 10 minutes on Minim.
 * Today counts once it reaches 10 minutes, but today being under 10 minutes never breaks the
 * streak: it only resets after a whole past day goes by under 10 minutes.
 */
object DayStreak {
    const val MIN_MS = 10L * 60L * 1000L

    /** Longest look back (a year), so a broken clock can't loop forever. */
    const val MAX_DAYS = 400

    /** [msByDay] = listening time per day (yyyymmdd); days not in it count as 0. */
    fun count(today: Int, msByDay: Map<Int, Long>): Int {
        var n = if ((msByDay[today] ?: 0L) >= MIN_MS) 1 else 0
        var d = Days.previous(today)
        var guard = 0
        while ((msByDay[d] ?: 0L) >= MIN_MS && guard++ < MAX_DAYS) {
            n++
            d = Days.previous(d)
        }
        return n
    }
}
