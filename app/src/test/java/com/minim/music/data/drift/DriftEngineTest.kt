package com.minim.music.data.drift

import com.minim.music.data.fermata.FermataSource
import com.minim.music.data.model.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 7.1.0: Drift's next-pick rules, without a phone. */
class DriftEngineTest {

    private val now = 1_800_000_000_000L

    private fun t(id: String, artist: String = "Artist $id", sec: Long = 200) =
        Track(id, "Song $id", artist, sec, "", "https://music.youtube.com/watch?v=$id")

    private fun c(id: String, artist: String = "Artist $id", taste: Double = 0.5, familiar: Boolean = true, origin: String? = null, last: Long = 0L) =
        DriftCandidate(t(id, artist), familiar, taste, last, origin)

    private fun state(
        recent: List<Track> = emptyList(),
        explore: Float = 0.5f,
        hidden: Set<String> = emptySet(),
        offline: Boolean = false,
        saved: Set<String> = emptySet(),
        fam: Int = 0,
        new: Int = 0,
        hours: HourAffinity = HourAffinity.EMPTY,
        hour: Int = 12,
    ) = DriftEngine.State(explore, now, hour, recent, hidden, offline, saved, fam, new, hours, salt = 7L)

    // ---- Live signals -------------------------------------------------------------------------

    @Test fun `an early skip steers away from that artist`() {
        val a1 = c("a1", "Arijit", 0.9); val a2 = c("a2", "Arijit", 0.9); val b = c("b", "Prateek", 0.7)
        val pool = listOf(a1, a2, b)
        val sig = DriftSignals()
        assertEquals("a2", DriftEngine.pick(listOf(a2, b), state(), sig).first().track.id)
        sig.earlySkip(a1, a1.track)
        assertEquals("b", DriftEngine.pick(listOf(a2, b), state(), sig).first().track.id)
        // and the skipped song itself is out for the session
        assertFalse(DriftEngine.pick(pool, state(), sig, 3).any { it.track.id == "a1" })
    }

    @Test fun `an early skip steers away from songs that sound like it`() {
        val seed = c("s", "X", 0.5)
        val near = c("n", "Y", 0.8, familiar = false, origin = "s")
        val far = c("f", "Z", 0.6, familiar = false, origin = "other")
        val sig = DriftSignals()
        assertEquals("n", DriftEngine.pick(listOf(near, far), state(explore = 1f), sig).first().track.id)
        sig.earlySkip(seed, seed.track)
        assertEquals("f", DriftEngine.pick(listOf(near, far), state(explore = 1f), sig).first().track.id)
    }

    @Test fun `a full play leans in, a heart leans in more`() {
        val played = c("p", "Ritviz", 0.5)
        val same = c("q", "Ritviz", 0.5)
        val other = c("o", "Seedhe Maut", 0.7)
        val sig = DriftSignals()
        assertEquals("o", DriftEngine.pick(listOf(same, other), state(), sig).first().track.id)
        sig.fullPlay(played, played.track)
        val afterPlay = DriftEngine.score(same, state(), sig)
        sig.like(played.track)
        val afterLike = DriftEngine.score(same, state(), sig)
        assertTrue(afterLike > afterPlay)
        assertEquals("q", DriftEngine.pick(listOf(same, other), state(), sig).first().track.id)
    }

    @Test fun `an early skip outweighs a full play`() {
        val x = c("x", "A")
        val sig = DriftSignals()
        sig.fullPlay(x, x.track)
        sig.earlySkip(x, x.track)
        assertTrue((sig.artist["a"] ?: 0.0) < 0)
    }

    @Test fun `a replay leans in`() {
        val x = c("x", "A")
        val sig = DriftSignals()
        sig.replay(x.track)
        assertTrue((sig.artist["a"] ?: 0.0) > DriftSignals.FULL_PLAY_ARTIST)
    }

    @Test fun `the hour of day matters`() {
        // Night artist played at 1-3 AM, day artist at 4-6 PM
        val plays = (1..20).map { "Night" to (1 + it % 3) } + (1..20).map { "Day" to (16 + it % 3) }
        val hours = HourAffinity.build(plays)
        val night = c("n", "Night", 0.5); val day = c("d", "Day", 0.5)
        val sig = DriftSignals()
        assertEquals("n", DriftEngine.pick(listOf(night, day), state(hours = hours, hour = 2), sig).first().track.id)
        assertEquals("d", DriftEngine.pick(listOf(night, day), state(hours = hours, hour = 17), sig).first().track.id)
    }

