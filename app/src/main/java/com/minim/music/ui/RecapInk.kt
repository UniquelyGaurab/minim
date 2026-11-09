package com.minim.music.ui

/**
 * 5.8.3 (update 55): text colours for the recap story and card. The recap has no blurred cover
 * behind anything any more, only solid colours from the covers, and every label (even the faint
 * ones like "Your #1", artist names, hints and "my day in music") is pushed toward the main text
 * colour until it reads clearly on its background. Pure logic (unit tested).
 */
object RecapInk {
    /** Faint labels: WCAG AA for normal text. */
    const val LABEL_MIN = 4.5

    /** Main text. */
    const val TEXT_MIN = 7.0

    fun contrast(a: Int, b: Int): Double = FullscreenLyricsLook.contrast(a, b)

    /**
     * [ink] if it already reads on [bg] at [min]; else [ink] moved step by step toward [toward]
     * (the slide's main text colour) until it does. Falls back to black or white, whichever reads.
     */
    fun readable(bg: Int, ink: Int, toward: Int, min: Double = LABEL_MIN): Int {
        val solidBg = bg or 0xFF000000.toInt()
        if (contrast(ink, solidBg) >= min) return ink
        var t = 0f
        while (t < 1f) {
            t += 0.05f
            val c = FullscreenLyricsLook.blend(ink, toward, t.coerceAtMost(1f))
            if (contrast(c, solidBg) >= min) return c
        }
        if (contrast(toward, solidBg) >= min) return toward
        val black = 0xFF000000.toInt()
        val white = 0xFFFFFFFF.toInt()
        return if (contrast(black, solidBg) >= contrast(white, solidBg)) black else white
    }

    /** [ink] moved toward [toward] until it reads on every one of [bgs] at [min]. */
    fun readableOn(bgs: List<Int>, ink: Int, toward: Int, min: Double = LABEL_MIN): Int {
        fun ok(c: Int) = bgs.all { contrast(c, it or 0xFF000000.toInt()) >= min }
        if (ok(ink)) return ink
        var t = 0f
        while (t < 1f) {
            t += 0.05f
            val c = FullscreenLyricsLook.blend(ink, toward, t.coerceAtMost(1f))
            if (ok(c)) return c
        }
        if (ok(toward)) return toward
        val black = 0xFF000000.toInt()
        val white = 0xFFFFFFFF.toInt()
        fun worst(c: Int) = bgs.minOf { contrast(c, it or 0xFF000000.toInt()) }
        return if (worst(black) >= worst(white)) black else white
    }

    /** The main text colour, made sure to read on [bg]. */
    fun text(bg: Int, fg: Int): Int = readable(bg, fg, if (contrast(0xFF000000.toInt(), bg or 0xFF000000.toInt()) >= contrast(0xFFFFFFFF.toInt(), bg or 0xFF000000.toInt())) 0xFF000000.toInt() else 0xFFFFFFFF.toInt(), TEXT_MIN)

    /** The main text colour, made sure to read on all of [bgs] (7:1 where the colours allow it). */
    fun textOn(bgs: List<Int>, fg: Int): Int {
        val black = 0xFF000000.toInt()
        val white = 0xFFFFFFFF.toInt()
        fun worst(c: Int) = bgs.minOf { contrast(c, it or black) }
        val toward = if (worst(black) >= worst(white)) black else white
        val target = minOf(TEXT_MIN, worst(toward))
        return readableOn(bgs, fg, toward, target - 0.001)
    }

    /**
     * A second solid shade of [bg] toward [fg], for the shapes behind a slide's words: as strong
     * as 16 % but never so strong that [fg] stops reading on it.
     */
    fun tone(bg: Int, fg: Int): Int {
        var t = 0.16f
        while (t > 0.01f) {
            val c = androidx.core.graphics.ColorUtils.blendARGB(bg, fg, t) or 0xFF000000.toInt()
            if (contrast(fg, c) >= LABEL_MIN + 0.2) return c
            t -= 0.02f
        }
        return bg or 0xFF000000.toInt()
    }
}
