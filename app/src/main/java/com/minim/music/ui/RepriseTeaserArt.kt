package com.minim.music.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect

/**
 * 6.9.0 part B: the Monthly teaser's art. The month's top covers as a 2x2 mosaic (one cover fills
 * it all, two or three repeat), and a heavily blurred copy for before the drop.
 */
object RepriseTeaserArt {

    const val SIZE = 256
    private const val SMALL = 48

    /** Which cover goes in each of the four tiles. */
    fun tiles(count: Int): List<Int> = when (count) {
        0 -> emptyList()
        1 -> listOf(0, 0, 0, 0)
        2 -> listOf(0, 1, 1, 0)
        3 -> listOf(0, 1, 2, 0)
        else -> listOf(0, 1, 2, 3)
    }

    /** The sharp mosaic and its blur, or nulls when there are no covers yet. */
    fun build(covers: List<Bitmap>): Pair<Bitmap?, Bitmap?> {
        val order = tiles(covers.size)
        if (order.isEmpty()) return null to null
        val sharp = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        val c = Canvas(sharp)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        val half = SIZE / 2
        for ((i, idx) in order.withIndex()) {
            val b = covers[idx]
            val side = minOf(b.width, b.height)
            val src = Rect((b.width - side) / 2, (b.height - side) / 2, (b.width + side) / 2, (b.height + side) / 2)
            val x = (i % 2) * half
            val y = (i / 2) * half
            c.drawBitmap(b, src, Rect(x, y, x + half, y + half), paint)
        }
        val small = Bitmap.createScaledBitmap(sharp, SMALL, SMALL, true)
        val px = IntArray(SMALL * SMALL)
        small.getPixels(px, 0, SMALL, 0, 0, SMALL, SMALL)
        val out = SoftwareBlur.blur(px, SMALL, SMALL, 6)
        val blurred = Bitmap.createBitmap(out, SMALL, SMALL, Bitmap.Config.ARGB_8888)
        if (small !== sharp) small.recycle()
        return sharp to blurred
    }
}
