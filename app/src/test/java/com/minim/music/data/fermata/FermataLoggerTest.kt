package com.minim.music.data.fermata

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.util.concurrent.Executor

/** 5.9.0 u59: the Fermata event log's rules, without a phone. */
class FermataLoggerTest {

    private class FakeSink : FermataSink {
        val rows = LinkedHashMap<Long, FermataEvent>()
        var updates = 0
        var failInsert = false
        var failUpdate = false
        private var next = 1L
        override fun insert(e: FermataEvent): Long {
            if (failInsert) throw IllegalStateException("disk full")
            val id = next++
            rows[id] = e.copy(id = id)
            return id
        }
        override fun updatePlay(rowId: Long, listenedMs: Long, durationMs: Long?, speedPct: Int?) {
            if (failUpdate) throw IllegalStateException("locked")
            updates++
            val r = rows[rowId] ?: return
            rows[rowId] = r.copy(listenedMs = listenedMs, durationMs = durationMs ?: r.durationMs, speedPct = speedPct ?: r.speedPct)
        }
        fun ofType(t: String) = rows.values.filter { it.type == t }
    }

    private val ist = ZoneId.of("Asia/Kolkata")
    // 2026-11-11 19:55 IST, a Wednesday
    private val now = longArrayOf(java.time.ZonedDateTime.of(2026, 11, 11, 19, 55, 0, 0, ist).toInstant().toEpochMilli())
    private val direct = Executor { it.run() }
    private var sessionCounter = 0

    private fun logger(
        sink: FermataSink, kv: FermataKv = MapKv(), deviceKv: FermataKv = MapKv(),
        executor: Executor = direct, errors: MutableList<Throwable> = ArrayList()
    ) = FermataLogger(
        sink, kv, executor, clock = { now[0] }, zone = { ist },
        newSessionId = { "session-${++sessionCounter}" }, deviceIdKv = deviceKv, onError = { errors.add(it) }
    )

    private fun FermataLogger.play(id: String, durMs: Long = 200_000L, source: String? = FermataSource.SEARCH) =
        startPlay(id, "Song $id", "Artist", durMs, isTape = false, source = source)

    private fun advance(ms: Long) { now[0] += ms }

    // ---- play rows ------------------------------------------------------------------------------

    @Test fun everyTrackGetsOnePlayRowWithTheTimeHeard() {
        val sink = FakeSink()
        val f = logger(sink)
        f.play("aaaaaaaaaaa")
        repeat(5) { advance(1_000); f.heard("aaaaaaaaaaa", 1_000) }
        f.leave(userMovedOn = false, positionMs = null)
        val plays = sink.ofType(FermataType.PLAY)
        assertEquals(1, plays.size)
        val p = plays[0]
        assertEquals(5_000L, p.listenedMs)
        assertEquals("aaaaaaaaaaa", p.trackId)
        assertEquals("Song aaaaaaaaaaa", p.title)
        assertEquals(200_000L, p.durationMs)
        assertEquals(19, p.hour)
        assertEquals(3, p.dow)
        assertNull(p.positionMs)
        assertNull(p.prevTrackId)
        assertEquals("search", p.source)
    }

    @Test fun aShortListenStillWritesAPlayRow() {
        val sink = FakeSink()
        val f = logger(sink)
        f.play("aaaaaaaaaaa")
        advance(800); f.heard("aaaaaaaaaaa", 800)
        f.leave(userMovedOn = true, positionMs = 800)
        assertEquals(800L, sink.ofType(FermataType.PLAY).single().listenedMs)
    }

    @Test fun pausesAreNotCounted() {
        val sink = FakeSink()
        val f = logger(sink)
        f.play("aaaaaaaaaaa")
        f.heard("aaaaaaaaaaa", 10_000)
        advance(10 * 60_000) // paused for ten minutes: the player sends no heard time
        f.heard("aaaaaaaaaaa", 5_000)
        f.leave(false, null)
        assertEquals(15_000L, sink.ofType(FermataType.PLAY).single().listenedMs)
    }

