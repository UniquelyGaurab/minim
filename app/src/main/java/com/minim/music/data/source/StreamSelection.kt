package com.minim.music.data.source

import kotlin.math.abs

/** One audio format the extractor offered for a song. */
data class StreamOption(
    val itag: Int,
    val url: String,
    val formatName: String,
    val bitrate: Int,
    val progressive: Boolean,
    /** Bytes, from the extractor or the URL's `clen`; -1 when unknown. */
    val contentLength: Long
)

/** The stream picked for playback, with everything the data source needs. */
data class ResolvedStream(
    val videoId: String,
    val url: String,
    val itag: Int,
    val contentLength: Long,
    /** True for a YouTube videoplayback URL that is fetched in chunks. False = HLS / other. */
    val chunked: Boolean,
    /** How many other usable formats exist for this song (for the fallback). */
    val otherOptions: Int
)

/**
 * v4.9.5: picks a format and orders the fallbacks. Pure logic for unit tests.
 *
 * Order: progressive first, then M4A, then WEBM, then closest to 128 kbps (unchanged from 4.8.0).
 * [excludedItags] are formats that already failed with a 403 for this song.
 * [preferItag] keeps the same format when only the link is being refreshed.
 */
object StreamSelection {

    fun order(options: List<StreamOption>): List<StreamOption> =
        options
            .filter { it.url.isNotBlank() && it.progressive }
            .sortedWith(
                compareBy<StreamOption> {
                    when {
                        it.formatName.contains("M4A", ignoreCase = true) -> 0
                        it.formatName.contains("WEBM", ignoreCase = true) -> 1
                        else -> 2
                    }
                }.thenBy {
                    val bitrate = if (it.bitrate > 0) it.bitrate else 128
                    abs(bitrate - 128)
                }
            )

    /**
     * The option to play, or null when nothing usable is left.
     * With [preferItag] set and still offered, that one wins.
     */
    fun pick(
        options: List<StreamOption>,
        excludedItags: Set<Int> = emptySet(),
        preferItag: Int? = null
    ): StreamOption? {
        val ordered = order(options)
        if (preferItag != null) {
            ordered.firstOrNull { it.itag == preferItag }?.let { return it }
        }
        return ordered.firstOrNull { it.itag !in excludedItags }
    }

    /** Usable formats left after [excludedItags], not counting [current]. */
    fun remaining(options: List<StreamOption>, excludedItags: Set<Int>, current: Int): Int =
        order(options).count { it.itag !in excludedItags && it.itag != current }
}
