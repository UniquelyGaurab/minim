package com.minim.music.data.fermata

import com.minim.fermata.Fermata
import com.minim.fermata.log.EventBody
import com.minim.fermata.log.InMemoryEventStore
import com.minim.fermata.log.Subject
import com.minim.fermata.log.TrackRef
import com.minim.music.data.discover.FermataShelves
import com.minim.music.data.model.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/**
 * 6.1: the radio and Discover asking Fermata. Fermata's own tests cover the ranking; these cover
 * Minim's side: the fallback before the model is built and in cold start, the order coming back
 * as Minim tracks, and Discover's shelves.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class RecommendationsTest {
    private class ManualClock(var now: Long) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId?): Clock = this
        override fun instant(): Instant = Instant.ofEpochMilli(now)
    }

    private val start = 1_780_000_000_000L
    private val clock = ManualClock(start)
    private val inline = Executor { it.run() }

    private fun track(id: String, artist: String = "Artist $id", sec: Long = 200) =
        Track(id, "Song $id", artist, sec, "https://img/$id", "https://www.youtube.com/watch?v=$id")

    private fun ref(t: Track) = TrackRef(t.id, t.title, t.artist, t.durationSec * 1000)

    private fun fermata() = Fermata(InMemoryEventStore(), "phone", clock, ZoneOffset.UTC)

    private fun Fermata.play(t: Track, ms: Long = 180_000) {
        // With its own time: Fermata's clock never goes back, and these tests jump around in time.
        recordAt(EventBody.Play(ref(t), ms), clock.now)
        clock.now += 4 * 60_000L
    }

    /** Past cold start: 36 counted plays over 12 tracks, a day ago. */
    private fun Fermata.history() {
        clock.now = start - 26 * HOUR
        repeat(36) { play(track("h${it % 12}", "History")) }
        clock.now = start
    }

    private val youtube = (1..8).map { track("yt$it") }

    @Test
    fun beforeFermataStartsTheRadioKeepsYouTubesOrder() {
        val r = Recommendations({ null }, inline)
        assertEquals(youtube, r.reorderRadio(youtube))
        assertNull(r.ready())
    }

    @Test
    fun coldStartKeepsYouTubesOrder() {
        val f = fermata()
        repeat(5) { f.play(track("few$it")) }
        f.record(EventBody.Like(Subject.Track(ref(youtube.last()))))
        val r = Recommendations({ f.recs }, inline)
        assertEquals(youtube, r.reorderRadio(youtube))
        assertFalse(f.recs.needsWarmUp())
        assertEquals(youtube, r.reorderShelf(youtube, r.ready()))
    }

    @Test
    fun notBuiltYetGivesTheOrderBackAndStartsTheBuild() {
        val f = fermata()
        f.history()
        f.record(EventBody.Like(Subject.Track(ref(youtube.last()))))
        val queued = ArrayList<Runnable>()
        val r = Recommendations({ f.recs }, Executor { queued += it })
        assertEquals(youtube, r.reorderRadio(youtube))
        assertEquals(1, queued.size)
        r.reorderRadio(youtube)
        assertEquals("one build at a time", 1, queued.size)
        queued.single().run()
        assertTrue(r.reorderRadio(youtube).indexOfFirst { it.id == "yt8" } <= 1)
    }

    @Test
    fun radioMovesLikedSongsUpAndLeavesOutSkippedAndRecentOnes() {
        val f = fermata()
        f.history()
        val liked = youtube[7]
        val skipped = youtube[0]
        val recent = youtube[1]
        f.record(EventBody.Like(Subject.Track(ref(liked))))
        f.record(EventBody.Skip(ref(skipped), 4_000))
        f.record(EventBody.Skip(ref(skipped), 6_000))
        f.play(recent)
        clock.now += HOUR
        val r = Recommendations({ f.recs }, inline)
        val pool = youtube + (9..20).map { track("yt$it") }
        val out = r.reorderRadio(pool)
        assertTrue(out.indexOf(liked) <= 1)
        assertFalse(skipped in out)
        assertFalse(recent in out)
        assertEquals(pool.size - 2, out.size)
        assertTrue("Minim's own track objects come back", out.all { t -> pool.any { it === t } })
    }

    @Test
    fun radioNeverComesBackEmpty() {
        val f = fermata()
        f.history()
        val two = youtube.take(2)
        two.forEach { t -> repeat(2) { f.record(EventBody.Skip(ref(t), 1_000)) } }
        val r = Recommendations({ f.recs }, inline)
        assertEquals(two, r.reorderRadio(two))
    }

    @Test
    fun awaitReadyWaitsForTheBuild() {
        val f = fermata()
        f.history()
        val r = Recommendations({ f.recs }, Executors.newSingleThreadExecutor())
        assertNotNull(r.awaitReady(5_000))
    }

    @Test
    fun discoverShelvesComeFromFermata() {
        val f = fermata()
        f.history()
        // Loved two months ago, not heard since.
        clock.now = start - 60 * DAY
        val old = (1..4).map { track("old$it", "Old Band") }
        old.forEach { t -> repeat(5) { f.play(t) } }
        // On repeat this week.
        clock.now = start - 3 * DAY
        val hot = (1..4).map { track("hot$it", "Hot Band") }
        hot.forEach { t -> repeat(4) { f.play(t) } }
        val unknown = track("unk", "Unknown Artist")
        repeat(20) { f.play(unknown) }
        clock.now = start
        f.recs.warmUp()
        val library = (old + hot).associateBy { it.id }
        val picks = FermataShelves.read(f.recs, library, blocked = setOf("hot4"), maxSongSec = 600, shelfSize = 10, now = start)
        assertEquals(setOf("hot1", "hot2", "hot3"), picks.onRepeat.map { it.id }.filter { it.startsWith("hot") }.toSet())
        assertFalse("not interested songs stay off", picks.onRepeat.any { it.id == "hot4" })
        assertEquals(old.map { it.id }.toSet(), picks.forgotten.map { it.id }.toSet())
        assertEquals("https://img/old1", picks.forgotten.first { it.id == "old1" }.thumbnailUrl)
        assertTrue(picks.becauseArtist in setOf("Hot Band", "History"))
        assertTrue(picks.becauseSeeds.isNotEmpty())
        assertFalse("Unknown Artist is never a 'Because you like'", picks.moreArtists.any { it.first == "Unknown Artist" } || picks.becauseArtist == "Unknown Artist")
    }

    @Test
    fun discoverShelvesAreEmptyInColdStart() {
        val f = fermata()
        repeat(3) { f.play(track("a$it")) }
        f.recs.warmUp()
        val picks = FermataShelves.read(f.recs, emptyMap(), emptySet(), 600, 10, start)
        assertTrue(picks.onRepeat.isEmpty() && picks.forgotten.isEmpty() && picks.becauseArtist == null)
    }

    @Test
    fun aTrackFromFermataAloneGetsAPlayableSourceAndArtwork() {
        val t = with(Recommendations.Companion) { TrackRef("abc", "T", "A", 61_000).toTrack() }
        assertEquals("https://www.youtube.com/watch?v=abc", t.sourceUrl)
        assertEquals(61L, t.durationSec)
        assertTrue(t.thumbnailUrl.contains("abc"))
    }

    private companion object {
        const val HOUR = 3_600_000L
        const val DAY = 24 * HOUR
    }
}