    // ---- Cooldown and spacing -----------------------------------------------------------------

    @Test fun `a recently played song stays out`() {
        val pool = (1..60).map { c("s$it", taste = 1.0 - it / 100.0) }
        val recent = pool.take(50).map { it.track }
        val picks = DriftEngine.pick(pool, state(recent = recent), DriftSignals(), 5)
        assertTrue(picks.none { it.track in recent })
        // Past the last 50 songs it may come back
        val longAgo = pool.take(1).map { it.track } + pool.drop(10).take(50).map { it.track }
        assertTrue(DriftEngine.pick(pool, state(recent = longAgo), DriftSignals(), 9).any { it.track.id == "s2" })
    }

    @Test fun `a song heard in the last few hours stays out`() {
        val fresh = c("f", taste = 1.0, last = now - 60 * 60 * 1000)
        val old = c("o", taste = 0.1, last = now - 5 * 60 * 60 * 1000)
        assertEquals(listOf("o"), DriftEngine.pick(listOf(fresh, old), state(), DriftSignals(), 2).map { it.track.id })
    }

    @Test fun `earlier sessions still cool down`() {
        val a = c("a", taste = 1.0); val b = c("b", taste = 0.1)
        val st = state().copy(earlier = setOf("a"))
        assertEquals("b", DriftEngine.pick(listOf(a, b), st, DriftSignals()).first().track.id)
    }

    @Test fun `the same artist is spaced out`() {
        // One artist owns all the top songs; others are weaker
        val top = (1..10).map { c("t$it", "Star", 0.95) }
        val rest = (1..10).map { c("r$it", "Other $it", 0.4) }
        val picks = DriftEngine.pick(top + rest, state(), DriftSignals(), 12)
        val artists = picks.map { DriftEngine.artistKey(it.track.artist) }
        for (i in artists.indices) {
            val window = artists.subList(maxOf(0, i - DriftEngine.ARTIST_GAP), i)
            if (artists[i] == "star") assertFalse("Star again within ${DriftEngine.ARTIST_GAP}: $artists", "star" in window)
        }
        assertTrue(artists.count { it == "star" } < 10)
    }

    @Test fun `spacing counts what played just before`() {
        val star = c("s1", "Star", 1.0); val other = c("o", "Other", 0.2)
        val recent = listOf(t("s0", "Star"))
        assertEquals("o", DriftEngine.pick(listOf(star, other), state(recent = recent), DriftSignals()).first().track.id)
    }

    @Test fun `with only one artist left it still plays`() {
        val only = listOf(c("a", "Solo"), c("b", "Solo"))
        assertEquals(2, DriftEngine.pick(only, state(), DriftSignals(), 2).size)
    }

    @Test fun `featured artists count as the main artist`() {
        assertEquals("arijit singh", DriftEngine.artistKey("Arijit Singh, Shreya Ghoshal"))
        assertEquals("divine", DriftEngine.artistKey("DIVINE feat. Nas"))
        assertEquals("karan aujla", DriftEngine.artistKey("Karan Aujla & Ikky"))
    }

    // ---- The dial -----------------------------------------------------------------------------

    private fun mixed(): List<DriftCandidate> =
        (1..20).map { c("f$it", "F$it", 0.9, familiar = true) } + (1..20).map { c("n$it", "N$it", 0.3, familiar = false) }

    @Test fun `the dial sets the familiar to new ratio`() {
        fun newShare(explore: Float) = DriftEngine.pick(mixed(), state(explore = explore), DriftSignals(), 20).count { !it.familiar }
        assertEquals(0, newShare(0f))
        assertEquals(20, newShare(1f))
        assertEquals(10, newShare(0.5f))
        val low = newShare(0.25f); val high = newShare(0.75f)
        assertEquals(5, low)
        assertEquals(15, high)
    }

    @Test fun `the dial balances against what already played`() {
        // Half way, and the session so far was all familiar: next comes a new find
        assertTrue(DriftEngine.wantsNew(0.5f, familiarPicked = 3, newPicked = 0))
        assertFalse(DriftEngine.wantsNew(0.5f, familiarPicked = 0, newPicked = 3))
    }

