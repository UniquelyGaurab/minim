package com.minim.music.data.stats

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * 6.2.0: listening time is saved once a minute while playing (and on pause, stop, song change and
 * background) instead of every 5 seconds. These replay the player's tick loop with both rules and
 * check the saved minutes come out the same.
 */
class ListenSavesTest {
    private val zone = ZoneId.of("Asia/Kolkata")

    private data class Add(val slice: ListenSlice, val track: String)

    /** What the database ends up with: ms per (day, hour, song), plus how many saves and Fermata events it took. */
    private class Db {
        val ms = HashMap<Triple<Int, Int, String>, Long>()
        var saves = 0
        var events = 0

        fun save(adds: List<Add>) {
            if (adds.isEmpty()) return
            saves++
            // One Fermata write per (day, song) a save touches (LibraryDatabase.addListenTime's outbox keys).
            events += adds.map { it.slice.day to it.track }.toSet().size
            for (a in adds) {
                val k = Triple(a.slice.day, a.slice.hour, a.track)
                ms[k] = (ms[k] ?: 0L) + a.slice.ms
            }
        }
    }

    /** One step of a listening session: play [track] for [seconds], then pause, skip or keep going. */
    private data class Step(val track: String, val seconds: Int, val thenPause: Int = 0)

    /**
     * The tick loop of PlaybackController: a credit every second while playing, a save when
     * [every] has passed, and a save on pause and on song change. [killAt] stops the app at that
     * second without the last save.
     */
    private fun run(steps: List<Step>, start: Long, every: Long, killAt: Int? = null): Db {
        val db = Db()
        val buf = ListenBuffer<Add>({ it.slice }, { a, s -> a.copy(slice = s) })
        var wall = start
        var rt = 1_000_000L
        var lastSaved = rt
        var elapsed = 0
        var current: String? = null
        for (step in steps) {
            if (step.track != current) { // song change: what's pending is saved first
                db.save(buf.drain())
                lastSaved = rt
                current = step.track
            }
            repeat(step.seconds) {
                if (killAt != null && elapsed >= killAt) return db
                wall += 1000
                rt += 1000
                elapsed++
                for (s in ListenSplit.split(wall - 1000, wall, zone)) buf.add("${s.day}|${s.hour}|${step.track}", Add(s, step.track))
                if (ListenSaves.due(rt, lastSaved, every)) {
                    db.save(buf.drain())
                    lastSaved = rt
                }
            }
            if (step.thenPause > 0) { // pause: saved right away, nothing credited while paused
                db.save(buf.drain())
                wall += step.thenPause * 1000L
                rt += step.thenPause * 1000L
                lastSaved = rt
            }
        }
        db.save(buf.drain()) // stop / background
        return db
    }

    // Three hours from 10:57 PM: across midnight and three hour boundaries, with pauses, skips
    // after a few seconds, songs shorter than a minute, a repeat of one song and a long pause.
    private val session: List<Step> = buildList {
        val songs = listOf("a", "b", "c", "d", "e")
        var i = 0
        while (sumOf { it.seconds + it.thenPause } < 3 * 3600) {
            val track = songs[i % songs.size]
            val seconds = listOf(213, 7, 48, 361, 3, 125, 59, 61, 600)[i % 9]
            val pause = listOf(0, 0, 12, 0, 0, 1800, 0, 4, 0)[i % 9]
            add(Step(track, seconds, pause))
            if (i % 7 == 3) add(Step(track, 95)) // the same song again: no song change
            i++
        }
    }

    private val start = LocalDateTime.of(2026, 11, 12, 22, 57, 13).atZone(zone).toInstant().toEpochMilli()

    @Test
    fun `saving once a minute gives the same minutes per day, hour and song as every 5 seconds`() {
        val old = run(session, start, every = 5_000L)
        val new = run(session, start, every = ListenSaves.CHECKPOINT_MS)
        assertEquals(old.ms, new.ms)
        val total = session.sumOf { it.seconds } * 1000L
        assertEquals(total, new.ms.values.sum())
        assertTrue("spans two days", new.ms.keys.map { it.first }.toSet().size == 2)
        assertTrue("spans several hours", new.ms.keys.map { it.first to it.second }.toSet().size >= 4)
        // About one save a minute instead of twelve.
        assertTrue("${new.events} events vs ${old.events}", new.events * 5 < old.events)
        println("listen saves over ${total / 60_000} min: ${old.saves} saves / ${old.events} events every 5 s, ${new.saves} saves / ${new.events} events once a minute")
    }

    @Test
    fun `a kill loses at most the last minute`() {
        // killAt counts seconds of playing (pauses don't count), so every kill must fall before the
        // session's last played second, or it's a clean stop and nothing is lost.
        val played = session.sumOf { it.seconds }
        val all = run(session, start, every = ListenSaves.CHECKPOINT_MS)
        assertEquals(played * 1000L, all.ms.values.sum())
        val kills = (listOf(30, 59, 61, 119, 1234, 4321, played / 2, played - 1) + (1 until played step 7)).toSortedSet()
        var worst = 0L
        for (killAt in kills) {
            assertTrue("kill at $killAt s is mid-session (played $played s)", killAt in 1 until played)
            val killed = run(session, start, every = ListenSaves.CHECKPOINT_MS, killAt = killAt)
            val heard = killAt * 1000L
            val saved = killed.ms.values.sum()
            assertTrue("kill at $killAt s: saved $saved of $heard", saved <= heard && heard - saved <= ListenSaves.CHECKPOINT_MS)
            assertTrue(all.ms.values.sum() >= saved)
            worst = maxOf(worst, heard - saved)
        }
        // The kills really land between saves: the worst one loses most of a minute, never more.
        assertTrue("worst loss $worst ms", worst in 50_000L..ListenSaves.CHECKPOINT_MS)
    }

    @Test
    fun `the buffer merges heard time per key and empties on drain`() {
        val buf = ListenBuffer<Add>({ it.slice }, { a, s -> a.copy(slice = s) })
        buf.add("1|10|a", Add(ListenSlice(1, 10, 1000), "a"))
        buf.add("1|10|b", Add(ListenSlice(1, 10, 500), "b"))
        buf.add("1|10|a", Add(ListenSlice(1, 10, 2500), "a"))
        assertEquals(listOf(Add(ListenSlice(1, 10, 3500), "a"), Add(ListenSlice(1, 10, 500), "b")), buf.drain())
        assertTrue(buf.isEmpty())
        assertEquals(emptyList<Add>(), buf.drain())
    }

    @Test
    fun `the checkpoint is a minute and live Stats keeps 5 seconds`() {
        assertEquals(60_000L, ListenSaves.CHECKPOINT_MS)
        assertEquals(5_000L, ListenSaves.LIVE_STATS_MS)
        assertTrue(ListenSaves.due(60_000L, 0L))
        assertTrue(!ListenSaves.due(59_999L, 0L))
    }
}
