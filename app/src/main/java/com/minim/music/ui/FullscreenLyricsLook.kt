package com.minim.music.ui

import kotlin.math.abs
import kotlin.math.pow

/**
 * v4.9.8: the pure rules behind the full screen lyrics mode (no Android classes, so the unit
 * tests can check them): the ink colour, the scrim strength, how dim each line is, the visible
 * part of a centre-cropped cover and the steps of the enter/exit animation.
 */
object FullscreenLyricsLook {

    const val WHITE_INK: Int = -0x1          // 0xFFFFFFFF
    const val DARK_INK: Int = -0xededee      // 0xFF121212

    /**
     * Same rule as the Artist page photo and the Home takeover ([MainActivity.isAreaLight]):
     * an average luminance above 0.40 is a light picture, so the text goes dark.
     */
    const val LIGHT_THRESHOLD = 0.40

    fun inkIsDark(meanLuminance: Double): Boolean = meanLuminance > LIGHT_THRESHOLD

    fun ink(dark: Boolean): Int = if (dark) DARK_INK else WHITE_INK

    /** A dark wash under white text, a light wash under dark text. */
    fun scrimBase(inkDark: Boolean): Int = if (inkDark) WHITE_INK else -0x1000000

    // ------------------------------------------------------------------------------------------
    // Scrim: as light a wash as possible while the text keeps strong contrast
    // ------------------------------------------------------------------------------------------

    const val SCRIM_MIN = 0.22f
    const val SCRIM_MAX = 0.64f
    /** WCAG AA for large text is 3:1 and for body text 4.5:1; lyrics are large, aim higher anyway. */
    const val TARGET_CONTRAST = 4.5

    /**
     * The scrim opacity for a blurred cover whose worst pixel (the brightest one under white ink,
     * the darkest one under dark ink) is [worst]: the lightest wash from [SCRIM_MIN] that gets the
     * ink to [TARGET_CONTRAST] against it, capped at [SCRIM_MAX] so the cover's colour survives.
     */
    fun scrimAlpha(worst: Int, inkDark: Boolean): Float {
        val ink = ink(inkDark)
        val base = scrimBase(inkDark)
        var a = SCRIM_MIN
        while (a < SCRIM_MAX) {
            if (contrast(ink, blend(worst, base, a)) >= TARGET_CONTRAST) return a
            a += 0.02f
        }
        return SCRIM_MAX
    }

    /** The scrim colour itself (base colour at [alpha]). */
    fun scrimColor(inkDark: Boolean, alpha: Float): Int =
        (scrimBase(inkDark) and 0x00FFFFFF) or ((alpha.coerceIn(0f, 1f) * 255f + 0.5f).toInt() shl 24)

    /**
     * The pixel the text has the hardest time against: the [pct] brightest one for white ink, the
     * [pct] darkest one for dark ink. A percentile instead of the extreme, so one speck of glare
     * doesn't wash the whole cover out.
     */
    fun worstPixel(pixels: IntArray, inkDark: Boolean, pct: Double = 0.9): Int {
        if (pixels.isEmpty()) return if (inkDark) DARK_INK else WHITE_INK
        val sorted = pixels.sortedBy { relativeLuminance(it) }
        val q = if (inkDark) 1.0 - pct else pct
        return sorted[((sorted.size - 1) * q).toInt().coerceIn(0, sorted.size - 1)]
    }

