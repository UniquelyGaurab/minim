package com.minim.music.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * v4.9.7 static checks on the layout files: the Settings pill clipping, the Home pill without
 * buttons, the Up next rows and the Now Playing 3 dot menu.
 */
class LayoutChecksTest {

    private val androidNs = "http://schemas.android.com/apk/res/android"

    private fun layoutDir(): File =
        listOf(File("src/main/res/layout"), File("app/src/main/res/layout")).first { it.isDirectory }

    private fun parse(name: String): Element {
        val f = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        return f.newDocumentBuilder().parse(File(layoutDir(), name)).documentElement
    }

    private fun Element.attr(name: String): String? =
        getAttributeNS(androidNs, name).takeIf { hasAttributeNS(androidNs, name) }

    private fun Element.elements(): List<Element> {
        val out = ArrayList<Element>()
        val all = getElementsByTagName("*")
        out.add(this)
        for (i in 0 until all.length) out.add(all.item(i) as Element)
        return out
    }

    private fun Element.childElements(): List<Element> {
        val out = ArrayList<Element>()
        val nodes = childNodes
        for (i in 0 until nodes.length) (nodes.item(i) as? Element)?.let { out.add(it) }
        return out
    }

    private fun ids(name: String): Set<String> =
        parse(name).elements().mapNotNull { it.attr("id")?.substringAfter('/') }.toSet()

    /** Bottom margin in dp a child really gets: its own attribute, else the Option style's 8dp. */
    private fun bottomMarginDp(child: Element): Float {
        child.attr("layout_marginBottom")?.let { return it.removeSuffix("dp").toFloatOrNull() ?: 0f }
        child.attr("layout_margin")?.let { return it.removeSuffix("dp").toFloatOrNull() ?: 0f }
        return if (child.getAttribute("style") == "@style/MinimText.Option") 8f else 0f
    }

    @Test
    fun centredRowsHaveNoChildBottomMargins_soNothingIsCutOffAtTheTop() {
        val offenders = ArrayList<String>()
        for (file in layoutDir().listFiles { f -> f.name.endsWith(".xml") }!!.sortedBy { it.name }) {
            for (el in parse(file.name).elements()) {
                if (el.tagName != "LinearLayout") continue
                if ((el.attr("orientation") ?: "horizontal") != "horizontal") continue
                val gravity = el.attr("gravity") ?: continue
                if (!gravity.contains("center_vertical") && gravity != "center") continue
                for (child in el.childElements()) {
                    if (bottomMarginDp(child) > 0f) offenders.add("${file.name}: ${child.attr("id") ?: child.tagName}")
                }
            }
        }
        assertTrue("Centred rows with child bottom margins: $offenders", offenders.isEmpty())
    }

    @Test
    fun settingsLyricsAndUpdateRowsAreFixed() {
        val settings = parse("view_settings.xml").elements()
        for (id in listOf("set_lyrics_minus", "set_lyrics_plus", "set_check_updates")) {
            val pill = settings.first { it.attr("id") == "@+id/$id" }
            assertEquals(id, 0f, bottomMarginDp(pill))
        }
    }

    @Test
    fun homePillHoldsOnlyCoverAndText_buttonsAreCirclesBelow() {
        val pill = ids("view_home_mini.xml")
        assertTrue(pill.containsAll(listOf("mini_art", "mini_title", "mini_artist")))
        assertFalse(pill.contains("mini_like") || pill.contains("mini_play") || pill.contains("mini_next"))
        val actions = parse("view_home_mini_actions.xml").elements()
        for (id in listOf("mini_like", "mini_play", "mini_next")) {
            val v = actions.first { it.attr("id") == "@+id/$id" }
            assertEquals("@drawable/shape_circle", v.attr("background"))
            assertEquals(v.attr("layout_width"), v.attr("layout_height"))
        }
        val home = parse("view_home.xml").elements()
        val include = home.first { it.attr("id") == "@+id/home_mini_actions" }
        assertEquals("@layout/view_home_mini_actions", include.getAttribute("layout"))
        val mini = home.first { it.attr("id") == "@+id/home_mini" }
        assertEquals("@layout/view_home_mini", mini.getAttribute("layout"))
    }

    @Test
    fun upNextRowsMatchSongRows_withButtonsAndNoPlaylistPill() {
        assertFalse(ids("view_queue.xml").contains("queue_add_to_playlist"))
        val row = parse("item_queue_track.xml").elements()
        assertTrue(row.map { it.attr("id") }.containsAll(listOf("@+id/queue_up", "@+id/queue_down", "@+id/queue_remove")))
        val card = row.first { it.attr("id") == "@+id/queue_row" }
        assertEquals("@drawable/shape_card", card.attr("background"))
        assertEquals("fill:soft", card.attr("tag"))
        for (id in listOf("queue_title", "queue_artist")) {
            val t = row.first { it.attr("id") == "@+id/$id" }
            assertEquals("end", t.attr("ellipsize"))
            assertEquals("true", t.attr("singleLine"))
        }
        val art = row.first { it.attr("id") == "@+id/queue_art" }
        assertEquals("44dp", art.attr("layout_width"))
    }

