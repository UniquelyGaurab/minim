package com.minim.music.data.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Minim 5.0.0 part 2: the pure merge rules of cloud sync (no Android, no Firestore). */
class SyncMergeTest {

    private fun pl(
        name: String = "Gym",
        created: Long = 100L,
        changed: Long = 100L,
        born: Long = 0L,
        deleted: Boolean = false,
        vararg tracks: Pair<String, PlEntry>
    ) = PlState(name, created, changed, born, deleted, mapOf(*tracks))

    private fun add(pos: Long, at: Long = pos) = PlEntry(pos, at, removed = false)
    private fun rem(at: Long) = PlEntry(0L, at, removed = true)

    // ---- likes ---------------------------------------------------------------------------------

    @Test
    fun likeNewestChangeWinsEitherWay() {
        val liked = LikeState(true, 1_000L)
        val unlikedLater = LikeState(false, 2_000L)
        assertEquals(unlikedLater, SyncMerge.like(liked, unlikedLater))
        assertEquals(unlikedLater, SyncMerge.like(unlikedLater, liked))

        val likedAgain = LikeState(true, 3_000L)
        assertEquals(likedAgain, SyncMerge.like(unlikedLater, likedAgain))
        assertEquals(likedAgain, SyncMerge.like(likedAgain, unlikedLater))
    }

    @Test
    fun likeTieGoesToNotLiked() {
        val a = LikeState(true, 5_000L)
        val b = LikeState(false, 5_000L)
        assertFalse(SyncMerge.like(a, b)!!.liked)
        assertFalse(SyncMerge.like(b, a)!!.liked)
    }

    @Test
    fun likeMissingSideAndIdempotent() {
        val a = LikeState(true, 1L)
        val b = LikeState(false, 2L)
        assertEquals(a, SyncMerge.like(a, null))
        assertEquals(a, SyncMerge.like(null, a))
        assertNull(SyncMerge.like(null, null))
        val once = SyncMerge.like(a, b)
        assertEquals(once, SyncMerge.like(once, b))
        assertEquals(once, SyncMerge.like(once, a))
        assertEquals(once, SyncMerge.like(once, once))
    }

    // ---- playlists -----------------------------------------------------------------------------

    @Test
    fun playlistSongsMergeWithoutDuplicatesInAddedOrder() {
        val phoneA = pl(changed = 100L, created = 100L, tracks = arrayOf("s1" to add(10L), "s3" to add(30L)))
        val phoneB = pl(changed = 200L, created = 200L, tracks = arrayOf("s2" to add(20L), "s3" to add(30L)))
        val m = SyncMerge.playlist(phoneA, phoneB)!!
        assertEquals(listOf("s1", "s2", "s3"), m.visibleSongs())
        assertEquals(3, m.tracks.size)
        // created keeps the earliest, changed the latest
        assertEquals(100L, m.created)
        assertEquals(200L, m.changed)
        assertFalse(m.deleted)
    }

    @Test
    fun playlistMergeIsOrderIndependent() {
        val a = pl(changed = 100L, tracks = arrayOf("s1" to add(10L), "s2" to rem(50L)))
        val b = pl(changed = 150L, tracks = arrayOf("s2" to add(20L, at = 20L), "s4" to add(40L)))
        assertTrue(SyncMerge.playlist(a, b)!!.sameAs(SyncMerge.playlist(b, a)))
    }

    @Test
    fun playlistMergingTwiceChangesNothing() {
        val a = pl(changed = 100L, tracks = arrayOf("s1" to add(10L), "s2" to rem(50L)))
        val b = pl(changed = 150L, born = 5L, tracks = arrayOf("s2" to add(20L), "s4" to add(40L)))
        val once = SyncMerge.playlist(a, b)!!
        assertTrue(once.sameAs(SyncMerge.playlist(once, b)))
        assertTrue(once.sameAs(SyncMerge.playlist(once, a)))
        assertTrue(once.sameAs(SyncMerge.playlist(once, once)))
    }

    @Test
    fun songRemovalNewerThanAddWins() {
        val removedHere = pl(tracks = arrayOf("s1" to rem(500L)))
        val addedThere = pl(tracks = arrayOf("s1" to add(100L)))
        val m = SyncMerge.playlist(removedHere, addedThere)!!
        assertTrue(m.tracks["s1"]!!.removed)
        assertTrue(m.visibleSongs().isEmpty())
    }

    @Test
    fun songAddedAgainAfterRemovalComesBack() {
        val removed = pl(tracks = arrayOf("s1" to rem(500L)))
        val addedAgain = pl(tracks = arrayOf("s1" to add(900L)))
        val m = SyncMerge.playlist(removed, addedAgain)!!
        assertEquals(listOf("s1"), m.visibleSongs())
    }

