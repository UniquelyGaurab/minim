package com.minim.music.data.offline

import com.minim.music.data.source.YoutubeStreamRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.coroutineContext

/** 5.4.0: the stream to save: its URL (expires after a few hours) and its exact size in bytes. */
data class FetchSource(val url: String, val length: Long)

/** 5.4.0: YouTube said no (403 / 410) even with a fresh link, or the file never fully arrived. */
class FetchFailedException(message: String, val httpCode: Int = 0) : IOException(message)

/**
 * 5.4.0: saves one audio file fast. YouTube slows down each single connection, so the file is
 * split into [segments] parts that download at the same time, each in pieces of up to
 * [pieceBytes] (ranged requests, NewPipe's `&range=` form for videoplayback links). The bytes are
 * written exactly as YouTube sends them, nothing is re-encoded.
 *
 * A refused link (403 / 410) asks [refresh] for a new one and carries on from where each part
 * stopped. A dropped connection is retried a few times with short waits.
 */
class ParallelFetcher(
    private val client: OkHttpClient,
    private val segments: Int = 4,
    private val pieceBytes: Long = 2L * 1024L * 1024L,
    private val retryWaitsMs: List<Long> = listOf(500L, 1_500L, 3_000L, 6_000L)
) {

    /**
     * Downloads [source] into [target] (overwritten). [onBytes] gets the total bytes so far.
     * [refresh] returns a new link for the same format, or null when there is none.
     */
    suspend fun fetch(
        source: FetchSource,
        target: File,
        refresh: suspend () -> FetchSource?,
        onBytes: (Long) -> Unit = {}
    ) = withContext(Dispatchers.IO) {
        require(source.length > 0) { "Unknown length" }
        target.parentFile?.mkdirs()
        RandomAccessFile(target, "rw").use { it.setLength(source.length) }
        val got = AtomicLong(0L)
        val linkLock = Mutex()
        var current = source
        var refreshes = 0

        suspend fun freshLink(stale: FetchSource): FetchSource {
            linkLock.withLock {
                if (current !== stale) return current // another part already refreshed it
                if (refreshes >= 3) throw FetchFailedException("YouTube keeps refusing this song", 403)
                refreshes++
                val next = refresh() ?: throw FetchFailedException("No new link for this song", 403)
                if (next.length != source.length) throw FetchFailedException("The song changed size")
                current = next
                return next
            }
        }

        val count = segments.coerceIn(1, maxOf(1, (source.length / (256L * 1024L)).toInt()))
        val size = (source.length + count - 1) / count
        coroutineScope {
            (0 until count).map { i ->
                val start = i * size
                val endEx = minOf(source.length, start + size)
                async {
                    var pos = start
                    var fails = 0
                    RandomAccessFile(target, "rw").use { raf ->
                        while (pos < endEx) {
                            coroutineContext.ensureActive()
                            val link = current
                            val last = minOf(endEx - 1, pos + pieceBytes - 1)
                            val before = pos
                            val outcome = try {
                                piece(link.url, pos, last, raf) { n ->
                                    pos += n
                                    onBytes(got.addAndGet(n.toLong()))
                                }
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: IOException) {
                                -1
                            }
                            when {
                                outcome == 0 -> fails = 0 // piece done
                                outcome == 403 || outcome == 410 -> freshLink(link)
                                outcome in 400..499 && outcome != 429 ->
                                    throw FetchFailedException("YouTube said $outcome", outcome)
                                else -> {
                                    // Dropped, 429 or 5xx: wait a moment and pick up where it stopped
                                    if (pos > before) fails = 0
                                    if (fails >= retryWaitsMs.size) {
                                        throw FetchFailedException(
                                            if (outcome > 0) "YouTube said $outcome" else "Connection lost", maxOf(outcome, 0)
                                        )
                                    }
                                    delay(retryWaitsMs[fails++])
                                }
                            }
                        }
                    }
                }
            }.awaitAll()
        }
        if (got.get() != source.length) {
            throw FetchFailedException("Got ${got.get()} of ${source.length} bytes")
        }
    }

    /**
     * One ranged request for bytes [from]..[toInclusive], written at their place in [raf].
     * Returns 0 when the piece fully arrived, the HTTP code when refused, -1 when it came short.
     */
    private suspend fun piece(
        url: String,
        from: Long,
        toInclusive: Long,
        raf: RandomAccessFile,
        onBytes: (Int) -> Unit
    ): Int {
        val request = if (YoutubeStreamRequest.isVideoPlaybackUrl(url)) {
            val u = YoutubeStreamRequest.chunkUrl(url, from, toInclusive, requestNumber.incrementAndGet())
            val b = Request.Builder().url(u).post(YoutubeStreamRequest.POST_BODY.toRequestBody(null))
            YoutubeStreamRequest.headersFor(u).forEach { (k, v) -> b.header(k, v) }
            b.build()
        } else {
            Request.Builder().url(url).header("Range", "bytes=$from-$toInclusive")
                .header("Accept-Encoding", "identity").build()
        }
        val call = client.newCall(request)
        val job = coroutineContext[kotlinx.coroutines.Job]
        val handle = job?.invokeOnCompletion { if (job.isCancelled) call.cancel() }
        try {
            call.execute().use { resp ->
                if (!resp.isSuccessful) return resp.code
                // A plain 200 to a Range request means the server sent the whole file: only
                // accept it for the very first byte, and read just the piece's bytes
                if (resp.code == 200 && from > 0 && !YoutubeStreamRequest.isVideoPlaybackUrl(url)) return 416
                val body = resp.body ?: return -1
                val input = body.byteStream()
                val want = toInclusive - from + 1
                var have = 0L
                val buf = ByteArray(64 * 1024)
                raf.seek(from)
                while (have < want) {
                    coroutineContext.ensureActive()
                    val n = input.read(buf, 0, minOf(buf.size.toLong(), want - have).toInt())
                    if (n < 0) break
                    raf.write(buf, 0, n)
                    have += n
                    onBytes(n)
                }
                return if (have == want) 0 else -1
            }
        } finally {
            handle?.dispose()
        }
    }

    private val requestNumber = AtomicLong(0L)
}