    @Test fun tapesWriteTapePlayWithTheirSpeed() {
        val sink = FakeSink()
        val f = logger(sink)
        f.startPlay("tapetapetap", "Tape", "SR PAY STORIES", 3_600_000L, isTape = true, source = FermataSource.TAPE, speedPct = 150)
        f.heard("tapetapetap", 60_000)
        f.leave(false, null)
        assertTrue(sink.ofType(FermataType.PLAY).isEmpty())
        val t = sink.ofType(FermataType.TAPE_PLAY).single()
        assertEquals(60_000L, t.listenedMs)
        assertEquals(150, t.speedPct)
        assertEquals("tape", t.source)
    }

    @Test fun songsHaveNoSpeed() {
        val sink = FakeSink()
        logger(sink).apply { startPlay("aaaaaaaaaaa", "a", "b", 1L, false, null, speedPct = 150); leave(false, null) }
        assertNull(sink.rows.values.single().speedPct)
    }

    // ---- skip vs play ---------------------------------------------------------------------------

    @Test fun movingOnWithMoreThanTenSecondsLeftAlsoWritesASkip() {
        val sink = FakeSink()
        val f = logger(sink)
        f.play("aaaaaaaaaaa", durMs = 200_000)
        f.heard("aaaaaaaaaaa", 42_000)
        f.leave(userMovedOn = true, positionMs = 42_000)
        assertEquals(1, sink.ofType(FermataType.PLAY).size)
        val s = sink.ofType(FermataType.SKIP).single()
        assertEquals(42_000L, s.positionMs)
        assertNull(s.listenedMs)
        assertEquals("aaaaaaaaaaa", s.trackId)
    }

    @Test fun tenSecondsLeftOrLessIsNotASkip() {
        for (pos in listOf(190_000L, 195_000L, 200_000L)) {
            val sink = FakeSink()
            val f = logger(sink)
            f.play("aaaaaaaaaaa", durMs = 200_000)
            f.leave(userMovedOn = true, positionMs = pos)
            assertTrue("at $pos", sink.ofType(FermataType.SKIP).isEmpty())
            assertEquals(1, sink.ofType(FermataType.PLAY).size)
        }
        val sink = FakeSink()
        logger(sink).apply { play("aaaaaaaaaaa", durMs = 200_000); leave(true, 189_999L) }
        assertEquals(1, sink.ofType(FermataType.SKIP).size)
    }

    @Test fun runningOutOnItsOwnIsNeverASkip() {
        val sink = FakeSink()
        val f = logger(sink)
        f.play("aaaaaaaaaaa", durMs = 200_000)
        f.leave(userMovedOn = false, positionMs = 30_000)
        assertTrue(sink.ofType(FermataType.SKIP).isEmpty())
    }

    @Test fun skipUsesTheLastPlaceSeenWhenNoneIsGiven() {
        val sink = FakeSink()
        val f = logger(sink)
        f.play("aaaaaaaaaaa", durMs = 0) // length unknown at the start
        f.position("aaaaaaaaaaa", 61_000, 240_000)
        f.leave(userMovedOn = true, positionMs = null)
        assertEquals(61_000L, sink.ofType(FermataType.SKIP).single().positionMs)
        assertEquals(240_000L, sink.ofType(FermataType.PLAY).single().durationMs)
    }

    @Test fun unknownLengthIsNeverASkip() {
        assertFalse(FermataSkip.isSkip(true, 5_000, 0))
        assertTrue(FermataSkip.isSkip(true, 5_000, 16_000))
        assertFalse(FermataSkip.isSkip(true, 6_000, 16_000))
        assertFalse(FermataSkip.isSkip(false, 5_000, 16_000))
    }

    @Test fun startingAnotherSongClosesTheOpenOne() {
        val sink = FakeSink()
        val f = logger(sink)
        f.play("aaaaaaaaaaa")
        f.heard("aaaaaaaaaaa", 3_000)
        f.play("bbbbbbbbbbb")
        f.leave(false, null)
        val plays = sink.ofType(FermataType.PLAY)
        assertEquals(listOf("aaaaaaaaaaa", "bbbbbbbbbbb"), plays.map { it.trackId })
        assertEquals(3_000L, plays[0].listenedMs)
        assertEquals("aaaaaaaaaaa", plays[1].prevTrackId)
    }

