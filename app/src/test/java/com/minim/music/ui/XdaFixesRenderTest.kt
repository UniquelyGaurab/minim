package com.minim.music.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ApplicationProvider
import com.minim.music.MinimApp
import com.minim.music.R
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import java.io.File
import java.time.Duration

/**
 * 5.8.5 u57 (XDA feedback): the tab row in Search, the round search button with even tab gaps
 * (and the slide when it swaps with the logo), and Library's "+ Add playlists" / "Search Playlists"
 * pills. Checked on a Pixel-sized phone, a 360dp phone and at 130% font. With
 * -Dminim.renderDir=/some/dir (or MINIM_RENDER_DIR) the screens are also saved as PNGs.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
@Config(sdk = [34], application = LaunchTestApp::class)
class XdaFixesRenderTest {

    private val app get() = ApplicationProvider.getApplicationContext<MinimApp>()

    private fun idle(ms: Long = 1000) {
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
    }

    private fun frame(a: android.app.Activity, name: String? = null) {
        val dm = a.resources.displayMetrics
        val root = a.window.decorView
        root.measure(
            View.MeasureSpec.makeMeasureSpec(dm.widthPixels, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(dm.heightPixels, View.MeasureSpec.EXACTLY)
        )
        root.layout(0, 0, dm.widthPixels, dm.heightPixels)
        val bmp = Bitmap.createBitmap(dm.widthPixels, dm.heightPixels, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bmp))
        val dir = System.getProperty("minim.renderDir") ?: System.getenv("MINIM_RENDER_DIR")
        if (name != null && dir != null) {
            File(dir).mkdirs()
            File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        idle(50)
    }

    private fun call(a: MainActivity, name: String, vararg args: Any?, wait: Long = 1000) {
        val m = MainActivity::class.java.declaredMethods.first { it.name == name && it.parameterCount == args.size }
        m.isAccessible = true
        m.invoke(a, *args)
        if (wait > 0) idle(wait)
    }

    private fun tab(name: String): Any =
        Class.forName("com.minim.music.ui.MainActivity\$Tab").enumConstants!!.first { (it as Enum<*>).name == name }

    private fun launch(playlists: List<String> = emptyList()): MainActivity {
        app.preferences.welcomeDone = true
        app.preferences.storiesTabSeen = true
        runBlocking { for (p in playlists) app.database.createPlaylist(p) }
        val c = Robolectric.buildActivity(MainActivity::class.java)
        c.setup()
        idle(3000)
        val a = c.get()
        frame(a)
        return a
    }

    private fun View.x(): Float {
        var x = left.toFloat() + translationX
        var p = parent as? View
        while (p != null && p.id != android.R.id.content) { x += p.left + p.translationX; p = p.parent as? View }
        return x
    }

    /** The visible start / end of a row's items: left item, four tabs' text, avatar. */
    private fun spans(rowRoot: View): List<Pair<Float, Float>> {
        val logo = rowRoot.findViewById<View>(R.id.top_logo)
        val search = rowRoot.findViewById<View>(R.id.top_search)
        val left = if (logo.visibility == View.VISIBLE) logo else search
        val out = mutableListOf(left.x() to left.x() + left.width)
        for (id in listOf(R.id.top_tab_home, R.id.top_tab_stories, R.id.top_tab_stats, R.id.top_tab_library)) {
            val t = rowRoot.findViewById<ViewGroup>(id)
            out += t.x() to t.x() + t.width
        }
        return out
    }

    private fun tabTexts(rowRoot: View): List<TextView> =
        listOf(R.id.top_tab_home, R.id.top_tab_stories, R.id.top_tab_stats, R.id.top_tab_library).map { id ->
            val first = rowRoot.findViewById<ViewGroup>(id).getChildAt(0)
            (first as? TextView) ?: ((first as ViewGroup).getChildAt(0) as TextView)
        }

    /** No wrapping, nothing off the screen, and the gap after the left item matches the tab gaps. */
    private fun checkRow(a: MainActivity, rowRoot: View, label: String) {
        val dm = a.resources.displayMetrics
        val s = spans(rowRoot)
        val gaps = (1 until s.size).map { s[it].first - s[it - 1].second }
        val tabGap = gaps[2]
        println("ROW $label gaps(dp)=${gaps.map { (it / dm.density * 10).toInt() / 10f }} end=${((spans(rowRoot).last().second) / dm.density).toInt()}/${(dm.widthPixels / dm.density).toInt()}")
        for (g in gaps) assertTrue("$label gaps $gaps", g > 4 * dm.density)
        // Even spacing: the left item's gap is the tabs' gap (a few px of font padding either way)
        assertTrue("$label gaps $gaps", kotlin.math.abs(gaps[0] - tabGap) <= 3 * dm.density)
        for (t in tabTexts(rowRoot)) assertEquals("$label ${t.text} wraps", 1, t.lineCount)
        val avatar = rowRoot.findViewById<View>(R.id.top_gear)
        assertTrue("$label avatar off screen: ${s.map { (it.first / dm.density).toInt() to (it.second / dm.density).toInt() }} avatar ${(avatar.x() / dm.density).toInt()}+${(avatar.width / dm.density).toInt()} of ${(dm.widthPixels / dm.density).toInt()}dp measured=${run { val u = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED); rowRoot.measure(u, u); rowRoot.measuredWidth / dm.density }} kids=${(0 until (rowRoot as ViewGroup).childCount).map { val c = rowRoot.getChildAt(it); "${c.javaClass.simpleName}:${(c.left / dm.density).toInt()}-${(c.right / dm.density).toInt()}/m${(c.measuredWidth / dm.density).toInt()}" }} sp=${tabTexts(rowRoot)[0].textSize / dm.scaledDensity}", avatar.x() + avatar.width <= dm.widthPixels)
        val search = rowRoot.findViewById<View>(R.id.top_search)
        if (search.visibility == View.VISIBLE) {
            assertEquals("$label search is round", search.width, search.height)
            assertTrue(search.contentDescription == "Search")
            // Lined up where the logo starts on Home
            val homeLogo = a.findViewById<View>(R.id.home_top).findViewById<View>(R.id.top_logo)
            assertTrue("$label search start", kotlin.math.abs(search.x() - homeLogo.x()) < 1.5f)
        }
    }

    private fun allScreens(prefix: String) {
        val a = launch(listOf("Road Trip", "Chill Evening Mix", "Arijit Favourites", "Mix Tape 2026"))
        frame(a, "${prefix}_home")
        checkRow(a, a.findViewById(R.id.home_top), "$prefix home")
        for ((t, id) in listOf("STORIES" to R.id.stories_top, "STATS" to R.id.stats_top, "LIBRARY" to R.id.library_top)) {
            call(a, "showTab", tab(t), false)
            frame(a, "${prefix}_${t.lowercase()}")
            checkRow(a, a.findViewById(id), "$prefix $t")
        }
        // Library: the two pills above everything, no "+" tile in the grid
        val add = a.findViewById<TextView>(R.id.library_add_pill)
        val find = a.findViewById<View>(R.id.library_search_pill)
        val grid = a.findViewById<RecyclerView>(R.id.library_grid)
        assertEquals("+ Add playlists", add.text.toString())
        assertEquals(1, add.lineCount)
        assertTrue(add.x() + add.width < find.x())
        assertTrue(find.x() + find.width <= a.resources.displayMetrics.widthPixels)
        assertEquals(3 + 4, grid.adapter!!.itemCount)
        // Search Playlists: grows into a name box and filters the grid
        call(a, "openLibrarySearch")
        a.findViewById<EditText>(R.id.library_search_input).setText("mix")
        idle(1000)
        frame(a, "${prefix}_library_filter")
        assertEquals(View.GONE, add.visibility)
        assertEquals(2, grid.adapter!!.itemCount)
        a.findViewById<EditText>(R.id.library_search_input).setText("zzz")
        idle(1000)
        frame(a, "${prefix}_library_filter_empty")
        assertEquals(0, grid.adapter!!.itemCount)
        assertEquals(View.VISIBLE, a.findViewById<View>(R.id.library_filter_empty).visibility)
        call(a, "closeLibrarySearch", true)
        frame(a)
        assertEquals(View.VISIBLE, add.visibility)
        assertEquals(7, grid.adapter!!.itemCount)

        // Search from Library: the tab row is there, Library underlined, the box on its own line
        call(a, "openSearch", a.findViewById<View>(R.id.library_top).findViewById(R.id.top_search), wait = 2000)
        frame(a, "${prefix}_search_from_library")
        val sRow = a.findViewById<View>(R.id.search_top)
        checkRow(a, sRow, "$prefix search")
        assertEquals(View.VISIBLE, sRow.findViewById<View>(R.id.top_logo).visibility)
        assertEquals(View.VISIBLE, sRow.findViewById<View>(R.id.top_line_library).visibility)
        assertEquals(View.INVISIBLE, sRow.findViewById<View>(R.id.top_line_home).visibility)
        for (v in listOf(R.id.top_tab_home, R.id.top_tab_stats, R.id.top_gear)) {
            assertEquals(0f, sRow.findViewById<View>(v).translationX, 0.01f)
        }
        val bar = a.findViewById<View>(R.id.search_bar)
        assertTrue(bar.x() >= 0f)
        assertTrue(bar.findViewById<View>(R.id.search_input).x() > sRow.findViewById<View>(R.id.top_logo).x() - 2)

        // Tapping Home in Search's row: Search clears away to Home
        sRow.findViewById<View>(R.id.top_tab_home).performClick()
        idle(120)
        frame(a, "${prefix}_search_to_home_mid")
        idle(1500)
        frame(a, "${prefix}_search_to_home_end")
        assertEquals(View.GONE, a.findViewById<View>(R.id.screen_search).visibility)
        assertEquals(View.VISIBLE, a.findViewById<View>(R.id.screen_home).visibility)
        for (v in listOf(R.id.top_tab_home, R.id.top_tab_stats, R.id.top_gear)) {
            assertEquals(1f, a.findViewById<View>(R.id.home_top).findViewById<View>(v).alpha, 0.01f)
        }
        checkRow(a, a.findViewById(R.id.home_top), "$prefix home after search")
        assertEquals(255, a.findViewById<View>(R.id.screen_search).background.alpha)

        // Search from Home, tap the current tab: just closes
        call(a, "openSearch", null, wait = 2000)
        frame(a, "${prefix}_search_from_home")
        sRow.findViewById<View>(R.id.top_tab_home).performClick()
        idle(2000)
        frame(a)
        assertEquals(View.GONE, a.findViewById<View>(R.id.screen_search).visibility)

        // Logo tab -> search tab: Stats' tabs start where Home's were and slide home
        val homeX = a.findViewById<View>(R.id.home_top).findViewById<View>(R.id.top_tab_home).x()
        call(a, "showTab", tab("STATS"), true, wait = 0)
        val statsRow = a.findViewById<View>(R.id.stats_top)
        val statsHome = statsRow.findViewById<View>(R.id.top_tab_home)
        val startTx = statsHome.translationX
        val statsLeft = statsRow.findViewById<View>(R.id.top_search)
        // Before the first frame: Stats' tabs are drawn exactly where Home's were
        val dm = a.resources.displayMetrics
        val laidOutAt = statsHome.x() - startTx
        assertTrue("stats tabs start where home's were: tx=$startTx home=$homeX stats=$laidOutAt", kotlin.math.abs(laidOutAt + startTx - homeX) < 2f || kotlin.math.abs(laidOutAt - homeX) < 2f)
        assertTrue("the slide moves them: tx=$startTx", kotlin.math.abs(startTx) > 10 * dm.density)
        assertEquals(0f, statsLeft.alpha, 0.01f)
        frame(a)
        idle(2000)
        frame(a)
        assertEquals(0f, statsHome.translationX, 0.01f)
        checkRow(a, statsRow, "$prefix stats after slide")
        // and every hidden row is back in place
        assertEquals(0f, a.findViewById<View>(R.id.home_top).findViewById<View>(R.id.top_tab_home).translationX, 0.01f)
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp-xxhdpi")
    fun pixelSized() = allScreens("p411")

    @Test
    @Config(qualifiers = "w360dp-h780dp-xhdpi")
    fun smallPhone() = allScreens("p360")

    @Test
    @Config(qualifiers = "w360dp-h780dp-xhdpi", fontScale = 1.3f)
    fun smallPhoneBigFont() = allScreens("p360_font130")

    @Test
    @Config(qualifiers = "w411dp-h891dp-xxhdpi", fontScale = 1.3f)
    fun pixelBigFont() = allScreens("p411_font130")

    @Test
    @Config(qualifiers = "w411dp-h891dp-xxhdpi")
    fun searchFromAPlaylistShowsTheTabRow_andATabLeavesTheList() {
        val a = launch(listOf("Road Trip"))
        call(a, "showTab", tab("LIBRARY"), false)
        val grid = a.findViewById<RecyclerView>(R.id.library_grid)
        frame(a)
        val holder = grid.findViewHolderForAdapterPosition(3)!!
        val box = holder.javaClass.getDeclaredField("box").apply { isAccessible = true }.get(holder) as View
        box.performClick()
        idle(2000)
        frame(a)
        assertEquals(View.VISIBLE, a.findViewById<View>(R.id.screen_list).visibility)
        call(a, "openSearch", null, wait = 2000)
        frame(a, "p411_search_from_playlist")
        val sRow = a.findViewById<View>(R.id.search_top)
        assertEquals(1f, sRow.alpha, 0.01f)
        assertEquals(View.VISIBLE, sRow.findViewById<View>(R.id.top_line_library).visibility)
        sRow.findViewById<View>(R.id.top_tab_stats).performClick()
        idle(2000)
        frame(a, "p411_playlist_search_to_stats")
        assertEquals(View.GONE, a.findViewById<View>(R.id.screen_search).visibility)
        assertEquals(View.GONE, a.findViewById<View>(R.id.screen_list).visibility)
        assertEquals(View.VISIBLE, a.findViewById<View>(R.id.screen_stats).visibility)
    }
}
