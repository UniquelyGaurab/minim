package com.minim.music.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.View
import androidx.core.graphics.ColorUtils
import com.minim.music.data.stats.StatsFormat

/** 6.9.0: bars with a label under each; the busiest bar solid with its time over it. */
@SuppressLint("ViewConstructor")
class RepriseBars(
    context: Context,
    private val labels: List<String>,
    private val values: List<Long>,
    private val busy: Int,
    private val quiet: Int,
    private val fg: Int,
    private val muted: Int,
    private val labelFace: Typeface,
    private val smallFace: Typeface
) : View(context) {
    private val d = resources.displayMetrics.density
    private val bar = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val rect = RectF()
    var grow = 1f
        set(v) { field = v; invalidate() }

    override fun onDraw(canvas: Canvas) {
        val n = values.size
        if (n == 0) return
        val max = values.maxOrNull()?.takeIf { it > 0L } ?: 1L
        val labelH = 20f * d
        val topH = 22f * d
        val chartH = height - labelH - topH
        val gap = (if (n > 12) 3f else 10f) * d
        val w = (width - gap * (n - 1)) / n
        val r = minOf(w / 2f, 8f * d)
        for (i in 0 until n) {
            val left = i * (w + gap)
            val f = RecapMotion.barGrow(grow, i)
            val minH = 4f * d
            val full = if (values[i] > 0L) maxOf(minH, chartH * values[i] / max) else minH
            val bh = full * f
            rect.set(left, topH + chartH - bh, left + w, topH + chartH)
            bar.color = when {
                i == busy -> fg
                values[i] > 0L -> ColorUtils.setAlphaComponent(fg, if (i == quiet) 70 else 120)
                else -> ColorUtils.setAlphaComponent(fg, 34)
            }
            canvas.drawRoundRect(rect, r, r, bar)
            if (i == busy && f > 0.6f) {
                text.typeface = labelFace
                text.textSize = 12f * d
                text.color = ColorUtils.setAlphaComponent(fg, ((f - 0.6f) / 0.4f * 255).toInt().coerceIn(0, 255))
                canvas.drawText(StatsFormat.duration(values[i]), (left + w / 2f).coerceIn(20f * d, width - 20f * d), rect.top - 6f * d, text)
            }
            if (labels.getOrNull(i).isNullOrEmpty()) continue
            text.typeface = if (i == busy) labelFace else smallFace
            text.textSize = 12f * d
            text.color = if (i == busy) fg else muted
            canvas.drawText(labels[i], left + w / 2f, height - 4f * d, text)
        }
    }
}

/**
 * 6.9.0: a month as a Sunday-first grid of rounded squares, darker for more listening. [levels]
 * is -1 for a blank, 0 (faint) to 4. [grow] fills the days in order.
 */
