package com.minim.music.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.math.max
import kotlin.math.min

/**
 * 5.4.1: the YouTube, YouTube Music and Spotify logos under imported playlists come out the same
 * size. Measures the real shapes in the drawables: every icon is the same height, every name has
 * the same capital letter height, centred on the icon, and only the width differs.
 */
class SourceLogoSizingTest {

    private val ns = "http://schemas.android.com/apk/res/android"
    private val logos = listOf("ic_wordmark_youtube", "ic_wordmark_youtube_music", "ic_wordmark_spotify")

    private fun drawableDir(): File =
        listOf(File("src/main/res/drawable"), File("app/src/main/res/drawable")).first { it.isDirectory }

    private class Logo(val viewportW: Float, val viewportH: Float, val paths: Map<String, List<String>>)

    private fun load(name: String): Logo {
        val f = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        val root = f.newDocumentBuilder().parse(File(drawableDir(), "$name.xml")).documentElement
        val paths = LinkedHashMap<String, MutableList<String>>()
        val list = root.getElementsByTagName("path")
        for (i in 0 until list.length) {
            val p = list.item(i) as Element
            paths.getOrPut(p.getAttributeNS(ns, "name")) { ArrayList() }.add(p.getAttributeNS(ns, "pathData"))
        }
        return Logo(
            root.getAttributeNS(ns, "viewportWidth").toFloat(),
            root.getAttributeNS(ns, "viewportHeight").toFloat(),
            paths
        )
    }

    /** Box (left, top, right, bottom) of absolute M/L/C/Q/Z path data, sampling the curves. */
    private fun bounds(datas: List<String>): FloatArray {
        val b = floatArrayOf(Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE)
        fun add(x: Float, y: Float) {
            b[0] = min(b[0], x); b[1] = min(b[1], y); b[2] = max(b[2], x); b[3] = max(b[3], y)
        }
        for (d in datas) {
            val tokens = Regex("[A-Za-z]|-?[0-9]*\\.?[0-9]+(?:e-?[0-9]+)?").findAll(d).map { it.value }.toList()
            var i = 0
            var cx = 0f
            var cy = 0f
            var cmd = 'M'
            fun num() = tokens[i++].toFloat()
            while (i < tokens.size) {
                if (tokens[i][0].isLetter()) {
                    cmd = tokens[i][0]; i++
                    if (cmd == 'Z') continue
                }
                when (cmd) {
                    'M', 'L' -> { cx = num(); cy = num(); add(cx, cy) }
                    'C' -> {
                        val x1 = num(); val y1 = num(); val x2 = num(); val y2 = num(); val x = num(); val y = num()
                        for (s in 0..40) {
                            val t = s / 40f; val u = 1 - t
                            add(
                                u * u * u * cx + 3 * u * u * t * x1 + 3 * u * t * t * x2 + t * t * t * x,
                                u * u * u * cy + 3 * u * u * t * y1 + 3 * u * t * t * y2 + t * t * t * y
                            )
                        }
                        cx = x; cy = y
                    }
                    'Q' -> {
                        val x1 = num(); val y1 = num(); val x = num(); val y = num()
                        for (s in 0..40) {
                            val t = s / 40f; val u = 1 - t
                            add(u * u * cx + 2 * u * t * x1 + t * t * x, u * u * cy + 2 * u * t * y1 + t * t * y)
                        }
                        cx = x; cy = y
                    }
                    else -> throw AssertionError("unexpected path command $cmd (only absolute M L C Q Z)")
                }
            }
        }
        return b
    }

    @Test fun everyIconIsTheSameHeight() {
        for (name in logos) {
            val logo = load(name)
            assertEquals(name, SourceLogos.ICON_UNITS, logo.viewportH, 0.01f)
            val icon = bounds(logo.paths.getValue("icon"))
            assertEquals("$name icon top", 0f, icon[1], 0.3f)
            assertEquals("$name icon height", SourceLogos.ICON_UNITS, icon[3] - icon[1], 0.3f)
        }
    }

    @Test fun everyNameHasTheSameCapitalHeightCentredOnTheIcon() {
        for (name in logos) {
            val logo = load(name)
            val cap = bounds(logo.paths.getValue("cap"))
            assertEquals("$name capital height", SourceLogos.CAP_UNITS, cap[3] - cap[1], 0.3f)
            assertEquals("$name capitals centred", SourceLogos.ICON_UNITS / 2, (cap[1] + cap[3]) / 2, 0.3f)
            // the name starts after the icon
            val icon = bounds(logo.paths.getValue("icon"))
            assertTrue("$name name after icon", cap[0] > icon[2])
        }
    }

    @Test fun nothingSpillsOutOfTheLogoAndOnlyTheWidthDiffers() {
        val widths = HashMap<String, Float>()
        for (name in logos) {
            val logo = load(name)
            val all = bounds(logo.paths.values.flatten())
            assertTrue("$name left", all[0] >= -0.3f)
            assertTrue("$name top", all[1] >= -0.3f)
            assertTrue("$name bottom", all[3] <= logo.viewportH + 0.3f)
            assertEquals("$name right edge", logo.viewportW, all[2], 0.5f)
            widths[name] = logo.viewportW
        }
        // YouTube Music just runs longer
        assertTrue(widths.getValue("ic_wordmark_youtube_music") > widths.getValue("ic_wordmark_spotify"))
        assertTrue(widths.getValue("ic_wordmark_youtube_music") > widths.getValue("ic_wordmark_youtube"))
    }

    @Test fun logoLettersMatchTheFromText() {
        // "from" at 10sp on a 3x phone: Poppins capitals are about 21 px, so the logo is 30 px tall
        val capPx = 21f
        val h = SourceLogos.heightForCap(capPx)
        assertEquals(30, h)
        assertEquals(capPx, h * SourceLogos.CAP_UNITS / SourceLogos.ICON_UNITS, 0.5f)
        assertEquals(1, SourceLogos.heightForCap(0f))
    }

    @Test fun everyPlaceSizesTheLogoFromItsFromText() {
        val src = listOf(File("src/main/java"), File("app/src/main/java")).first { it.isDirectory }
        val main = File(src, "com/minim/music/ui/MainActivity.kt").readText()
        val adapters = File(src, "com/minim/music/ui/adapter/Adapters.kt").readText()
        assertTrue(main.contains("SourceLogos.show(listSourceLogo, logo, listSourceFrom)"))
        assertTrue(main.contains("SourceLogos.show(logo, mark, from)"))
        assertTrue(adapters.contains("SourceLogos.show(logo, mark, from)"))
    }
}
