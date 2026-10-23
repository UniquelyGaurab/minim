package com.minim.music.ui

import android.graphics.Outline
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.ImageView

/**
 * v4.9.6: one way to make an artist photo a perfect circle, used everywhere a round artist photo
 * shows (Stats boxes, today's artists sheet, artist search pills).
 *
 * The circle is the largest one that fits the view, centred, so it stays round even if the view is
 * ever laid out a little wider than tall. The photo must sit fully inside its parent's content
 * area (no negative margins): a parent ViewGroup clips its children to its padding by default,
 * which is what cut the left side of the photos flat in 4.9.3 to 4.9.5.
 */
object RoundPhoto {

    private val OVAL = object : ViewOutlineProvider() {
        override fun getOutline(view: View, outline: Outline) {
            val w = view.width
            val h = view.height
            val d = minOf(w, h)
            val left = (w - d) / 2
            val top = (h - d) / 2
            outline.setOval(left, top, left + d, top + d)
        }
    }

    fun apply(view: ImageView) {
        view.scaleType = ImageView.ScaleType.CENTER_CROP
        view.outlineProvider = OVAL
        view.clipToOutline = true
        view.invalidateOutline()
    }
}
