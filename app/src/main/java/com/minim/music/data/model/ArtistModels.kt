package com.minim.music.data.model

/**
 * v4.6.1: what the number under an artist's name really is. YouTube Music's artist results say
 * "647M monthly audience" for most artists and "155K subscribers" for some; the channel page
 * gives a real subscriber count when it shows one. UNKNOWN when the label couldn't be read.
 */
enum class ArtistCountKind { MONTHLY_LISTENERS, SUBSCRIBERS, UNKNOWN }

/** v4.6: an artist as it appears in search (YouTube Music artist result). */
data class ArtistRef(
    val url: String,
    val name: String,
    /** -1 when YouTube doesn't say. What it counts is in [countKind]. */
    val count: Long,
    val thumbnailUrl: String,
    val countKind: ArtistCountKind = ArtistCountKind.UNKNOWN
)

/** v4.6: an album (or single / EP) on the artist page. Opens in the playlist screen. */
data class Album(
    val url: String,
    val title: String,
    val subtitle: String,
    val thumbnailUrl: String
)

/** v4.6: everything the Artist page shows. */
data class ArtistPage(
    val url: String,
    val name: String,
    /** -1 when unknown. What it counts is in [countKind]. */
    val count: Long,
    val countKind: ArtistCountKind,
    /** Square photo for the 1:1 header, or "" for the plain green block. */
    val photoUrl: String,
    val songs: List<Track>,
    val albums: List<Album>
)
