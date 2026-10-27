package com.minim.music.data.update

import okhttp3.CacheControl
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/** v4.9.6: how an APK download ended. */
sealed class DownloadResult {
    data class Done(val file: File) : DownloadResult()
    object Cancelled : DownloadResult()
    data class Failed(val kind: Kind, val detail: String) : DownloadResult()

    enum class Kind { OFFLINE, SERVER, STORAGE, BROKEN }
}

/**
 * v4.9.6: downloads a release APK into [dir] with progress.
 *
 * Writes to "<name>.part" and renames only once the whole file (and the size GitHub promised) is
 * there, so a half-finished download is never handed to the installer. No HTTP cache (the APK
 * would push everything else out of it). Blocking: call from a background thread; [cancel] from
 * any thread.
 */
class ApkDownloader(
    client: OkHttpClient,
    /**
     * 5.4.0: how long to wait before each new try after the connection drops mid-download.
     * Mobile data often drops or stalls for a few seconds (switching towers, Data Saver waking
     * up); until 5.3.2 any drop ended the download. Now the download picks up where it stopped
     * (an HTTP Range request) after each wait, and only gives up once all of them are used.
     * A try that got new bytes starts the waits over.
     */
    private val retryWaitsMs: List<Long> = listOf(1_000L, 2_000L, 4_000L, 8_000L, 15_000L, 15_000L, 30_000L, 30_000L),
    private val sleeper: (Long) -> Unit = { Thread.sleep(it) }
) {

    private val client: OkHttpClient = client.newBuilder()
        .cache(null)
        .connectTimeout(15, TimeUnit.SECONDS)
        // 5.4.0: a stalled mobile connection is noticed after 20 s and the download resumes
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(0, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .retryOnConnectionFailure(true)
        .build()

    @Volatile private var call: Call? = null
    @Volatile private var cancelled = false

    fun cancel() {
        cancelled = true
        call?.cancel()
    }

    /**
     * Downloads [url] into [dir]/[fileName] through a ".part" file, reporting (done, total).
     * [expectedSize] is the size GitHub lists for the asset (0 if unknown).
     */
    fun download(
        url: String,
        dir: File,
        fileName: String,
        expectedSize: Long,
        userAgent: String = "Minim",
        onProgress: (Long, Long) -> Unit = { _, _ -> }
    ): DownloadResult {
        if (cancelled) return DownloadResult.Cancelled
        if (!url.startsWith("https://") && !url.startsWith("http://127.0.0.1") && !url.startsWith("http://localhost")) {
            return DownloadResult.Failed(DownloadResult.Kind.BROKEN, "not https")
        }
        try {
            if (!dir.exists() && !dir.mkdirs()) return DownloadResult.Failed(DownloadResult.Kind.STORAGE, "mkdirs")
        } catch (e: SecurityException) {
            return DownloadResult.Failed(DownloadResult.Kind.STORAGE, "mkdirs")
        }
        val target = File(dir, fileName)
        val part = File(dir, "$fileName.part")
        part.delete()
        var done = 0L
        var total = if (expectedSize > 0) expectedSize else -1L
        var lastReported = -1L
        var waitIndex = 0
        var reportedStart = false
        var everGot = false
        try {
            while (true) {
                if (cancelled) { part.delete(); return DownloadResult.Cancelled }
                val before = done
                val outcome = attempt(url, userAgent, part, done, expectedSize) { got, declaredTotal ->
                    if (declaredTotal > 0 && total <= 0) total = declaredTotal
                    if (!reportedStart) { reportedStart = true; onProgress(0L, total) }
                    done = got
                    val step = if (total > 0) maxOf(64L * 1024L, total / 100) else 64L * 1024L
                    if (done - lastReported >= step) {
                        lastReported = done
                        onProgress(done, total)
                    }
                }
                when (outcome) {
                    is Attempt.Finished -> break
                    is Attempt.Stop -> { part.delete(); return outcome.result }
                    is Attempt.Dropped -> {
                        if (cancelled) { part.delete(); return DownloadResult.Cancelled }
                        if (done > before) { waitIndex = 0; everGot = true }
                        // No connection at all from the start: say so after two quick tries
                        // instead of making the user wait through every retry
                        val limit = if (everGot) retryWaitsMs.size else minOf(2, retryWaitsMs.size)
                        if (waitIndex >= limit) {
                            part.delete()
                            return DownloadResult.Failed(DownloadResult.Kind.OFFLINE, outcome.detail)
                        }
                        val wait = retryWaitsMs[waitIndex++]
                        // Sleep in small steps so Cancel still works while waiting
                        var left = wait
                        while (left > 0) {
                            if (cancelled) { part.delete(); return DownloadResult.Cancelled }
                            val s = minOf(left, 250L)
                            sleeper(s)
                            left -= s
                        }
                    }
                }
            }
            if (cancelled) { part.delete(); return DownloadResult.Cancelled }
            if (!reportedStart) onProgress(0L, total)
            if ((total > 0 && done != total) || done == 0L) {
                part.delete()
                return DownloadResult.Failed(DownloadResult.Kind.BROKEN, "got $done of $total")
            }
            onProgress(done, total)
            target.delete()
            if (!part.renameTo(target)) {
                part.delete()
                return DownloadResult.Failed(DownloadResult.Kind.STORAGE, "rename")
            }
            return DownloadResult.Done(target)
        } catch (e: Exception) {
            part.delete()
            if (cancelled) return DownloadResult.Cancelled
            return DownloadResult.Failed(DownloadResult.Kind.BROKEN, e.javaClass.simpleName)
        } finally {
            call = null
        }
    }

    private sealed class Attempt {
        object Finished : Attempt()
        data class Dropped(val detail: String) : Attempt()
        data class Stop(val result: DownloadResult) : Attempt()
    }

    /**
     * One request: from byte 0, or from [from] with a Range header after a drop. Appends to
     * [part]. A server that ignores the Range (200 instead of 206) starts the file over.
     */
    private fun attempt(
        url: String,
        userAgent: String,
        part: File,
        from: Long,
        expectedSize: Long,
        progress: (Long, Long) -> Unit
    ): Attempt {
        val builder = Request.Builder()
            .url(url)
            .cacheControl(CacheControl.FORCE_NETWORK)
            .header("Accept", "application/octet-stream")
            .header("User-Agent", userAgent)
        if (from > 0) builder.header("Range", "bytes=$from-")
        val c = client.newCall(builder.build())
        call = c
        if (cancelled) { c.cancel(); return Attempt.Stop(DownloadResult.Cancelled) }
        var done = from
        try {
            c.execute().use { response ->
                if (from > 0 && response.code == 416) {
                    // Everything already arrived before the drop
                    return Attempt.Finished
                }
                if (!response.isSuccessful) {
                    return if (response.code >= 500 && from > 0) Attempt.Dropped("HTTP ${response.code}")
                    else Attempt.Stop(DownloadResult.Failed(DownloadResult.Kind.SERVER, "HTTP ${response.code}"))
                }
                val body = response.body ?: return Attempt.Stop(DownloadResult.Failed(DownloadResult.Kind.SERVER, "no body"))
                val resumed = from > 0 && response.code == 206
                if (!resumed) done = 0L
                val declared = body.contentLength()
                val fullSize = when {
                    resumed && declared > 0 -> from + declared
                    !resumed -> declared
                    else -> -1L
                }
                if (fullSize > UpdateRules.MAX_APK_BYTES) return Attempt.Stop(DownloadResult.Failed(DownloadResult.Kind.BROKEN, "too big"))
                if (expectedSize > 0 && fullSize > 0 && fullSize != expectedSize) {
                    return Attempt.Stop(DownloadResult.Failed(DownloadResult.Kind.BROKEN, "size $fullSize != $expectedSize"))
                }
                progress(done, fullSize)
                val out = try {
                    java.io.FileOutputStream(part, resumed)
                } catch (e: IOException) {
                    return Attempt.Stop(DownloadResult.Failed(DownloadResult.Kind.STORAGE, e.javaClass.simpleName))
                }
                out.use { sink ->
                    val input = body.byteStream()
                    val buf = ByteArray(32 * 1024)
                    while (true) {
                        if (cancelled) return Attempt.Stop(DownloadResult.Cancelled)
                        val n = try {
                            input.read(buf)
                        } catch (e: IOException) {
                            sink.flush()
                            progress(done, fullSize)
                            return if (cancelled) Attempt.Stop(DownloadResult.Cancelled) else Attempt.Dropped(e.javaClass.simpleName)
                        }
                        if (n < 0) break
                        try {
                            sink.write(buf, 0, n)
                        } catch (e: IOException) {
                            return Attempt.Stop(DownloadResult.Failed(DownloadResult.Kind.STORAGE, e.javaClass.simpleName))
                        }
                        done += n
                        if (done > UpdateRules.MAX_APK_BYTES) {
                            return Attempt.Stop(DownloadResult.Failed(DownloadResult.Kind.BROKEN, "too big"))
                        }
                        progress(done, fullSize)
                    }
                }
                // The body ended early (connection closed cleanly but short): treat as a drop
                if (expectedSize > 0 && done < expectedSize) return Attempt.Dropped("short body")
                return Attempt.Finished
            }
        } catch (e: IOException) {
            if (cancelled) return Attempt.Stop(DownloadResult.Cancelled)
            // Couldn't connect, or the connection dropped: wait and pick up where it stopped
            progress(done, -1L)
            return Attempt.Dropped(e.javaClass.simpleName)
        }
    }
}
