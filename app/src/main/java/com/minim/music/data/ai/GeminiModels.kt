package com.minim.music.data.ai

/**
 * v5.2.0: the models the Settings dropdown offers. Taken from Google's Gemini API
 * model list (ai.google.dev/gemini-api/docs/models, October 2026): the current
 * Flash-Lite models, newest first. "Custom" lets you type any model ID from AI Studio.
 */
object GeminiModels {

    data class Preset(val id: String, val label: String)

    val PRESETS = listOf(
        Preset("gemini-3.5-flash-lite", "Gemini 3.5 Flash-Lite"),
        Preset("gemini-3.1-flash-lite", "Gemini 3.1 Flash-Lite"),
    )

    /** The newest Flash-Lite model. */
    val DEFAULT: String = PRESETS.first().id

    private val ID = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,99}$")

    /** Trims what you paste from AI Studio: spaces, quotes and a leading "models/". */
    fun normalize(raw: String?): String {
        var s = (raw ?: "").trim().trim('"', '\'', '`').trim()
        if (s.startsWith("models/")) s = s.removePrefix("models/")
        return s
    }

    /** Only plain model IDs, so nothing odd ends up in the request URL. */
    fun isValidId(raw: String?): Boolean = ID.matches(normalize(raw))

    fun isPreset(id: String): Boolean = PRESETS.any { it.id == id }

    fun labelFor(id: String): String = PRESETS.firstOrNull { it.id == id }?.label ?: id

    /** What the app actually calls: the saved choice when it's a usable ID, else the default. */
    fun resolve(saved: String?): String {
        val id = normalize(saved)
        return if (id.isNotEmpty() && isValidId(id)) id else DEFAULT
    }
}