    /** WCAG relative luminance of an opaque sRGB colour, 0..1. */
    fun relativeLuminance(c: Int): Double {
        fun ch(v: Int): Double {
            val s = v / 255.0
            return if (s <= 0.03928) s / 12.92 else ((s + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * ch((c shr 16) and 0xFF) + 0.7152 * ch((c shr 8) and 0xFF) + 0.0722 * ch(c and 0xFF)
    }

    fun contrast(a: Int, b: Int): Double {
        val la = relativeLuminance(a)
        val lb = relativeLuminance(b)
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }

    /** [over] painted over [under] at [alpha], channel by channel, the way a View draws it. */
    fun blend(under: Int, over: Int, alpha: Float): Int {
        val a = alpha.coerceIn(0f, 1f)
        fun mix(shift: Int): Int {
            val u = (under shr shift) and 0xFF
            val o = (over shr shift) and 0xFF
            return ((u + (o - u) * a) + 0.5f).toInt().coerceIn(0, 255)
        }
        return (0xFF shl 24) or (mix(16) shl 16) or (mix(8) shl 8) or mix(0)
    }

    /** Channel-wise colour mix used by the crossfades, including alpha. */
    fun lerpColor(from: Int, to: Int, t: Float): Int {
        val k = t.coerceIn(0f, 1f)
        fun mix(shift: Int): Int {
            val f = (from ushr shift) and 0xFF
            val e = (to ushr shift) and 0xFF
            return ((f + (e - f) * k) + 0.5f).toInt().coerceIn(0, 255)
        }
        return (mix(24) shl 24) or (mix(16) shl 16) or (mix(8) shl 8) or mix(0)
    }

    // ------------------------------------------------------------------------------------------
    // The cover as it shows on screen
    // ------------------------------------------------------------------------------------------

    /**
     * The part of a [bw] x [bh] bitmap that a centre-crop ImageView of [vw] x [vh] shows, as
     * left, top, right, bottom (right/bottom exclusive). A square cover on a tall phone only
     * shows its middle strip, so the brightness check has to look at that strip.
     */
    fun centerCropRect(bw: Int, bh: Int, vw: Int, vh: Int): IntArray {
        if (bw <= 0 || bh <= 0 || vw <= 0 || vh <= 0) return intArrayOf(0, 0, maxOf(bw, 0), maxOf(bh, 0))
        val scale = maxOf(vw.toDouble() / bw, vh.toDouble() / bh)
        val sw = (vw / scale).coerceAtMost(bw.toDouble())
        val sh = (vh / scale).coerceAtMost(bh.toDouble())
        val l = ((bw - sw) / 2.0).toInt()
        val t = ((bh - sh) / 2.0).toInt()
        val r = (l + maxOf(1, sw.toInt())).coerceAtMost(bw)
        val b = (t + maxOf(1, sh.toInt())).coerceAtMost(bh)
        return intArrayOf(l, t, r, b)
    }

    /** Pixels inside [rect] (from [centerCropRect]) of a row-major [w]-wide pixel array. */
    fun crop(pixels: IntArray, w: Int, rect: IntArray): IntArray {
        val (l, t, r, b) = rect.toList()
        val out = IntArray(maxOf(0, (r - l) * (b - t)))
        var i = 0
        for (y in t until b) for (x in l until r) out[i++] = pixels[y * w + x]
        return out
    }

    /** Width of the small copy the software blur works on (older Android without RenderEffect). */
    const val SOFT_BLUR_WIDTH = 96
    /** Box radius for that small copy; 3 passes approximate a Gaussian. */
    const val SOFT_BLUR_RADIUS = 7

    /**
     * RenderEffect blur radius in screen pixels for a screen [widthPx] wide. Much stronger than
     * anything else in the app: the cover becomes a soft colour field, not a picture.
     */
    fun blurRadiusPx(widthPx: Int): Float = (widthPx * 0.09f).coerceIn(48f, 150f)

    // ------------------------------------------------------------------------------------------
    // Lines
    // ------------------------------------------------------------------------------------------

    const val ACTIVE_ALPHA = 1f
    /** Untimed lyrics: one even, readable level for every line, nothing highlighted. */
    const val UNTIMED_ALPHA = 0.92f
    const val INACTIVE_SCALE = 0.95f

    /** How far line [index] is from the [active] one; before the first line, line 0 is one away. */
    fun distance(index: Int, active: Int): Int = abs(index - active)

    /** Opacity of a line [distance] lines away from the active one. */
    fun lineAlpha(distance: Int, synced: Boolean): Float = when {
        !synced -> UNTIMED_ALPHA
        distance == 0 -> ACTIVE_ALPHA
        distance == 1 -> 0.42f
        distance == 2 -> 0.34f
        else -> 0.28f
    }

    fun lineScale(distance: Int, synced: Boolean): Float =
        if (!synced || distance == 0) 1f else INACTIVE_SCALE

    /** The line the list keeps around the centre (line 0 before the song's first line). */
    fun scrollTarget(active: Int, count: Int): Int =
        if (count <= 0) -1 else active.coerceIn(0, count - 1)

    /** Scroll needed to bring a child spanning [top]..[bottom] to the centre of [areaTop]..[areaBottom]. */
    fun centreDelta(top: Int, bottom: Int, areaTop: Int, areaBottom: Int): Int =
        (top + bottom) / 2 - (areaTop + areaBottom) / 2

    /** Side padding: at least [minPx], and lines never wider than [maxLinePx] on big screens. */
    fun sidePadding(widthPx: Int, minPx: Int, maxLinePx: Int): Int =
        maxOf(minPx, (widthPx - maxLinePx) / 2)

    // ------------------------------------------------------------------------------------------
    // Enter / exit: one value [t] from 0 (Now Playing) to 1 (full screen lyrics)
    // ------------------------------------------------------------------------------------------

    private fun smooth(x: Float): Float { val k = x.coerceIn(0f, 1f); return k * k * (3f - 2f * k) }

    /** Now Playing's own elements: gone in the first 55% of the way. */
    fun nowPlayingAlpha(t: Float): Float = 1f - smooth(t / 0.55f)

    /** The blurred cover and its scrim. */
    fun backdropAlpha(t: Float): Float = smooth(t / 0.75f)

    /** The lyric lines arrive last, once Now Playing has mostly gone. */
    fun linesAlpha(t: Float): Float = smooth((t - 0.35f) / 0.65f)

    /** The lines rise this fraction of their lift while arriving (1 = full lift, 0 = in place). */
    fun linesLift(t: Float): Float = 1f - linesAlpha(t)

    /** Status bar icons and the nav bar switch over at the halfway point, like the takeover. */
    fun chromeSwitched(t: Float): Boolean = t >= 0.5f
}
