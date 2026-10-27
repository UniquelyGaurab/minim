package com.minim.music.ui

import com.minim.music.util.SafeRegex

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

    private val VIDEO_ID = SafeRegex.of("^[A-Za-z0-9_-]{11}$")

    /** Link for Share: a YouTube Music link when the id is a video id, else the stored source link. */
    fun shareUrl(id: String, sourceUrl: String): String =
        if (VIDEO_ID.matches(id)) "https://music.youtube.com/watch?v=$id"
        else sourceUrl.ifBlank { "https://music.youtube.com/watch?v=$id" }

    /** Text for Share: title · artist, then the link. */
    fun shareText(title: String, artist: String, url: String): String =
        if (artist.isBlank()) "$title\n$url" else "$title · $artist\n$url"

    /** 5.4.1: the Save offline row in the Now Playing menu: not saved, saving now, or saved. */
    enum class OfflineRowState { SAVE, SAVING, SAVED }

    /** How the row looks: its label, its icon, and the ring's progress (null = no ring). */
    data class OfflineRowLook(val label: Int, val icon: Int, val ring: Float?)

    /** Saving wins over saved (a song being saved again shows its ring). */
    fun offlineRowState(saved: Boolean, saving: Float?): OfflineRowState = when {
        saving != null -> OfflineRowState.SAVING
        saved -> OfflineRowState.SAVED
        else -> OfflineRowState.SAVE
    }

    /**
     * Same words and icons as the long-press menu: "Save offline" until it's saving or saved, then
     * "Remove from offline" (which also stops a save that's running). While saving, the row shows
     * the small ring from the song lists.
     */
    fun offlineRowLook(saved: Boolean, saving: Float?): OfflineRowLook = when (offlineRowState(saved, saving)) {
        OfflineRowState.SAVE -> OfflineRowLook(com.minim.music.R.string.offline_save, com.minim.music.R.drawable.ic_offline, null)
        OfflineRowState.SAVING -> OfflineRowLook(
            com.minim.music.R.string.offline_remove, com.minim.music.R.drawable.ic_offline_filled, saving!!.coerceIn(0f, 1f)
        )
        OfflineRowState.SAVED -> OfflineRowLook(com.minim.music.R.string.offline_remove, com.minim.music.R.drawable.ic_offline_filled, null)
    }
}