    @Test fun theUserMoveReasonsAreSeekAndNewList() {
        assertTrue(FermataMoves.isUserMove(androidx.media3.common.Player.MEDIA_ITEM_TRANSITION_REASON_SEEK))
        assertTrue(FermataMoves.isUserMove(androidx.media3.common.Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED))
        assertFalse(FermataMoves.isUserMove(androidx.media3.common.Player.MEDIA_ITEM_TRANSITION_REASON_AUTO))
        assertFalse(FermataMoves.isUserMove(androidx.media3.common.Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT))
    }

    // ---- queued ---------------------------------------------------------------------------------

    @Test fun queuedByHandWritesAQueuedRow() {
        val sink = FakeSink()
        val f = logger(sink)
        f.play("aaaaaaaaaaa")
        f.queued("ccccccccccc", "Song c", "Artist", 180_000, FermataSource.QUEUE)
        val q = sink.ofType(FermataType.QUEUED).single()
        assertEquals("ccccccccccc", q.trackId)
        assertEquals("queue", q.source)
        assertEquals(sink.ofType(FermataType.PLAY).single().sessionId, q.sessionId)
        assertEquals("aaaaaaaaaaa", q.prevTrackId)
        assertNull(q.listenedMs)
    }

    // ---- sessions -------------------------------------------------------------------------------

    @Test fun aNewSessionStartsAfterThirtyMinutesOfNothing() {
        val sink = FakeSink()
        val f = logger(sink)
        f.play("aaaaaaaaaaa")
        advance(60_000); f.heard("aaaaaaaaaaa", 60_000)
        f.leave(false, null)
        advance(30 * 60_000L - 1) // just under 30 minutes since the last sound
        f.play("bbbbbbbbbbb")
        f.leave(false, null)
        advance(30 * 60_000L) // exactly 30 minutes
        f.play("ccccccccccc")
        f.leave(false, null)
        val p = sink.ofType(FermataType.PLAY)
        assertEquals(p[0].sessionId, p[1].sessionId)
        assertEquals("aaaaaaaaaaa", p[1].prevTrackId)
        assertNotEquals(p[1].sessionId, p[2].sessionId)
        assertNull(p[2].prevTrackId)
    }

    @Test fun aLongSongKeepsItsSessionAlive() {
        val sink = FakeSink()
        val f = logger(sink)
        f.startPlay("tapetapetap", "Tape", "Ch", 3_600_000, isTape = true, source = null)
        repeat(50) { advance(60_000); f.heard("tapetapetap", 60_000) } // 50 minutes of listening
        f.play("bbbbbbbbbbb")
        f.leave(false, null)
        val rows = sink.rows.values.toList()
        assertEquals(rows[0].sessionId, rows[1].sessionId)
        assertEquals("tapetapetap", rows[1].prevTrackId)
    }

    @Test fun theSessionCarriesOnAfterAnAppRestart() {
        val sink = FakeSink()
        val kv = MapKv()
        val dev = MapKv()
        logger(sink, kv, dev).apply {
            play("aaaaaaaaaaa")
            advance(20_000); heard("aaaaaaaaaaa", 20_000)
            flush()
        }
        // the app is killed; a new logger starts from what was saved
        advance(5 * 60_000L)
        logger(sink, kv, dev).apply { play("bbbbbbbbbbb"); leave(false, null) }
        val p = sink.ofType(FermataType.PLAY)
        assertEquals(p[0].sessionId, p[1].sessionId)
        assertEquals("aaaaaaaaaaa", p[1].prevTrackId)
        // and after a long break it doesn't
        advance(31 * 60_000L)
        logger(sink, kv, dev).apply { play("ccccccccccc"); leave(false, null) }
        assertNotEquals(p[1].sessionId, sink.ofType(FermataType.PLAY)[2].sessionId)
    }

    @Test fun resumingTheSameSongAfterALongPauseStartsANewRowAndSession() {
        val sink = FakeSink()
        val f = logger(sink)
        f.play("aaaaaaaaaaa")
        f.heard("aaaaaaaaaaa", 10_000)
        advance(45 * 60_000L)
        f.play("aaaaaaaaaaa") // pressed play again on the same song
        f.leave(false, null)
        val p = sink.ofType(FermataType.PLAY)
        assertEquals(2, p.size)
        assertNotEquals(p[0].sessionId, p[1].sessionId)
        assertNull(p[1].prevTrackId)
    }

