package com.minim.music.ui

import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ApplicationProvider
import com.minim.music.MinimApp
import com.minim.music.R
import com.minim.music.playback.PlaybackService
import com.minim.music.util.CrashReporter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import java.time.Duration

/**
 * The real Minim application, with the playback service connection answered by a stub (Robolectric
 * can't run the media session). Registered before MinimApp.onCreate, which is where it connects.
 */
class LaunchTestApp : MinimApp() {
    override fun onCreate() {
        val svc = ComponentName(this, PlaybackService::class.java)
        val bind = Intent("androidx.media3.session.MediaSessionService").setClassName(packageName, svc.className)
        Shadows.shadowOf(this).setComponentNameAndServiceForBindServiceForIntent(bind, svc, android.os.Binder())
        super.onCreate()
    }
}

/**
 * 5.7.0 u50: launch smoke test. u48 and u49 crashed on every launch (the Artist page heart was
 * wired before its view was looked up) and no test ever started MainActivity, so nothing caught
 * it. This starts the real MainActivity with the real application, opens every tab and every
 * screen that got edge fades in u49, scrolls and draws them, and checks the crash report screen.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
@Config(sdk = [34], application = LaunchTestApp::class, qualifiers = "w411dp-h891dp-xxhdpi")
class LaunchSmokeTest {

    private val app get() = ApplicationProvider.getApplicationContext<MinimApp>()

    @Before
    fun noOldReport() {
        CrashReporter.clear(app.filesDir)
    }

    private fun idle(seconds: Long = 1) {
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(seconds))
    }

    /** Measures, lays out and draws the whole window, like a frame on a 1080x2400-ish phone. */
    private fun frame(a: android.app.Activity) {
        val root = a.window.decorView
        root.measure(
            View.MeasureSpec.makeMeasureSpec(1233, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(2673, View.MeasureSpec.EXACTLY)
        )
        root.layout(0, 0, 1233, 2673)
        root.draw(Canvas(Bitmap.createBitmap(1233, 2673, Bitmap.Config.ARGB_8888)))
        idle()
    }

    private fun call(a: MainActivity, name: String, vararg args: Any?) {
        val m = MainActivity::class.java.declaredMethods.first { it.name == name && it.parameterCount == args.size }
        m.isAccessible = true
        m.invoke(a, *args)
        idle()
    }

    private fun tab(name: String): Any {
        val cls = Class.forName("com.minim.music.ui.MainActivity\$Tab")
        return cls.enumConstants!!.first { (it as Enum<*>).name == name }
    }

    private fun visibleLists(v: View, out: MutableList<View> = mutableListOf()): List<View> {
        if (!v.isShown) return out
        if (v is RecyclerView || v is androidx.core.widget.NestedScrollView) out += v
        if (v is ViewGroup) for (i in 0 until v.childCount) visibleLists(v.getChildAt(i), out)
        return out
    }

    /** Draws the screen at rest, scrolled down, and back at the top. */
    private fun scrollAndDraw(a: MainActivity) {
        frame(a)
        for (list in visibleLists(a.window.decorView)) {
            list.scrollBy(0, 600)
            frame(a)
            list.scrollBy(0, -600)
        }
        frame(a)
    }

    private fun launch(intent: Intent? = null): MainActivity {
        val c = if (intent != null) Robolectric.buildActivity(MainActivity::class.java, intent) else Robolectric.buildActivity(MainActivity::class.java)
        c.setup()
        idle(3)
        return c.get()
    }

    @Test
    fun launchesAndEveryTabOpens() {
        val a = launch()
        assertFalse(a.isFinishing)
        assertNotNull(a.findViewById<View>(R.id.artist_like))
        frame(a)
        for (t in listOf("STORIES", "STATS", "LIBRARY", "HOME")) {
            call(a, "showTab", tab(t), false)
            scrollAndDraw(a)
        }
        assertNull(Shadows.shadowOf(a).nextStartedActivity)
    }

    @Test
    fun everyFadingListScreenOpens() {
        val a = launch()
        call(a, "showTab", tab("LIBRARY"), false)
        // Liked, Recents and Offline (the ids MainActivity uses for them)
        for ((id, name) in listOf(-1L to "Liked", -2L to "Recents", -3L to "Offline")) {
            call(a, "openList", id, name, null, null)
            scrollAndDraw(a)
            a.onBackPressedDispatcher.onBackPressed()
            idle()
        }
        // A Discover grid
        call(a, "openShelfPage", com.minim.music.data.discover.Shelf("smoke", "Smoke test", emptyList()), false)
        scrollAndDraw(a)
        a.onBackPressedDispatcher.onBackPressed()
        idle()
        // Search, Up next and Settings
        call(a, "openSearch", null)
        scrollAndDraw(a)
        a.onBackPressedDispatcher.onBackPressed()
        idle()
        call(a, "openQueue", null)
        scrollAndDraw(a)
        a.onBackPressedDispatcher.onBackPressed()
        idle()
        // The Artist page (its heart is what crashed u48/u49) opens and draws, even empty
        call(a, "showArtistScreen", false)
        frame(a)
        a.onBackPressedDispatcher.onBackPressed()
        idle()
        call(a, "openSettings", null)
        scrollAndDraw(a)
        a.onBackPressedDispatcher.onBackPressed()
        idle()
        frame(a)
    }

    @Test
    fun savedCrashOpensTheReportScreenFirstThenMinim() {
        CrashReporter.save(
            app.filesDir,
            "Minim crash report\nVersion: 5.7.0 (49)\nThread: main\n\n" +
                "kotlin.UninitializedPropertyAccessException: lateinit property artistLike has not been initialized\n" +
                "\tat com.minim.music.ui.MainActivity.bindViews(MainActivity.kt:533)\n"
        )
        val c = Robolectric.buildActivity(MainActivity::class.java).setup()
        idle()
        val main = c.get()
        // Nothing of the app was built: it went straight to the report screen
        assertTrue(main.isFinishing)
        val open = Shadows.shadowOf(main).nextStartedActivity
        assertEquals(CrashReportActivity::class.java.name, open.component?.className)
        val report = open.getStringExtra(CrashReportActivity.EXTRA_REPORT)!!
        assertTrue(report.contains("artistLike"))
        // Shown once: the saved report is gone
        assertNull(CrashReporter.pending(app.filesDir))

        val screen = Robolectric.buildActivity(CrashReportActivity::class.java, open).setup().get()
        idle()
        frame(screen)
        val title = screen.findViewById<TextView>(R.id.crash_report_title)
        assertEquals("Minim crashed last time", title.text.toString())
        assertTrue(screen.findViewById<TextView>(R.id.crash_report_text).text.contains("bindViews"))

        // Copy puts the whole report on the clipboard; nothing is started or sent
        screen.findViewById<View>(R.id.crash_report_copy).performClick()
        val cm = screen.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        assertEquals(report, cm.primaryClip!!.getItemAt(0).text.toString())
        assertNull(Shadows.shadowOf(screen).nextStartedActivity)

        // Share opens Android's share sheet with the report as text
        screen.findViewById<View>(R.id.crash_report_share).performClick()
        val chooser = Shadows.shadowOf(screen).nextStartedActivity
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        @Suppress("DEPRECATION")
        val send = chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
        assertEquals(Intent.ACTION_SEND, send.action)
        assertEquals(report, send.getStringExtra(Intent.EXTRA_TEXT))

        // Continue opens Minim, which now builds normally
        screen.findViewById<View>(R.id.crash_report_continue).performClick()
        assertTrue(screen.isFinishing)
        val next = Shadows.shadowOf(screen).nextStartedActivity
        assertEquals(MainActivity::class.java.name, next.component?.className)
        val again = launch(next)
        assertFalse(again.isFinishing)
        frame(again)
        assertNotNull(again.findViewById<View>(R.id.artist_like))
    }

    @Test
    fun backOnTheReportScreenAlsoOpensMinim() {
        val open = Intent(app, CrashReportActivity::class.java).putExtra(CrashReportActivity.EXTRA_REPORT, "Minim crash report\nThread: main\n\njava.lang.IllegalStateException: boom\n")
        val screen = Robolectric.buildActivity(CrashReportActivity::class.java, open).setup().get()
        idle()
        @Suppress("DEPRECATION")
        screen.onBackPressed()
        assertTrue(screen.isFinishing)
        assertEquals(MainActivity::class.java.name, Shadows.shadowOf(screen).nextStartedActivity.component?.className)
    }

    /**
     * Upgrade paths into u50. u47 had no liked-artists list at all (it's new in u48), u48/u49 have
     * one, and a half-written one must not matter either. The library database is version 5 in
     * u47, u48, u49 and u50 alike, so it needs no migration. Each case launches and opens Liked.
     */
    @Test
    fun launchesOverUpdate47_48And49Data() {
        val prefs = app.getSharedPreferences("minim_liked_artists", Context.MODE_PRIVATE)
        val cases = listOf(
            null, // straight from u47: nothing saved yet
            "[{\"k\":\"url:https://music.youtube.com/channel/UCsmoke\",\"u\":\"https://music.youtube.com/channel/UCsmoke\"," +
                "\"n\":\"Smoke Artist\",\"t\":\"\",\"c\":625000000,\"ck\":\"MONTHLY_LISTENERS\",\"at\":1730900000000}]", // u48/u49
            "[{\"broken" // a cut-off save
        )
        for (saved in cases) {
            prefs.edit().apply { if (saved == null) remove("artists_v1") else putString("artists_v1", saved) }.commit()
            val a = launch()
            assertFalse(a.isFinishing)
            call(a, "showTab", tab("LIBRARY"), false)
            call(a, "openList", -1L, "Liked", null, com.minim.music.data.stories.LibraryKind.ARTISTS)
            scrollAndDraw(a)
            a.finish()
            idle()
        }
    }

    @Test
    fun noSavedCrashMeansNoReportScreen() {
        val a = launch()
        assertFalse(a.isFinishing)
        assertNull(Shadows.shadowOf(a).nextStartedActivity)
    }

    @Test
    fun aShareToMinimLinkSurvivesTheReportScreen() {
        CrashReporter.save(app.filesDir, "Minim crash report\nThread: main\n\njava.lang.IllegalStateException: boom\n")
        val shared = Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://music.youtube.com/playlist?list=PLsmoke"))
        val c = Robolectric.buildActivity(MainActivity::class.java, shared).setup()
        idle()
        val open = Shadows.shadowOf(c.get()).nextStartedActivity
        val screen = Robolectric.buildActivity(CrashReportActivity::class.java, open).setup().get()
        idle()
        screen.findViewById<View>(R.id.crash_report_continue).performClick()
        val next = Shadows.shadowOf(screen).nextStartedActivity
        assertEquals(MainActivity::class.java.name, next.component?.className)
        assertEquals(Intent.ACTION_VIEW, next.action)
        assertEquals("https://music.youtube.com/playlist?list=PLsmoke", next.dataString)
        assertTrue(next.getBooleanExtra(CrashReportActivity.EXTRA_SHOWN, false))
        val built = launch(next)
        assertFalse(built.isFinishing)
        assertEquals("https://music.youtube.com/playlist?list=PLsmoke", built.intent.dataString)
    }

    // ---- 5.7.0 u51: one Stats page for songs and tapes ----

    private fun waitFor(what: String, check: () -> Boolean) {
        repeat(80) {
            if (check()) return
            Thread.sleep(50)
            idle()
        }
        assertTrue(what, check())
    }

    private fun stats(a: MainActivity): StatsScreen {
        val f = MainActivity::class.java.getDeclaredField("statsScreen")
        f.isAccessible = true
        return f.get(a) as StatsScreen
    }

    private val today get() = com.minim.music.data.stats.Days.keyOf(System.currentTimeMillis())

    private fun seedSongs(day: Int) = kotlinx.coroutines.runBlocking {
        val song = com.minim.music.data.model.Track(
            "smokeSong", "Suroor", "Neha Kakkar", 200L, "", "https://www.youtube.com/watch?v=smokeSong"
        )
        val row = app.database.recordCountedPlay(song, 120_000L)
        app.database.addListenTime(
            listOf(com.minim.music.data.local.LibraryDatabase.ListenAdd(com.minim.music.data.stats.ListenSlice(day, 9, 12 * 60_000L), song)),
            row, 120_000L
        )
    }

    private fun seedTapes(day: Int) = kotlinx.coroutines.runBlocking {
        val tape = com.minim.music.data.stories.StoryTracks.track("smokeTape1", "The haunted haveli", "SR PAY STORIES", 3600L, "UCsmokechannel1")
        app.database.addTapeListenTime(listOf(
            com.minim.music.data.local.LibraryDatabase.TapeListenAdd(com.minim.music.data.stats.ListenSlice(day, 23, 44 * 60_000L), tape, "UCsmokechannel1", 150)
        ))
        app.database.recordTapePlay(tape, "UCsmokechannel1", com.minim.music.data.stats.Days.startMs(day) + 23 * 3_600_000L + 60_000L)
    }

    private fun rowOf(v: View): Int = (v.parent as View).id

    /** Past the first-run welcome (sheets don't open over it). */
    private fun welcomed() {
        app.preferences.welcomeDone = true
    }

    @Test
    fun statsWithTapesPairsTheBoxesAndEverythingOpens() {
        welcomed()
        seedSongs(today)
        seedTapes(today)
        val a = launch()
        call(a, "showTab", tab("STATS"), false)
        val s = stats(a)
        waitFor("tapes row shows") { a.findViewById<View>(R.id.stats_today_row3).visibility == View.VISIBLE }
        frame(a)
        assertEquals(R.id.stats_today_row1, rowOf(s.todayTapesBox))
        assertEquals(R.id.stats_today_row2, rowOf(s.todayChannelsBox))
        assertEquals(R.id.stats_today_row3, rowOf(s.todayTimeBox))
        assertEquals(View.VISIBLE, s.todayChannelCard.visibility)
        assertEquals("SR PAY STORIES", a.findViewById<TextView>(R.id.stats_today_top_channel).text.toString())
        val saved = a.findViewById<TextView>(R.id.stats_today_saved)
        assertEquals(View.VISIBLE, saved.visibility)
        assertEquals("1.5x saved you 22 min", saved.text.toString())
        scrollAndDraw(a)
        // tapes, channels and listened open their lists
        for (box in listOf(s.todayTapesBox, s.todayChannelsBox, s.todayTimeBox, s.todaySongsBox, s.todayArtistsBox)) {
            val clicked = box.performClick()
            idle(2)
            waitFor("sheet opens") { a.findViewById<View>(R.id.sheet_root).visibility == View.VISIBLE && a.findViewById<ViewGroup>(R.id.sheet_content).childCount > 1 }
            frame(a)
            a.onBackPressedDispatcher.onBackPressed()
            idle(2)
        }
        // the top channel card opens its Channel page
        s.todayChannelCard.performClick()
        idle(2)
        frame(a)
        assertFalse(a.isFinishing)
    }

    @Test
    fun aMusicOnlyDayLooksLikeBefore() {
        seedSongs(today)
        val a = launch()
        call(a, "showTab", tab("STATS"), false)
        val s = stats(a)
        waitFor("songs counted") { a.findViewById<TextView>(R.id.stats_today_songs).text.toString() == "1" }
        frame(a)
        assertEquals(View.GONE, a.findViewById<View>(R.id.stats_today_row3).visibility)
        assertEquals(R.id.stats_today_row1, rowOf(s.todayTimeBox))
        assertEquals(R.id.stats_today_row2, rowOf(s.todayArtistsBox))
        assertEquals(View.GONE, s.todayChannelCard.visibility)
        assertEquals(View.GONE, a.findViewById<View>(R.id.stats_today_saved).visibility)
        scrollAndDraw(a)
    }

    @Test
    fun aRecapWithTapesShowsThemAndTheWrappedPlays() {
        val yesterday = com.minim.music.data.stats.Days.previous(today)
        welcomed()
        seedSongs(yesterday)
        seedTapes(yesterday)
        val a = launch()
        call(a, "showTab", tab("STATS"), false)
        val s = stats(a)
        waitFor("recap tapes row") { a.findViewById<View>(R.id.stats_recap_row2).visibility == View.VISIBLE }
        assertEquals(View.VISIBLE, s.recapTopChannelCard.visibility)
        assertEquals(View.VISIBLE, a.findViewById<View>(R.id.stats_recap_split).visibility)
        scrollAndDraw(a)
        s.recapTapesBox.performClick()
        idle(2)
        waitFor("recap tapes sheet") { a.findViewById<View>(R.id.sheet_root).visibility == View.VISIBLE && a.findViewById<ViewGroup>(R.id.sheet_content).childCount > 1 }
        frame(a)
        a.onBackPressedDispatcher.onBackPressed()
        idle(2)
        // dive in: the wrapped, slide by slide (it has a tapes slide now)
        s.diveIn.performClick()
        repeat(12) {
            idle(4)
            frame(a)
        }
        assertFalse(a.isFinishing)
    }

    // 5.8.2 (update 54): the reworked recap story

    /** Six songs played across a past day: enough for the guess, the top five and first and last. */
    private fun seedRecapDay(day: Int) = kotlinx.coroutines.runBlocking {
        val db = app.database
        val order = listOf("rA", "rB", "rA", "rC", "rB", "rA", "rD", "rE", "rC", "rA", "rF")
        for ((i, id) in order.withIndex()) {
            val t = com.minim.music.data.model.Track(id, "Song $id", if (id == "rA" || id == "rB") "Anirudh Ravichander" else "Artist $id",
                200L, "", "https://www.youtube.com/watch?v=$id")
            val row = db.recordCountedPlay(t, 150_000L)
            val hour = 8 + i
            val at = com.minim.music.data.stats.Days.startMs(day) + hour * 3_600_000L + 5 * 60_000L
            db.writableDatabase.execSQL("UPDATE plays SET played_at = ? WHERE rowid = ?", arrayOf<Any>(at, row))
            db.addListenTime(
                listOf(com.minim.music.data.local.LibraryDatabase.ListenAdd(com.minim.music.data.stats.ListenSlice(day, hour, 3 * 60_000L), t)),
                row, 150_000L
            )
        }
    }

    private fun story(a: MainActivity): RecapStory? {
        val f = MainActivity::class.java.getDeclaredField("recapStory")
        f.isAccessible = true
        return f.get(a) as RecapStory?
    }

    private fun clickables(v: View, out: MutableList<View> = mutableListOf()): List<View> {
        if (v.visibility != View.VISIBLE) return out
        if (v.isClickable && v !is RecapStory) out += v
        if (v is ViewGroup) for (i in 0 until v.childCount) clickables(v.getChildAt(i), out)
        return out
    }

    @Test
    fun theReworkedRecapStoryPlaysThroughToTheCard() {
        val yesterday = com.minim.music.data.stats.Days.previous(today)
        welcomed()
        seedRecapDay(yesterday)
        seedTapes(yesterday)
        val a = launch()
        call(a, "showTab", tab("STATS"), false)
        val s = stats(a)
        waitFor("recap shows") { s.diveIn.isShown || s.diveIn.visibility == View.VISIBLE }
        s.diveIn.performClick()
        waitFor("story opens") { story(a) != null }
        val st = story(a)!!
        assertEquals(
            // 5.8.3 u55: every seeded song is new, so first listens shows too
            listOf(RecapSlides.Kind.INTRO, RecapSlides.Kind.TIME, RecapSlides.Kind.HOURS, RecapSlides.Kind.FIRST_LAST,
                RecapSlides.Kind.FIRST_LISTENS, RecapSlides.Kind.GUESS, RecapSlides.Kind.TOP5, RecapSlides.Kind.ARTIST, RecapSlides.Kind.TAPES,
                RecapSlides.Kind.ROAST, RecapSlides.Kind.PERSONA, RecapSlides.Kind.CARD),
            st.kinds
        )
        val next = RecapStory::class.java.getDeclaredMethod("next").apply { isAccessible = true }
        var guard = 0
        while (st.currentKind != RecapSlides.Kind.CARD && guard++ < 20) {
            frame(a)
            idle(2)
            frame(a)
            if (st.currentKind == RecapSlides.Kind.GUESS) {
                // the guess waits for a pick: tap the first cover
                val covers = clickables(st).filterIsInstance<android.widget.ImageView>().filter { it.contentDescription?.startsWith("Song ") == true }
                assertEquals(3, covers.size)
                covers[0].performClick()
                idle(1)
                frame(a)
            }
            next.invoke(st)
            idle()
        }
        assertEquals(RecapSlides.Kind.CARD, st.currentKind)
        idle(3)
        frame(a)
        // save as playlist: the day's songs, best first
        val pl = clickables(st).filterIsInstance<TextView>().first { it.text.toString() == "save as playlist" }
        pl.performClick()
        waitFor("playlist saved") {
            kotlinx.coroutines.runBlocking { app.database.getPlaylists() }.any { it.name.endsWith("top songs") && it.trackCount == 6 }
        }
        val made = kotlinx.coroutines.runBlocking { app.database.getPlaylists() }.first { it.name.endsWith("top songs") }
        val tracks = kotlinx.coroutines.runBlocking { app.database.getPlaylistTracks(made.id) }
        // rB and rC tie on plays and time: the one played later (rC) ranks higher, as everywhere in stats
        assertEquals(listOf("rA", "rC", "rB"), tracks.take(3).map { it.id })
        assertEquals(6, tracks.size)
        frame(a)
        // swipe down / Back closes it, and the story's song goes with it
        a.onBackPressedDispatcher.onBackPressed()
        idle(2)
        assertNull(story(a))
        val m = MainActivity::class.java.getDeclaredField("recapMusic").apply { isAccessible = true }
        assertNull(m.get(a))
        frame(a)
        assertFalse(a.isFinishing)
    }

    private fun texts(v: View, out: MutableList<String> = mutableListOf()): List<String> {
        if (v.visibility != View.VISIBLE) return out
        if (v is TextView) out += v.text.toString()
        if (v is ViewGroup) for (i in 0 until v.childCount) texts(v.getChildAt(i), out)
        return out
    }

    // 5.8.3 (update 55): the new slides, from a real database
    @Test
    fun theNewRecapSlidesShowWithRealData() {
        val yesterday = com.minim.music.data.stats.Days.previous(today)
        val dayBefore = com.minim.music.data.stats.Days.previous(yesterday)
        welcomed()
        seedRecapDay(yesterday)
        kotlinx.coroutines.runBlocking {
            val db = app.database
            fun track(id: String) = com.minim.music.data.model.Track(id, "Song $id",
                if (id == "rA" || id == "rB") "Anirudh Ravichander" else "Artist $id", 200L, "", "https://www.youtube.com/watch?v=$id")
            // the day before: rA and rB, 12 minutes (a day streak of 2, and something to compare with)
            for ((i, id) in listOf("rA", "rB", "rA", "rB").withIndex()) {
                val row = db.recordCountedPlay(track(id), 150_000L)
                val at = com.minim.music.data.stats.Days.startMs(dayBefore) + (9 + i) * 3_600_000L
                db.writableDatabase.execSQL("UPDATE plays SET played_at = ? WHERE rowid = ?", arrayOf<Any>(at, row))
                db.addListenTime(listOf(com.minim.music.data.local.LibraryDatabase.ListenAdd(
                    com.minim.music.data.stats.ListenSlice(dayBefore, 9 + i, 3 * 60_000L), track(id))), row, 180_000L)
            }
            // yesterday 10 PM: eight new songs back to back, a 24 minute session
            for ((i, id) in listOf("sG", "sH", "sI", "sJ", "sK", "sL", "sM", "sN").withIndex()) {
                val row = db.recordCountedPlay(track(id), 150_000L)
                val at = com.minim.music.data.stats.Days.startMs(yesterday) + 22 * 3_600_000L + i * 3 * 60_000L + 30_000L
                db.writableDatabase.execSQL("UPDATE plays SET played_at = ? WHERE rowid = ?", arrayOf<Any>(at, row))
                db.addListenTime(listOf(com.minim.music.data.local.LibraryDatabase.ListenAdd(
                    com.minim.music.data.stats.ListenSlice(yesterday, 22, 3 * 60_000L), track(id))), row, 180_000L)
            }
            // hearted yesterday: rA and sG
            for (id in listOf("rA", "sG")) {
                db.toggleLike(track(id))
                db.writableDatabase.execSQL("UPDATE tracks SET liked_at = ? WHERE id = ?",
                    arrayOf<Any>(com.minim.music.data.stats.Days.startMs(yesterday) + 12 * 3_600_000L, id))
            }
        }
        val a = launch()
        call(a, "showTab", tab("STATS"), false)
        val s = stats(a)
        waitFor("recap shows") { s.diveIn.isShown || s.diveIn.visibility == View.VISIBLE }
        val opening = MainActivity::class.java.getDeclaredField("recapStoryOpening").apply { isAccessible = true }
        var waited = 0
        while (story(a) == null && waited < 600) {
            // tap again if the stats page wasn't ready for the first tap
            if (waited % 50 == 0 && opening.get(a) == false) s.diveIn.performClick()
            Thread.sleep(30)
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
            waited++
        }
        assertTrue("story opens (still preparing: ${opening.get(a)})", story(a) != null)
        val st = story(a)!!
        assertEquals(
            listOf(RecapSlides.Kind.INTRO, RecapSlides.Kind.TIME, RecapSlides.Kind.VS_YESTERDAY, RecapSlides.Kind.HOURS, RecapSlides.Kind.SESSION, RecapSlides.Kind.FIRST_LAST, RecapSlides.Kind.FIRST_LISTENS, RecapSlides.Kind.GUESS, RecapSlides.Kind.TOP5,
                RecapSlides.Kind.ARTIST, RecapSlides.Kind.HEARTED, RecapSlides.Kind.STREAK, RecapSlides.Kind.ROAST, RecapSlides.Kind.PERSONA, RecapSlides.Kind.CARD),
            st.kinds
        )
        val next = RecapStory::class.java.getDeclaredMethod("next").apply { isAccessible = true }
        // Robolectric runs the bars' timers instantly, so open each slide directly with its bar paused
        val seen = HashMap<RecapSlides.Kind, List<String>>()
        val visited = ArrayList<RecapSlides.Kind>()
        val show = RecapStory::class.java.getDeclaredMethod("show", Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType).apply { isAccessible = true }
        val bar = RecapStory::class.java.getDeclaredField("animator").apply { isAccessible = true }
        for ((i, kind) in st.kinds.withIndex()) {
            show.invoke(st, i, true)
            (bar.get(st) as? android.animation.Animator)?.pause()
            frame(a)
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(4_000))
            // the bar is paused, so run the slide's timed steps (the #1 reveal) by hand
            @Suppress("UNCHECKED_CAST")
            val phases = RecapStory::class.java.getDeclaredField("phases").apply { isAccessible = true }.get(st) as MutableList<Pair<Long, () -> Unit>>
            ArrayList(phases).sortedBy { it.first }.forEach { it.second() }
            phases.clear()
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1_500))
            frame(a)
            assertEquals(kind, st.currentKind)
            visited += kind
            if (kind == RecapSlides.Kind.FIRST_LISTENS) {
                val covers = clickables(st).filterIsInstance<android.widget.ImageView>().filter { it.contentDescription?.startsWith("Song ") == true }
                assertEquals(10, covers.size)
                covers[0].performClick()
                Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500))
                frame(a)
            }
            seen[kind] = texts(st)
        }
        assertTrue(visited.toString(), RecapSlides.Kind.FIRST_LISTENS in seen)
        assertEquals(RecapSlides.Kind.CARD, st.currentKind)
        // rC rD rE rF + the eight 10 PM songs were new: 12, ten covers shown
        assertTrue(seen[RecapSlides.Kind.FIRST_LISTENS]!!.toString(), seen[RecapSlides.Kind.FIRST_LISTENS]!!.contains("12 first listens"))
        assertTrue(seen[RecapSlides.Kind.FIRST_LISTENS]!!.contains("and 2 more"))
        assertTrue(seen[RecapSlides.Kind.FIRST_LISTENS]!!.any { it.startsWith("Song rC") })
        assertTrue(seen[RecapSlides.Kind.HEARTED]!!.toString(), seen[RecapSlides.Kind.HEARTED]!!.contains("2 songs hearted"))
        assertTrue(seen[RecapSlides.Kind.HEARTED]!!.contains("Song sG"))
        assertTrue(seen[RecapSlides.Kind.STREAK]!!.toString(), seen[RecapSlides.Kind.STREAK]!!.contains("Day 2 in a row"))
        assertTrue(seen[RecapSlides.Kind.SESSION]!!.toString(), seen[RecapSlides.Kind.SESSION]!!.any { it.startsWith("nonstop, starting 10:") })
        val dayBeforeName = com.minim.music.data.stats.RecapMoreMath.beforeName(yesterday)
        assertTrue(seen[RecapSlides.Kind.VS_YESTERDAY]!!.toString(), seen[RecapSlides.Kind.VS_YESTERDAY]!!.contains("more than $dayBeforeName"))
        // rA: 4 plays yesterday, 2 the day before
        assertTrue(seen[RecapSlides.Kind.TOP5]!!.toString(), seen[RecapSlides.Kind.TOP5]!!.contains("4 today, 6 all time"))
        a.onBackPressedDispatcher.onBackPressed()
        idle(2)
        assertNull(story(a))
        assertFalse(a.isFinishing)
    }

    // 5.8.1 (update 53): the colour field is gone, the blurred cover is back, Gemini lyrics

    private fun fsLyrics(a: MainActivity): FullscreenLyrics {
        val f = MainActivity::class.java.getDeclaredField("fsLyrics")
        f.isAccessible = true
        return f.get(a) as FullscreenLyrics
    }

    private fun none(id: String = "smoke") =
        com.minim.music.data.model.LyricsData(id, emptyList(), false, false, "none")

    private fun aiLyrics(id: String = "smoke") = com.minim.music.data.model.LyricsData(
        id,
        listOf(
            com.minim.music.data.model.LyricsLine(1_000, 4_000, "first line"),
            com.minim.music.data.model.LyricsLine(4_000, 8_000, "second line"),
            com.minim.music.data.model.LyricsLine(8_000, 12_000, "third line"),
        ),
        true, false, com.minim.music.data.ai.AI_LYRICS_PROVIDER
    )

    private fun textsIn(v: View, out: MutableList<String> = mutableListOf()): List<String> {
        if (v.visibility != View.VISIBLE) return out
        if (v is TextView) out += v.text.toString()
        if (v is ViewGroup) for (i in 0 until v.childCount) textsIn(v.getChildAt(i), out)
        return out
    }

    @Test
    fun theColourFieldIsGone() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        assertEquals(0, ctx.resources.getIdentifier("fs_field", "id", ctx.packageName))
        for (name in listOf(
            "com.minim.music.ui.ColorFieldView", "com.minim.music.ui.ColorFieldMotion", "com.minim.music.ui.CoverPalette",
            "com.minim.music.playback.MusicPulse", "com.minim.music.playback.PulseAnalyzer", "com.minim.music.playback.PulseTapSink",
        )) {
            assertNull(name, runCatching { Class.forName(name) }.getOrNull())
        }
    }

    @Test
    fun aSongWithNoLyricsShowsTheBlurredCoverAndTheGeminiOffer() {
        welcomed()
        val a = launch()
        val fs = fsLyrics(a)
        fs.setLyrics(none())
        fs.setAiSong("smoke")
        fs.open()
        idle(2)
        frame(a)
        assertTrue(fs.isShowing)
        // the u51 background: the blurred cover
        assertEquals(View.VISIBLE, a.findViewById<View>(R.id.fs_images).visibility)
        assertEquals(View.VISIBLE, a.findViewById<View>(R.id.fs_scrim).visibility)
        // the offer, with its disclaimer; nothing has run
        val panel = a.findViewById<View>(R.id.fs_ai_panel)
        assertEquals(View.VISIBLE, panel.visibility)
        assertEquals("Get lyrics with Gemini", a.findViewById<TextView>(R.id.fs_ai_action).text.toString())
        assertTrue(a.findViewById<TextView>(R.id.fs_ai_note).text.contains("AI-generated and might not be accurate"))
        assertEquals(View.GONE, a.findViewById<View>(R.id.fs_message).visibility)
        assertEquals(View.GONE, a.findViewById<View>(R.id.fs_ai_badge).visibility)
        assertEquals(0, MinimApp.instance.aiLyrics.requestsMade)
        // no key: tapping asks for one, still no request
        a.findViewById<View>(R.id.fs_ai_action).performClick()
        idle(3)
        frame(a)
        assertEquals("Add your Gemini API key first", a.findViewById<TextView>(R.id.fs_ai_head).text.toString())
        assertEquals("Add key in Settings", a.findViewById<TextView>(R.id.fs_ai_action).text.toString())
        assertEquals(0, MinimApp.instance.aiLyrics.requestsMade)
        // ...and that button goes straight to the key in Settings
        a.findViewById<View>(R.id.fs_ai_action).performClick()
        idle(3)
        frame(a)
        assertEquals(View.VISIBLE, a.findViewById<View>(R.id.screen_settings).visibility)
        assertEquals(View.VISIBLE, a.findViewById<View>(R.id.sheet_root).visibility)
        assertFalse(a.isFinishing)
    }

    @Test
    fun aiLyricsShowTheDisclaimerOnceAndARegenerateButton() {
        welcomed()
        val a = launch()
        val fs = fsLyrics(a)
        fs.setLyrics(aiLyrics())
        fs.setAiSong("smoke")
        fs.open()
        idle(2)
        fs.setPlayback(5_000, 200_000, false, 0)
        frame(a)
        scrollAndDraw(a)
        val overlay = a.findViewById<View>(R.id.screen_fs_lyrics)
        val disclaimer = "Generated by AI \u00B7 might not be accurate"
        assertEquals(1, textsIn(overlay).count { it == disclaimer })
        assertTrue(textsIn(overlay).count { it == "first line" || it == "second line" } >= 1)
        assertEquals(View.VISIBLE, a.findViewById<View>(R.id.fs_ai_badge).visibility)
        assertEquals(View.VISIBLE, a.findViewById<View>(R.id.fs_ai_redo).visibility)
        assertEquals(View.GONE, a.findViewById<View>(R.id.fs_ai_panel).visibility)
        assertEquals(View.VISIBLE, a.findViewById<View>(R.id.fs_images).visibility)
        // real lyrics: no AI line, no regenerate
        fs.setLyrics(aiLyrics().copy(providerName = "lrclib"))
        fs.setAiSong(null)
        idle(2)
        frame(a)
        assertEquals(0, textsIn(overlay).count { it == disclaimer })
        assertEquals(View.GONE, a.findViewById<View>(R.id.fs_ai_redo).visibility)
        // regenerate with no key: the AI lyrics come back with a short note
        fs.setLyrics(aiLyrics())
        fs.setAiSong("smoke")
        idle(2)
        a.findViewById<View>(R.id.fs_ai_redo).performClick()
        idle(3)
        frame(a)
        assertEquals(View.VISIBLE, a.findViewById<View>(R.id.fs_ai_badge).visibility)
        assertEquals(View.VISIBLE, a.findViewById<View>(R.id.fs_lines).visibility)
        assertEquals(0, MinimApp.instance.aiLyrics.requestsMade)
        a.onBackPressedDispatcher.onBackPressed()
        idle(2)
        assertFalse(fs.isShowing)
        assertFalse(a.isFinishing)
    }

    @Test
    fun theGeminiKeyStaysOutOfBackups() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        for (name in listOf("backup_rules", "data_extraction_rules")) {
            val id = ctx.resources.getIdentifier(name, "xml", ctx.packageName)
            val p = ctx.resources.getXml(id)
            var excludes = 0
            var sections = 0
            var ev = p.eventType
            while (ev != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
                if (ev == org.xmlpull.v1.XmlPullParser.START_TAG) {
                    if (p.name == "cloud-backup" || p.name == "device-transfer" || p.name == "full-backup-content") sections++
                    if (p.name == "exclude" && p.getAttributeValue(null, "domain") == "sharedpref" &&
                        p.getAttributeValue(null, "path") == com.minim.music.data.ai.GeminiSettings.PREFS + ".xml"
                    ) excludes++
                }
                ev = p.next()
            }
            assertTrue(name, excludes >= 1)
            assertEquals(name, sections, excludes)
        }
    }

    @Test
    fun tapesKeepTheBlurredCover() {
        val a = launch()
        val fs = fsLyrics(a)
        fs.captionsMode = true
        fs.open()
        idle(2)
        frame(a)
        assertTrue(fs.isShowing)
        assertEquals(View.VISIBLE, a.findViewById<View>(R.id.fs_images).visibility)
        assertEquals(View.GONE, a.findViewById<View>(R.id.fs_ai_panel).visibility)
        a.onBackPressedDispatcher.onBackPressed()
        idle(2)
        assertFalse(fs.isShowing)
    }

    @Test
    fun theLyricAreaOpensFullScreenForAnySongButNotATapeWithoutCaptions() {
        assertTrue(NowPlayingLyricSlot.slotOpensFullscreen(NowPlayingLyricSlot.Mode.NONE, isStory = false))
        assertTrue(NowPlayingLyricSlot.slotOpensFullscreen(NowPlayingLyricSlot.Mode.LOADING, isStory = false))
        assertTrue(NowPlayingLyricSlot.slotOpensFullscreen(NowPlayingLyricSlot.Mode.LINES, isStory = true))
        assertFalse(NowPlayingLyricSlot.slotOpensFullscreen(NowPlayingLyricSlot.Mode.NONE, isStory = true))
    }
}
