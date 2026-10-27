package com.minim.music.data.ai

import android.content.Context
import android.content.pm.PackageManager
import java.security.MessageDigest

/**
 * v5.2.0: picks where the chat goes.
 * - A key in Settings: straight to the Gemini API with that key.
 * - No key: Minim's built-in Gemini through Firebase AI Logic. The project ID and key come
 *   from the private Firebase file at runtime, so the public code holds no secrets. A copy
 *   built without that file has no built-in Gemini and asks for a key instead.
 */
object GeminiRoute {

    /** Set after the built-in route said it's switched off, so the chat can ask for a key up front. */
    @Volatile var defaultKnownOff = false

    fun hasBuiltIn(context: Context): Boolean = firebaseOptions(context) != null

    /** v5.3.2: swaps this phone's App Check debug token for App Check tokens. Set up on first use. */
    @Volatile private var exchanger: AppCheckExchanger? = null

    private fun exchanger(): AppCheckExchanger = exchanger ?: synchronized(this) {
        exchanger ?: AppCheckExchanger(
            runCatching { com.minim.music.MinimApp.instance.okHttpClient }.getOrElse { okhttp3.OkHttpClient() }
        ).also { exchanger = it }
    }

    /** v5.3.2: Gemini refused the App Check token; get a fresh one next time. */
    fun appCheckRejected() {
        exchanger?.invalidate()
    }

    suspend fun endpoint(context: Context, settings: GeminiSettings): GeminiEndpoint {
        val model = settings.model
        settings.apiKey()?.takeIf { it.isNotBlank() }?.let { return GeminiEndpoint.direct(model, it) }
        val o = firebaseOptions(context) ?: throw GeminiException(GeminiProblem.DefaultUnavailable)
        val cert = certSha1(context)
        // v5.3.2: App Check is enforced on Minim's project, so the built-in route needs a token.
        val appCheck = if (!o.appId.isNullOrBlank() && !o.projectNumber.isNullOrBlank()) {
            exchanger().token(
                AppCheckApp(o.projectNumber, o.appId, o.apiKey, context.packageName, cert),
                AppCheckDebugToken.get(context),
            )
        } else null
        return GeminiEndpoint.firebase(
            model = model,
            projectId = o.projectId,
            apiKey = o.apiKey,
            appId = o.appId,
            packageName = context.packageName,
            certSha1 = cert,
            appCheckToken = appCheck,
        )
    }

    private class Opts(val projectId: String, val apiKey: String, val appId: String?, val projectNumber: String?)

    private fun firebaseOptions(context: Context): Opts? = try {
        val apps = com.google.firebase.FirebaseApp.getApps(context)
        val app = apps.firstOrNull()
        val o = app?.options
        val pid = o?.projectId
        val key = o?.apiKey
        // The sender ID in the Firebase file is the project number App Check wants
        if (pid.isNullOrBlank() || key.isNullOrBlank()) null else Opts(pid, key, o.applicationId, o.gcmSenderId)
    } catch (_: Throwable) {
        null
    }

    @Volatile private var certCache: String? = null

    /** The app's signing certificate SHA-1, which Google uses to match a key's Android restriction. */
    private fun certSha1(context: Context): String? {
        certCache?.let { return it }
        return try {
            val pm = context.packageManager
            val info = pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
            val sig = info.signingInfo?.apkContentsSigners?.firstOrNull() ?: return null
            val d = MessageDigest.getInstance("SHA-1").digest(sig.toByteArray())
            d.joinToString("") { "%02X".format(it) }.also { certCache = it }
        } catch (_: Throwable) {
            null
        }
    }
}
