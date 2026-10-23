package com.minim.music.data.source

import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper

/**
 * v4.9.5: how Minim asks googlevideo.com for audio, copied from NewPipe's own player
 * (TeamNewPipe/NewPipe `YoutubeHttpDataSource`, dev branch, October 2026):
 *
 * - every `/videoplayback` request is a POST with the 2-byte body `x\u0000`
 * - the byte range goes in the URL (`&range=start-end`), not in a Range header
 * - every request carries a request number (`&rn=0`, `&rn=1`, ...)
 * - the User-Agent matches the YouTube client the extractor got the stream from
 *   (the visionOS user agent for `c=VISIONOS` URLs, desktop Firefox for everything else)
 * - `c=WEB` URLs also get Origin / Referer / Sec-Fetch headers, like a browser tab
 *
 * Pure string logic, no Android classes, so it is covered by unit tests.
 */
object YoutubeStreamRequest {

    /** Same desktop user agent NewPipe's DownloaderImpl sends (Firefox 153 ESR). */
    const val DESKTOP_USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:153.0) Gecko/20100101 Firefox/153.0"

    private const val YOUTUBE_BASE_URL = "https://www.youtube.com"

    /** NewPipe's POST body for videoplayback requests: {0x78, 0x00}. */
    val POST_BODY: ByteArray = byteArrayOf(0x78, 0)

    /** Audio is fetched in 1 MB pieces: a 4-minute song at 128 kbps is about 4 requests. */
    const val DEFAULT_CHUNK_BYTES: Long = 1L * 1024L * 1024L

    fun isVideoPlaybackUrl(url: String): Boolean {
        val path = url.substringAfter("://", "").substringAfter('/', "").substringBefore('?')
        return path.startsWith("videoplayback")
    }

    /** The `c=` value of a streaming URL (VISIONOS, WEB, ANDROID, IOS ...), or null. */
    fun clientOf(url: String): String? = queryParam(url, "c")

    /** Headers for one videoplayback request, matching the client the URL belongs to. */
    fun headersFor(url: String): Map<String, String> {
        val headers = LinkedHashMap<String, String>()
        val client = clientOf(url).orEmpty()
        headers["User-Agent"] = if (YoutubeParsingHelper.isVisionOsStreamingUrl(url)) {
            YoutubeParsingHelper.getVisionOsUserAgent(null)
        } else {
            DESKTOP_USER_AGENT
        }
        // NewPipe's isWebStreamingUrl matches "&c=WEB" (WEB, WEB_REMIX, WEB_EMBEDDED_PLAYER ...)
        if (client.startsWith("WEB") || YoutubeParsingHelper.isWebStreamingUrl(url)) {
            headers["Origin"] = YOUTUBE_BASE_URL
            headers["Referer"] = YOUTUBE_BASE_URL
            headers["Sec-Fetch-Dest"] = "empty"
            headers["Sec-Fetch-Mode"] = "cors"
            headers["Sec-Fetch-Site"] = "cross-site"
        }
        headers["Accept-Encoding"] = "identity"
        return headers
    }

    /**
     * The URL for one chunk: the stream URL without any old `range`/`rn`, plus
     * `&rn=<requestNumber>&range=<start>-<endInclusive>` (NewPipe's order).
     */
    fun chunkUrl(baseUrl: String, start: Long, endInclusive: Long, requestNumber: Long): String {
        require(start >= 0 && endInclusive >= start) { "Bad range $start-$endInclusive" }
        val clean = removeParams(baseUrl, setOf("range", "rn"))
        val sep = if (clean.contains('?')) "&" else "?"
        return "$clean${sep}rn=$requestNumber&range=$start-$endInclusive"
    }

    /** Last byte (inclusive) of the chunk that starts at [position]. */
    fun chunkEnd(position: Long, chunkBytes: Long, endExclusive: Long): Long {
        val byChunk = position + chunkBytes - 1
        return if (endExclusive > 0) minOf(byChunk, endExclusive - 1) else byChunk
    }

    /** Total size from the URL's `clen` parameter, or -1. */
    fun contentLength(url: String): Long = queryParam(url, "clen")?.toLongOrNull() ?: -1L

    /** Link expiry in epoch ms from the URL's `expire` parameter (seconds), or -1. */
    fun expiresAtMs(url: String): Long =
        queryParam(url, "expire")?.toLongOrNull()?.let { it * 1000L } ?: -1L

    /** The `itag` parameter of the URL, or -1. */
    fun itagOf(url: String): Int = queryParam(url, "itag")?.toIntOrNull() ?: -1

    fun queryParam(url: String, name: String): String? {
        val query = url.substringAfter('?', "")
        if (query.isEmpty()) return null
        for (part in query.split('&')) {
            val eq = part.indexOf('=')
            val key = if (eq >= 0) part.substring(0, eq) else part
            if (key == name) return if (eq >= 0) part.substring(eq + 1) else ""
        }
        return null
    }

    fun removeParams(url: String, names: Set<String>): String {
        val q = url.indexOf('?')
        if (q < 0) return url
        val kept = url.substring(q + 1).split('&').filter { part ->
            part.isNotEmpty() && part.substringBefore('=') !in names
        }
        val base = url.substring(0, q)
        return if (kept.isEmpty()) base else base + "?" + kept.joinToString("&")
    }
}