    @Test fun `with no new finds yet the dial falls back to favourites`() {
        val onlyFamiliar = (1..5).map { c("f$it") }
        assertEquals(3, DriftEngine.pick(onlyFamiliar, state(explore = 1f), DriftSignals(), 3).size)
    }

    // ---- Offline, hidden, songs only ----------------------------------------------------------

    @Test fun `offline picks only saved songs`() {
        val pool = (1..10).map { c("s$it", taste = it / 10.0) }
        val saved = setOf("s2", "s5")
        val picks = DriftEngine.pick(pool, state(offline = true, saved = saved), DriftSignals(), 5)
        assertEquals(setOf("s2", "s5"), picks.map { it.track.id }.toSet())
        assertTrue(DriftEngine.pick(pool, state(offline = true, saved = emptySet()), DriftSignals(), 3).isEmpty())
    }

    @Test fun `hidden songs never play`() {
        val pool = listOf(c("h", taste = 1.0), c("ok", taste = 0.1))
        val picks = DriftEngine.pick(pool, state(hidden = setOf("h")), DriftSignals(), 2)
        assertEquals(listOf("ok"), picks.map { it.track.id })
    }

    @Test fun `tapes and hour-long uploads are not songs`() {
        val long = DriftCandidate(t("long", sec = 3600), true, 1.0)
        val tape = DriftCandidate(Track("tape", "T", "Ch", 300, "", "https://youtube.com/watch?v=tape&minim=story"), true, 1.0)
        val song = c("song", taste = 0.1)
        assertEquals(listOf("song"), DriftEngine.pick(listOf(long, tape, song), state(), DriftSignals(), 3).map { it.track.id })
    }

    @Test fun `picking again with nothing changed picks the same`() {
        val pool = mixed()
        val a = DriftEngine.pick(pool, state(), DriftSignals(), 2).map { it.track.id }
        val b = DriftEngine.pick(pool, state(), DriftSignals(), 2).map { it.track.id }
        assertEquals(a, b)
        val c = DriftEngine.pick(pool, state().copy(salt = 99L), DriftSignals(), 20).map { it.track.id }
        assertNotEquals(DriftEngine.pick(pool, state(), DriftSignals(), 20).map { it.track.id }, c)
    }

    @Test fun `ranking a big pool is fast`() {
        val pool = (1..1500).map { c("s$it", "A${it % 200}", (it % 97) / 97.0, familiar = it % 3 != 0) }
        val recent = pool.take(50).map { it.track }
        repeat(20) { DriftEngine.pick(pool, state(recent = recent), DriftSignals(), 2) } // warm up
        val t0 = System.nanoTime()
        repeat(20) { DriftEngine.pick(pool, state(recent = recent), DriftSignals(), 2) }
        val ms = (System.nanoTime() - t0) / 1_000_000.0 / 20
        assertTrue("pick took $ms ms", ms < 20.0)
    }

    // ---- Taste and Fermata --------------------------------------------------------------------

    @Test fun `Fermata's order becomes a 0 to 1 score`() {
        assertEquals(mapOf("a" to 1.0, "b" to 0.5, "c" to 0.0), DriftEngine.rankScores(listOf("a", "b", "c")))
        assertEquals(0.5, DriftEngine.blend(0.5, null), 1e-9)
        assertEquals(0.8, DriftEngine.blend(0.5, 1.0), 1e-9)
    }

    @Test fun `local taste rewards plays and hearts`() {
        assertTrue(DriftEngine.localTaste(10, true, now, now) > DriftEngine.localTaste(10, false, now, now))
        assertTrue(DriftEngine.localTaste(30, false, 0, now) > DriftEngine.localTaste(1, false, 0, now))
        assertTrue(DriftEngine.localTaste(10_000, true, now, now) <= 1.0)
    }

    // ---- The source tag -----------------------------------------------------------------------

    @Test fun `Drift plays carry their own source`() {
        assertEquals("drift", FermataSource.DRIFT)
        assertEquals("drift", FermataSource.normalize("drift"))
        assertEquals("drift", FermataSource.normalize(" Drift "))
        // Fermata's log has no Drift yet: it files them as radio plays
        assertEquals("radio", FermataSource.forFermata("drift"))
        assertEquals("playlist", FermataSource.forFermata("playlist"))
    }
}