    @Test
    fun nowPlayingHasCollapsePillAndMenuButton() {
        val np = parse("view_now_playing.xml").elements()
        val back = np.firstOrNull { it.attr("id") == "@+id/np_back" }
        val more = np.firstOrNull { it.attr("id") == "@+id/np_more" }
        assertNotNull(back)
        assertNotNull(more)
        assertEquals("center", back!!.attr("layout_gravity"))
        assertTrue(more!!.attr("layout_gravity")!!.startsWith("end"))
        assertNull(np.firstOrNull { it.attr("id") == "@+id/queue_add_to_playlist" })
    }

    @Test
    fun nowPlayingHasUpNextPillInsteadOfSwipeHint() {
        val np = parse("view_now_playing.xml")
        val all = np.elements()
        assertNull(all.firstOrNull { it.attr("id") == "@+id/np_hint" })
        assertTrue(all.none { it.attr("text") == "@string/np_swipe_hint" })
        val pill = all.first { it.attr("id") == "@+id/np_up_next" }
        assertEquals("@drawable/shape_pill", pill.attr("background"))
        assertEquals("fill:fg text:bg", pill.attr("tag"))
        assertEquals("@string/np_up_next", pill.attr("text"))
        // It's the last thing on the screen, where the hint was, with a fixed height for fitNowPlaying
        // (v4.9.8: the screen's elements live in np_content, inside the coloured screen)
        val content = np.childElements().single()
        assertEquals("@+id/np_content", content.attr("id"))
        assertEquals("@+id/np_up_next", content.childElements().last().attr("id"))
        assertTrue(pill.attr("layout_height")!!.endsWith("dp"))
    }

    @Test
    fun nowPlayingScreenKeepsItsColourWhileItsContentFades() {
        val np = parse("view_now_playing.xml")
        assertEquals("@+id/screen_now_playing", np.attr("id"))
        assertEquals("bg", np.attr("tag"))
        assertEquals("true", np.attr("clickable"))
        val content = np.childElements().single()
        assertEquals("vertical", content.attr("orientation"))
        assertNull("the colour belongs to the screen, not the fading content", content.attr("tag"))
    }

    @Test
    fun fullscreenLyricsReplaceTheOldLyricsScreen() {
        val dir = layoutDir()
        assertFalse(File(dir, "view_lyrics.xml").exists())
        val main = parse("activity_main.xml").childElements()
        val includes = main.filter { it.tagName == "include" }.map { it.getAttribute("layout") }
        val np = includes.indexOf("@layout/view_now_playing")
        val fs = includes.indexOf("@layout/view_fullscreen_lyrics")
        assertTrue(np >= 0 && fs == np + 1)
        assertFalse(includes.contains("@layout/view_lyrics"))
    }

    @Test
    fun fullscreenLyricsLayout_isUnpaintedAndHasNoControls() {
        val root = parse("view_fullscreen_lyrics.xml")
        assertEquals("@+id/screen_fs_lyrics", root.attr("id"))
        assertEquals(Painter.UNPAINTED, root.attr("tag"))
        assertEquals("gone", root.attr("visibility"))
        val all = root.elements()
        // Nothing inside takes palette colours (the cover decides them)
        assertTrue(all.drop(1).none { it.attr("tag") != null })
        val ids = all.mapNotNull { it.attr("id")?.substringAfter('/') }.toSet()
        assertTrue(ids.containsAll(listOf("fs_backdrop", "fs_bg_a", "fs_bg_b", "fs_scrim", "fs_lines", "fs_bar")))
        // Lyrics only: no title, buttons or other controls
        assertTrue(all.none { it.tagName == "Button" || it.tagName.endsWith("ImageButton") })
        assertTrue(all.none { it.attr("id")?.contains("title") == true })
        // The bar rests at the bottom; the cover is cropped to fill the screen
        val bar = all.first { it.attr("id") == "@+id/fs_bar" }
        assertEquals("com.minim.music.ui.ProgressLine", bar.tagName)
        assertTrue(bar.attr("layout_gravity")!!.startsWith("bottom"))
        assertEquals("centerCrop", all.first { it.attr("id") == "@+id/fs_bg_a" }.attr("scaleType"))
        val lines = all.first { it.attr("id") == "@+id/fs_lines" }
        assertEquals("vertical", lines.attr("requiresFadingEdge"))
    }

    @Test
    fun lyricLineKeepsItsFixedHeight_andSettingsHasNoQueueLongPress() {
        val lyric = parse("view_now_playing.xml").elements().first { it.attr("id") == "@+id/np_lyric" }
        assertEquals("68dp", lyric.attr("layout_height"))
        val settings = ids("view_settings.xml")
        assertFalse(settings.contains("set_lpress_queue"))
        assertTrue(settings.containsAll(listOf("set_lpress_lyrics", "set_lpress_off")))
    }
}
