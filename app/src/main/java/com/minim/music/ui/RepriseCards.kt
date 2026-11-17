package com.minim.music.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.text.TextPaint
import android.text.TextUtils
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.ColorUtils
import com.minim.music.R
import com.minim.music.data.stats.Days
import com.minim.music.data.stats.StatsPeriods
import com.minim.music.data.stats.StatsFormat
import com.minim.music.data.stats.TopSong
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.TextStyle
import java.time.temporal.IsoFields
import java.util.Locale

/**
 * 6.9.0 b76: the Weekly ("Receipt") and Monthly ("Calendar") Reprise cards, drawn straight onto a
 * 1080 x 1920 (9:16) bitmap like the Daily card, so what's shared or saved is what the story shows.
 * Both end with "Shared by <name>" and the small "minim" mark bottom right. Pure drawing: the
 * images are loaded by the caller (the same ones the story shows). Works on Android 9 (no
 * RenderEffect: the blur is a software blur on a small copy).
 */
object RepriseCards {
    const val W = 1080
    const val H = 1920
    private const val M = 96f

    // ---------- data ----------

    /** What the Weekly card shows. Any list may be short or empty. */
    class Week(
        val start: LocalDate,
        val end: LocalDate,
        val top: List<TopSong>,
        val songs: Int,
        val tapes: Int,
        val topArtist: String?,
        val streak: Int,
        val totalMs: Long
    )

    /** What the Monthly card shows. */
    class Month(
        val ym: YearMonth,
        /** Listening per day key (songs and tapes). */
        val dayTotals: Map<Int, Long>,
        /** Today's key when the month is still going, else null. */
        val todayKey: Int?,
        val totalMs: Long,
        val topSong: TopSong?,
        val topArtist: String?
    )

    /** "Nov 8 – 14, 2026" ("Nov 29 – Dec 5, 2026" across months, both years across years). */
    fun weekDates(start: LocalDate, end: LocalDate): String {
        fun m(d: LocalDate) = d.month.getDisplayName(TextStyle.SHORT, Locale.ENGLISH)
        return when {
            start.year != end.year -> "${m(start)} ${start.dayOfMonth}, ${start.year} \u2013 ${m(end)} ${end.dayOfMonth}, ${end.year}"
            start.month != end.month -> "${m(start)} ${start.dayOfMonth} \u2013 ${m(end)} ${end.dayOfMonth}, ${end.year}"
            else -> "${m(start)} ${start.dayOfMonth} \u2013 ${end.dayOfMonth}, ${end.year}"
        }
    }

    /** The playful order number: the week of the year, e.g. "ORDER #46". */
    fun orderNo(start: LocalDate): String = "ORDER #" + start.plusDays(3).get(IsoFields.WEEK_OF_WEEK_BASED_YEAR)

    /** The receipt's footer line, picked steadily from the week (the same week always gets the same line). */
    fun footer(w: Week): String {
        val lines = buildList {
            add("No refunds on skips.")
            add("Keep this receipt for your records. Your neighbours have one too.")
            add("Prices include emotional damage.")
            add("All sales final. Your ears agreed.")
            w.topArtist?.let { add("Thank you for your loyalty to $it.") }
        }
        val seed = (w.start.toEpochDay() * 31 + w.totalMs / 60_000L).let { if (it < 0) -it else it }
        return lines[(seed % lines.size).toInt()]
    }

    /** The subtotal rows: songs, tapes (only with tapes), top artist and day streak (only when known). */
    fun subtotals(w: Week): List<Pair<String, String>> = buildList {
        add("SONGS" to w.songs.toString())
        if (w.tapes > 0) add("TAPES" to w.tapes.toString())
        w.topArtist?.takeIf { it.isNotBlank() }?.let { add("TOP ARTIST" to it) }
        if (w.streak > 0) add("DAY STREAK" to if (w.streak == 1) "1 day" else "${w.streak} days")
    }

    /** Bar widths (1 to 4 units) of a barcode made from the week's numbers: same week, same barcode. */
    fun barcode(w: Week, bars: Int = 44): IntArray {
        var x = (w.start.toEpochDay() xor (w.totalMs / 1000L) xor (w.songs.toLong() shl 20)) or 1L
        return IntArray(bars) {
            x = x xor (x shl 13); x = x xor (x ushr 7); x = x xor (x shl 17)
            1 + ((x ushr 3) and 3L).toInt()
        }
    }

