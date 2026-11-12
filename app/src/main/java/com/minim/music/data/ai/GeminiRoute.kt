package com.minim.music.data.ai

import android.content.Context

/**
 * v5.2.0: picks where a Gemini request goes.
 *
 * 5.10.1 (V6): there is one route only, your own Google AI Studio key from Settings, straight to
 * the Gemini API. Minim's built-in Gemini through Firebase AI Logic (and its App Check) is gone,
 * so without a key every Gemini feature (lyrics, translation, chat, the Search shelf) stops with
 * [GeminiProblem.NoKey] and shows a short prompt to add one.
 */
object GeminiRoute {

    /** True when Gemini can be used at all: a key is saved in Settings. */
    fun available(settings: GeminiSettings): Boolean = settings.hasApiKey

    @Suppress("UNUSED_PARAMETER")
    suspend fun endpoint(context: Context, settings: GeminiSettings): GeminiEndpoint = endpointFor(settings.model, settings.apiKey())

    /** The request target for this key, or [GeminiProblem.NoKey] when there's none. */
    fun endpointFor(model: String, apiKey: String?): GeminiEndpoint {
        val key = apiKey?.takeIf { it.isNotBlank() } ?: throw GeminiException(GeminiProblem.NoKey)
        return GeminiEndpoint.direct(model, key)
    }
}