@SuppressLint("ViewConstructor")
class HeatGrid(
    context: Context,
    private val levels: List<Int>,
    private val fg: Int,
    private val muted: Int,
    private val face: Typeface
) : View(context) {
    private val d = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val rect = RectF()
    var grow = 1f
        set(v) { field = v; invalidate() }

    /** 6.9.0 (74): day of the month per slot (0 = blank), drawn inside each box. Null draws none. */
    var dayNumbers: List<Int>? = null
        set(v) { field = v; invalidate() }

    /** 74: today's slot, outlined with a bolder number. -1 for none. */
    var todayIndex = -1
        set(v) { field = v; invalidate() }

    /** 74: slots after today: drawn faint (they can't be tapped either). */
    var future: List<Boolean> = emptyList()
        set(v) { field = v; invalidate() }

    var boldFace: Typeface? = null

    private val num = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

    /** 74: the alpha a box of level [lv] is drawn with ([shown] false while growing in). */
    internal fun boxAlpha(i: Int, lv: Int, shown: Boolean): Int = when {
        future.getOrElse(i) { false } -> HeatInk.FUTURE_ALPHA
        !shown -> 22
        else -> when (lv) { 0 -> 22; 1 -> 70; 2 -> 120; 3 -> 180; else -> 255 }
    }

    /** 6.9.0: the grid slot under ([x], [y]), or -1 (header, gaps, outside). Same geometry as onDraw. */
    fun indexAt(x: Float, y: Float): Int {
        val cols = 7
        val rows = (levels.size + cols - 1) / cols
        val headH = 20f * d
        val gap = 6f * d
        val cell = minOf((width - gap * (cols - 1)) / cols, (height - headH - gap * (rows - 1)) / rows.coerceAtLeast(1))
        if (cell <= 0f) return -1
        val left0 = (width - (cell * cols + gap * (cols - 1))) / 2f
        val cx = x - left0
        val cy = y - headH
        if (cx < 0f || cy < 0f) return -1
        val c = (cx / (cell + gap)).toInt()
        val r = (cy / (cell + gap)).toInt()
        if (c >= cols || r >= rows) return -1
        val i = r * cols + c
        return if (i in levels.indices) i else -1
    }

    override fun onDraw(canvas: Canvas) {
        val cols = 7
        val rows = (levels.size + cols - 1) / cols
        val headH = 20f * d
        val gap = 6f * d
        val cell = minOf((width - gap * (cols - 1)) / cols, (height - headH - gap * (rows - 1)) / rows.coerceAtLeast(1))
        val left0 = (width - (cell * cols + gap * (cols - 1))) / 2f
        text.typeface = face
        text.textSize = 11f * d
        text.color = muted
        for ((i, s) in listOf("S", "M", "T", "W", "T", "F", "S").withIndex()) {
            canvas.drawText(s, left0 + i * (cell + gap) + cell / 2f, 13f * d, text)
        }
        val days = levels.count { it >= 0 }.coerceAtLeast(1)
        var n = 0
        for (i in levels.indices) {
            val lv = levels[i]
            if (lv < 0) continue
            val shown = grow * days >= n + 1 || grow >= 1f
            n++
            val x = left0 + (i % cols) * (cell + gap)
            val y = headH + (i / cols) * (cell + gap)
            rect.set(x, y, x + cell, y + cell)
            val alpha = boxAlpha(i, lv, shown)
            paint.color = ColorUtils.setAlphaComponent(fg, alpha)
            canvas.drawRoundRect(rect, cell * 0.28f, cell * 0.28f, paint)
            val dn = dayNumbers?.getOrNull(i) ?: 0
            if (dn <= 0) continue
            val isToday = i == todayIndex
            val isFuture = future.getOrElse(i) { false }
            if (isToday) {
                ring.strokeWidth = 1.5f * d
                ring.color = ColorUtils.setAlphaComponent(fg, 230)
                val inset = ring.strokeWidth / 2f
                rect.inset(inset, inset)
                canvas.drawRoundRect(rect, cell * 0.28f, cell * 0.28f, ring)
            }
            num.typeface = if (isToday) (boldFace ?: face) else face
            num.textSize = (cell * 0.34f).coerceIn(8f * d, 12f * d)
            val ink = HeatInk.ink(fg, alpha)
            num.color = ColorUtils.setAlphaComponent(ink, if (isFuture) 0x66 else if (isToday) 0xFF else 0xD9)
            val cy = y + cell / 2f - (num.descent() + num.ascent()) / 2f
            canvas.drawText(dn.toString(), x + cell / 2f, cy, num)
        }
    }
}

/**
 * 6.9.0 (74): ink for a heatmap day number. A box is the text colour [fg] at some alpha over the
 * screen; the screen is dark when [fg] is light (Dark, or Haze on a dark cover) and light when it's
 * dark (Light, or Haze on a bright cover). The number is dark on a box that comes out bright and
 * light on one that comes out dim, so it stays readable on full and empty days in every theme.
 */
object HeatInk {
    const val FUTURE_ALPHA = 10
    const val DARK = 0xFF111111.toInt()
    const val LIGHT = 0xFFFFFFFF.toInt()

    /** The screen behind the boxes, as far as contrast goes. */
    fun backdrop(fg: Int): Int = if (ColorUtils.calculateLuminance(fg) > 0.5) 0xFF121212.toInt() else 0xFFF6F6F6.toInt()

    fun box(fg: Int, alpha: Int): Int = ColorUtils.compositeColors(ColorUtils.setAlphaComponent(fg, alpha.coerceIn(0, 255)), backdrop(fg))

    fun ink(fg: Int, alpha: Int): Int {
        val b = box(fg, alpha)
        return if (ColorUtils.calculateContrast(DARK, b) >= ColorUtils.calculateContrast(LIGHT, b)) DARK else LIGHT
    }
}