    // ---------- Weekly: Receipt ----------

    fun receipt(context: Context, w: Week, cover: Bitmap?, sharedBy: String?): Bitmap {
        val bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        drawBlurredBackdrop(c, cover, 0xFF2A2A2A.toInt())
        val mono = Typeface.MONOSPACE
        val monoBold = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        val ink = 0xFF1C1C1C.toInt()
        val faint = 0xFF6B6B6B.toInt()
        val paper = 0xFFFAF8F3.toInt()

        val left = 150f
        val right = W - 150f
        val top = 190f
        val bottom = 1650f
        val tooth = 22f
        val path = zigzagPaper(left, top, right, bottom, tooth)
        // Soft shadow under the paper
        c.save()
        c.translate(0f, 18f)
        c.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x40000000 })
        c.restore()
        c.save()
        c.translate(0f, 8f)
        c.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x26000000 })
        c.restore()
        c.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = paper })

        val pl = left + 56f
        val pr = right - 56f
        val pw = (pr - pl).toInt()
        val cx = (left + right) / 2f
        var y = top + tooth + 92f
        center(c, "MINIM", cx, y, 64f, monoBold, ink, pw, spacing = 0.3f)
        y += 58f
        center(c, "WEEKLY REPRISE", cx, y, 32f, mono, ink, pw, spacing = 0.2f)
        y += 64f
        center(c, weekDates(w.start, w.end), cx, y, 30f, mono, faint, pw)
        y += 44f
        center(c, orderNo(w.start), cx, y, 30f, mono, faint, pw)
        y += 40f
        dashed(c, pl, pr, y, faint)
        y += 62f

        // Line items: the top 5 songs, time right-aligned
        val items = w.top.take(5)
        if (items.isEmpty()) {
            center(c, "NOTHING ON THE TAB YET", cx, y, 28f, mono, faint, pw)
            y += 56f
        } else {
            for ((i, s) in items.withIndex()) {
                val time = StatsFormat.duration(s.ms)
                val tp = paint(30f, mono, ink)
                val tw = tp.measureText(time)
                start(c, "${i + 1} ${s.title}", pl, y, 30f, mono, ink, (pw - tw - 28f).toInt())
                end(c, time, pr, y, 30f, mono, ink)
                start(c, "  ${s.artist}", pl, y + 36f, 24f, mono, faint, (pw - tw - 28f).toInt())
                y += 86f
            }
        }
        y += 4f
        dashed(c, pl, pr, y, faint)
        y += 58f

        for ((k, v) in subtotals(w)) {
            val kp = paint(28f, mono, faint)
            val kw = kp.measureText(k)
            start(c, k, pl, y, 28f, mono, faint, pw)
            end(c, v, pr, y, 28f, mono, ink, (pw - kw - 28f).toInt())
            y += 46f
        }
        y += 8f
        dashed(c, pl, pr, y, faint)
        y += 72f
        start(c, "TOTAL", pl, y, 46f, monoBold, ink, pw / 2)
        end(c, StatsFormat.duration(w.totalMs), pr, y, 46f, monoBold, ink, pw / 2)
        y += 30f
        dashed(c, pl, pr, y, faint)

        // Barcode, sized to whatever room is left above the footer line
        val footerY = bottom - tooth - 60f
        val barTop = y + 48f
        val barBottom = minOf(barTop + 130f, footerY - 70f)
        if (barBottom - barTop >= 40f) {
            val widths = barcode(w)
            val total = widths.sum() * 2 - 1
            val unit = (pw * 0.8f) / total
            var x = cx - (total * unit) / 2f
            val bp = Paint().apply { color = ink }
            for ((i, wd) in widths.withIndex()) {
                if (i % 2 == 0) c.drawRect(x, barTop, x + wd * unit, barBottom, bp)
                x += wd * unit + unit
            }
        }
        center(c, footer(w), cx, footerY, 25f, mono, faint, pw)

        drawFooter(context, c, sharedBy, Color.WHITE, ColorUtils.setAlphaComponent(Color.WHITE, 200))
        return bmp
    }

    /** The paper's outline: straight sides, a zigzag torn edge top and bottom. */
    internal fun zigzagPaper(l: Float, t: Float, r: Float, b: Float, tooth: Float): Path {
        val p = Path()
        val n = ((r - l) / (tooth * 2f)).toInt().coerceAtLeast(4)
        val step = (r - l) / n
        p.moveTo(l, t + tooth)
        for (i in 0 until n) {
            p.lineTo(l + step * i + step / 2f, t)
            p.lineTo(l + step * (i + 1), t + tooth)
        }
        p.lineTo(r, b - tooth)
        for (i in n downTo 1) {
            p.lineTo(l + step * i - step / 2f, b)
            p.lineTo(l + step * (i - 1), b - tooth)
        }
        p.close()
        return p
    }

    // ---------- Monthly: Calendar ----------

    /** The day that's subtly ringed: today while the month is going, else the busiest day. */
    fun highlightKey(m: Month): Int? {
        m.todayKey?.takeIf { YearMonth.from(Days.date(it)) == m.ym }?.let { return it }
        return m.dayTotals.filter { YearMonth.from(Days.date(it.key)) == m.ym && it.value > 0 }.maxByOrNull { it.value }?.key
    }

    /** "42 h listened" (or minutes under an hour). */
    fun hoursLine(totalMs: Long): String {
        val h = totalMs / 3_600_000L
        return if (h >= 1) "$h ${if (h == 1L) "hour" else "hours"} listened" else "${StatsFormat.duration(totalMs)} listened"
    }

    fun calendar(context: Context, m: Month, pal: Pal, cover: Bitmap?, artistPhoto: Bitmap?, sharedBy: String?): Bitmap {
        val bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val bg = pal.bg or 0xFF000000.toInt()
        c.drawColor(bg)
        // A soft band in a second shade of the cover's palette, like the Daily card
        c.drawRect(0f, 0f, W.toFloat(), 560f, Paint().apply { color = RecapCard.bandColor(pal) })
        val bold = font(context, R.font.poppins_bold, Typeface.DEFAULT_BOLD)
        val semi = font(context, R.font.poppins_semibold, Typeface.DEFAULT_BOLD)
        val regular = font(context, R.font.poppins_regular, Typeface.DEFAULT)
        val medium = font(context, R.font.poppins_medium, Typeface.DEFAULT)
        val fg = pal.fg
        val muted = pal.muted
        val right = W - M
        val pw = (right - M).toInt()

        val name = m.ym.month.getDisplayName(TextStyle.FULL, Locale.ENGLISH).uppercase(Locale.ENGLISH)
        // Big month name, shrunk to fit (SEPTEMBER is the widest)
        val np = paint(190f, bold, fg).apply { letterSpacing = -0.02f }
        while (np.measureText(name) > pw && np.textSize > 90f) np.textSize -= 6f
        c.drawText(name, M, 300f, np)
        start(c, m.ym.year.toString(), M, 380f, 44f, medium, muted, pw / 2)
        end(c, hoursLine(m.totalMs), right, 380f, 44f, semi, fg, pw / 2)

        // Heatmap: Sunday-first, same levels as the Stats heatmap
        val grid = StatsPeriods.monthGrid(m.ym)
        val cols = 7
        val rows = (grid.size + cols - 1) / cols
        val gap = 18f
        val headTop = 640f
        val gridTop = headTop + 56f
        val maxGridH = 1340f - gridTop
        val cell = minOf((pw - gap * (cols - 1)) / cols, (maxGridH - gap * (rows - 1)) / rows)
        val gridW = cell * cols + gap * (cols - 1)
        val gx = M + (pw - gridW) / 2f
        val heads = listOf("S", "M", "T", "W", "T", "F", "S")
        for (i in 0 until cols) center(c, heads[i], gx + i * (cell + gap) + cell / 2f, headTop + 30f, 30f, medium, muted, cell.toInt())
        val inMonth = m.dayTotals.filterKeys { YearMonth.from(Days.date(it)) == m.ym }
        val maxMs = inMonth.values.maxOrNull() ?: 0L
        val hi = highlightKey(m)
        val box = Paint(Paint.ANTI_ALIAS_FLAG)
        val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 5f; color = fg }
        val num = paint((cell * 0.34f).coerceIn(24f, 44f), medium, fg).apply { textAlign = Paint.Align.CENTER }
        for ((i, key) in grid.withIndex()) {
            if (key == null) continue
            val col = i % cols
            val row = i / cols
            val l = gx + col * (cell + gap)
            val t = gridTop + row * (cell + gap)
            val rect = RectF(l, t, l + cell, t + cell)
            val future = m.todayKey != null && key > m.todayKey
            val lv = StatsPeriods.heatLevel(inMonth[key] ?: 0L, maxMs)
            val alpha = if (future) 14 else boxAlpha(lv)
            val fill = ColorUtils.compositeColors(ColorUtils.setAlphaComponent(fg, alpha), bg)
            box.color = fill
            c.drawRoundRect(rect, cell * 0.28f, cell * 0.28f, box)
            if (key == hi) c.drawRoundRect(RectF(rect.left - 7f, rect.top - 7f, rect.right + 7f, rect.bottom + 7f), cell * 0.32f, cell * 0.32f, ring)
            num.color = numberInk(fill, future)
            num.typeface = if (key == hi) semi else medium
            val fm = num.fontMetrics
            c.drawText(Days.date(key).dayOfMonth.toString(), rect.centerX(), rect.centerY() - (fm.ascent + fm.descent) / 2f, num)
        }
        val gridBottom = gridTop + rows * cell + (rows - 1) * gap

        // Top song and top artist
        var y = gridBottom + 90f
        val coverSize = 170f
        m.topSong?.let { s ->
            val r = RectF(M, y, M + coverSize, y + coverSize)
            if (cover != null) fitImage(c, cover, r, 24f) else c.drawRoundRect(r, 24f, 24f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ColorUtils.setAlphaComponent(fg, 40) })
            val tx = r.right + 36f
            val tw = (right - tx).toInt()
            start(c, "TOP SONG", tx, y + 46f, 26f, medium, muted, tw)
            start(c, s.title, tx, y + 104f, 48f, semi, fg, tw)
            start(c, s.artist, tx, y + 150f, 32f, regular, muted, tw)
            y += coverSize + 44f
        }
        m.topArtist?.takeIf { it.isNotBlank() }?.let { a ->
            val d = 120f
            val r = RectF(M + (coverSize - d) / 2f, y, M + (coverSize + d) / 2f, y + d)
            if (artistPhoto != null) fitImage(c, artistPhoto, r, d / 2f)
            else {
                c.drawOval(r, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ColorUtils.setAlphaComponent(fg, 40) })
                center(c, a.trim().take(1).uppercase(), r.centerX(), r.centerY() + 20f, 56f, semi, fg, d.toInt())
            }
            val tx = M + coverSize + 36f
            val tw = (right - tx).toInt()
            start(c, "TOP ARTIST", tx, r.centerY() - 8f, 26f, medium, muted, tw)
            start(c, a, tx, r.centerY() + 44f, 44f, semi, fg, tw)
        }
        drawFooter(context, c, sharedBy, fg, muted)
        return bmp
    }

    /** Same steps as the Stats heatmap boxes. */
    fun boxAlpha(lv: Int): Int = when (lv) { 0 -> 22; 1 -> 70; 2 -> 120; 3 -> 180; else -> 255 }

    /** A day number that reads on its box: dark on bright boxes, light on dark ones. */
    fun numberInk(box: Int, future: Boolean): Int {
        // Whichever of near-black and white stands out more from this box (over 4:1 for any colour)
        val dark = 0xFF111111.toInt()
        val base = if (ColorUtils.calculateContrast(dark, box or 0xFF000000.toInt()) >= ColorUtils.calculateContrast(Color.WHITE, box or 0xFF000000.toInt())) dark else Color.WHITE
        return ColorUtils.setAlphaComponent(base, if (future) 90 else 255)
    }

    // ---------- shared ----------

    /** "Shared by <name>" bottom left (when there's a name) and the small "minim" mark bottom right. */
    private fun drawFooter(context: Context, c: Canvas, sharedBy: String?, fg: Int, muted: Int) {
        val regular = font(context, R.font.poppins_regular, Typeface.DEFAULT)
        val medium = font(context, R.font.poppins_medium, Typeface.DEFAULT)
        sharedBy?.trim()?.takeIf { it.isNotEmpty() }?.let { start(c, "Shared by $it", M, 1860f, 28f, regular, muted, 700) }
        end(c, "minim", W - M, 1862f, 40f, medium, fg, 200)
    }

    /** The blurred top cover over the whole card, a little darker so the paper stands out. */
    private fun drawBlurredBackdrop(c: Canvas, cover: Bitmap?, fallback: Int) {
        val blurred = cover?.let { RepriseLoadingView.blurSmall(it) }
        if (blurred == null) {
            c.drawColor(fallback)
        } else {
            val shader = BitmapShader(blurred, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            val scale = maxOf(W.toFloat() / blurred.width, H.toFloat() / blurred.height)
            shader.setLocalMatrix(Matrix().apply {
                setScale(scale, scale)
                postTranslate((W - blurred.width * scale) / 2f, (H - blurred.height * scale) / 2f)
            })
            c.drawRect(0f, 0f, W.toFloat(), H.toFloat(), Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply { this.shader = shader })
        }
        c.drawColor(0x59000000)
    }

    /** A square or round image drawn whole: a square source fills [rect]; others are centred inside it, never cut. */
    internal fun fitImage(c: Canvas, src: Bitmap, rect: RectF, radius: Float) {
        val scale = fitScale(src.width, src.height, rect.width(), rect.height())
        val shader = BitmapShader(src, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        val dx = rect.left + (rect.width() - src.width * scale) / 2f
        val dy = rect.top + (rect.height() - src.height * scale) / 2f
        shader.setLocalMatrix(Matrix().apply { setScale(scale, scale); postTranslate(dx, dy) })
        c.drawRoundRect(rect, radius, radius, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply { this.shader = shader })
    }

    /**
     * The scale an image is drawn at in a [w] x [h] slot. Square-ish covers and photos fit whole (never
     * cut); video frames (16:9 with letterbox bars) fill the slot from their middle, where the picture is.
     */
    fun fitScale(srcW: Int, srcH: Int, w: Float, h: Float): Float {
        val aspect = srcW.toFloat() / srcH.coerceAtLeast(1)
        return if (aspect in 0.9f..1.1f) minOf(w / srcW, h / srcH) else maxOf(w / srcW, h / srcH)
    }

    private fun dashed(c: Canvas, l: Float, r: Float, y: Float, color: Int) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color; strokeWidth = 3f; style = Paint.Style.STROKE
            pathEffect = DashPathEffect(floatArrayOf(14f, 10f), 0f)
        }
        c.drawLine(l, y, r, y, p)
    }

    private fun font(context: Context, id: Int, fallback: Typeface): Typeface =
        try { ResourcesCompat.getFont(context, id) ?: fallback } catch (_: Exception) { fallback }

    private fun paint(size: Float, face: Typeface, color: Int) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = size; typeface = face; this.color = color
    }

    private fun fit(s: String, p: TextPaint, maxW: Int) = TextUtils.ellipsize(s, p, maxW.toFloat(), TextUtils.TruncateAt.END).toString()

    private fun center(c: Canvas, s: String, cx: Float, y: Float, size: Float, face: Typeface, color: Int, maxW: Int, spacing: Float = 0f) {
        val p = paint(size, face, color).apply { textAlign = Paint.Align.CENTER; letterSpacing = spacing }
        c.drawText(fit(s, p, maxW), cx, y, p)
    }

    private fun start(c: Canvas, s: String, x: Float, y: Float, size: Float, face: Typeface, color: Int, maxW: Int) {
        val p = paint(size, face, color)
        c.drawText(fit(s, p, maxW), x, y, p)
    }

    private fun end(c: Canvas, s: String, x: Float, y: Float, size: Float, face: Typeface, color: Int, maxW: Int = 600) {
        val p = paint(size, face, color).apply { textAlign = Paint.Align.RIGHT }
        c.drawText(fit(s, p, maxW), x, y, p)
    }
}
