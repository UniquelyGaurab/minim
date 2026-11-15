package com.minim.music.ui

import com.minim.music.data.local.AppPreferences

/**
 * 6.8.0 (71): the Haze theme. The playing song's cover, blurred, is the background of the whole
 * app, and one ink (white or near-black) is picked for every text and icon from how bright that
 * blurred cover is. Plain maths on ARGB ints so all of it runs in unit tests; the bitmaps and the
 * drawable live in HazeBackdrop.
 */
object HazeLook {

    const val LIGHT_INK = 0xFFFFFFFF.toInt()
    const val DARK_INK = 0xFF111111.toInt()

    /**
     * The luminance (0..1) where white and #111 read equally well on a colour: (L+0.05)^2 equals
     * 1.05 x 0.0556. Below it white ink, above it dark ink.
     */
    const val EVEN_LUMINANCE = 0.1916

    /** Hysteresis: white ink only gives way above this, dark ink only below [TO_LIGHT]. */
    const val TO_DARK = 0.22
    const val TO_LIGHT = 0.165

    /** The faint scrim is never weaker than this, and never stronger than [SCRIM_MAX]. */
    const val SCRIM_MIN = 0.08f
    const val SCRIM_MAX = 0.60f
    const val SCRIM_STEP = 0.02f

    /** Contrast the ink keeps over the scrimmed cover, measured on its hardest part. */
    const val TARGET_CONTRAST = 4.5

    /** Soft fills (song cards, soft pills, sheet panels): the ink at this opacity (about 12%). */
    const val FROST_ALPHA = 0x1F

    /** How far the base colour (text inside ink pills) leans away from the ink. */
    const val BASE_LEAN = 0.45f

    /** Secondary text: the ink, this far towards the cover. */
    const val MUTED_BLEND = 0.35f

    /** One song's look. */
    data class Look(
        val lightInk: Boolean,
        val ink: Int,
        /** Black under white ink, white under dark ink. */
        val scrim: Int,
        val scrimAlpha: Float,
        /** The blurred cover's average colour with the scrim over it. */
        val average: Int,
        val pal: Pal
    )

    /**
     * White ink (true) or dark ink (false) for a blurred cover of average luminance [luma].
     * [wasLight] is the ink the last song had (null: none yet); near the middle it stays.
     */
    fun lightInk(luma: Double, wasLight: Boolean?): Boolean = when (wasLight) {
        null -> luma < EVEN_LUMINANCE
        true -> luma <= TO_DARK
        false -> luma < TO_LIGHT
    }

    /**
     * The weakest scrim (in [SCRIM_STEP]s from [SCRIM_MIN]) that keeps [ink] at [TARGET_CONTRAST]
     * over [hardest], the part of the cover that's closest to the ink (its brightest part under
     * white ink, its darkest under dark ink).
     */
    fun scrimAlpha(ink: Int, hardest: Int): Float {
        val scrim = scrimFor(ink)
        var a = SCRIM_MIN
        while (a < SCRIM_MAX) {
            if (contrast(ink, over(scrim, a, hardest)) >= TARGET_CONTRAST) return a
            a += SCRIM_STEP
        }
        return SCRIM_MAX
    }

    fun scrimFor(ink: Int): Int = if (ink == LIGHT_INK) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()

    /**
     * The look for a blurred cover: [luma] its average luminance, [average] its average colour,
     * [brightest] and [darkest] its brightest and darkest parts.
     */
    fun look(luma: Double, average: Int, brightest: Int, darkest: Int, wasLight: Boolean?): Look {
        val light = lightInk(luma, wasLight)
        val ink = if (light) LIGHT_INK else DARK_INK
        val scrim = scrimFor(ink)
        val a = scrimAlpha(ink, if (light) brightest else darkest)
        val avg = over(scrim, a, average)
        return Look(light, ink, scrim, a, avg, palFor(ink, avg))
    }

    /**
     * The app palette over the haze: text and icons in the ink, the base (inside ink-filled pills,
     * small placeholders) leaning away from it, muted lines half way. All opaque.
     */
    fun palFor(ink: Int, average: Int): Pal {
        val light = ink == LIGHT_INK
        val away = if (light) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
        // The base has to sit on the far side of the middle from the ink, because the status bar,
        // the Artist page and others pick dark or light icons from it (Painter.isLight)
        var lean = BASE_LEAN
        var base = ThemeWallpaper.blend(average, away, lean)
        while (lean < 1f && (luminance(base) > 0.5) == !light) {
            lean = (lean + 0.05f).coerceAtMost(1f)
            base = ThemeWallpaper.blend(average, away, lean)
        }
        val muted = ThemeWallpaper.blend(ink, average, MUTED_BLEND)
        return Pal(bg = base, fg = ink, muted = muted)
    }

