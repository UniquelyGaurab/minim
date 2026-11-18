package com.minim.music.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.TextPaint
import android.text.TextUtils
import com.minim.music.data.stats.Days
import com.minim.music.data.stats.RepriseScope
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

/**
 * 6.9.0 b77: any Reprise slide as a 9:16 share card. The slide is drawn as a still (its final,
 * revealed state, with no story chrome or hints), then laid out on the slide's own colour with the
 * period on top, and "Shared by <name>" and the small "minim" mark at the bottom, like the other
 * cards. Pure drawing: safe off the main thread.
 */
object SlideCard {
    const val W = 1080
    const val H = 1920
    private const val M = 96f
    /** Where the slide may go: under the period, over the footer. */
    const val BOX_TOP = 210f
    const val BOX_BOTTOM = 1770f
    private const val BOX_SIDE = 40f

    /** Daily: "Monday, 16 Nov". Weekly: "Nov 8 – 14, 2026". Monthly: "November 2026". */
    fun period(scope: RepriseScope?, day: Int): String {
        if (scope == null) return dayLabel(Days.date(day))
        return if (scope.isWeek) weekLabel(scope.start, scope.start.plusDays(6)) else monthLabel(scope.start)
    }

    fun dayLabel(d: LocalDate): String {
        val wd = d.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.ENGLISH)
        val m = d.month.getDisplayName(TextStyle.SHORT, Locale.ENGLISH)
        return "$wd, ${d.dayOfMonth} $m"
    }

    fun weekLabel(start: LocalDate, end: LocalDate): String {
        fun m(d: LocalDate) = d.month.getDisplayName(TextStyle.SHORT, Locale.ENGLISH)
        return when {
            start.year != end.year -> "${m(start)} ${start.dayOfMonth}, ${start.year} – ${m(end)} ${end.dayOfMonth}, ${end.year}"
            start.month != end.month -> "${m(start)} ${start.dayOfMonth} – ${m(end)} ${end.dayOfMonth}, ${end.year}"
            else -> "${m(start)} ${start.dayOfMonth} – ${end.dayOfMonth}, ${end.year}"
        }
    }

    fun monthLabel(d: LocalDate): String =
        d.month.getDisplayName(TextStyle.FULL, Locale.ENGLISH) + " " + d.year

    /** Where [slideW] x [slideH] lands on the card: as big as fits the box, centred in it. */
    fun slideRect(slideW: Int, slideH: Int): RectF {
        val bw = W - 2 * BOX_SIDE
        val bh = BOX_BOTTOM - BOX_TOP
        if (slideW <= 0 || slideH <= 0) return RectF(BOX_SIDE, BOX_TOP, W - BOX_SIDE, BOX_BOTTOM)
        val s = minOf(bw / slideW, bh / slideH)
        val w = slideW * s
        val h = slideH * s
        val l = (W - w) / 2f
        val t = BOX_TOP + (bh - h) / 2f
        return RectF(l, t, l + w, t + h)
    }

    class Faces(val semi: Typeface, val medium: Typeface, val regular: Typeface)

    fun render(
        slide: Bitmap?,
        bg: Int,
        fg: Int,
        muted: Int,
        period: String,
        sharedBy: String?,
        faces: Faces
    ): Bitmap {
        val bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(bg or 0xFF000000.toInt())
        text(c, period, W / 2f, 150f, 38f, faces.semi, muted, (W - 2 * M).toInt(), Paint.Align.CENTER)
        if (slide != null && !slide.isRecycled) {
            val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
            c.drawBitmap(slide, null, slideRect(slide.width, slide.height), p)
        }
        val right = W - M
        val name = sharedBy?.trim()?.takeIf { it.isNotEmpty() }
        if (name != null) text(c, "Shared by $name", M, 1860f, 28f, faces.regular, muted, 700, Paint.Align.LEFT)
        text(c, "minim", right, 1862f, 40f, faces.medium, fg, 200, Paint.Align.RIGHT)
        return bmp
    }

    private fun text(c: Canvas, s: String, x: Float, baseline: Float, size: Float, face: Typeface, color: Int, maxW: Int, align: Paint.Align) {
        val p = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = size
            typeface = face
            this.color = color
            textAlign = align
        }
        val t = TextUtils.ellipsize(s, p, maxW.toFloat(), TextUtils.TruncateAt.END).toString()
        c.drawText(t, x, baseline, p)
    }
}
