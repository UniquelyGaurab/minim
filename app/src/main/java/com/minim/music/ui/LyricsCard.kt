package com.minim.music.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
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
 * one light 4:5 card with the picked lines in Poppins bold, sized down only as far as they need
 * to fit, centred in the space above a hairline; under it the footer, pinned to the card's
 * bottom, with the minim mark in the bottom-right corner.
 *
 * 6.7.0: smaller text, tight rows inside a wrapped line and a clear gap between lines
 * ([LyricsCardSpacing]); a Blurred cover background; and [Scene], which draws the frames of the
 * animated card: the same word-by-word fill as the in-app lyrics, then a 1-second ending that
 * fades to "minim" with "Shared by <name>" (the only place the name shows in a video).
 *
 * 6.7.0 revision 3: the card is always 4:5 (wider, still 4:5, only when the text can't fit);
 * the footer is one row [cover · title/artist … minim] on animated cards and static cards without
 * a name, two rows (cover row, then "Shared by" + minim) on static cards with one; and on
 * animated cards only the line being sung is lit, finished lines dim back down.
 */
object LyricsCard {
    /** One picked line: [text] as sung, [translation] when there is one. */
    data class Line(val text: String, val translation: String? = null)

    const val W = ShareCard.W
    const val H = ShareCard.H
    /**
     * 6.7.0 revision 3: the card is always 4:5 (portrait), whatever the lines, the mode, the type
     * or the background: about 68% of the width (734 x 918), centred, with backdrop on all sides.
     */
    const val CARD_SHARE = 0.68f
    /** When the lines don't fit even at the smallest text, the card widens (still 4:5) up to this. */
    const val MAX_CARD_SHARE = 0.80f
    /** Height over width. */
    const val ASPECT = 1.25f
    const val CARD_W = W * CARD_SHARE
    const val CARD_H = CARD_W * ASPECT
    /** Everything inside the card is sized from the 68% card (also on a widened one). */
    const val SCALE = CARD_W / 880f
    const val PAD = 64f * SCALE
    private const val COVER = 116f * SCALE
    /** The cover in a one-row footer, next to minim. */
    private const val SMALL_COVER = 88f * SCALE
    /** Footer: "Shared by" and the minim mark, scaled down a bit more than the rest. */
    const val FOOTER_SCALE = SCALE * 0.86f
    /** Card bottom to the bottom of the lyric space with two footer rows (cover row, then "Shared by" + minim). */
    const val RESERVE_TWO_ROWS = PAD + (84f + 116f + 46f + 56f) * SCALE
    /** The same with one footer row: cover, title and artist beside minim. */
    const val RESERVE_ONE_ROW = PAD + 14f * FOOTER_SCALE + (44f + 46f + 56f) * SCALE
    const val MAX_SIZE = LyricsCardSpacing.MAX_SIZE * SCALE
    /** Auto-size can go smaller on the smaller card, but never under [MIN_TEXT_PX] (stays readable). */
    const val MIN_TEXT_PX = 28f
    val MIN_SIZE: Float = maxOf(LyricsCardSpacing.MIN_SIZE * SCALE, MIN_TEXT_PX)
    /** Only when the widest card still can't hold the lines at [MIN_SIZE]: smaller rather than cut. */
    const val LAST_RESORT_PX = 20f
    /** 30/45/60 cards: text at most this share of the biggest size. */
    const val SCROLL_SIZE = 0.8f
    /** The ending: "minim" about half its 6.7.0 size (150 px), "Shared by" smaller and lighter. */
    const val END_MARK_SIZE = 76f
    const val END_NAME_SIZE = 30f
    const val END_NAME_ALPHA = 150
    /** How lit an unsung word (and a finished line) is on the animated card. */
    const val DIM = 0.32f
    /** How long a finished line takes to dim back down. */
    const val PAST_FADE_MS = 300L

    /** The 4:5 card for a card [share] of the width, centred on the canvas. */
    fun cardRect(share: Float = CARD_SHARE): RectF {
        val w = W * share
        val h = w * ASPECT
        val left = (W - w) / 2f
        val top = (H - h) / 2f
        return RectF(left, top, left + w, top + h)
    }

    /** Width the lyrics wrap at on a card [share] of the width. */
    fun textWidth(share: Float): Int = (W * share - 2 * PAD).toInt()

    /** Height of the lyric space (card top padding to the gap above the divider). */
    fun lyricSpace(share: Float, oneRow: Boolean): Float =
        W * share * ASPECT - PAD - if (oneRow) RESERVE_ONE_ROW else RESERVE_TWO_ROWS

    /** How wide the card is ([share] of the canvas) and the text size the lines get. */
    data class Fit(val share: Float, val size: Float)

    /**
     * Text shrinks from [MAX_SIZE] only as far as it has to, down to [MIN_SIZE]. When it still
     * doesn't fit, the card widens (staying 4:5) just enough, up to [MAX_CARD_SHARE]; never cut.
     * [measure] gives the text's height at a size and wrap width.
     */
    fun fitCard(oneRow: Boolean, measure: (Float, Int) -> Float): Fit {
        val baseW = textWidth(CARD_SHARE)
        val baseH = lyricSpace(CARD_SHARE, oneRow)
        if (measure(MIN_SIZE, baseW) <= baseH) return Fit(CARD_SHARE, fitSize(baseH) { measure(it, baseW) })
        var step = 1
        while (true) {
            val share = minOf(MAX_CARD_SHARE, CARD_SHARE + step * 0.005f)
            val tw = textWidth(share)
            val space = lyricSpace(share, oneRow)
            if (measure(MIN_SIZE, tw) <= space) return Fit(share, fitSize(space) { measure(it, tw) })
            if (share >= MAX_CARD_SHARE) {
                var size = MIN_SIZE
                while (size > LAST_RESORT_PX && measure(size, tw) > space) size -= 1f
                return Fit(share, size.coerceAtLeast(LAST_RESORT_PX))
            }
            step++
        }
    }

    /** How far a finished line has dimmed back down: 0 while it's sung, 1 [PAST_FADE_MS] after it ends. */
    fun pastFade(nowMs: Long, endMs: Long?): Float {
        if (endMs == null) return 0f
        val x = ((nowMs - endMs).toFloat() / PAST_FADE_MS).coerceIn(0f, 1f)
        return x * x * (3 - 2 * x)
    }

    /**
     * When line [i] of [timing] is done: when its words finish (never after the next line starts).
     * The last line on the card has no next one and stays lit into the ending.
     */
    fun lineEndMs(timing: List<Timing>, i: Int): Long? {
        if (i !in timing.indices || i + 1 >= timing.size) return null
        val t = timing[i]
        return minOf(t.startMs + t.durMs, timing[i + 1].startMs)
    }

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
     * Where the footer's parts sit. [oneRow]: cover, title and artist on minim's row (animated
     * cards, and static cards without a name); else the cover row, then "Shared by" + minim.
     */
    class Footer(
        val oneRow: Boolean,
        val divider: Float,
        val cover: RectF,
        /** minim's baseline and the mark (logo + "minim") bounds. */
        val markBaseline: Float,
        val mark: RectF,
        val textLeft: Float,
        /** Title and artist end with "…" before this. */
        val textLimit: Float,
        val titleBaseline: Float,
        val artistBaseline: Float,
        val titleSize: Float,
        val artistSize: Float,
        val shownTitle: String,
        val shownArtist: String,
        /** Right edges of the title and artist as drawn. */
        val titleRight: Float,
        val artistRight: Float,
        /** "Shared by <name>" on minim's row (two-row footer only). */
        val sharedBy: String?,
        val sharedByRight: Float
    )

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
        private val video: Boolean,
        /** 30/45/60: how many of [lines] are the picked ones; the rest scroll in after them. */
        private val pickedCount: Int = lines.size,
        /** 30/45/60: the card is a small lyrics screen that scrolls. */
        private val scrolls: Boolean = false
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
        /** The lyric area's height: the text, or the scrolling window. */
        private val textH: Float
        /** The 4:5 card. */
        val card: RectF
        /** The card's width as a share of the canvas: [CARD_SHARE], more only when the lines need it. */
        val cardShare: Float
        /** The text size the lines were laid out at. */
        val textSize: Float
        /** Cover, title and artist on minim's row: animated cards, and static cards without a name. */
        val oneRowFooter: Boolean = video || this.sharer == null
        /** The lyric space: top of the text area and its height. */
        val lyricTop: Float
        val lyricSpace: Float
        /** Where the (non-scrolling) text starts: centred in the lyric space. */
        val textTop: Float
        /** The text's own height (all of it). */
        val textHeight: Float get() = fullH
        private val textW: Int
        val footer: Footer

        // ---- the scrolling window (30/45/60) ----
        private val fullH: Float
        private val pickedH: Float
        private val lineTops: FloatArray
        private val lineBottoms: FloatArray
        /** Height of the scrolling window (the lyric area) on a 30/45/60 card. */
        val windowH: Float get() = textH
        private var scrollY = 0f

        init {
            val pieces = LyricsCardSpacing.pieces(lines.map { it.text }, lines.map { usableOf(it) }, mode)
            val picked = if (scrolls) pieces.filter { it.line < pickedCount } else pieces
            val pickedPieces = picked.mapIndexed { i, p -> if (i == picked.lastIndex) p.copy(gapAfter = LyricsCardSpacing.Gap.NONE) else p }
            val fit = fitCard(oneRowFooter) { s, w -> layOut(pickedPieces, s, w).second }
            cardShare = fit.share
            // a scrolling card is a small lyrics screen: a bit smaller so 4-5 lines show at once
            val size = if (scrolls) minOf(fit.size, MAX_SIZE * SCROLL_SIZE) else fit.size
            textSize = size
            textW = textWidth(fit.share)
            val (l, h) = layOut(pieces, size, textW)
            laid = l
            fullH = h
            val n = lines.size
            lineTops = FloatArray(n) { Float.MAX_VALUE }
            lineBottoms = FloatArray(n)
            for (x in l) {
                val i = x.piece.line
                if (i !in 0 until n) continue
                lineTops[i] = minOf(lineTops[i], x.top)
                lineBottoms[i] = maxOf(lineBottoms[i], x.top + x.layout.height)
            }
            for (i in 0 until n) if (lineTops[i] == Float.MAX_VALUE) {
                lineTops[i] = if (i > 0) lineBottoms[i - 1] else 0f
                lineBottoms[i] = lineTops[i]
            }
            pickedH = if (pickedCount in 1..n) lineBottoms[pickedCount - 1] else h
            card = cardRect(fit.share)
            lyricTop = card.top + PAD
            lyricSpace = LyricsCard.lyricSpace(fit.share, oneRowFooter)
            // 30/45/60: the scrolling window fills the lyric space; else the text sits in its middle
            textH = if (scrolls) lyricSpace else h
            textTop = if (scrolls) lyricTop else lyricTop + ((lyricSpace - h) / 2f).coerceAtLeast(0f)
            footer = layOutFooter()
        }

        private fun usableOf(l: Line): String? =
            l.translation?.trim()?.takeIf { it.isNotEmpty() && !it.equals(l.text.trim(), ignoreCase = true) }

        private fun markPaint() = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 38f * FOOTER_SCALE; typeface = medium; color = ink
        }

        private fun layOutFooter(): Footer {
            val x = card.left + PAD
            val right = card.right - PAD
            // minim: bottom-right corner, the same spot and size on every card
            val baseline = card.bottom - PAD
            val markW = markPaint().measureText("minim")
            val markLeft = right - markW
            val logoSize = 60f * FOOTER_SCALE
            val logo = RectF(markLeft - 16f * FOOTER_SCALE - logoSize, baseline - 44f * FOOTER_SCALE,
                markLeft - 16f * FOOTER_SCALE, baseline - 44f * FOOTER_SCALE + logoSize)
            val mark = RectF(logo.left, logo.top, right, logo.bottom)
            val markCy = logo.centerY()
            val art: RectF
            val titleBase: Float
            val artistBase: Float
            val titleSize: Float
            val artistSize: Float
            val limit: Float
            if (oneRowFooter) {
                art = RectF(x, markCy - SMALL_COVER / 2f, x + SMALL_COVER, markCy + SMALL_COVER / 2f)
                titleSize = 40f * SCALE
                artistSize = 30f * SCALE
                titleBase = markCy - 7f * SCALE
                artistBase = markCy + 31f * SCALE
                limit = mark.left - 24f * SCALE
            } else {
                art = RectF(x, baseline - 84f * SCALE - COVER, x + COVER, baseline - 84f * SCALE)
                titleSize = 44f * SCALE
                artistSize = 32f * SCALE
                titleBase = art.centerY() - 6f * SCALE
                artistBase = art.centerY() + 44f * SCALE
                limit = right
            }
            val textLeft = art.right + (if (oneRowFooter) 28f else 32f) * SCALE
            val maxW = (limit - textLeft).coerceAtLeast(0f)
            val tp = ShareCard.paint(titleSize, bold, ink)
            val ap = ShareCard.paint(artistSize, regular, muted)
            val t = fitText(title.trim(), tp, maxW)
            val a = fitText(artist.trim(), ap, maxW)
            val shared = if (oneRowFooter) null else sharer?.let { name ->
                val sp = ShareCard.paint(30f * FOOTER_SCALE, regular, muted)
                val w = (logo.left - 28f * FOOTER_SCALE - x).coerceAtLeast(0f)
                fitText("Shared by $name", sp, w).takeIf { it.isNotEmpty() }
            }
            val sharedRight = shared?.let { x + ShareCard.paint(30f * FOOTER_SCALE, regular, muted).measureText(it) } ?: x
            return Footer(
                oneRow = oneRowFooter,
                divider = art.top - 46f * SCALE,
                cover = art,
                markBaseline = baseline,
                mark = mark,
                textLeft = textLeft,
                textLimit = limit,
                titleBaseline = titleBase,
                artistBaseline = artistBase,
                titleSize = titleSize,
                artistSize = artistSize,
                shownTitle = t,
                shownArtist = a,
                titleRight = textLeft + tp.measureText(t),
                artistRight = textLeft + ap.measureText(a),
                sharedBy = shared,
                sharedByRight = sharedRight
            )
        }

        /** Where line [i] sits on a card that doesn't scroll (for tests). */
        fun lineRect(i: Int): RectF {
            val x = card.left + PAD
            return RectF(x, textTop + lineTops[i], x + textW, textTop + lineBottoms[i])
        }

        private fun layOut(pieces: List<LyricsCardSpacing.Piece>, size: Float, width: Int): Pair<List<Laid>, Float> {
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
                val layout = if (p.sub) layout(text, small, 1f, width) else layout(text, big, LyricsCardSpacing.ROW_MULT, width)
                Triple(p, layout, spans to words)
            }
            val heights = FloatArray(built.size) { built[it].second.height.toFloat() }
            val (tops, total) = LyricsCardSpacing.stack(pieces, heights, mainRow)
            return built.mapIndexed { i, (p, l, sw) -> Laid(p, l, tops[i], sw.first, sw.second) } to total
        }

        /** Original text fills word by word; translations light up a whole line at a time. */
        private fun wordByWord(p: LyricsCardSpacing.Piece): Boolean =
            !p.sub && mode != LyricsCardOptions.Lyrics.TRANSLATION

        /** Backdrop, card, divider and footer: everything but the lyric text. */
        fun drawBase(c: Canvas) {
            drawBackdrop(c)
            ShareCard.drawCardShape(c, card, cardColor, SCALE)
            val f = footer
            val x = card.left + PAD
            val right = card.right - PAD
            c.drawRect(x, f.divider, right, f.divider + 2f, Paint().apply { color = ColorUtils.setAlphaComponent(ink, 34) })
            val art = f.cover
            if (cover != null) {
                ShareCard.roundImage(c, cover, art, 24f * SCALE * art.width() / COVER)
            } else {
                val k = art.width() / COVER
                c.drawRoundRect(art, 24f * SCALE * k, 24f * SCALE * k, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = ColorUtils.blendARGB(cardColor, ink, 0.08f)
                })
                ShareCard.centered(c, "♪", art.centerX(), art.centerY() + 22f * SCALE * k, 64f * SCALE * k, bold, muted, 100)
            }
            if (f.shownTitle.isNotEmpty()) c.drawText(f.shownTitle, f.textLeft, f.titleBaseline, ShareCard.paint(f.titleSize, bold, ink))
            if (f.shownArtist.isNotEmpty()) c.drawText(f.shownArtist, f.textLeft, f.artistBaseline, ShareCard.paint(f.artistSize, regular, muted))
            // minim, bottom-right
            val logo = runCatching { BitmapFactory.decodeResource(ctx.resources, R.mipmap.ic_launcher_round) }.getOrNull()
            val logoSize = f.mark.height()
            if (logo != null) ShareCard.roundImage(c, logo, RectF(f.mark.left, f.mark.top, f.mark.left + logoSize, f.mark.bottom), logoSize / 2f)
            val mp = markPaint()
            c.drawText("minim", right - mp.measureText("minim"), f.markBaseline, mp)
            // "Shared by <name>" on minim's row, small and lighter (static cards with a name only)
            f.sharedBy?.let {
                c.drawText(it, x, f.markBaseline, ShareCard.paint(30f * FOOTER_SCALE, regular, ColorUtils.setAlphaComponent(muted, 180)))
            }
        }

        fun drawText(c: Canvas) {
            val x = card.left + PAD
            if (!scrolls) {
                for (l in laid) {
                    c.save()
                    c.translate(x, textTop + l.top)
                    l.layout.draw(c)
                    c.restore()
                }
                return
            }
            // 30/45/60: the lines move up inside a window; the edges fade where text goes past them
            val top = lyricTop
            val winTop = top - PAD * 0.6f
            val winBottom = top + textH + 30f * SCALE
            val shown = LyricsCardScroll.visible(lineTops, lineBottoms, scrollY, textH, PAD)
            val layer = c.saveLayer(card.left, winTop, card.right, winBottom, null)
            for (l in laid) {
                if (l.piece.line !in shown) continue
                c.save()
                c.translate(x, top + l.top - scrollY)
                l.layout.draw(c)
                c.restore()
            }
            val fade = 90f * SCALE
            val topFade = (scrollY / fade).coerceIn(0f, 1f)
            val bottomFade = ((fullH - scrollY - textH) / fade).coerceIn(0f, 1f)
            if (topFade > 0f) c.drawRect(card.left, winTop, card.right, winTop + fade + PAD * 0.6f, Paint().apply {
                shader = LinearGradient(0f, winTop, 0f, winTop + fade + PAD * 0.6f,
                    ColorUtils.setAlphaComponent(Color.BLACK, (255 * topFade).toInt()), Color.TRANSPARENT, Shader.TileMode.CLAMP)
                xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT)
            })
            if (bottomFade > 0f) c.drawRect(card.left, winBottom - fade, card.right, winBottom, Paint().apply {
                shader = LinearGradient(0f, winBottom - fade, 0f, winBottom,
                    Color.TRANSPARENT, ColorUtils.setAlphaComponent(Color.BLACK, (255 * bottomFade).toInt()), Shader.TileMode.CLAMP)
                xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT)
            })
            c.restoreToCount(layer)
        }

        /** 30/45/60: scrolls the window for a lyric-timeline time. */
        fun setScroll(nowMs: Long, timing: List<Timing>) {
            if (!scrolls) return
            scrollY = LyricsCardScroll.offset(
                nowMs, timing.map { it.startMs }, lineTops, fullH, textH, pickedCount, pickedH
            )
        }

        /** The scroll offset last set (for tests). */
        val scrollOffset: Float get() = scrollY

        /** The backdrop on its own (also behind the ending). */
        fun drawBackdrop(c: Canvas) {
            if (background == LyricsCardOptions.Background.BLUR && cover != null) drawBlur(c, cover)
            else ShareCard.drawBackdrop(c, pal)
        }

        /** The ending: the backdrop with "minim" and "Shared by <name>" (just "minim" when blank). */
        fun drawEnding(c: Canvas) {
            drawBackdrop(c)
            val endInk = RecapInk.text(bgTone, cardColor)
            val mark = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = END_MARK_SIZE; typeface = semi; color = endInk }
            val cy = H * 0.48f
            val markW = mark.measureText("minim")
            c.drawText("minim", (W - markW) / 2f, cy + END_MARK_SIZE * 0.27f, mark)
            if (sharer != null) {
                ShareCard.centered(
                    c, "Shared by $sharer", W / 2f, cy + END_MARK_SIZE * 0.27f + 62f, END_NAME_SIZE, regular,
                    ColorUtils.setAlphaComponent(endInk, END_NAME_ALPHA), (W * 0.7f).toInt()
                )
            }
        }

        /**
         * Sets each word's level for a lyric-timeline time. Only the line being sung is lit (word
         * by word, or whole in Translation mode); once it's done it dims back to [DIM] over
         * [PAST_FADE_MS], its translation with it.
         */
        fun setTime(nowMs: Long, timing: List<Timing>) {
            for (l in laid) {
                val i = l.piece.line
                val t = timing.getOrNull(i) ?: continue
                val keep = 1f - pastFade(nowMs, lineEndMs(timing, i))
                if (wordByWord(l.piece) && l.spans.size == l.words.size && l.spans.isNotEmpty()) {
                    val bounds = boundsFor(l, t)
                    val since = nowMs - t.startMs
                    l.spans.forEachIndexed { k, s -> s.level = DIM + (1 - DIM) * FlowTiming.wordFill(bounds, k, since) * keep }
                } else {
                    val lv = LyricsVideoPlan.lineLevel(nowMs, t.startMs)
                    l.spans.forEach { it.level = DIM + (1 - DIM) * lv * keep }
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
            setScroll(lyricMs, timing)
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

    /** [s] as it fits in [maxW] at [p]: whole, or cut at a word or letter with "…" at the end. */
    internal fun fitText(s: String, p: Paint, maxW: Float): String {
        if (s.isEmpty() || maxW <= 0f) return ""
        if (p.measureText(s) <= maxW) return s
        val dots = "\u2026"
        var lo = 0
        var hi = s.length
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (p.measureText(s.substring(0, mid).trimEnd() + dots) <= maxW) lo = mid else hi = mid - 1
        }
        val cut = s.substring(0, lo).trimEnd().trimEnd(',', '(', '-', '&', '·')
        return if (cut.isEmpty()) (if (p.measureText(dots) <= maxW) dots else "") else cut.trimEnd() + dots
    }

    private fun layout(s: CharSequence, p: TextPaint, spacing: Float, width: Int): StaticLayout =
        StaticLayout.Builder.obtain(s, 0, s.length, p, width)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(0f, spacing)
            .setIncludePad(false)
            .setMaxLines(12)
            .build()
}
