package com.minim.music.data.ai

import com.minim.music.data.greetings.MiniJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

/**
 * v5.2.0: where a chat request goes.
 * - [direct]: your own AI Studio key, straight to Google's Gemini API.
 * - [firebase]: Minim's built-in route, Firebase AI Logic on Minim's Firebase project. Its
 *   project ID and key are read at runtime from the private Firebase file, so nothing
 *   secret is in the public code.
 */
data class GeminiEndpoint(
    val url: String,
    val headers: Map<String, String>,
    val ownKey: Boolean,
    val model: String,
) {
    /** Never print the key, not even by accident in a log or crash report. */
    override fun toString(): String = "GeminiEndpoint(ownKey=$ownKey, model=$model)"

    companion object {
        const val DIRECT_BASE = "https://generativelanguage.googleapis.com/v1beta"
        const val FIREBASE_BASE = "https://firebasevertexai.googleapis.com/v1beta"

        fun direct(model: String, apiKey: String, base: String = DIRECT_BASE) = GeminiEndpoint(
            url = "$base/models/$model:streamGenerateContent?alt=sse",
            headers = mapOf("x-goog-api-key" to apiKey),
            ownKey = true,
            model = model,
        )

        fun firebase(
            model: String,
            projectId: String,
            apiKey: String,
            appId: String?,
            packageName: String?,
            certSha1: String?,
            base: String = FIREBASE_BASE,
        ): GeminiEndpoint {
            val h = linkedMapOf("x-goog-api-key" to apiKey)
            if (!appId.isNullOrBlank()) h["X-Firebase-AppId"] = appId
            if (!packageName.isNullOrBlank()) h["X-Android-Package"] = packageName
            if (!certSha1.isNullOrBlank()) h["X-Android-Cert"] = certSha1
            return GeminiEndpoint(
                url = "$base/projects/$projectId/models/$model:streamGenerateContent?alt=sse",
                headers = h,
                ownKey = false,
                model = model,
            )
        }
    }
}

/** One function call Gemini asked for. */
data class FunctionCall(val name: String, val args: Map<String, Any?>, val id: String?)

/** One whole model turn, after the stream ends. */
data class ModelTurn(
    /** The parts exactly as received, so they go back unchanged (thought signatures included). */
    val parts: List<Map<String, Any?>>,
    val text: String,
    val calls: List<FunctionCall>,
    val blocked: Boolean,
)

/** Reads Gemini's server-sent events: one JSON chunk per `data:` line. */
object GeminiStream {

    data class Chunk(
        val parts: List<Map<String, Any?>>,
        val text: String,
        val calls: List<FunctionCall>,
        val blocked: Boolean,
    )

    fun parseChunk(json: String): Chunk? {
        val root = runCatching { MiniJson.parse(json) }.getOrNull().asObj() ?: return null
        val blockedPrompt = root["promptFeedback"].asObj()?.get("blockReason") != null
        val cand = root["candidates"].asList()?.firstOrNull().asObj()
        val finish = cand?.get("finishReason") as? String
        val blocked = blockedPrompt || finish == "SAFETY" || finish == "PROHIBITED_CONTENT" ||
            finish == "BLOCKLIST" || finish == "SPII"
        val parts = cand?.get("content").asObj()?.get("parts").asList().orEmpty().mapNotNull { it.asObj() }
        val text = StringBuilder()
        val calls = ArrayList<FunctionCall>()
        for (p in parts) {
            if (p["thought"] == true) continue
            (p["text"] as? String)?.let { text.append(it) }
            p["functionCall"].asObj()?.let { fc ->
                val name = fc["name"] as? String ?: return@let
                calls += FunctionCall(name, fc["args"].asObj() ?: emptyMap(), fc["id"] as? String)
            }
        }
        return Chunk(parts, text.toString(), calls, blocked)
    }
}

class GeminiClient(baseClient: OkHttpClient) {

    private val http: OkHttpClient = baseClient.newBuilder()
        .cache(null)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .callTimeout(120, TimeUnit.SECONDS)
        .build()

    /**
     * Sends one request and streams the reply. [onText] gets each new piece of text as it
     * arrives (on the IO thread). Throws [GeminiException] with a sorted problem.
     */
    suspend fun stream(endpoint: GeminiEndpoint, body: String, onText: (String) -> Unit): ModelTurn =
        withContext(Dispatchers.IO) {
            val rb = Request.Builder().url(endpoint.url)
                .post(body.toRequestBody("application/json; charset=utf-8".toMediaType()))
                .header("Accept", "text/event-stream")
            endpoint.headers.forEach { (k, v) -> rb.header(k, v) }
            val call = http.newCall(rb.build())
            cancelOnCoroutineCancel(call) {
                try {
                    call.execute().use { resp ->
                        if (!resp.isSuccessful) {
                            val err = runCatching { resp.body?.string() }.getOrNull()
                            throw GeminiException(
                                GeminiErrors.classify(resp.code, err, endpoint.ownKey, endpoint.model)
                            )
                        }
                        val source = resp.body?.source() ?: throw GeminiException(GeminiProblem.Busy)
                        val parts = ArrayList<Map<String, Any?>>()
                        val calls = ArrayList<FunctionCall>()
                        val text = StringBuilder()
                        var blocked = false
                        val data = StringBuilder()
                        fun flush() {
                            if (data.isEmpty()) return
                            val chunk = GeminiStream.parseChunk(data.toString())
                            data.setLength(0)
                            if (chunk == null) return
                            parts += chunk.parts
                            calls += chunk.calls
                            if (chunk.blocked) blocked = true
                            if (chunk.text.isNotEmpty()) {
                                text.append(chunk.text)
                                onText(chunk.text)
                            }
                        }
                        while (true) {
                            val line = source.readUtf8Line() ?: break
                            when {
                                line.isEmpty() -> flush()
                                line.startsWith("data:") -> {
                                    if (data.isNotEmpty()) data.append('\n')
                                    data.append(line.removePrefix("data:").trimStart())
                                }
                                // comments / event names / ids: nothing to do
                            }
                        }
                        flush()
                        ModelTurn(parts, text.toString(), calls, blocked)
                    }
                } catch (e: GeminiException) {
                    throw e
                } catch (e: UnknownHostException) {
                    throw GeminiException(GeminiProblem.Offline)
                } catch (e: java.net.ConnectException) {
                    throw GeminiException(GeminiProblem.Offline)
                } catch (e: java.net.SocketTimeoutException) {
                    throw GeminiException(GeminiProblem.Busy)
                } catch (e: IOException) {
                    currentCoroutineContext().ensureActive()
                    throw GeminiException(GeminiProblem.Offline)
                }
            }
        }

    private suspend fun <T> cancelOnCoroutineCancel(call: Call, block: suspend () -> T): T {
        val job = currentCoroutineContext()[kotlinx.coroutines.Job]
        val handle = job?.invokeOnCompletion { if (job.isCancelled) call.cancel() }
        try {
            return block()
        } finally {
            handle?.dispose()
        }
    }
}
