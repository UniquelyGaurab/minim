package com.minim.music.data.ai

import android.content.Context
import com.minim.music.data.greetings.MiniJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * v5.3.2: Firebase App Check, debug provider.
 *
 * Minim's Firebase project enforces App Check on Firebase AI Logic, so the built-in Gemini
 * only answers requests that carry an App Check token. This is the debug provider: each phone
 * makes its own random debug token once, you add that token in the Firebase console
 * (App Check → Apps → Minim → Manage debug tokens), and Minim swaps it for a short-lived
 * App Check token with the same exchangeDebugToken call Firebase's own debug provider uses.
 *
 * Minim does the exchange itself instead of using firebase-appcheck-debug, because that
 * library prints the debug token to the system log, and this token must never be logged.
 *
 * The token lives in minim_ai, the settings file that cloud sync never reads and Android's
 * backup skips, so it stays on this phone. Nothing here logs it or puts it in an error.
 */
object AppCheckDebugToken {

    private const val KEY = "appcheck_debug_token"

    /** This phone's debug token, made the first time it's needed. */
    fun get(context: Context): String {
        val prefs = context.applicationContext.getSharedPreferences(GeminiSettings.PREFS, Context.MODE_PRIVATE)
        prefs.getString(KEY, null)?.takeIf { isValid(it) }?.let { return it }
        synchronized(this) {
            prefs.getString(KEY, null)?.takeIf { isValid(it) }?.let { return it }
            val fresh = UUID.randomUUID().toString()
            prefs.edit().putString(KEY, fresh).commit()
            return fresh
        }
    }

    /** Firebase's debug tokens are UUIDs, the same shape its own debug provider makes. */
    fun isValid(token: String): Boolean = runCatching { UUID.fromString(token).toString() == token.lowercase() }
        .getOrDefault(false)

    /** "c554b803…9f1e" for showing which token this is without printing all of it. */
    fun masked(token: String): String = if (token.length <= 12) "••••" else token.take(8) + "…" + token.takeLast(4)
}

/** Where the exchange goes, from the Firebase file. [projectNumber] is the sender ID. */
data class AppCheckApp(
    val projectNumber: String,
    val appId: String,
    val apiKey: String,
    val packageName: String?,
    val certSha1: String?,
) {
    override fun toString(): String = "AppCheckApp(project=$projectNumber)"
}

/**
 * Swaps the debug token for an App Check token and keeps it until a few minutes before it
 * runs out. Throws [GeminiException] with [GeminiProblem.DeviceNotApproved] when Firebase
 * doesn't know this phone's token.
 */
class AppCheckExchanger(
    baseClient: OkHttpClient,
    private val base: String = BASE,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val http: OkHttpClient = baseClient.newBuilder()
        .cache(null)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .build()

    private val lock = Mutex()
    @Volatile private var cached: String? = null
    @Volatile private var cachedFor: String? = null
    @Volatile private var expiresAt = 0L

    /** Forgets the App Check token, so the next request exchanges again (after a rejection). */
    fun invalidate() {
        cached = null
        expiresAt = 0L
    }

    suspend fun token(app: AppCheckApp, debugToken: String): String = lock.withLock {
        val key = app.appId + "|" + debugToken.hashCode()
        val c = cached
        if (c != null && cachedFor == key && now() < expiresAt) return@withLock c
        val (token, ttlMs) = exchange(app, debugToken)
        cached = token
        cachedFor = key
        // Refresh five minutes early (or halfway, for a very short token)
        expiresAt = now() + (ttlMs - REFRESH_EARLY_MS).coerceAtLeast(ttlMs / 2)
        token
    }

    private suspend fun exchange(app: AppCheckApp, debugToken: String): Pair<String, Long> =
        withContext(Dispatchers.IO) {
            val body = "{\"debug_token\":\"" + debugToken + "\"}"
            val rb = Request.Builder()
                .url("$base/projects/${app.projectNumber}/apps/${app.appId}:exchangeDebugToken")
                .post(body.toRequestBody("application/json; charset=utf-8".toMediaType()))
                .header("X-Goog-Api-Key", app.apiKey)
            app.packageName?.takeIf { it.isNotBlank() }?.let { rb.header("X-Android-Package", it) }
            app.certSha1?.takeIf { it.isNotBlank() }?.let { rb.header("X-Android-Cert", it) }
            try {
                http.newCall(rb.build()).execute().use { resp ->
                    val text = runCatching { resp.body?.string() }.getOrNull()
                    if (!resp.isSuccessful) throw GeminiException(classify(resp.code, text))
                    parse(text) ?: throw GeminiException(GeminiProblem.Busy)
                }
            } catch (e: GeminiException) {
                throw e
            } catch (e: java.net.SocketTimeoutException) {
                throw GeminiException(GeminiProblem.Busy)
            } catch (e: IOException) {
                throw GeminiException(GeminiProblem.Offline)
            }
        }

    companion object {
        const val BASE = "https://firebaseappcheck.googleapis.com/v1"
        private const val REFRESH_EARLY_MS = 5 * 60_000L

        /** {"token": "...", "ttl": "3600s"} -> token and lifetime in ms. */
        fun parse(json: String?): Pair<String, Long>? {
            val o = runCatching { MiniJson.parse(json ?: "") }.getOrNull().asObj() ?: return null
            val token = (o["token"] as? String)?.takeIf { it.isNotBlank() } ?: return null
            val ttl = (o["ttl"] as? String)?.removeSuffix("s")?.toDoubleOrNull()
            val ms = ((ttl ?: 3600.0) * 1000).toLong().coerceIn(60_000L, 7 * 24 * 3_600_000L)
            return token to ms
        }

        /**
         * Firebase says 403 "App attestation failed." (PERMISSION_DENIED) when the debug token
         * isn't on the console's list. A key or API problem means built-in Gemini is off.
         */
        fun classify(code: Int, body: String?): GeminiProblem {
            val err = runCatching { MiniJson.parse(body ?: "") }.getOrNull().asObj()?.get("error").asObj()
            val msg = (err?.get("message") as? String).orEmpty().lowercase()
            val status = (err?.get("status") as? String).orEmpty()
            val reasons = err?.get("details").asList().orEmpty().mapNotNull { it.asObj()?.get("reason") as? String }
            val keyOrService = reasons.any { it.startsWith("API_KEY_") || it == "SERVICE_DISABLED" } ||
                msg.contains("api key") || msg.contains("has not been used in project") || msg.contains("is disabled")
            return when {
                code == 429 || status == "RESOURCE_EXHAUSTED" -> GeminiProblem.Busy
                keyOrService -> GeminiProblem.DefaultUnavailable
                msg.contains("attestation") || code == 403 || status == "PERMISSION_DENIED" ||
                    code == 401 || status == "UNAUTHENTICATED" -> GeminiProblem.DeviceNotApproved
                code == 404 || code == 400 -> GeminiProblem.DefaultUnavailable
                code >= 500 -> GeminiProblem.Busy
                else -> GeminiProblem.Other("App Check HTTP $code")
            }
        }
    }
}
