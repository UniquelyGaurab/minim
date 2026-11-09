package com.minim.music.ui

/**
 * 5.8.2 (update 54): the recap story's motion maths, kept apart from the views so it's unit tested.
 */
object RecapMotion {

    /** Where each word of [text] starts (a word keeps the spaces before it). */
    fun wordStarts(text: String): List<Int> {
        val out = ArrayList<Int>()
        var i = 0
        while (i < text.length) {
            while (i < text.length && text[i].isWhitespace()) i++
            if (i >= text.length) break
            out.add(i)
            while (i < text.length && !text[i].isWhitespace()) i++
        }
        if (out.isEmpty()) out.add(0)
        return out
    }

    /** How visible word [i] of [count] is at [f] (0..1) of a word-by-word fade. */
    fun wordAlpha(f: Float, i: Int, count: Int): Float {
        if (count <= 0 || f >= 1f) return 1f
        val span = 1f / count
        val start = i * span * 0.85f
        val len = span * 0.85f + 0.15f
        return ((f - start) / len).coerceIn(0f, 1f)
    }

    /** The value a count-up shows at [f] (0..1): whole minutes, never past [target]. */
    fun countValue(target: Long, f: Float): Long {
        if (target <= 0L) return 0L
        if (f >= 1f) return target
        val v = (target * f.coerceIn(0f, 1f)).toLong()
        return (v / 60_000L) * 60_000L
    }

    /** How far bar [hour] of the day chart has grown at [f] (0..1): left to right, staggered. */
    fun barGrow(f: Float, hour: Int): Float {
        val start = hour / 24f * 0.45f
        return ((f - start) / 0.55f).coerceIn(0f, 1f)
    }
}
