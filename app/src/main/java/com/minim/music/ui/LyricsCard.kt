package com.minim.music.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.text.Layout
import android.text.SpannableString
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import androidx.core.graphics.ColorUtils
import com.minim.music.R
import com.minim.music.data.model.LyricsLine
import com.minim.music.data.share.LyricsCardOptions
import com.minim.music.data.share.LyricsVideoPlan
import com.minim.music.data.share.ShareLinks

/**
 * The lyrics card. 1080 x 1920 (9:16): a backdrop (the cover's colours, or the cover blurred),
 * one light card with the picked lines in Poppins bold, sized down only as far as they need to
 * fit; under a hairline, a small cover with the title and artist, then "Shared by <name>" and
 * the minim mark.
 *
 * 6.7.0: smaller text, tight rows inside a wrapped line and a clear gap between lines
 * ([LyricsCardSpacing]); a Blurred cover background; and [Scene], which draws the frames of the
 * animated card: the same word-by-word fill as the in-app lyrics, then a 1-second ending that
 * fades to "minim" with "Shared by <name>" (the only place the name shows in a video).
 */
object LyricsCard {
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
    const val MAX_SIZE = LyricsCardSpacing.MAX_SIZE
    const val MIN_SIZE = LyricsCardSpacing.MIN_SIZE
    /** How lit an unsung word is on the animated card. */
    const val DIM = 0.32f

    /** Whether any picked line has a translation worth showing. */
    fun hasTranslation(lines: List<Line>): Boolean = lines.any { usable(it) != null }

    private fun usable(l: Line): String? =
        l.translation?.trim()?.takeIf { it.isNotEmpty() && !it.equals(l.text.trim(), ignoreCase = true) }

    /** The blocks the card draws, as (big text, small text under it or null). */
    fun blocks(lines: List<Line>, mode: LyricsCardOptions.Lyrics): List<Pair<String, String?>> = lines.map { l ->
        val t = usable(l)
        when (mode) {
            LyricsCardOptions.Lyrics.ORIGINAL -> l.text.trim() to null
            LyricsCardOptions.Lyrics.TRANSLATION -> (t ?: l.text.trim()) to null
            LyricsCardOptions.Lyrics.BOTH -> l.text.trim() to t
        }
    }

    /** The pieces in reading order, with the gaps between them. */
    fun pieces(lines: List<Line>, mode: LyricsCardOptions.Lyrics): List<LyricsCardSpacing.Piece> =
        LyricsCardSpacing.pieces(lines.map { it.text }, lines.map { usable(it) }, mode)

    /**
     * The biggest text size, from [MAX_SIZE] down to [MIN_SIZE], at which the text fits in [maxH].
     * [measure] gives the height of the text at a size (the renderer lays it out; tests fake it).
     */
    fun fitSize(maxH: Float, measure: (Float) -> Float): Float {
        var size = MAX_SIZE
        while (size > MIN_SIZE && measure(size) > maxH) size -= 2f
        return size.coerceAtLeast(MIN_SIZE)
    }

    /** The static card. */
    fun render(
        context: Context,
        pal: Pal,
        lines: List<Line>,
        mode: LyricsCardOptions.Lyrics,
        title: String,
        artist: String,
        sharer: String?,
        cover: Bitmap?,
        background: LyricsCardOptions.Background = LyricsCardOptions.Background.COLOURS
    ): Bitmap {
        val scene = Scene(context, pal, lines, mode, title, artist, sharer, cover, background, video = false)
        val bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        scene.drawBase(c)
        scene.drawText(c)
        return bmp
    }

    /** Per picked line: when it starts and how long it flows, on the lyric timeline. */
    data class Timing(val startMs: Long, val durMs: Long, val source: LyricsLine?)

