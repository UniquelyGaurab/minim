package com.minim.music.data.greetings

import okhttp3.CacheControl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * v4.9.6: downloads greetings.json from the public repo with the app's own OkHttp client.
 * Sends the last ETag so an unchanged file costs almost nothing, and never reads more than
 * [RemoteGreetingsStore.MAX_BYTES] (a bigger file counts as a failed download).
 */
class OkHttpGreetingsFetcher(
    client: OkHttpClient,
    private val url: String = RemoteGreetingsSync.URL
) : GreetingsFetcher {

    private val client: OkHttpClient = client.newBuilder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .callTimeout(20, TimeUnit.SECONDS)
        .build()

    override fun fetch(etag: String?): FetchResult {
        val request = Request.Builder()
            .url(url)
            .cacheControl(CacheControl.FORCE_NETWORK)
            .header("Accept", "application/json, text/plain, */*")
            .apply { if (!etag.isNullOrBlank()) header("If-None-Match", etag) }
            .build()
        return try {
            client.newCall(request).execute().use { response ->
                when {
                    response.code == 304 -> FetchResult.NotModified
                    !response.isSuccessful -> FetchResult.Failed("HTTP ${response.code}")
                    else -> {
                        val body = response.body ?: return FetchResult.Failed("no body")
                        val declared = body.contentLength()
                        if (declared > RemoteGreetingsStore.MAX_BYTES) return FetchResult.Failed("too big")
                        val source = body.source()
                        source.request(RemoteGreetingsStore.MAX_BYTES + 1L)
                        if (source.buffer.size > RemoteGreetingsStore.MAX_BYTES) return FetchResult.Failed("too big")
                        val text = source.buffer.readUtf8()
                        FetchResult.Ok(text, response.header("ETag"))
                    }
                }
            }
        } catch (e: Exception) {
            FetchResult.Failed(e.javaClass.simpleName)
        }
    }
}

/**
 * Keeps [com.minim.music.ui.Greetings.remote] filled: the offline copy as soon as the app starts,
 * then a fresh download about once a day. Every failure just leaves the current lines (or none) in
 * place, so Home always has the built-in greetings.
 */
object RemoteGreetingsSync {

    const val URL = "https://raw.githubusercontent.com/UniquelyGaurab/minim/main/greetings/greetings.json"

    private val running = AtomicBoolean(false)
    @Volatile private var cacheLoaded = false

    /**
     * Loads the offline copy (first call only) and downloads a new file when one is due. Blocking:
     * call from a background thread. [publish] receives each file to use.
     */
    fun run(dir: File, fetcher: GreetingsFetcher, publish: (RemoteGreetingFile) -> Unit) {
        if (!running.compareAndSet(false, true)) return
        try {
            val store = RemoteGreetingsStore(dir)
            if (!cacheLoaded) {
                cacheLoaded = true
                store.loadCached()?.let(publish)
            }
            val result = store.refresh(fetcher)
            result.file?.let(publish)
        } catch (e: Throwable) {
            // Never let greetings take the app down
        } finally {
            running.set(false)
        }
    }
}
