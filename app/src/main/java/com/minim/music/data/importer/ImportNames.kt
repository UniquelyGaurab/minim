package com.minim.music.data.importer

/** 5.3.0: small helpers for saving an import (kept out of the activity so they can be tested). */
object ImportNames {

    /** The name to try on the [n]th attempt: "Chill Mix", then "Chill Mix (2)", "Chill Mix (3)"... */
    fun candidate(base: String, n: Int): String {
        val clean = base.trim().ifBlank { "Imported playlist" }.take(MAX_NAME)
        return if (n <= 1) clean else "$clean ($n)"
    }

    /** "3:45" for a song length, empty when unknown. */
    fun length(sec: Long): String = if (sec <= 0) "" else "%d:%02d".format(sec / 60, sec % 60)

    /** What kind of YouTube list a list id is: an album (OLAK5uy_), a mix (RD...) or a playlist. */
    fun youTubeKind(listId: String): String = when {
        listId.startsWith("OLAK5uy_") -> "album"
        listId.startsWith("RD") -> "mix"
        else -> "playlist"
    }

    private const val MAX_NAME = 80
}
