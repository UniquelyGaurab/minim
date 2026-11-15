package com.minim.music.ui

import com.minim.music.data.share.LyricsCardOptions

/**
 * How lyric text is stacked on a lyrics card (6.7.0). The rows of one wrapped lyric line sit
 * tight together, different lyric lines get a clear gap of about half a row, and in Both mode a
 * translation sits right under its own original with the gap between pairs.
 */
object LyricsCardSpacing {
    /** Row height multiplier inside one wrapped line (tight). */
    const val ROW_MULT = 0.96f
    /** Gap between different lyric lines, as a share of the main row height. */
    const val LINE_GAP = 0.5f
    /** Gap between an original and its own translation (Both mode). */
    const val PAIR_GAP = 0.08f
    /** Translation size against the main text in Both mode. */
    const val SUB_SCALE = 0.62f
    /** 6.7.0: text is about 13% smaller than 6.6.0 (76 → 66 at the largest, 38 → 33 at the smallest). */
    const val MAX_SIZE = 66f
    const val MIN_SIZE = 33f

    enum class Gap { NONE, PAIR, LINE }

    data class Piece(val text: String, val sub: Boolean, val line: Int, val gapAfter: Gap)

    /**
     * The text blocks for the picked lines in reading order. [originals] and [translations] are
     * per picked line; a missing translation shows the original instead in Translation mode.
     */
    fun pieces(
        originals: List<String>,
        translations: List<String?>,
        mode: LyricsCardOptions.Lyrics
    ): List<Piece> {
        val out = ArrayList<Piece>()
        originals.forEachIndexed { i, orig ->
            val tr = translations.getOrNull(i)?.trim()?.takeIf { it.isNotEmpty() }
            when (mode) {
                LyricsCardOptions.Lyrics.ORIGINAL -> out.add(Piece(orig.trim(), false, i, Gap.LINE))
                LyricsCardOptions.Lyrics.TRANSLATION -> out.add(Piece(tr ?: orig.trim(), false, i, Gap.LINE))
                LyricsCardOptions.Lyrics.BOTH -> {
                    if (tr != null) {
                        out.add(Piece(orig.trim(), false, i, Gap.PAIR))
                        out.add(Piece(tr, true, i, Gap.LINE))
                    } else {
                        out.add(Piece(orig.trim(), false, i, Gap.LINE))
                    }
                }
            }
        }
        if (out.isNotEmpty()) out[out.lastIndex] = out.last().copy(gapAfter = Gap.NONE)
        return out
    }

    fun gapPx(gap: Gap, mainRowPx: Float): Float = when (gap) {
        Gap.NONE -> 0f
        Gap.PAIR -> mainRowPx * PAIR_GAP
        Gap.LINE -> mainRowPx * LINE_GAP
    }

    /**
     * Top offset of each piece and the total height. [heights] is each piece's laid-out height
     * (its rows times its row height); the gaps are sized from [mainRowPx].
     */
    fun stack(pieces: List<Piece>, heights: FloatArray, mainRowPx: Float): Pair<FloatArray, Float> {
        val tops = FloatArray(pieces.size)
        var y = 0f
        pieces.forEachIndexed { i, p ->
            tops[i] = y
            y += heights.getOrElse(i) { 0f }
            if (i < pieces.lastIndex) y += gapPx(p.gapAfter, mainRowPx)
        }
        return tops to y
    }

    /**
     * A small, fast blur for the Blurred cover background: three box passes over a tiny copy of
     * the cover (ARGB pixels, in place).
     */
    fun boxBlur(px: IntArray, w: Int, h: Int, radius: Int, passes: Int = 3) {
        if (radius < 1 || w < 1 || h < 1) return
        val tmp = IntArray(px.size)
        repeat(passes) {
            blurPass(px, tmp, w, h, radius, horizontal = true)
            blurPass(tmp, px, w, h, radius, horizontal = false)
        }
    }

    private fun blurPass(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int, horizontal: Boolean) {
        val outer = if (horizontal) h else w
        val inner = if (horizontal) w else h
        for (o in 0 until outer) {
            for (i in 0 until inner) {
                var a = 0; var rr = 0; var g = 0; var b = 0; var n = 0
                for (k in -r..r) {
                    val j = (i + k).coerceIn(0, inner - 1)
                    val c = if (horizontal) src[o * w + j] else src[j * w + o]
                    a += c ushr 24; rr += (c shr 16) and 0xFF; g += (c shr 8) and 0xFF; b += c and 0xFF; n++
                }
                val idx = if (horizontal) o * w + i else i * w + o
                dst[idx] = ((a / n) shl 24) or ((rr / n) shl 16) or ((g / n) shl 8) or (b / n)
            }
        }
    }
}
