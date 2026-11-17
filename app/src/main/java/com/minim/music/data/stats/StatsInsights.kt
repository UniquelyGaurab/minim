package com.minim.music.data.stats

/**
 * 6.9.0: the Stats tab's insight rows for one period. Each row is built on its own and left out
 * when it has nothing to say, so an empty period shows no insights at all.
 */
object StatsInsights {

    class Row(val label: String, val value: String)

    fun build(
        plays: List<PlayRow>,
        listens: List<ListenRow>,
        hours: LongArray,
        totalMs: Long,
        log: List<LogRow>,
        fromMs: Long,
        toMs: Long,
        artistsBefore: Set<String>?,
        songsBefore: Set<String>?,
        prevPlays: List<PlayRow>?,
        prevListens: List<ListenRow>?
    ): List<Row> {
        val out = ArrayList<Row>()
        RepriseMath.onRepeat(plays)?.let { (s, _) ->
            out.add(Row("On repeat", "${s.title} · ${s.count} plays in one day"))
        }
        if (songsBefore != null) {
            RepriseMath.obsession(StatsEngine.rankSongs(plays, listens).take(5), songsBefore)?.let {
                out.add(Row("New obsession", "${it.title} · ${it.artist}"))
            }
        }
        if (artistsBefore != null) {
            val fresh = RepriseMath.newArtists(plays, artistsBefore)
            if (fresh.isNotEmpty()) {
                val names = fresh.take(2).joinToString(", ")
                val more = if (fresh.size > 2) " +${fresh.size - 2}" else ""
                out.add(Row(if (fresh.size == 1) "New artist" else "${fresh.size} new artists", names + more))
            }
        }
        if (prevPlays != null && prevListens != null) {
            val (rising, fading) = RepriseMath.risingFading(
                RepriseMath.artistMs(plays, listens), RepriseMath.artistMs(prevPlays, prevListens), 1
            )
            rising.firstOrNull()?.let { out.add(Row("Rising", "${it.name} · +${StatsPeriods.long(it.nowMs - it.beforeMs)}")) }
            fading.firstOrNull()?.let { out.add(Row("Fading", it.name)) }
        }
        RepriseMath.mostSkipped(log, fromMs, toMs)?.let {
            out.add(Row("Most skipped", "${it.title} · ${it.count} skips"))
        }
        val hits = RepriseMath.fermataHits(log, fromMs, toMs)
        if (hits.isNotEmpty()) {
            val more = if (hits.size > 1) " and ${hits.size - 1} more" else ""
            out.add(Row("Found by Fermata", hits.first().title + more))
        }
        val late = RepriseMath.lateNightShare(hours)
        if (late >= 0.05f) out.add(Row("Late nights", "${RepriseMath.percent(late)} after 11 PM"))
        RepriseMath.funComparison(totalMs)?.let { out.add(Row("In other words", it)) }
        return out
    }
}