    @Test fun theSameSongStartingAgainSoonIsTheSameRow() {
        val sink = FakeSink()
        val f = logger(sink)
        f.play("aaaaaaaaaaa")
        f.heard("aaaaaaaaaaa", 10_000)
        advance(60_000)
        f.play("aaaaaaaaaaa") // paused a minute, then play: the tick calls start again
        f.heard("aaaaaaaaaaa", 10_000)
        f.leave(false, null)
        assertEquals(20_000L, sink.ofType(FermataType.PLAY).single().listenedMs)
    }

    // ---- repeat one -----------------------------------------------------------------------------

    @Test fun repeatOneWritesOneRowPerLoop() {
        val sink = FakeSink()
        val f = logger(sink)
        f.play("aaaaaaaaaaa", durMs = 100_000)
        f.heard("aaaaaaaaaaa", 100_000)
        f.startLoop("aaaaaaaaaaa", "Song aaaaaaaaaaa", "Artist", 100_000, false, FermataSource.SEARCH)
        f.heard("aaaaaaaaaaa", 100_000)
        f.startLoop("aaaaaaaaaaa", "Song aaaaaaaaaaa", "Artist", 100_000, false, FermataSource.SEARCH)
        f.heard("aaaaaaaaaaa", 40_000)
        f.leave(false, null)
        val p = sink.ofType(FermataType.PLAY)
        assertEquals(3, p.size)
        assertEquals(listOf(100_000L, 100_000L, 40_000L), p.map { it.listenedMs })
        assertNull(p[0].prevTrackId)
        assertEquals("aaaaaaaaaaa", p[1].prevTrackId)
        assertEquals("aaaaaaaaaaa", p[2].prevTrackId)
        assertEquals(1, p.map { it.sessionId }.distinct().size)
        assertTrue(sink.ofType(FermataType.SKIP).isEmpty())
    }

    // ---- flush on stop --------------------------------------------------------------------------

    @Test fun flushingNeverAddsASecondRow() {
        val sink = FakeSink()
        val f = logger(sink)
        f.play("aaaaaaaaaaa")
        f.heard("aaaaaaaaaaa", 30_000)
        f.flush()
        f.flush()
        f.heard("aaaaaaaaaaa", 15_000)
        f.flushAndWait(100)
        assertEquals(1, sink.rows.size)
        assertEquals(45_000L, sink.rows.values.single().listenedMs)
        // the service comes back and the same song carries on, then leaves: still one row
        f.heard("aaaaaaaaaaa", 5_000)
        f.leave(false, null)
        f.flush()
        f.leave(false, null)
        assertEquals(1, sink.rows.size)
        assertEquals(50_000L, sink.rows.values.single().listenedMs)
    }

    @Test fun aKilledAppKeepsTheRowWithWhatWasHeard() {
        val sink = FakeSink()
        val kv = MapKv()
        logger(sink, kv).apply {
            play("aaaaaaaaaaa")
            heard("aaaaaaaaaaa", 25_000)
            flush() // the regular save; then the process dies with no leave()
        }
        val row = sink.rows.values.single()
        assertEquals(FermataType.PLAY, row.type)
        assertEquals(25_000L, row.listenedMs)
    }

    // ---- device id ------------------------------------------------------------------------------

    @Test fun everyRowSaysWhichPhoneWroteIt() {
        val sink = FakeSink()
        val dev = MapKv()
        val f = logger(sink, deviceKv = dev)
        f.play("aaaaaaaaaaa", durMs = 200_000)
        f.queued("bbbbbbbbbbb", "b", "c", 1, null)
        f.leave(true, 1_000)
        val id = dev.getString(FermataDeviceId.KEY)!!
        assertTrue(FermataDeviceId.isValid(id))
        assertEquals(3, sink.rows.size)
        assertTrue(sink.rows.values.all { it.deviceId == id })
        // made once: a second start keeps it
        val sink2 = FakeSink()
        logger(sink2, deviceKv = dev).apply { play("ccccccccccc"); leave(false, null) }
        assertEquals(id, sink2.rows.values.single().deviceId)
    }

    @Test fun deviceIdsAreRandom() {
        val ids = (1..50).map { FermataDeviceId.random() }
        assertEquals(50, ids.distinct().size)
        assertTrue(ids.all { FermataDeviceId.isValid(it) })
        val kv = MapKv().apply { put(mapOf(FermataDeviceId.KEY to "not-an-id")) }
        assertTrue(FermataDeviceId.isValid(FermataDeviceId.getOrCreate(kv)))
    }

