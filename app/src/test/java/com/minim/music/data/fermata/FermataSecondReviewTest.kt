package com.minim.music.data.fermata

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.minim.fermata.log.EventBody
import com.minim.music.data.local.LibraryDatabase
import com.minim.music.data.local.TestLibraryFiles
import com.minim.music.data.model.Track
import com.minim.music.data.stats.Days
import com.minim.music.data.stats.ListenSlice
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Clock

/**
 * The dev's second review: a like that came from 5.9.1's tables and a later unlike made on another
 * phone on 5.10.0 must agree on both phones after the FIRST two-way merge, in either order.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FermataSecondReviewTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val opened = mutableListOf<LibraryDatabase>()

    @Before fun clean() = wipe()

    @After fun close() {
        opened.forEach { runCatching { it.close() } }
        wipe()
        context.deleteDatabase(PHONE_B)
    }

    private fun wipe() {
        TestLibraryFiles.wipe(context)
        context.deleteDatabase(PHONE_B)
        context.getSharedPreferences(FermataStore.STATE_PREFS, Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences(FermataStore.DEVICE_PREFS, Context.MODE_PRIVATE).edit().clear().commit()
    }

    private fun <T : LibraryDatabase> keep(db: T): T = db.also { opened += it }
    private fun bridge(lib: LibraryDatabase, device: String) =
        FermataBridge(context, lib, "5.10.0-test", deviceId = device, clock = Clock.systemDefaultZone())

    private fun song(id: String) =
        Track(id, "Song $id", "Arijit Singh", 200L, "https://img/$id", "https://www.youtube.com/watch?v=$id")

    private fun liked(lib: LibraryDatabase) = runBlocking { lib.getLikedTracks() }.map { it.id }.toSet()

    private fun pause() = Thread.sleep(5)

    private fun assertScreenMatchesLog(lib: LibraryDatabase, b: FermataBridge, phone: String) {
        val loggedLikes = b.foldedDocs().filterKeys { it.startsWith("likes/") }
            .filterValues { it["l"] == true }.keys.map { it.removePrefix("likes/") }.toSet()
        assertEquals("$phone: liked songs on screen vs. in the log", loggedLikes, liked(lib))
        assertEquals(
            "$phone: liked songs on screen vs. Fermata's library",
            b.fermata.library().likedTracks.map { it.track.id }.toSet(), liked(lib),
        )
    }

    /** Phone A: "Song A" liked on 5.9.1 (its own file), then the upgrade to 5.10.0 later. */
    private fun phoneAFrom591(likedOn591: Boolean = true): Pair<LibraryDatabase, () -> FermataBridge> {
        val old = keep(TestLibraryFiles.legacy(context))
        if (likedOn591) runBlocking { old.toggleLike(song("A")) }
        old.close(); opened -= old
        pause()
        val lib = keep(LibraryDatabase(context))
        return Pair(lib, { bridge(lib, "phoneA").also { it.start() } })
    }

    /** Phone B already on 5.10.0 (a fresh install). */
    private fun phoneBOn510(): Pair<LibraryDatabase, FermataBridge> {
        val lib = keep(LibraryDatabase(context, PHONE_B))
        val b = bridge(lib, "phoneB")
        b.start()
        return lib to b
    }

    /** B likes then unlikes "Song A" on 5.10.0, after A's 5.9.1 like; A upgrades after that. */
    private fun devCase(): Triple<Pair<LibraryDatabase, FermataBridge>, Pair<LibraryDatabase, FermataBridge>, Unit> {
        val (libA, startA) = phoneAFrom591()
        val (libB, b) = phoneBOn510()
        runBlocking { libB.toggleLike(song("A")) }
        pause()
        runBlocking { libB.toggleLike(song("A")) }
        pause()
        val a = startA()
        assertEquals("A's 5.9.1 like came through the upgrade", setOf("A"), liked(libA))
        assertEquals(emptySet<String>(), liked(libB))
        return Triple(libA to a, libB to b, Unit)
    }

    @Test
    fun aLikeFrom591AndALaterUnlikeOnTheOtherPhoneAgreeAfterTheFirstTwoWayMerge() {
        val (pa, pb) = devCase()
        val (libA, a) = pa
        val (libB, b) = pb
        a.fermata.merge(b.fermata.events())
        b.fermata.merge(a.fermata.events())
        assertEquals("A: B's later unlike wins", emptySet<String>(), liked(libA))
        assertEquals("B: its later unlike wins", emptySet<String>(), liked(libB))
        assertEquals("A's Fermata library", emptySet<String>(), a.fermata.library().likedTracks.map { it.track.id }.toSet())
        assertEquals("B's Fermata library", emptySet<String>(), b.fermata.library().likedTracks.map { it.track.id }.toSet())
        assertScreenMatchesLog(libA, a, "A")
        assertScreenMatchesLog(libB, b, "B")
    }

    @Test
    fun theSameCaseWithTheMergeOrderReversed() {
        val (pa, pb) = devCase()
        val (libA, a) = pa
        val (libB, b) = pb
        b.fermata.merge(a.fermata.events())
        a.fermata.merge(b.fermata.events())
        assertEquals(emptySet<String>(), liked(libA))
        assertEquals(emptySet<String>(), liked(libB))
        assertScreenMatchesLog(libA, a, "A")
        assertScreenMatchesLog(libB, b, "B")
    }

    /** Roles swapped: B has the 5.9.1 like and upgrades last, A likes and unlikes on 5.10.0. */
    @Test
    fun theLikeAndUnlikeOnAAndThe591LikeOnB() {
        // B: 5.9.1 data in its own file (no bridge yet, so nothing goes to a log)
        val libB = keep(LibraryDatabase(context, PHONE_B))
        runBlocking { libB.toggleLike(song("A")) }
        pause()
        // A: a fresh 5.10.0 install
        val (libA, startA) = phoneAFrom591(likedOn591 = false)
        val a = startA()
        runBlocking { libA.toggleLike(song("A")) }
        pause()
        runBlocking { libA.toggleLike(song("A")) }
        pause()
        val b = bridge(libB, "phoneB")
        b.start() // B's upgrade, after A's unlike
        assertEquals(setOf("A"), liked(libB))

        a.fermata.merge(b.fermata.events())
        b.fermata.merge(a.fermata.events())
        assertEquals(emptySet<String>(), liked(libA))
        assertEquals(emptySet<String>(), liked(libB))
        assertScreenMatchesLog(libA, a, "A")
        assertScreenMatchesLog(libB, b, "B")
    }

    /** The other way round in time: a 5.9.1 like that is newer than B's unlike wins on both. */
    @Test
    fun aNewer591LikeStillWinsOverAnOlderUnlike() {
        val (libB, b) = phoneBOn510()
        runBlocking { libB.toggleLike(song("A")) }
        pause()
        runBlocking { libB.toggleLike(song("A")) }
        pause()
        val (libA, startA) = phoneAFrom591() // liked on 5.9.1 after B's unlike
        val a = startA()
        a.fermata.merge(b.fermata.events())
        b.fermata.merge(a.fermata.events())
        assertEquals(setOf("A"), liked(libA))
        assertEquals(setOf("A"), liked(libB))
        assertScreenMatchesLog(libA, a, "A")
        assertScreenMatchesLog(libB, b, "B")
    }

    /** The import's like events carry the time of the 5.9.1 change, not the time of the upgrade. */
    @Test
    fun theImportStampsLikesWithTheirOwnChangeTime() {
        val (libA, startA) = phoneAFrom591()
        val likedAt = runBlocking { libA.getLikedTracks() }.single().let { _ ->
            libA.readableDatabase.rawQuery("SELECT like_changed_at FROM tracks WHERE id = 'A'", null).use { c -> c.moveToFirst(); c.getLong(0) }
        }
        Thread.sleep(20)
        val a = startA()
        val like = a.fermata.events().first { it.body is EventBody.Like }
        assertEquals(likedAt, like.timestamp)
        val write = a.fermata.events().first { FermataBridge.writeOf(it)?.first == "likes/A" }
        assertEquals(likedAt, write.timestamp)
    }

    // ---- clear → 5.9.1 → upgrade: the clear's own hour ---------------------------------------

    private fun listen(lib: LibraryDatabase, id: String, ms: Long) = runBlocking {
        val now = System.currentTimeMillis()
        val row = lib.recordCountedPlay(song(id), ms)
        lib.addListenTime(listOf(LibraryDatabase.ListenAdd(ListenSlice(Days.keyOf(now), Days.hourOf(now), ms), song(id))), row, ms)
    }

    private fun minutesThisHour(lib: LibraryDatabase): Map<String, Long> {
        val now = System.currentTimeMillis()
        return lib.readableDatabase.rawQuery(
            "SELECT track_id, SUM(ms) FROM listen_time WHERE day = ? AND hour = ? GROUP BY track_id",
            arrayOf(Days.keyOf(now).toString(), Days.hourOf(now).toString()),
        ).use { c -> buildMap { while (c.moveToNext()) put(c.getString(0), c.getLong(1)) } }
    }

    @Test
    fun theMinutesFromTheHourOfTheClearDontComeBackAfterGoingBackTo591() {
        val old = keep(TestLibraryFiles.legacy(context))
        listen(old, "A", 60_000L) // this hour, on 5.9.1
        old.close(); opened -= old
        pause()

        var lib = keep(LibraryDatabase(context))
        bridge(lib, "phoneA").start()
        listen(lib, "B", 45_000L) // this hour, on 5.10.0, before the clear
        pause()
        runBlocking { lib.clearHistory() }
        pause()
        listen(lib, "C", 30_000L) // this hour, after the clear: stays
        assertEquals(mapOf("C" to 30_000L), minutesThisHour(lib))
        lib.close(); opened -= lib
        pause()

        // back on 5.9.1: its own file still has A's minute from before the upgrade
        val back = keep(TestLibraryFiles.legacy(context))
        assertEquals(mapOf("A" to 60_000L), minutesThisHour(back))
        back.close(); opened -= back
        context.getDatabasePath(LibraryDatabase.DB_NAME).setLastModified(System.currentTimeMillis() + 60_000L)

        lib = keep(LibraryDatabase(context))
        val b = bridge(lib, "phoneA")
        b.start()
        assertEquals("only what was played after the clear", mapOf("C" to 30_000L), minutesThisHour(lib))
        val hour = Days.hourOf(System.currentTimeMillis())
        val logged = b.foldedDocs().filterKeys { it.startsWith("history/") }.values.flatMap { doc ->
            com.minim.music.data.sync.SyncCodec.map(doc["l"]).values.flatMap { com.minim.music.data.sync.SyncCodec.map(it).keys }
        }.mapNotNull { com.minim.music.data.sync.SyncCodec.parseListenKey(it) }.filter { it.first == hour }.map { it.second }.toSet()
        assertFalse("A's minute isn't recorded again", "A" in logged)
    }

    @Test
    fun aClearInALaterHourThanTheUpgradeKeeps591sMinutesFromThatHour() {
        // the kept copy imported in an earlier hour: 5.9.1's rows for the clear's hour were all
        // played after going back, so they stay (only the hours before the clear go)
        val old = keep(TestLibraryFiles.legacy(context))
        old.close(); opened -= old
        pause()
        var lib = keep(LibraryDatabase(context))
        val first = bridge(lib, "phoneA")
        first.start()
        // pretend the import happened two hours ago
        lib.writableDatabase.execSQL(
            "UPDATE fermata_meta SET v = ? WHERE k = 'imported'",
            arrayOf<Any>("${System.currentTimeMillis() - 2 * 3_600_000L} 0"),
        )
        runBlocking { lib.clearHistory() }
        lib.close(); opened -= lib
        pause()
        val back = keep(TestLibraryFiles.legacy(context))
        listen(back, "D", 20_000L) // this hour, on 5.9.1, after the clear
        back.close(); opened -= back
        context.getDatabasePath(LibraryDatabase.DB_NAME).setLastModified(System.currentTimeMillis() + 60_000L)

        lib = keep(LibraryDatabase(context))
        bridge(lib, "phoneA").start()
        assertEquals(mapOf("D" to 20_000L), minutesThisHour(lib))
    }

    companion object {
        private const val PHONE_B = "phoneB.db"
    }
}
