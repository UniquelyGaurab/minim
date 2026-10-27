package com.minim.music.ui

import android.widget.ImageView
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
     * Shows [res] in [view] at [heightPx] tall and as wide as the logo's shape needs, so the
     * whole name fits without stretching.
     */
    fun show(view: ImageView, res: Int, heightPx: Int) {
        view.setImageResource(res)
        val d = view.drawable
        val ratio = if (d != null && d.intrinsicHeight > 0) d.intrinsicWidth.toFloat() / d.intrinsicHeight else 3.5f
        view.layoutParams = view.layoutParams.apply {
            height = heightPx
            width = (heightPx * ratio).toInt()
        }
    }
}
