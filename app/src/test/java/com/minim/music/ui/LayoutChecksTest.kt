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
}
