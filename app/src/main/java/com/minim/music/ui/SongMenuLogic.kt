package com.minim.music.ui

/** v4.8: small pure pieces of the song menu, kept here so they can be unit tested. */
object SongMenuLogic {

    /**
     * "Play next" on a queue row: where to move the song at [from] so it plays right after the one
     * at [current]. Null when there's nothing to do (it's the playing song, it's already next, or
     * nothing is playing). Moving an earlier song shifts the playing one up by one.
     */
    fun playNextTarget(from: Int, current: Int, size: Int): Int? {
        if (current < 0 || from < 0 || from >= size || from == current || from == current + 1) return null
        return if (from < current) current else current + 1
    }

    private val VIDEO_ID = Regex("^[A-Za-z0-9_-]{11}$")

    /** Link for Share: a YouTube Music link when the id is a video id, else the stored source link. */
    fun shareUrl(id: String, sourceUrl: String): String =
        if (VIDEO_ID.matches(id)) "https://music.youtube.com/watch?v=$id"
        else sourceUrl.ifBlank { "https://music.youtube.com/watch?v=$id" }

    /** Text for Share: title · artist, then the link. */
    fun shareText(title: String, artist: String, url: String): String =
        if (artist.isBlank()) "$title\n$url" else "$title · $artist\n$url"
}