    @Test
    fun songEntryTieGoesToRemovedAndKeepsDetails() {
        val meta = SongMeta("Title", "Artist", "thumb", 200L)
        val added = PlEntry(10L, 500L, removed = false, meta = meta)
        val removed = PlEntry(0L, 500L, removed = true)
        val e = SyncMerge.entry(added, removed)!!
        assertTrue(e.removed)
        assertEquals(meta, e.meta)
        assertEquals(e, SyncMerge.entry(removed, added))
    }

    @Test
    fun deleteBeatsOlderChanges() {
        val alive = pl(changed = 100L, tracks = arrayOf("s1" to add(10L), "s2" to add(20L)))
        val deleted = pl(changed = 300L, deleted = true)
        val m = SyncMerge.playlist(alive, deleted)!!
        assertTrue(m.deleted)
        assertTrue(m.tracks.isEmpty())
        assertTrue(m.sameAs(SyncMerge.playlist(deleted, alive)))
    }

    @Test
    fun playlistMadeAgainAfterDeleteStartsEmpty() {
        // the cloud still holds the deleted playlist's old songs (merge writes never remove fields)
        val oldCloud = pl(changed = 100L, tracks = arrayOf("old1" to add(10L), "old2" to add(20L)))
        val deleted = pl(changed = 300L, deleted = true)
        val remade = pl(changed = 500L, created = 500L, tracks = arrayOf("new1" to add(600L)))

        val step1 = SyncMerge.playlist(oldCloud, deleted)!!
        val step2 = SyncMerge.playlist(step1, remade)!!
        assertFalse(step2.deleted)
        assertEquals(listOf("new1"), step2.visibleSongs())
        assertTrue(step2.born >= 300L)

        // a phone that never saw the delete merges its stale copy: the old songs stay gone
        val stale = SyncMerge.playlist(step2, oldCloud)!!
        assertEquals(listOf("new1"), stale.visibleSongs())
        assertTrue(stale.sameAs(SyncMerge.playlist(stale, oldCloud)))
    }

    @Test
    fun deleteNewerThanRemakeWins() {
        val remade = pl(changed = 200L, tracks = arrayOf("s1" to add(250L)))
        val deletedLater = pl(changed = 400L, deleted = true)
        assertTrue(SyncMerge.playlist(remade, deletedLater)!!.deleted)
    }

    @Test
    fun normalizeDropsSongsFromBeforeBorn() {
        val s = pl(born = 300L, tracks = arrayOf("a" to add(100L), "b" to add(400L)))
        assertEquals(listOf("b"), SyncMerge.normalize(s).visibleSongs())
        val dead = pl(deleted = true, tracks = arrayOf("a" to add(100L)))
        assertTrue(SyncMerge.normalize(dead).tracks.isEmpty())
    }

    // ---- plays and listening -------------------------------------------------------------------

    @Test
    fun samePlayGetsSameIdAndMergesToTheLongerTime() {
        val id1 = SyncIds.eventId(1_700_000_000_000L, "abcDEF12345")
        val id2 = SyncIds.eventId(1_700_000_000_000L, "abcDEF12345")
        assertEquals(id1, id2)
        assertNotEquals(id1, SyncIds.eventId(1_700_000_000_001L, "abcDEF12345"))
        assertTrue(SyncIds.isSyncableSongId(id1))

        val short = PlayEv("abcDEF12345", 1L, 31_000L, true)
        val long = short.copy(listenedMs = 95_000L)
        assertEquals(long, SyncMerge.play(short, long))
        assertEquals(long, SyncMerge.play(long, short))
        assertEquals(long, SyncMerge.play(SyncMerge.play(long, short), short))
    }

    @Test
    fun listeningIsKeptPerPhoneAndMergeTakesTheMax() {
        val a = mapOf("dA" to mapOf("9_s1" to 5_000L, "10_s1" to 1_000L), "dB" to mapOf("9_s1" to 2_000L))
        val b = mapOf("dA" to mapOf("9_s1" to 4_000L), "dB" to mapOf("9_s1" to 7_000L), "dC" to mapOf("9_s2" to 3_000L))
        val m = SyncMerge.listens(a, b)
        assertEquals(5_000L, m["dA"]!!["9_s1"])
        assertEquals(1_000L, m["dA"]!!["10_s1"])
        assertEquals(7_000L, m["dB"]!!["9_s1"])
        assertEquals(3_000L, m["dC"]!!["9_s2"])
        // phones are never added together by the merge, and merging again changes nothing
        assertEquals(m, SyncMerge.listens(m, b))
        assertEquals(m, SyncMerge.listens(m, a))
        assertEquals(m, SyncMerge.listens(b, a))
    }

