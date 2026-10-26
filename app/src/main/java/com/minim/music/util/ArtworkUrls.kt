package com.minim.music.util

import java.net.URI
import kotlin.math.roundToInt

/**
 * v4.9.8: which image URL to fetch for a view of a given size. Pure, so it's unit-tested.
 *
 * Why: search, mixes and playlists hand over tiny thumbnails (YouTube Music covers at =w60-h60 /
 * =w120-h120, YouTube video frames at 246x138 with black bars). Shown on a 1080px Now Playing they
 * looked cheap. Every load now goes through [candidates], which asks for a size that fits the view:
 *
 * - Google image hosts (lh3/yt3.googleusercontent.com, *.ggpht.com): the size option at the end of the
 *   URL (`=w60-h60-l90-rj`, `=s88-c-k-c0x00ffffff-no-rj`) is rewritten to [bucket] (300 / 544 / 1080),
 *   keeping the other options.
 * - YouTube video thumbnails (i.ytimg.com / img.youtube.com `/vi/<id>/...`): rebuilt from the video id as
 *   maxresdefault (1280x720) -> sddefault (640x480) -> hqdefault (480x360), smallest list first for small
 *   views, then the original URL as the last fallback. Missing sizes answer 404 and the next one is tried.
 *   These frames are 16:9 (or 4:3 with black bars), so [squareCrop] cuts a centred square from the picture
 *   area.
 * - Anything else is fetched as it is.
 */
object ArtworkUrls {

    const val SMALL = 300
    const val MEDIUM = 544
    const val LARGE = 1080

    /** Views up to this many pixels (list rows, the Home pill) get [SMALL]. */
    const val SMALL_MAX_PX = 200
    /** Views up to this many pixels (shelves, the Stats boxes) get [MEDIUM]; bigger ones get [LARGE]. */
    const val MEDIUM_MAX_PX = 544

    /** The source size asked for a view [targetPx] pixels across. */
    fun bucket(targetPx: Int): Int = when {
        targetPx <= SMALL_MAX_PX -> SMALL
        targetPx <= MEDIUM_MAX_PX -> MEDIUM
        else -> LARGE
    }

    /** URLs to try, best first, for a view [targetPx] pixels across. Never empty for a non-blank URL. */
    fun candidates(url: String?, targetPx: Int): List<String> {
        val clean = url?.trim().orEmpty()
        if (clean.isEmpty()) return emptyList()
        val id = videoThumbId(clean)
        if (id != null) {
            val names = when (bucket(targetPx)) {
                SMALL -> listOf("hqdefault")
                MEDIUM -> listOf("sddefault", "hqdefault")
                else -> listOf("maxresdefault", "sddefault", "hqdefault")
            }
            val list = names.map { "https://i.ytimg.com/vi/$id/$it.jpg" }.toMutableList()
            if (clean !in list) list.add(clean)
            return list
        }
        if (isGoogleImage(clean)) {
            val sized = googleSized(clean, bucket(targetPx))
            return if (sized == clean) listOf(clean) else listOf(sized, clean)
        }
        return listOf(clean)
    }

    // ---- Google image hosts ----------------------------------------------------------------------

    private val GOOGLE_HOSTS = listOf("googleusercontent.com", "ggpht.com")

    fun isGoogleImage(url: String): Boolean {
        val host = hostOf(url) ?: return false
        return GOOGLE_HOSTS.any { host == it || host.endsWith(".$it") }
    }

    private val WH = SafeRegex.of("""(^|-)w\d+-h\d+(?=-|$)""")
    private val H_THEN_W = SafeRegex.of("""(^|-)h\d+-w\d+(?=-|$)""")
    private val S = SafeRegex.of("""(^|-)s\d+(?=-|$)""")

    /**
     * [url] with its size option set to [size] x [size]. Only the options part after the last '=' in the
     * path is touched (a query string is left alone). A URL without a size option is returned unchanged.
     */
    fun googleSized(url: String, size: Int): String {
        if (!isGoogleImage(url)) return url
        val queryAt = url.indexOf('?').let { if (it < 0) url.length else it }
        val path = url.substring(0, queryAt)
        val query = url.substring(queryAt)
        val eq = path.lastIndexOf('=')
        val slash = path.lastIndexOf('/')
        if (eq < 0 || eq < slash) return url
        val opts = path.substring(eq + 1)
        val newOpts = when {
            WH.containsMatchIn(opts) -> WH.replaceFirst(opts, "\$1w$size-h$size")
            H_THEN_W.containsMatchIn(opts) -> H_THEN_W.replaceFirst(opts, "\$1w$size-h$size")
            S.containsMatchIn(opts) -> S.replaceFirst(opts, "\$1s$size")
            else -> return url
        }
        return path.substring(0, eq + 1) + newOpts + query
    }

    // ---- YouTube video thumbnails ----------------------------------------------------------------

    private val VIDEO_ID = SafeRegex.of("""^[A-Za-z0-9_-]{11}$""")

    /** The video id of an i.ytimg.com / img.youtube.com `/vi/<id>/...` thumbnail, or null. */
    fun videoThumbId(url: String): String? {
        val host = hostOf(url) ?: return null
        if (host != "i.ytimg.com" && !host.matches(SafeRegex.of("""i\d\.ytimg\.com""")) && host != "img.youtube.com") return null
        val path = try {
            URI(url).rawPath
        } catch (e: Exception) {
            null
        } ?: return null
        val parts = path.split('/').filter { it.isNotEmpty() }
        if (parts.size < 3) return null
        if (parts[0] != "vi" && parts[0] != "vi_webp") return null
        val id = parts[1]
        val file = parts[2].substringBefore('.')
        // Live and storyboard frames have no fixed sizes to rebuild
        if (file.endsWith("_live") || file.startsWith("sb")) return null
        return id.takeIf { VIDEO_ID.matches(it) }
    }

    fun isVideoThumb(url: String): Boolean = videoThumbId(url) != null

    /** A square inside an image: left, top, side. */
    data class Square(val left: Int, val top: Int, val side: Int)

    /**
     * The centred square to keep from a [width] x [height] image. For a video frame the picture is 16:9:
     * a 4:3 file (hqdefault, sddefault) has black bars above and below it, so the square is cut from the
     * 16:9 band in the middle. Other images: the largest centred square.
     */
    fun squareCrop(width: Int, height: Int, videoFrame: Boolean): Square {
        if (width <= 0 || height <= 0) return Square(0, 0, 0)
        val pictureHeight = if (videoFrame) minOf(height, (width * 9f / 16f).roundToInt()) else height
        val side = minOf(width, pictureHeight).coerceAtLeast(1)
        return Square((width - side) / 2, (height - side) / 2, side)
    }

    private fun hostOf(url: String): String? = try {
        URI(url).host?.lowercase()
    } catch (e: Exception) {
        null
    }
}
