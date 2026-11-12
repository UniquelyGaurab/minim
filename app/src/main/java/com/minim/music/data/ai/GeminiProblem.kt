package com.minim.music.data.ai

import com.minim.music.data.greetings.MiniJson

/** v5.2.0: what went wrong with a Gemini request, sorted so the chat can say it plainly. */
sealed class GeminiProblem {
    /** Your own key was rejected: wrong, expired, or the Gemini API isn't on for it. */
    object BadKey : GeminiProblem()
    /** Rate limit or daily free quota used up. */
    object Quota : GeminiProblem()
    /** The model ID doesn't exist or isn't available to this key. */
    data class UnknownModel(val model: String) : GeminiProblem()
    /**
     * 5.10.1: no Gemini key in Settings. Minim's built-in Gemini (through Firebase) is gone, so
     * every Gemini feature needs your own free Google AI Studio key.
     */
    object NoKey : GeminiProblem()
    /** No internet. */
    object Offline : GeminiProblem()
    /** Gemini wouldn't answer this one (safety block). */
    object Blocked : GeminiProblem()
    /** Google's side is busy or down. */
    object Busy : GeminiProblem()
    /** Gemini isn't offered where you are. */
    object Region : GeminiProblem()
    data class Other(val message: String) : GeminiProblem()
}

class GeminiException(val problem: GeminiProblem) : Exception(problem.toString())

object GeminiErrors {

    /**
     * Sorts an HTTP error from the Gemini API. [ownKey] is true when the request used the key
     * from Settings (always, since 5.10.1); key and permission errors then mean "check your key".
     * Without a key they mean "add a key".
     */
    fun classify(httpCode: Int, body: String?, ownKey: Boolean, model: String): GeminiProblem {
        val err = runCatching { MiniJson.parse(body ?: "") }.getOrNull().asObj()?.get("error").asObj()
        val status = (err?.get("status") as? String).orEmpty()
        val message = (err?.get("message") as? String).orEmpty()
        val reasons = err?.get("details").asList().orEmpty()
            .mapNotNull { it.asObj()?.get("reason") as? String }
        val msg = message.lowercase()

        val keyProblem = reasons.any { it.startsWith("API_KEY_") } ||
            msg.contains("api key not valid") || msg.contains("api key expired") ||
            msg.contains("api_key_invalid")
        val serviceOff = reasons.any { it == "SERVICE_DISABLED" || it == "ACCESS_TOKEN_SCOPE_INSUFFICIENT" } ||
            msg.contains("has not been used in project") || msg.contains("is disabled")

        return when {
            httpCode == 429 || status == "RESOURCE_EXHAUSTED" -> GeminiProblem.Quota
            msg.contains("location is not supported") -> GeminiProblem.Region
            keyProblem || serviceOff || httpCode == 401 || httpCode == 403 ||
                status == "PERMISSION_DENIED" || status == "UNAUTHENTICATED" ->
                if (ownKey) GeminiProblem.BadKey else GeminiProblem.NoKey
            httpCode == 404 || status == "NOT_FOUND" ||
                (msg.contains("model") && (msg.contains("not found") || msg.contains("not supported"))) ->
                GeminiProblem.UnknownModel(model)
            httpCode >= 500 || status == "UNAVAILABLE" || status == "INTERNAL" -> GeminiProblem.Busy
            else -> GeminiProblem.Other(message.ifBlank { "HTTP $httpCode" }.take(200))
        }
    }
}