    // ---- ids and encodings ---------------------------------------------------------------------

    @Test
    fun playlistIdComesFromTheTrimmedName() {
        assertEquals(SyncIds.playlistUid("Gym"), SyncIds.playlistUid("  Gym "))
        assertNotEquals(SyncIds.playlistUid("Gym"), SyncIds.playlistUid("gym"))
        assertTrue(SyncIds.isSyncableSongId(SyncIds.playlistUid("Late night \uD83C\uDF19")))
    }

    @Test
    fun songIdCheck() {
        assertTrue(SyncIds.isSyncableSongId("dQw4w9WgXcQ"))
        assertFalse(SyncIds.isSyncableSongId(""))
        assertFalse(SyncIds.isSyncableSongId("a/b"))
        assertFalse(SyncIds.isSyncableSongId("__x__"))
        assertFalse(SyncIds.isSyncableSongId("a".repeat(65)))
    }

    @Test
    fun deviceKeyIsStableAndIgnoresTheKnownBadAndroidId() {
        assertEquals(SyncIds.deviceKey("abc", "x"), SyncIds.deviceKey("abc", "y"))
        assertEquals(SyncIds.deviceKey("9774d56d682e549c", "seed"), SyncIds.deviceKey(null, "seed"))
        assertTrue(SyncIds.deviceKey("abc", "x").startsWith("d"))
    }

    @Test
    fun encodingsRoundTrip() {
        val meta = SongMeta("Ti\u001Ftle", "Artist", "https://img/x=s120", 215L)
        assertEquals(SongMeta("Title", "Artist", "https://img/x=s120", 215L), SyncCodec.decodeMeta(SyncCodec.encodeMeta(meta)))

        val entry = PlEntry(10L, 20L, removed = false, meta = SongMeta("T", "A", "u", 3L))
        assertEquals(entry, SyncCodec.decodeEntry(SyncCodec.encodeEntry(entry)))
        val tomb = PlEntry(0L, 99L, removed = true)
        assertEquals(tomb, SyncCodec.decodeEntry(SyncCodec.encodeEntry(tomb)))

        val play = PlayEv("song_1", 1_700_000_000_000L, 42_000L, tracked = true)
        assertEquals(play, SyncCodec.decodePlay(SyncCodec.encodePlay(play)))

        assertEquals(9 to "a_b-c", SyncCodec.parseListenKey(SyncCodec.listenKey(9, "a_b-c")))
        assertNull(SyncCodec.parseListenKey("24_x"))
        assertNull(SyncCodec.decodePlay(null))
        assertNull(SyncCodec.decodeEntry("bad"))
    }

    @Test
    fun documentsRoundTrip() {
        val like = LikeState(false, 1234L)
        assertEquals(like, SyncCodec.decodeLike(SyncCodec.likeDoc(like, null)))
        // Firestore hands numbers back as Long or Double
        assertEquals(like, SyncCodec.decodeLike(mapOf("l" to false, "at" to 1234.0)))
        assertNull(SyncCodec.decodeLike(mapOf("l" to true)))

        val p = pl(changed = 300L, born = 200L, tracks = arrayOf("s1" to add(250L)))
        val data = HashMap<String, Any?>(SyncCodec.playlistHeader(p))
        data["t"] = mapOf("s1" to SyncCodec.encodeEntry(p.tracks.getValue("s1")))
        val back = SyncCodec.decodePlaylist(data)
        assertNotNull(back)
        assertTrue(p.sameAs(back))
    }

    @Test
    fun statusLines() {
        val now = 10L * 24 * 3_600_000L
        assertEquals("Sync on", SyncText.syncedLine(now, 0L))
        assertEquals("Synced just now", SyncText.syncedLine(now, now - 30_000L))
        assertEquals("Synced 5 min ago", SyncText.syncedLine(now, now - 5 * 60_000L))
        assertEquals("Synced 3 h ago", SyncText.syncedLine(now, now - 3 * 3_600_000L))
        assertEquals(SyncProblem.OFFLINE, SyncText.classify("UNAVAILABLE"))
        assertEquals(SyncProblem.PERMISSION, SyncText.classify("PERMISSION_DENIED"))
        assertEquals(SyncProblem.QUOTA, SyncText.classify("RESOURCE_EXHAUSTED"))
        assertEquals(SyncProblem.OFFLINE, SyncText.classify(null, timedOut = true))
        assertEquals(SyncProblem.FAILED, SyncText.classify("INTERNAL"))
    }
}
