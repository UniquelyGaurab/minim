package com.minim.music.data.drift

import com.minim.music.data.model.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** 7.1.0: Smart Shuffle only reorders: never a song more, never a song less. */
class SmartShuffleTest {

    private val now = 1_800_000_000_000L
    private val day = 24L * 60 * 60 * 1000

    private fun t(id: String, artist: String) = Track(id, "Song $id", artist, 200, "", "https://music.youtube.com/watch?v=$id")

    private fun playlist(): List<Track> = (1..30).map { t("s$it", "Artist ${it % 6}") }

    @Test fun `it never adds or drops a song`() {
        val list = playlist()
        repeat(50) { seed ->
            val out = SmartShuffle.order(list, emptyMap(), emptyMap(), now, Random(seed))
            assertEquals(list.size, out.size)
            assertEquals(list.map { it.id }.sorted(), out.map { it.id }.sorted())
        }
    }

    @Test fun `duplicates stay duplicates`() {
        val a = t("a", "X")
        val list = listOf(a, t("b", "Y"), a)
        val out = SmartShuffle.order(list, emptyMap(), now = now, random = Random(1))
        assertEquals(2, out.count { it.id == "a" })
        assertEquals(3, out.size)
    }

    @Test fun `tiny lists come back as they are`() {
        assertEquals(emptyList<Track>(), SmartShuffle.order(emptyList(), emptyMap()))
        val one = listOf(t("a", "X"))
        assertEquals(one, SmartShuffle.order(one, emptyMap()))
    }

    @Test fun `no two songs by one artist in a row while others are left`() {
        val list = playlist()
        repeat(30) { seed ->
            val out = SmartShuffle.order(list, emptyMap(), emptyMap(), now, Random(seed))
            for (i in 1 until out.size) {
                assertNotEquals("seed $seed: ${out.map { it.artist }}", out[i - 1].artist, out[i].artist)
            }
        }
    }

    @Test fun `a lopsided playlist still spreads the big artist out`() {
        // 6 by one artist, 5 by others: it fits only as A x A x A ...
        val list = (1..6).map { t("a$it", "Big") } + (1..5).map { t("o$it", "Other $it") }
        val out = SmartShuffle.order(list, emptyMap(), now = now, random = Random(3))
        for (i in 1 until out.size) assertTrue(out.map { it.artist }.toString(), !(out[i].artist == "Big" && out[i - 1].artist == "Big"))
    }

    @Test fun `songs not heard in a while come sooner`() {
        val list = (1..40).map { t("s$it", "Artist $it") }
        val stale = (1..20).map { "s$it" }.toSet()
        val last = list.associate { it.id to if (it.id in stale) now - 90 * day else now - 60_000L }
        var staleFirstHalf = 0
        repeat(20) { seed ->
            staleFirstHalf += SmartShuffle.order(list, last, now = now, random = Random(seed)).take(20).count { it.id in stale }
        }
        // Plain shuffle would average 10 of 20; stale songs clearly lead
        assertTrue("stale in first half: ${staleFirstHalf / 20.0}", staleFirstHalf / 20.0 > 14)
    }

    @Test fun `never-heard songs count as stale`() {
        assertEquals(1.0, SmartShuffle.staleness(null, now), 1e-9)
        assertEquals(1.0, SmartShuffle.staleness(0L, now), 1e-9)
        assertEquals(0.0, SmartShuffle.staleness(now, now), 1e-9)
        assertEquals(0.5, SmartShuffle.staleness(now - 15 * day, now), 1e-9)
    }

    @Test fun `Fermata's rank is a light nudge`() {
        val list = (1..40).map { t("s$it", "Artist $it") }
        val loved = (1..20).map { "s$it" }.toSet()
        val rank = list.associate { it.id to if (it.id in loved) 1.0 else 0.0 }
        var lovedFirstHalf = 0
        repeat(40) { seed ->
            lovedFirstHalf += SmartShuffle.order(list, emptyMap(), rank, now, Random(seed)).take(20).count { it.id in loved }
        }
        val avg = lovedFirstHalf / 40.0
        // Leans towards favourites, but stays a shuffle
        assertTrue("loved in first half: $avg", avg > 10.5 && avg < 17)
    }

    @Test fun `it is still random`() {
        val list = playlist()
        val a = SmartShuffle.order(list, emptyMap(), now = now, random = Random(1))
        val b = SmartShuffle.order(list, emptyMap(), now = now, random = Random(2))
        assertNotEquals(a, b)
    }
}
