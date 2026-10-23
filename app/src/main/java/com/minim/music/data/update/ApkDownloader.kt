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
class ApkDownloader(client: OkHttpClient) {

    private val client: OkHttpClient = client.newBuilder()
        .cache(null)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(0, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    @Volatile private var call: Call? = null
    @Volatile private var cancelled = false

    fun cancel() {
        cancelled = true
        call?.cancel()
    }

    /**
     * Downloads [url] to [dir]/[fileName]. [expectedSize] is GitHub's size (or -1). [onProgress]
     * gets (bytes so far, total or -1), at most about every 1% / 64 KB.
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
        val request = Request.Builder()
            .url(url)
            .cacheControl(CacheControl.FORCE_NETWORK)
            .header("Accept", "application/octet-stream")
            .header("User-Agent", userAgent)
            .build()
        val c = client.newCall(request)
        call = c
        if (cancelled) { c.cancel(); return DownloadResult.Cancelled }
        try {
            c.execute().use { response ->
                if (!response.isSuccessful) {
                    return DownloadResult.Failed(DownloadResult.Kind.SERVER, "HTTP ${response.code}")
                }
                val body = response.body ?: return DownloadResult.Failed(DownloadResult.Kind.SERVER, "no body")
                val declared = body.contentLength()
                if (declared > UpdateRules.MAX_APK_BYTES) return DownloadResult.Failed(DownloadResult.Kind.BROKEN, "too big")
                if (expectedSize > 0 && declared > 0 && declared != expectedSize) {
                    return DownloadResult.Failed(DownloadResult.Kind.BROKEN, "size $declared != $expectedSize")
                }
                val total = if (expectedSize > 0) expectedSize else declared
                var done = 0L
                var lastReported = -1L
                val step = if (total > 0) maxOf(64L * 1024L, total / 100) else 64L * 1024L
                onProgress(0L, total)
                val out = try {
                    part.outputStream()
                } catch (e: IOException) {
                    return DownloadResult.Failed(DownloadResult.Kind.STORAGE, e.javaClass.simpleName)
                }
                out.use { sink ->
                    val input = body.byteStream()
                    val buf = ByteArray(32 * 1024)
                    while (true) {
                        if (cancelled) { part.delete(); return DownloadResult.Cancelled }
                        val n = input.read(buf)
                        if (n < 0) break
                        try {
                            sink.write(buf, 0, n)
                        } catch (e: IOException) {
                            part.delete()
                            return DownloadResult.Failed(DownloadResult.Kind.STORAGE, e.javaClass.simpleName)
                        }
                        done += n
                        if (done > UpdateRules.MAX_APK_BYTES) {
                            part.delete()
                            return DownloadResult.Failed(DownloadResult.Kind.BROKEN, "too big")
                        }
                        if (done - lastReported >= step) {
                            lastReported = done
                            onProgress(done, total)
                        }
                    }
                }
                if (cancelled) { part.delete(); return DownloadResult.Cancelled }
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
            }
        } catch (e: IOException) {
            part.delete()
            if (cancelled) return DownloadResult.Cancelled
            // Couldn't connect, or the connection dropped mid-download: both read as "check your connection"
            return DownloadResult.Failed(DownloadResult.Kind.OFFLINE, e.javaClass.simpleName)
        } catch (e: Exception) {
            part.delete()
            if (cancelled) return DownloadResult.Cancelled
            return DownloadResult.Failed(DownloadResult.Kind.BROKEN, e.javaClass.simpleName)
        } finally {
            call = null
        }
    }
}