    /**
     * The pieces of a card, laid out once. For a static card [drawBase] + [drawText]; for a video
     * frame [drawFrame], which lights the words for a time and fades into the ending.
     */
    class Scene(
        context: Context,
        private val pal: Pal,
        lines: List<Line>,
        private val mode: LyricsCardOptions.Lyrics,
        title: String,
        artist: String,
        sharer: String?,
        cover: Bitmap?,
        private val background: LyricsCardOptions.Background,
        private val video: Boolean
    ) {
        private val bold = ShareCard.font(context, R.font.poppins_bold, Typeface.DEFAULT_BOLD)
        private val semi = ShareCard.font(context, R.font.poppins_semibold, Typeface.DEFAULT_BOLD)
        private val medium = ShareCard.font(context, R.font.poppins_medium, Typeface.DEFAULT)
        private val regular = ShareCard.font(context, R.font.poppins_regular, Typeface.DEFAULT)
        private val ctx = context.applicationContext ?: context
        private val title = title
        private val artist = artist
        private val sharer = ShareLinks.cleanName(sharer)
        private val cover = cover

        private val cardColor = pal.fg or 0xFF000000.toInt()
        private val bgTone: Int = if (background == LyricsCardOptions.Background.BLUR && cover != null)
            blurTone(cover) else (pal.bg or 0xFF000000.toInt())
        private val ink = RecapInk.text(cardColor, bgTone)
        private val muted = RecapInk.readable(cardColor, pal.muted, ink)

        private class Laid(
            val piece: LyricsCardSpacing.Piece,
            val layout: StaticLayout,
            val top: Float,
            val spans: List<FlowWordSpan>,
            val words: List<FlowTiming.Word>
        )

        private val laid: List<Laid>
        private val textH: Float
        val card: RectF

        init {
            val pieces = LyricsCardSpacing.pieces(lines.map { it.text }, lines.map { usableOf(it) }, mode)
            val size = fitSize(MAX_TEXT_H) { s -> layOut(pieces, s).second }
            val (l, h) = layOut(pieces, size)
            laid = l
            textH = h
            val cardH = PAD + textH.coerceAtLeast(COVER) + BOTTOM_H
            val left = (W - CARD_W) / 2f
            val top = ((H - cardH) / 2f - 20f).coerceAtLeast(MARGIN_Y / 2f)
            card = RectF(left, top, left + CARD_W, top + cardH)
        }

        private fun usableOf(l: Line): String? =
            l.translation?.trim()?.takeIf { it.isNotEmpty() && !it.equals(l.text.trim(), ignoreCase = true) }

        private fun layOut(pieces: List<LyricsCardSpacing.Piece>, size: Float): Pair<List<Laid>, Float> {
            val big = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = size; typeface = bold; color = ink }
            val small = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                textSize = size * LyricsCardSpacing.SUB_SCALE; typeface = regular; color = muted
            }
            val fm = big.fontMetrics
            val mainRow = (fm.descent - fm.ascent) * LyricsCardSpacing.ROW_MULT
            val built = pieces.map { p ->
                val text = SpannableString(p.text)
                val words = if (video) FlowTiming.splitWords(p.text) else emptyList()
                val spans = ArrayList<FlowWordSpan>()
                if (video) {
                    // One span per word (word-by-word fill), or one for the whole piece (whole-line light-up)
                    if (wordByWord(p)) {
                        for (w in words) FlowWordSpan().also {
                            it.level = DIM
                            text.setSpan(it, w.start, w.end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                            spans += it
                        }
                    } else if (p.text.isNotEmpty()) {
                        FlowWordSpan().also {
                            it.level = DIM
                            text.setSpan(it, 0, p.text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                            spans += it
                        }
                    }
                }
                val layout = if (p.sub) layout(text, small, 1f) else layout(text, big, LyricsCardSpacing.ROW_MULT)
                Triple(p, layout, spans to words)
            }
            val heights = FloatArray(built.size) { built[it].second.height.toFloat() }
            val (tops, total) = LyricsCardSpacing.stack(pieces, heights, mainRow)
            return built.mapIndexed { i, (p, l, sw) -> Laid(p, l, tops[i], sw.first, sw.second) } to total
        }

        /** Original text fills word by word; translations light up a whole line at a time. */
        private fun wordByWord(p: LyricsCardSpacing.Piece): Boolean =
            !p.sub && mode != LyricsCardOptions.Lyrics.TRANSLATION

        /** Backdrop, card, hairline, cover row and footer: everything but the lyric text. */
        fun drawBase(c: Canvas) {
            drawBackdrop(c)
            ShareCard.drawCardShape(c, card, cardColor)
            val x = card.left + PAD
            val right = card.right - PAD
            var y = card.top + PAD + textH.coerceAtLeast(COVER) + 56f
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
            // A video keeps "Shared by" for its ending only
            ShareCard.drawFooter(ctx, c, x, right, art.bottom + 92f, if (video) null else sharer, medium, semi, ink, muted)
        }

        fun drawText(c: Canvas) {
            val x = card.left + PAD
            for (l in laid) {
                c.save()
                c.translate(x, card.top + PAD + l.top)
                l.layout.draw(c)
                c.restore()
            }
        }

        /** The backdrop on its own (also behind the ending). */
        fun drawBackdrop(c: Canvas) {
            if (background == LyricsCardOptions.Background.BLUR && cover != null) drawBlur(c, cover)
            else ShareCard.drawBackdrop(c, pal)
        }

        /** The ending: the backdrop with "minim" and "Shared by <name>" (just "minim" when blank). */
        fun drawEnding(c: Canvas) {
            drawBackdrop(c)
            val endInk = RecapInk.text(bgTone, cardColor)
            val mark = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 150f; typeface = semi; color = endInk }
            val cy = H * 0.47f
            val markW = mark.measureText("minim")
            c.drawText("minim", (W - markW) / 2f, cy + 40f, mark)
            if (sharer != null) {
                ShareCard.centered(
                    c, "Shared by $sharer", W / 2f, cy + 150f, 46f, medium,
                    ColorUtils.setAlphaComponent(endInk, 205), (W - 2 * PAD.toInt() * 2)
                )
            }
        }

