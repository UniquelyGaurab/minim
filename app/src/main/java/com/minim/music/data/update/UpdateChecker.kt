package com.minim.music.data.update

import okhttp3.CacheControl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/** v4.9.6: what one update check found. */
sealed class UpdateCheckResult {
    /** A newer release with an APK. */
    data class Available(val update: AvailableUpdate) : UpdateCheckResult()
    /** The latest release is this version (or older, or has no APK). */
    data class UpToDate(val latestTag: String?) : UpdateCheckResult()
    /** The repo has no published release yet (404). */
    object NoRelease : UpdateCheckResult()
    /** Offline, timed out, rate limited, server error or a reply that couldn't be read. */
    data class Failed(val reason: String, val offline: Boolean = false) : UpdateCheckResult()
}

/**
 * v4.9.6: asks GitHub for the latest release of the public repo.
 *
 * Uses the app's own OkHttp client (and its HTTP cache): the request says "no-cache", so OkHttp
 * always revalidates with the saved ETag. An unchanged release then comes back as 304, which
 * GitHub doesn't count against the 60-an-hour limit for apps without a sign-in. Blocking: call
 * from a background thread.
 */
class UpdateChecker(
    client: OkHttpClient,
    private val url: String = LATEST_URL,
    private val userAgent: String = "Minim"
) {
    private val client: OkHttpClient = client.newBuilder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .callTimeout(20, TimeUnit.SECONDS)
        .build()

    fun check(installedVersionName: String?): UpdateCheckResult {
        val request = Request.Builder()
            .url(url)
            .cacheControl(CacheControl.Builder().noCache().build())
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .header("User-Agent", userAgent)
            .build()
        return try {
            client.newCall(request).execute().use { response ->
                when {
                    response.code == 404 -> UpdateCheckResult.NoRelease
                    response.code == 403 || response.code == 429 -> UpdateCheckResult.Failed("rate limited")
                    !response.isSuccessful -> UpdateCheckResult.Failed("HTTP ${response.code}")
                    else -> {
                        val body = response.body ?: return UpdateCheckResult.Failed("no body")
                        if (body.contentLength() > MAX_JSON_BYTES) return UpdateCheckResult.Failed("too big")
                        val source = body.source()
                        source.request(MAX_JSON_BYTES + 1L)
                        if (source.buffer.size > MAX_JSON_BYTES) return UpdateCheckResult.Failed("too big")
                        val release = GitHubReleaseParser.parse(source.buffer.readUtf8())
                            ?: return UpdateCheckResult.Failed("unreadable release")
                        val update = UpdateRules.availableUpdate(installedVersionName, release)
                        if (update != null) UpdateCheckResult.Available(update)
                        else UpdateCheckResult.UpToDate(release.tag)
                    }
                }
            }
        } catch (e: IOException) {
            UpdateCheckResult.Failed(e.javaClass.simpleName, offline = DownloadErrors.isOffline(e))
        } catch (e: Exception) {
            UpdateCheckResult.Failed(e.javaClass.simpleName)
        }
    }

    companion object {
        const val REPO = "UniquelyGaurab/minim"
        const val LATEST_URL = "https://api.github.com/repos/$REPO/releases/latest"
        const val MAX_JSON_BYTES = 200L * 1024L
    }
}

/** v4.9.6: sorting failures into the few friendly messages the pop-up shows. */
object DownloadErrors {
    fun isOffline(e: Throwable): Boolean =
        e is java.net.UnknownHostException ||
            e is java.net.ConnectException ||
            e is java.net.NoRouteToHostException ||
            e is java.net.SocketTimeoutException ||
            e is java.io.InterruptedIOException
}