    // ---- source ---------------------------------------------------------------------------------

    @Test fun sourcesAreNormalised() {
        for (s in listOf("radio", "search", "library", "playlist", "discover", "tape", "queue", "unknown")) {
            assertEquals(s, FermataSource.normalize(s))
        }
        assertEquals("unknown", FermataSource.normalize(null))
        assertEquals("unknown", FermataSource.normalize(""))
        assertEquals("unknown", FermataSource.normalize("gemini"))
        assertEquals("radio", FermataSource.normalize(" Radio "))
        val sink = FakeSink()
        logger(sink).apply { play("aaaaaaaaaaa", source = null); leave(false, null) }
        assertEquals("unknown", sink.rows.values.single().source)
    }

    @Test fun hourAndIsoWeekday() {
        val sunday2330 = java.time.ZonedDateTime.of(2026, 11, 15, 23, 30, 0, 0, ist).toInstant().toEpochMilli()
        assertEquals(23, FermataTime.hourOf(sunday2330, ist))
        assertEquals(7, FermataTime.dowOf(sunday2330, ist))
        val monday0005 = java.time.ZonedDateTime.of(2026, 11, 16, 0, 5, 0, 0, ist).toInstant().toEpochMilli()
        assertEquals(0, FermataTime.hourOf(monday0005, ist))
        assertEquals(1, FermataTime.dowOf(monday0005, ist))
    }

    // ---- never breaks playback ------------------------------------------------------------------

    @Test fun aFailingDatabaseNeverThrowsAndDropsOnlyThatRow() {
        val sink = FakeSink().apply { failInsert = true; failUpdate = true }
        val errors = ArrayList<Throwable>()
        val f = logger(sink, errors = errors)
        f.play("aaaaaaaaaaa")
        f.heard("aaaaaaaaaaa", 20_000)
        f.flush()
        f.queued("bbbbbbbbbbb", "b", "c", 1, null)
        f.leave(true, 1_000)
        f.flushAndWait(50)
        assertTrue(sink.rows.isEmpty())
        assertTrue(errors.isNotEmpty())
        // the database recovers: logging carries on
        sink.failInsert = false; sink.failUpdate = false
        f.play("ccccccccccc")
        f.heard("ccccccccccc", 5_000)
        f.leave(false, null)
        assertEquals(5_000L, sink.ofType(FermataType.PLAY).single().listenedMs)
    }

    @Test fun aRefusingWriterNeverThrows() {
        val errors = ArrayList<Throwable>()
        val f = logger(FakeSink(), executor = Executor { throw java.util.concurrent.RejectedExecutionException("shut down") }, errors = errors)
        f.play("aaaaaaaaaaa")
        f.heard("aaaaaaaaaaa", 1_000)
        f.leave(true, 0)
        f.flushAndWait(10)
        assertTrue(errors.isNotEmpty())
    }

    @Test fun aBrokenClockNeverThrows() {
        val errors = ArrayList<Throwable>()
        val f = FermataLogger(FakeSink(), MapKv(), direct, clock = { throw IllegalStateException("clock") }, onError = { errors.add(it) })
        f.startPlay("aaaaaaaaaaa", "a", "b", 1, false, null)
        f.queued("aaaaaaaaaaa", "a", "b", 1, null)
        f.heard("aaaaaaaaaaa", 10)
        f.leave(true, 0)
        assertTrue(errors.isNotEmpty())
    }

    @Test fun writesHappenOnTheWriterNotTheCaller() {
        val sink = FakeSink()
        val pending = ArrayList<Runnable>()
        val f = logger(sink, executor = Executor { pending.add(it) })
        f.play("aaaaaaaaaaa", durMs = 200_000)
        f.heard("aaaaaaaaaaa", 5_000)
        f.leave(true, 5_000)
        assertTrue("nothing written on the calling thread", sink.rows.isEmpty())
        pending.forEach { it.run() }
        assertEquals(1, sink.ofType(FermataType.PLAY).size)
        assertEquals(5_000L, sink.ofType(FermataType.PLAY).single().listenedMs)
        assertEquals(1, sink.ofType(FermataType.SKIP).size)
    }
}
