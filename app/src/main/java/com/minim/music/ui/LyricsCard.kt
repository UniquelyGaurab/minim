package com.minim.music.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.core.graphics.ColorUtils
import com.minim.music.R

/**
 * 6.6.0 (update 68): the lyrics card. 1080 x 1920 (9:16), drawn like [ShareCard]: the same gradient
 * from the cover's colours and one light card. On it, the picked lines big in Poppins bold, sized
 * down only as far as they need to fit; under a hairline, a small cover with the title and artist,
 * then "Shared by <name>" (left out when blank) and the minim mark.
 *
 * [Mode] picks the words: the original lines, the translation, or each line with its translation
 * under it, smaller. A line with no translation (it's already in the language) stays as it is.
 */
object LyricsCard {
    enum class Mode { ORIGINAL, TRANSLATION, BOTH }

    /** One picked line: [text] as sung, [translation] when there is one. */
    data class Line(val text: String, val translation: String? = null)

    const val W = ShareCard.W
    const val H = ShareCard.H
    private const val CARD_W = 880f
    private const val PAD = 64f
    private const val TEXT_W = (CARD_W - 2 * PAD).toInt()
    private const val MARGIN_Y = 200f
    private const val COVER = 116f
    /** The bottom part under the lyrics: gap, hairline, cover row, footer row, padding. */
    private const val BOTTOM_H = 56f + 2f + 44f + COVER + 92f + PAD
    private const val MAX_TEXT_H = H - 2 * MARGIN_Y - PAD - BOTTOM_H
    const val MAX_SIZE = 76f
    const val MIN_SIZE = 38f

    /** Whether any picked line has a translation worth showing. */
    fun hasTranslation(lines: List<Line>): Boolean = lines.any { usable(it) != null }

    private fun usable(l: Line): String? =
        l.translation?.trim()?.takeIf { it.isNotEmpty() && !it.equals(l.text.trim(), ignoreCase = true) }

    /** The blocks the card draws, as (big text, small text under it or null). */
    fun blocks(lines: List<Line>, mode: Mode): List<Pair<String, String?>> = lines.map { l ->
        val t = usable(l)
        when (mode) {
            Mode.ORIGINAL -> l.text.trim() to null
            Mode.TRANSLATION -> (t ?: l.text.trim()) to null
            Mode.BOTH -> l.text.trim() to t
        }
    }

    /**
     * The biggest text size, from [MAX_SIZE] down to [MIN_SIZE], at which [blocks] fit in [maxH].
     * [measure] gives the height of the blocks at a size (the renderer lays them out; tests fake it).
     */
    fun fitSize(maxH: Float, measure: (Float) -> Float): Float {
        var size = MAX_SIZE
        while (size > MIN_SIZE && measure(size) > maxH) size -= 2f
        return size.coerceAtLeast(MIN_SIZE)
    }

    fun render(
        context: Context,
        pal: Pal,
        lines: List<Line>,
        mode: Mode,
        title: String,
        artist: String,
        sharer: String?,
        cover: Bitmap?
    ): Bitmap {
        val bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val bold = ShareCard.font(context, R.font.poppins_bold, Typeface.DEFAULT_BOLD)
        val semi = ShareCard.font(context, R.font.poppins_semibold, Typeface.DEFAULT_BOLD)
        val medium = ShareCard.font(context, R.font.poppins_medium, Typeface.DEFAULT)
        val regular = ShareCard.font(context, R.font.poppins_regular, Typeface.DEFAULT)

        val bg = ShareCard.drawBackdrop(c, pal)
        val cardColor = pal.fg or 0xFF000000.toInt()
        val ink = RecapInk.text(cardColor, bg)
        val muted = RecapInk.readable(cardColor, pal.muted, ink)

        // Lay the words out at the biggest size that fits
        val blocks = blocks(lines, mode)
        val size = fitSize(MAX_TEXT_H) { s -> layouts(blocks, s, bold, regular, ink, muted).second }
        val (laid, textH) = layouts(blocks, size, bold, regular, ink, muted)

        val cardH = PAD + textH.coerceAtLeast(COVER) + BOTTOM_H
        val left = (W - CARD_W) / 2f
        val top = ((H - cardH) / 2f - 20f).coerceAtLeast(MARGIN_Y / 2f)
        val card = RectF(left, top, left + CARD_W, top + cardH)
        ShareCard.drawCardShape(c, card, cardColor)

        // The lines
        val x = card.left + PAD
        var y = card.top + PAD
        for ((layout, gapAfter) in laid) {
            c.save()
            c.translate(x, y)
            layout.draw(c)
            c.restore()
            y += layout.height + gapAfter
        }

        // Hairline, then the small cover with the title and artist
        val right = card.right - PAD
        y = card.top + PAD + textH.coerceAtLeast(COVER) + 56f
        c.drawRect(x, y, right, y + 2f, Paint().apply { color = ColorUtils.setAlphaComponent(ink, 34) })
        y += 2f + 44f
        val art = RectF(x, y, x + COVER, y + COVER)
        if (cover != null) {
            ShareCard.roundImage(c, cover, art, 24f)
        } else {
            c.drawRoundRect(art, 24f, 24f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = ColorUtils.blendARGB(cardColor, ink, 0.08f)
            })
            ShareCard.centered(c, "♪", art.centerX(), art.centerY() + 22f, 64f, bold, muted, 100)
        }
        val textLeft = art.right + 32f
        val textW = (right - textLeft).toInt()
        ShareCard.start(c, title, textLeft, art.centerY() - 6f, 44f, bold, ink, textW)
        ShareCard.start(c, artist, textLeft, art.centerY() + 44f, 32f, regular, muted, textW)

        // "Shared by <name>" and the minim mark, like the song card
        ShareCard.drawFooter(context, c, x, right, art.bottom + 92f, sharer, medium, semi, ink, muted)
        return bmp
    }

    /** Each block laid out (big line, then its translation), with the gap after it, and the total height. */
    private fun layouts(
        blocks: List<Pair<String, String?>>, size: Float, bold: Typeface, regular: Typeface, ink: Int, muted: Int
    ): Pair<List<Pair<StaticLayout, Float>>, Float> {
        val big = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = size; typeface = bold; color = ink }
        val small = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = size * 0.6f; typeface = regular; color = muted }
        val out = ArrayList<Pair<StaticLayout, Float>>()
        val lineGap = size * 0.32f
        val subGap = size * 0.12f
        blocks.forEachIndexed { i, (main, sub) ->
            val last = i == blocks.lastIndex
            out += layout(main, big, 1.08f) to (if (sub != null) subGap else if (last) 0f else lineGap)
            if (sub != null) out += layout(sub, small, 1.1f) to (if (last) 0f else lineGap * 1.3f)
        }
        val h = out.sumOf { (l, gap) -> (l.height + gap).toDouble() }.toFloat()
        return out to h
    }

    private fun layout(s: String, p: TextPaint, spacing: Float): StaticLayout =
        StaticLayout.Builder.obtain(s, 0, s.length, p, TEXT_W)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(0f, spacing)
            .setIncludePad(false)
            .setMaxLines(8)
            .build()
}
