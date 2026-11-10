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
    val contentLength: Long,
    /**
     * 5.8.5: which audio track of the video this format belongs to, for videos that have several
     * (an original plus dubs). "ORIGINAL", "DUBBED", "DESCRIPTIVE", "SECONDARY", or null when
     * YouTube didn't say (almost every song: one track only).
     */
    val trackType: String? = null,
    /** 5.8.5: YouTube's id for the audio track (e.g. "en.4"), or null for single-track videos. */
    val trackId: String? = null,
    /** 5.8.5: the track's language tag (e.g. "en", "hi"), or null when unknown. */
    val locale: String? = null
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
    val otherOptions: Int,
    /** 5.8.5: the audio track the format belongs to (null for single-track videos). */
    val trackId: String? = null
)

/**
 * v4.9.5: picks a format and orders the fallbacks. Pure logic for unit tests.
 *
 * Order: progressive first, then M4A, then WEBM, then closest to 128 kbps (unchanged from 4.8.0).
 * [excludedItags] are formats that already failed with a 403 for this song.
 * [preferItag] keeps the same format when only the link is being refreshed.
 */
object StreamSelection {

    const val ORIGINAL = "ORIGINAL"
    const val DUBBED = "DUBBED"
    const val DESCRIPTIVE = "DESCRIPTIVE"
    const val SECONDARY = "SECONDARY"

    /**
     * 5.8.5: keeps only the formats of the video's original audio track. NewPipe lists every
     * language's copy of a format under the same itag, so without this the first copy listed
     * (often a dub) won. Order of preference:
     * 1. the ORIGINAL track (plus any format with no track info);
     * 2. formats with no track info at all;
     * 3. a SECONDARY, then a DUBBED track (one track only, so a refresh can't switch language);
     * 4. a DESCRIPTIVE (audio description) track, only when it's all there is.
     * A video with one track (no track info anywhere) comes back exactly as it went in.
     * [preferTrackId] keeps the track already playing when it's still in the chosen group.
     */
    fun originalOnly(options: List<StreamOption>, preferTrackId: String? = null): List<StreamOption> {
        if (options.none { it.trackType != null || it.trackId != null }) return options
        val untyped = options.filter { it.trackType == null }
        val originals = options.filter { it.trackType.equals(ORIGINAL, ignoreCase = true) }
        val chosen = when {
            originals.isNotEmpty() -> originals + untyped
            untyped.isNotEmpty() -> untyped
            else -> {
                val alt = options.filter { it.trackType.equals(SECONDARY, ignoreCase = true) }
                    .ifEmpty { options.filter { it.trackType.equals(DUBBED, ignoreCase = true) } }
                    .ifEmpty { options.filter { !it.trackType.equals(DESCRIPTIVE, ignoreCase = true) } }
                    .ifEmpty { options }
                oneTrack(alt, preferTrackId)
            }
        }
        // Several tracks in the chosen group (rare): stay on one, the playing one when it's there
        val ids = chosen.mapNotNull { it.trackId }.distinct()
        return if (ids.size > 1) oneTrack(chosen, preferTrackId) else chosen
    }

    private fun oneTrack(options: List<StreamOption>, preferTrackId: String?): List<StreamOption> {
        val keep = preferTrackId?.takeIf { id -> options.any { it.trackId == id } }
            ?: options.firstOrNull { it.trackId != null }?.trackId
            ?: return options
        return options.filter { it.trackId == keep || it.trackId == null }
    }

    /**
     * 5.4.0: the format to save offline: the best quality, not the 128 kbps playback pick.
     * Only whole files with a known size on a videoplayback link qualify (so they can be pulled
     * in ranged pieces). Highest bitrate first (Opus ~160 kbps, itag 251, usually), and on a tie
     * Opus/WEBM before M4A since it sounds better at the same bitrate.
     */
    fun bestForSaving(options: List<StreamOption>, excludedItags: Set<Int> = emptySet()): StreamOption? =
        originalOnly(options)
            .filter {
                it.url.isNotBlank() && it.progressive && it.contentLength > 0 &&
                    it.itag !in excludedItags && YoutubeStreamRequest.isVideoPlaybackUrl(it.url)
            }
            .sortedWith(
                compareByDescending<StreamOption> { it.bitrate }
                    .thenBy { if (it.formatName.contains("WEBM", ignoreCase = true)) 0 else 1 }
            )
            .firstOrNull()

    /** 5.4.0: file extension for a saved format, exactly what YouTube sent. */
    fun fileFormat(option: StreamOption): String =
        if (option.formatName.contains("WEBM", ignoreCase = true) || option.itag in setOf(249, 250, 251, 774)) "webm" else "m4a"


    fun order(options: List<StreamOption>, preferTrackId: String? = null): List<StreamOption> =
        originalOnly(options, preferTrackId)
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
     * 5.8.5: only the original audio track is ever considered ([originalOnly]); [preferTrackId]
     * keeps a link refresh or a 403 fallback on the track that was playing.
     */
    fun pick(
        options: List<StreamOption>,
        excludedItags: Set<Int> = emptySet(),
        preferItag: Int? = null,
        preferTrackId: String? = null
    ): StreamOption? {
        val ordered = order(options, preferTrackId)
        if (preferItag != null) {
            ordered.firstOrNull { it.itag == preferItag }?.let { return it }
        }
        return ordered.firstOrNull { it.itag !in excludedItags }
    }

    /** Usable formats left after [excludedItags], not counting [current]. */
    fun remaining(options: List<StreamOption>, excludedItags: Set<Int>, current: Int, preferTrackId: String? = null): Int =
        order(options, preferTrackId).count { it.itag !in excludedItags && it.itag != current }
}
