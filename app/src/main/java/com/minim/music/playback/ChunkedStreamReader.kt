package com.minim.music.playback

import com.minim.music.data.source.ResolvedStream
import com.minim.music.data.source.YoutubeStreamRequest
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.io.InputStream

/**
 * v4.9.5: the piece-by-piece YouTube reader behind [YoutubeChunkedDataSource].
 * No Android classes, so unit tests run it against a local fake server.
 *
 * - asks for [chunkBytes] at a time with `&rn=N&range=start-end` in the URL (NewPipe style),
 *   as a POST with NewPipe's body and the headers of the link's client
 * - 403 / 410 / 401 on a piece: a fresh link for the SAME format, then the same byte again
 *   (at most [StreamRecovery.MAX_CONSECUTIVE_REFRESHES] in a row, [StreamRecovery.MAX_REFRESHES_PER_OPEN]
 *   per open); past that, or if the format is gone, a [YoutubeStreamException]
 * - a dropped connection: the same byte again, at most twice in a row
 */
class ChunkedStreamReader(
    private val client: OkHttpClient,
    private val resolver: StreamResolver,
    private val chunkBytes: Long = YoutubeStreamRequest.DEFAULT_CHUNK_BYTES,
    private val excludedItags: (String) -> Set<Int> = { StreamRecovery.excludedItags(it) }
) {
    private var videoId = ""
    private var stream: ResolvedStream? = null
    private var position = 0L
    private var endExclusive = -1L
    private var ended = false

    private var response: Response? = null
    private var body: InputStream? = null
    private var chunkRequested = 0L
    private var chunkRead = 0L
    private var requestNumber = 0L
    private var refreshesThisOpen = 0
    private var consecutiveRefreshes = 0
    private var dropRetries = 0

    /** Response headers of the piece being read (Content-Type helps ExoPlayer pick a parser). */
    var headers: Map<String, List<String>> = emptyMap()
        private set

    /** URL of the last piece requested (for tests and logs). */
    var lastRequestUrl: String? = null
        private set

    /** Number of link refreshes done since [open]. */
    val refreshCount: Int get() = refreshesThisOpen

    /**
     * Starts reading [resolved] at [startPosition]. [length] is how many bytes ExoPlayer wants,
     * or -1 for "to the end". Returns the bytes that will be read, or -1 if unknown.
     */
    @Throws(IOException::class)
    fun open(resolved: ResolvedStream, startPosition: Long, length: Long): Long {
        close()
        videoId = resolved.videoId
        stream = resolved
        position = startPosition
        requestNumber = 0L
        refreshesThisOpen = 0
        consecutiveRefreshes = 0
        dropRetries = 0
        ended = false

        val total = resolved.contentLength
        if (total > 0 && startPosition > total) throw PositionOutOfRangeException()
        endExclusive = when {
            length >= 0 && total > 0 -> minOf(total, startPosition + length)
            length >= 0 -> startPosition + length
            else -> total
        }
        if (endExclusive > 0 && position >= endExclusive) {
            ended = true
        } else {
            openChunk()
        }
        return if (endExclusive > 0) endExclusive - startPosition else -1L
    }

    /** Reads up to [length] bytes. Returns -1 at the end of the song. [onBytes] gets each count. */
    @Throws(IOException::class)
    fun read(buffer: ByteArray, offset: Int, length: Int, onBytes: (Int) -> Unit = {}): Int {
        if (length == 0) return 0
        while (true) {
            if (ended || (endExclusive > 0 && position >= endExclusive)) return -1
            val input = body
            if (input == null) {
                openChunk()
                continue
            }
            val wanted = minOf(length.toLong(), chunkRequested - chunkRead).toInt()
            if (wanted <= 0) {
                closeChunk() // piece done, the next loop opens the next one
                continue
            }
            val n = try {
                input.read(buffer, offset, wanted)
            } catch (e: IOException) {
                closeChunk()
                if (dropRetries++ < 2) continue // connection dropped: pick up from the same byte
                throw e
            }
            if (n == -1) {
                val short = chunkRead < chunkRequested
                val gotNothing = chunkRead == 0L
                closeChunk()
                if (short) {
                    if (endExclusive <= 0) {
                        ended = true // last piece of a song whose size we did not know
                        continue
                    }
                    if (dropRetries++ < 2 && !gotNothing) continue
                    throw IOException("YouTube ended the audio early")
                }
                continue
            }
            position += n
            chunkRead += n
            consecutiveRefreshes = 0
            dropRetries = 0
            onBytes(n)
            return n
        }
    }

    fun close() {
        closeChunk()
        stream = null
        headers = emptyMap()
    }

    @Throws(IOException::class)
    private fun openChunk() {
        while (true) {
            val current = stream ?: throw IOException("No stream")
            val end = YoutubeStreamRequest.chunkEnd(position, chunkBytes, endExclusive)
            val url = YoutubeStreamRequest.chunkUrl(current.url, position, end, requestNumber++)
            lastRequestUrl = url
            val builder = Request.Builder()
                .url(url)
                .post(YoutubeStreamRequest.POST_BODY.toRequestBody(null))
            YoutubeStreamRequest.headersFor(url).forEach { (k, v) -> builder.header(k, v) }

            val resp = client.newCall(builder.build()).execute()
            if (resp.isSuccessful) {
                val input = resp.body?.byteStream()
                if (input == null) {
                    resp.close()
                    throw IOException("Empty response from YouTube")
                }
                response = resp
                body = input
                headers = resp.headers.toMultimap()
                chunkRequested = end - position + 1
                chunkRead = 0L
                return
            }

            val code = resp.code
            resp.close()

            if (code == 416 && endExclusive <= 0) {
                ended = true // asked past the end of a song of unknown length
                return
            }

            val refusable = code == 403 || code == 410 || code == 401
            if (refusable &&
                consecutiveRefreshes < StreamRecovery.MAX_CONSECUTIVE_REFRESHES &&
                refreshesThisOpen < StreamRecovery.MAX_REFRESHES_PER_OPEN
            ) {
                consecutiveRefreshes++
                refreshesThisOpen++
                val fresh = try {
                    resolver.resolve(videoId, excludedItags(videoId), current.itag, true)
                } catch (e: IOException) {
                    throw failure(code, current, e)
                }
                val sameBytes = fresh.chunked && fresh.itag == current.itag &&
                    (current.contentLength <= 0 || fresh.contentLength <= 0 ||
                        fresh.contentLength == current.contentLength)
                if (!sameBytes) throw failure(code, current, null) // format gone: player switches
                stream = fresh
                continue
            }
            throw failure(code, current, null)
        }
    }

    private fun failure(code: Int, current: ResolvedStream, cause: Throwable?) = YoutubeStreamException(
        "Response code: $code", videoId, current.itag, code, current.otherOptions, cause
    )

    private fun closeChunk() {
        try {
            response?.close()
        } catch (_: Exception) {
        }
        response = null
        body = null
    }

    class PositionOutOfRangeException : IOException("Read position is past the end of the song")
}
