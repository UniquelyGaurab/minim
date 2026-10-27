package com.minim.music.ui

import android.graphics.Rect
import android.widget.ImageView
import android.widget.TextView
import com.minim.music.R
import com.minim.music.data.importer.ImportOrigin

/**
 * 5.4.0: the full logos (icon + name) of where an imported playlist came from, drawn in one
 * colour and tinted with the app colour ("tint:fg"). Used in the playlist header, on the
 * Library tile and on the import review screen. Pasted song lists have no logo.
 */
object SourceLogos {

    fun wordmark(o: ImportOrigin?): Int? = when (o) {
        ImportOrigin.SPOTIFY -> R.drawable.ic_wordmark_spotify
        ImportOrigin.YOUTUBE_MUSIC -> R.drawable.ic_wordmark_youtube_music
        ImportOrigin.YOUTUBE -> R.drawable.ic_wordmark_youtube
        ImportOrigin.TEXT, null -> null
    }

    fun name(o: ImportOrigin): String = when (o) {
        ImportOrigin.SPOTIFY -> "Spotify"
        ImportOrigin.YOUTUBE_MUSIC -> "YouTube Music"
        ImportOrigin.YOUTUBE -> "YouTube"
        ImportOrigin.TEXT -> o.label
    }

    /**
     * 5.4.1: all three logos are drawn on one grid (tools/size_wordmarks.py): the icon is
     * [ICON_UNITS] tall and the capital letters are [CAP_UNITS] tall, centred on the icon. So at the
     * same view height the play button, the YouTube Music circle and the Spotify waves come out the
     * same height, and "YouTube", "YouTube Music" and "Spotify" get the same letter height. Only the
     * width differs (YouTube Music is longer).
     */
    const val ICON_UNITS = 100f
    const val CAP_UNITS = 70f

    /** How tall to show a logo so its capitals are [capPx] tall, the same as the "from" next to it. */
    fun heightForCap(capPx: Float): Int = Math.round(capPx * ICON_UNITS / CAP_UNITS).coerceAtLeast(1)

    /** The capital letter height of [text]'s font at its size, in pixels. */
    fun capHeight(text: TextView): Float {
        val r = Rect()
        text.paint.getTextBounds("H", 0, 1, r)
        // Before the font is measured (or with no font), Poppins capitals are 0.698 of the text size
        return if (r.height() > 0) r.height().toFloat() else text.textSize * 0.698f
    }

    /**
     * Shows [res] in [view] with its letters as tall as the capitals of [from] ("from"), and as
     * wide as the logo's name needs, so the whole name fits without stretching.
     */
    fun show(view: ImageView, res: Int, from: TextView) = show(view, res, heightForCap(capHeight(from)))

    /** Shows [res] in [view] at [heightPx] tall and as wide as the logo needs. */
    fun show(view: ImageView, res: Int, heightPx: Int) {
        view.setImageResource(res)
        val d = view.drawable
        val ratio = if (d != null && d.intrinsicHeight > 0) d.intrinsicWidth.toFloat() / d.intrinsicHeight else 4.4f
        view.layoutParams = view.layoutParams.apply {
            height = heightPx
            width = Math.round(heightPx * ratio)
        }
    }
}
