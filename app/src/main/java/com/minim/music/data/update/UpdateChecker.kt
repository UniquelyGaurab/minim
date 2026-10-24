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
 * Uses the app's own OkHttp client (and its HTTP cache). v4.9.8: the request says "max-age=0", so
 * OkHttp revalidates with the saved ETag and an unchanged release comes back as 304, which GitHub
 * doesn't count against the 60-an-hour limit for apps without a sign-in. (4.9.6/4.9.7 said
 * "no-cache", which makes OkHttp skip the cache entirely, so every check was a full, counted request.)
 *
 * v4.9.8: that limit is per IP address, and mobile networks put many phones behind one address, so
 * the API can say 403/429 even for the first check of the day. Then, when [webLatestUrl] is set, the
 * checker asks github.com instead: `/releases/latest` redirects to `/releases/tag/<tag>`, and the APK
 * of every Minim release is `<downloadBaseUrl>/<tag>/Minim-<tag>.apk` (checked with a HEAD request,
 * which also gives its size). No release notes that way; the pop-up then says so.
 * Blocking: call from a background thread.
 */
class UpdateChecker(
    client: OkHttpClient,
    private val url: String = LATEST_URL,
    private val userAgent: String = "Minim",
    private val webLatestUrl: String? = null,
    private val downloadBaseUrl: String? = null
) {
    private val client: OkHttpClient = client.newBuilder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .callTimeout(20, TimeUnit.SECONDS)
        .build()

    fun check(installedVersionName: String?): UpdateCheckResult {
        val request = Request.Builder()
            .url(url)
            .cacheControl(CacheControl.Builder().maxAge(0, TimeUnit.SECONDS).build())
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .header("User-Agent", userAgent)
            .build()
        return try {
            client.newCall(request).execute().use { response ->
                when {
                    response.code == 404 -> UpdateCheckResult.NoRelease
                    response.code == 403 || response.code == 429 ->
                        checkViaWeb(installedVersionName) ?: UpdateCheckResult.Failed("rate limited")
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

    /** The fallback through github.com, or null when it isn't set up or doesn't give an answer. */
    private fun checkViaWeb(installedVersionName: String?): UpdateCheckResult? {
        val web = webLatestUrl ?: return null
        val downloads = downloadBaseUrl ?: return null
        return try {
            val noRedirects = client.newBuilder().followRedirects(false).followSslRedirects(false).build()
            val tag = noRedirects.newCall(
                Request.Builder().url(web).header("User-Agent", userAgent)
                    .cacheControl(CacheControl.FORCE_NETWORK).build()
            ).execute().use { r ->
                if (r.code !in 300..399) return null
                tagFromLocation(r.header("Location")) ?: return null
            }
            val version = AppVersion.parse(tag) ?: return null
            val apkName = "Minim-$tag.apk"
            val apkUrl = downloads.trimEnd('/') + "/" + tag + "/" + apkName
            val release = GitHubRelease(
                tag = tag, version = version, name = tag, body = "",
                pageUrl = web.substringBeforeLast("/latest") + "/tag/" + tag,
                draft = false, preRelease = false,
                assets = listOf(ReleaseAsset(apkName, apkUrl, -1L, null, "uploaded"))
            )
            // Not newer: no need to look for the file
            if (UpdateRules.availableUpdate(installedVersionName, release) == null) return UpdateCheckResult.UpToDate(tag)
            val size = client.newCall(
                Request.Builder().url(apkUrl).head().header("User-Agent", userAgent)
                    .cacheControl(CacheControl.FORCE_NETWORK).build()
            ).execute().use { r ->
                if (!r.isSuccessful) return null
                r.header("Content-Length")?.toLongOrNull()?.takeIf { it > 0 } ?: -1L
            }
            val update = UpdateRules.availableUpdate(
                installedVersionName,
                release.copy(assets = listOf(ReleaseAsset(apkName, apkUrl, size, null, "uploaded")))
            )
            if (update != null) UpdateCheckResult.Available(update) else UpdateCheckResult.UpToDate(tag)
        } catch (e: Exception) {
            null
        }
    }

    companion object {
        const val REPO = "UniquelyGaurab/minim"
        const val LATEST_URL = "https://api.github.com/repos/$REPO/releases/latest"
        /** v4.9.8: the github.com fallback when the API is rate limited. */
        const val WEB_LATEST_URL = "https://github.com/$REPO/releases/latest"
        const val DOWNLOAD_BASE_URL = "https://github.com/$REPO/releases/download"

        /** "v4.9.8" from a `.../releases/tag/v4.9.8` redirect target, or null. */
        fun tagFromLocation(location: String?): String? {
            if (location.isNullOrBlank()) return null
            val marker = "/releases/tag/"
            val at = location.indexOf(marker)
            if (at < 0) return null
            val raw = location.substring(at + marker.length).substringBefore('?').substringBefore('#').trimEnd('/')
            if (raw.isEmpty() || raw.contains('/')) return null
            val tag = try {
                java.net.URLDecoder.decode(raw, "UTF-8")
            } catch (e: Exception) {
                return null
            }
            return tag.takeIf { it.isNotBlank() && it.length <= 64 }
        }
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