        /** Sets each word's level for a lyric-timeline time. */
        fun setTime(nowMs: Long, timing: List<Timing>) {
            for (l in laid) {
                val t = timing.getOrNull(l.piece.line) ?: continue
                if (wordByWord(l.piece) && l.spans.size == l.words.size && l.spans.isNotEmpty()) {
                    val bounds = boundsFor(l, t)
                    val since = nowMs - t.startMs
                    l.spans.forEachIndexed { i, s -> s.level = DIM + (1 - DIM) * FlowTiming.wordFill(bounds, i, since) }
                } else {
                    val lv = LyricsVideoPlan.lineLevel(nowMs, t.startMs)
                    l.spans.forEach { it.level = DIM + (1 - DIM) * lv }
                }
            }
        }

        private val boundsCache = HashMap<Int, LongArray>()
        private fun boundsFor(l: Laid, t: Timing): LongArray =
            boundsCache.getOrPut(System.identityHashCode(l)) {
                t.source?.let { FlowTiming.realWordBounds(it, l.words, t.durMs) }
                    ?: FlowTiming.wordBounds(IntArray(l.words.size) { l.words[it].weight }, t.durMs)
            }

        // ---- video frames ----
        private var baseFrame: Bitmap? = null
        private var endFrame: Bitmap? = null
        private val fadePaint = Paint(Paint.FILTER_BITMAP_FLAG)

        /** Draws video frame [frameMs] of [plan] into [c] (1080 x 1920). */
        fun drawFrame(c: Canvas, plan: LyricsVideoPlan.Plan, timing: List<Timing>, frameMs: Long) {
            val base = baseFrame ?: Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888).also { drawBase(Canvas(it)); baseFrame = it }
            val lyricMs = plan.lyricStartMs + frameMs.coerceAtMost(plan.lyricsMs)
            c.drawBitmap(base, 0f, 0f, null)
            setTime(lyricMs, timing)
            drawText(c)
            val into = frameMs - plan.lyricsMs
            if (into >= 0) {
                val end = endFrame ?: Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888).also { drawEnding(Canvas(it)); endFrame = it }
                val x = (into.toFloat() / LyricsVideoPlan.FADE_TO_END_MS).coerceIn(0f, 1f)
                fadePaint.alpha = (255 * x * x * (3 - 2 * x)).toInt()
                c.drawBitmap(end, 0f, 0f, fadePaint)
            }
        }

        fun recycle() {
            baseFrame?.recycle(); baseFrame = null
            endFrame?.recycle(); endFrame = null
        }
    }

    /** Timings for the picked lines from a plan. */
    fun timings(plan: LyricsVideoPlan.Plan, sources: List<LyricsLine?>): List<Timing> =
        plan.lineStarts.indices.map { Timing(plan.lineStarts[it], plan.lineDurs[it], sources.getOrNull(it)) }

    // ---- Blurred cover ----

    private const val BLUR_PX = 40

    private fun tiny(cover: Bitmap): Bitmap {
        val small = Bitmap.createScaledBitmap(cover, BLUR_PX, BLUR_PX, true)
        val px = IntArray(BLUR_PX * BLUR_PX)
        small.getPixels(px, 0, BLUR_PX, 0, 0, BLUR_PX, BLUR_PX)
        LyricsCardSpacing.boxBlur(px, BLUR_PX, BLUR_PX, 3)
        val out = Bitmap.createBitmap(BLUR_PX, BLUR_PX, Bitmap.Config.ARGB_8888)
        out.setPixels(px, 0, BLUR_PX, 0, 0, BLUR_PX, BLUR_PX)
        if (small !== cover) small.recycle()
        return out
    }

    /** The colour the blurred backdrop reads as (its average, darkened like the overlay). */
    private fun blurTone(cover: Bitmap): Int {
        val one = Bitmap.createScaledBitmap(cover, 1, 1, true)
        val avg = one.getPixel(0, 0) or 0xFF000000.toInt()
        if (one !== cover) one.recycle()
        return ColorUtils.blendARGB(avg, Color.BLACK, 0.42f)
    }

    private fun drawBlur(c: Canvas, cover: Bitmap) {
        val t = tiny(cover)
        // centre-crop the square onto the tall card, a little zoomed so the edges never show
        val side = H * 1.08f
        val left = (W - side) / 2f
        val top = (H - side) / 2f
        c.drawBitmap(t, Rect(0, 0, BLUR_PX, BLUR_PX), RectF(left, top, left + side, top + side), Paint(Paint.FILTER_BITMAP_FLAG))
        t.recycle()
        // darken it so the card and the ending stand out, a bit more at the bottom
        c.drawRect(0f, 0f, W.toFloat(), H.toFloat(), Paint().apply {
            shader = LinearGradient(
                0f, 0f, 0f, H.toFloat(),
                intArrayOf(ColorUtils.setAlphaComponent(Color.BLACK, 84), ColorUtils.setAlphaComponent(Color.BLACK, 140)),
                null, Shader.TileMode.CLAMP
            )
        })
    }

    private fun layout(s: CharSequence, p: TextPaint, spacing: Float): StaticLayout =
        StaticLayout.Builder.obtain(s, 0, s.length, p, TEXT_W)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(0f, spacing)
            .setIncludePad(false)
            .setMaxLines(8)
            .build()
}