    /** A soft fill over the haze: the ink, see-through. */
    fun frost(ink: Int): Int = (FROST_ALPHA shl 24) or (ink and 0x00FFFFFF)

    /** Nothing playing and no history: the Dark look. */
    val NO_COVER_PAL: Pal get() = Pal.build(0, darkMode = true)

    /** Where the background comes from. */
    sealed class Source {
        data class Cover(val url: String) : Source()
        object Dark : Source()
    }

    /**
     * The playing song's cover; with nothing playing, the last played song's; with no history at
     * all (or no covers), the Dark look.
     */
    fun source(playingUrl: String?, lastPlayedUrl: String?): Source = when {
        !playingUrl.isNullOrBlank() -> Source.Cover(playingUrl)
        !lastPlayedUrl.isNullOrBlank() -> Source.Cover(lastPlayedUrl)
        else -> Source.Dark
    }

    // ---- Settings: the Colours row ----

    /** How the Colours row shows: on or faded out, the note under it, and which option is lit. */
    data class ColoursRow(val enabled: Boolean, val showNote: Boolean, val dynamicSelected: Boolean)

    /**
     * Haze takes its look from the cover, so Dynamic / Default fades out and can't be tapped. The
     * saved choice isn't touched, so Light or Dark bring it back as it was.
     */
    fun coloursRow(themeMode: Int, colourMode: Int): ColoursRow {
        val haze = themeMode == AppPreferences.THEME_HAZE
        return ColoursRow(
            enabled = !haze,
            showNote = haze,
            dynamicSelected = colourMode != AppPreferences.COLOURS_DEFAULT
        )
    }

    /** The Colours row's opacity: full, or faded out under Haze. */
    fun coloursAlpha(enabled: Boolean): Float = if (enabled) 1f else 0.35f

    // ---- colour maths (no Android classes) ----

    /** [top] at [alpha] over the opaque colour [under]. */
    fun over(top: Int, alpha: Float, under: Int): Int =
        ThemeWallpaper.blend(under, top or 0xFF000000.toInt(), alpha.coerceIn(0f, 1f))

    fun luminance(c: Int): Double {
        fun ch(v: Int): Double {
            val s = v / 255.0
            return if (s <= 0.03928) s / 12.92 else Math.pow((s + 0.055) / 1.055, 2.4)
        }
        return 0.2126 * ch((c shr 16) and 0xFF) + 0.7152 * ch((c shr 8) and 0xFF) + 0.0722 * ch(c and 0xFF)
    }

    fun contrast(a: Int, b: Int): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }

    /** Stats for a blurred cover's pixels: average luminance, average colour, brightest, darkest. */
    data class Stats(val luma: Double, val average: Int, val brightest: Int, val darkest: Int)

    /**
     * Reads [px] (opaque ARGB). The brightest and darkest are taken at the 90th and 10th
     * percentile so a single speck doesn't decide the scrim.
     */
    fun stats(px: IntArray): Stats {
        if (px.isEmpty()) return Stats(0.0, 0xFF000000.toInt(), 0xFF000000.toInt(), 0xFF000000.toInt())
        var r = 0L
        var g = 0L
        var b = 0L
        var sum = 0.0
        val lum = DoubleArray(px.size)
        for (i in px.indices) {
            val c = px[i]
            r += (c shr 16) and 0xFF
            g += (c shr 8) and 0xFF
            b += c and 0xFF
            lum[i] = luminance(c)
            sum += lum[i]
        }
        val n = px.size
        val avg = (0xFF shl 24) or ((r / n).toInt() shl 16) or ((g / n).toInt() shl 8) or (b / n).toInt()
        val order = px.indices.sortedBy { lum[it] }
        val dark = px[order[((n - 1) * 0.10).toInt()]] or 0xFF000000.toInt()
        val bright = px[order[((n - 1) * 0.90).toInt()]] or 0xFF000000.toInt()
        return Stats(sum / n, avg, bright, dark)
    }
}
