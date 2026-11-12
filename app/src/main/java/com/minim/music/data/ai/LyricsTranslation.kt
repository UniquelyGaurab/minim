package com.minim.music.data.ai

import com.minim.music.data.greetings.MiniJson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException
import java.io.File
import java.security.MessageDigest

/**
 * 5.6.0: AI lyrics translation for Full Screen Lyrics.
 *
 * One Gemini call per song, on the same route and model as the Gemini chat ([GeminiRoute]: your
 * own key from Settings; without one it asks for a key).
 * Every lyric line that has words goes in one request with a fixed instruction ([SYSTEM_PROMPT]),
 * each with its line number, and Gemini answers in JSON that must follow [responseSchema]: one
 * `{id, en}` item per line. Translations are matched to lines by that number, never by position
 * alone, so a skipped or extra item can't push the rest onto the wrong lines.
 */
object LyricsTranslationPrompt {

    /** Bump when the prompt changes enough that saved translations should be redone. */
    const val VERSION = 1

    /** Longest translation kept for one line; Gemini is asked for far less. */
    const val MAX_CHARS = 140

    /** Lines sent in one call (a very long lyric file is cut here; the rest shows no translation). */
    const val MAX_LINES = 220

    val SYSTEM_PROMPT: String = """
You translate song lyrics into English for Minim, a music player. The translation of each line is shown in small text right under the original line while the song plays.

How to translate:
- Read the whole song first, then translate every line in context: who is singing, to whom, and the story or feeling of the song.
- Write natural, fluent English that keeps the meaning, the mood, the emotion and the poetry of the line, the way a gifted songwriter would translate it. Never translate word for word, and never sound like a machine translation.
- Translate idioms, metaphors and sayings by what they mean, using an image or phrase that feels natural in English.
- Keep names of people, places and gods, and words English-speaking listeners already know, exactly as they are. Examples: ishq, jaan, yaar, habibi, amor, oppa, inshallah.
- Match the tone: tender stays tender, playful stays playful, slang stays casual. Don't censor or soften anything.
- Keep each translation short enough to sit under its original line: about as long as the original, and never more than about 12 words.
- If a line is already entirely in English, return an empty string for it. If a line mixes English with another language, translate the whole line into natural English.
- Pure vocal sounds (oh, la la, na na, ooh) get an empty string.
- Plain text only: no quotation marks around the line, no notes, no explanations, no romanization.

Output rules:
- Answer only with JSON that follows the schema.
- Return exactly one item for every input line, using the same id. Never skip, merge, split, add or reorder lines, even when lines repeat.
""".trim()

    /** Gemini's structured output: `{"lines": [{"id": 3, "en": "..."}, ...]}`. */
    val responseSchema: Map<String, Any?> = linkedMapOf(
        "type" to "OBJECT",
        "properties" to linkedMapOf(
            "lines" to linkedMapOf(
                "type" to "ARRAY",
                "items" to linkedMapOf(
                    "type" to "OBJECT",
                    "properties" to linkedMapOf(
                        "id" to mapOf("type" to "INTEGER"),
                        "en" to mapOf("type" to "STRING"),
                    ),
                    "required" to listOf("id", "en"),
                    "propertyOrdering" to listOf("id", "en"),
                ),
            ),
        ),
        "required" to listOf("lines"),
    )

    /** The user turn: the song (when known) and the numbered lines, as JSON. */
    fun userText(lines: List<String>, ids: List<Int>, title: String?, artist: String?): String {
        val sb = StringBuilder()
        if (!title.isNullOrBlank()) {
            sb.append("Song: \"").append(title.trim()).append('"')
            if (!artist.isNullOrBlank()) sb.append(" by ").append(artist.trim())
            sb.append('\n')
        }
        sb.append("Translate these ").append(ids.size).append(" lyric lines into English. ")
        sb.append("Return exactly ").append(ids.size).append(" items, one per id.\n")
        sb.append(JsonOut.write(ids.map { linkedMapOf("id" to it, "text" to lines[it]) }))
        return sb.toString()
    }

    fun requestBody(lines: List<String>, ids: List<Int>, title: String?, artist: String?): String = JsonOut.write(
        linkedMapOf(
            "systemInstruction" to mapOf("parts" to listOf(mapOf("text" to SYSTEM_PROMPT))),
            "contents" to listOf(
                mapOf("role" to "user", "parts" to listOf(mapOf("text" to userText(lines, ids, title, artist))))
            ),
            "generationConfig" to linkedMapOf(
                "responseMimeType" to "application/json",
                "responseSchema" to responseSchema,
                "temperature" to 0.4,
                "maxOutputTokens" to 16384,
            ),
        )
    )
}

/** Reads Gemini's answer and puts each translation under the right line. */
object LyricsTranslationParser {

    /** One item as Gemini sent it: [id] is null when it came without a line number. */
    data class Item(val id: Int?, val text: String)

    /** The items in Gemini's JSON, or null when it isn't the JSON we asked for. */
    fun parseItems(raw: String): List<Item>? {
        val json = stripFences(raw)
        if (json.isEmpty()) return null
        val root = runCatching { MiniJson.parse(json, 1024 * 1024) }.getOrNull() ?: return null
        val list = when (root) {
            is Map<*, *> -> (root["lines"] ?: root["translations"] ?: root["items"]).asList()
            is List<*> -> root.asList()
            else -> null
        } ?: return null
        val out = ArrayList<Item>(list.size)
        for (e in list) {
            when (e) {
                is String -> out += Item(null, e)
                is Map<*, *> -> {
                    val o = e.asObj() ?: continue
                    val id = when (val v = o["id"] ?: o["i"]) {
                        is Long -> v.toInt()
                        is Double -> if (v == Math.floor(v)) v.toInt() else null
                        is String -> v.trim().toIntOrNull()
                        else -> null
                    }
                    val text = (o["en"] ?: o["text"] ?: o["translation"]) as? String ?: ""
                    out += Item(id, text)
                }
                else -> out += Item(null, "")
            }
        }
        return out
    }

    /**
     * Matches [items] to the line numbers that were sent ([ids]). Returns line number to
     * translation (blank ones left out), or null when the answer can't be trusted to sit on the
     * right lines:
     * - Items with line numbers go to those lines. Unknown numbers are ignored, a repeated number
     *   keeps its first item, and a line Gemini skipped just gets no translation. If fewer than
     *   half of the lines came back, the answer is refused.
     * - Items without numbers are only used when there is exactly one per line sent; otherwise
     *   there's no way to know which line each belongs to.
     */
    fun align(ids: List<Int>, items: List<Item>): Map<Int, String>? {
        if (ids.isEmpty()) return emptyMap()
        val numbered = items.count { it.id != null }
        val out = LinkedHashMap<Int, String>()
        if (numbered == items.size && items.isNotEmpty()) {
            val wanted = ids.toHashSet()
            val seen = HashSet<Int>()
            for (it in items) {
                val id = it.id ?: continue
                if (id !in wanted || !seen.add(id)) continue
                clean(it.text)?.let { t -> out[id] = t }
            }
            if (seen.size * 2 < ids.size) return null
            return out
        }
        if (numbered == 0 && items.size == ids.size) {
            for (i in ids.indices) clean(items[i].text)?.let { out[ids[i]] = it }
            return out
        }
        return null
    }

    /**
     * The final translation for each lyric line (null = nothing under it): only lines that were
     * sent, never under a line that is plainly English already or a translation that just repeats
     * the original.
     */
    fun finish(lines: List<String>, kinds: List<LyricLanguage.Kind>, aligned: Map<Int, String>): List<String?> =
        lines.indices.map { i ->
            val t = aligned[i] ?: return@map null
            when {
                kinds.getOrNull(i) == LyricLanguage.Kind.SKIP -> null
                LyricLanguage.isSurelyEnglish(lines[i]) -> null
                same(lines[i], t) -> null
                else -> t
            }
        }

    /** Trims spaces and wrapping quotes; blank becomes null; very long text is cut at a word. */
    internal fun clean(raw: String): String? {
        var t = raw.replace('\n', ' ').replace('\r', ' ').trim()
        while (t.contains("  ")) t = t.replace("  ", " ")
        val pairs = listOf('"' to '"', '“' to '”', '\'' to '\'', '‘' to '’')
        for ((a, b) in pairs) {
            if (t.length >= 2 && t.first() == a && t.last() == b) t = t.substring(1, t.length - 1).trim()
        }
        if (t.isEmpty()) return null
        if (t.all { !Character.isLetterOrDigit(it) }) return null
        if (t.length > LyricsTranslationPrompt.MAX_CHARS) {
            val cut = t.substring(0, LyricsTranslationPrompt.MAX_CHARS)
            val space = cut.lastIndexOf(' ')
            t = (if (space > 40) cut.substring(0, space) else cut).trimEnd(',', ';', ' ') + "…"
        }
        return t
    }

    private fun same(a: String, b: String): Boolean = norm(a) == norm(b)

    private fun norm(s: String): String = s.lowercase().filter { Character.isLetterOrDigit(it) }

    /** Takes the JSON out of ```json fences, if Gemini added them anyway. */
    internal fun stripFences(raw: String): String {
        var t = raw.trim()
        if (t.startsWith("```")) {
            t = t.removePrefix("```").let { s -> if (s.startsWith("json", ignoreCase = true)) s.substring(4) else s }
            val end = t.lastIndexOf("```")
            if (end >= 0) t = t.substring(0, end)
            t = t.trim()
        }
        return t
    }
}

/**
 * Saved translations, one small JSON file per song in the app's no-backup folder, so they
 * survive restarts but are never backed up or synced. The name is the song ID plus a hash of
 * the lyric lines (and the prompt version), so changed lyrics get a fresh translation. The
 * oldest files go once there are more than [maxFiles].
 */
class TranslationCache(private val dir: File, private val maxFiles: Int = 400) {

    private val memory = object : LinkedHashMap<String, List<String?>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<String?>>?): Boolean = size > 24
    }

    fun key(trackId: String, lines: List<String>): String {
        val md = MessageDigest.getInstance("SHA-256")
        md.update("v${LyricsTranslationPrompt.VERSION}\n".toByteArray())
        md.update(trackId.toByteArray())
        for (l in lines) {
            md.update('\n'.code.toByte())
            md.update(l.toByteArray())
        }
        val hash = md.digest().joinToString("") { "%02x".format(it) }.take(24)
        val safeId = trackId.filter { it.isLetterOrDigit() || it == '-' || it == '_' }.take(40).ifEmpty { "song" }
        return "${safeId}_$hash"
    }

    @Synchronized
    fun get(trackId: String, lines: List<String>): List<String?>? {
        val k = key(trackId, lines)
        memory[k]?.let { return it }
        val f = File(dir, "$k.json")
        if (!f.isFile) return null
        val read = runCatching {
            val root = MiniJson.parse(f.readText(), 1024 * 1024).asObj() ?: return@runCatching null
            if (root["track"] != trackId) return@runCatching null
            val t = root["t"].asList() ?: return@runCatching null
            if (t.size != lines.size) return@runCatching null
            t.map { (it as? String)?.takeIf { s -> s.isNotEmpty() } }
        }.getOrNull()
        if (read == null) {
            f.delete()
            return null
        }
        f.setLastModified(System.currentTimeMillis())
        memory[k] = read
        return read
    }

    @Synchronized
    fun put(trackId: String, lines: List<String>, translations: List<String?>) {
        require(translations.size == lines.size)
        val k = key(trackId, lines)
        memory[k] = translations
        runCatching {
            dir.mkdirs()
            val body = JsonOut.write(
                linkedMapOf(
                    "v" to LyricsTranslationPrompt.VERSION,
                    "track" to trackId,
                    "t" to translations.map { it ?: "" },
                )
            )
            val tmp = File(dir, "$k.json.tmp")
            tmp.writeText(body)
            val dest = File(dir, "$k.json")
            if (!tmp.renameTo(dest)) {
                dest.writeText(body)
                tmp.delete()
            }
            trim()
        }
    }

    private fun trim() {
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".json") } ?: return
        if (files.size <= maxFiles) return
        files.sortedBy { it.lastModified() }.take(files.size - maxFiles).forEach { it.delete() }
    }
}

/**
 * Gets a song's translation: from [cache] when it's there, else with one Gemini call. A song that
 * is already being translated isn't asked for twice (turning translations off and on again while
 * Gemini works just waits for the same answer), and the work carries on in [scope] even if the
 * screen stops waiting, so the answer is still saved. An answer whose lines don't match is asked
 * for once more; after that, or after [timeoutMs], it fails with a [GeminiException].
 */
class LyricsTranslator(
    private val client: GeminiClient,
    private val cache: TranslationCache,
    private val scope: CoroutineScope,
    private val timeoutMs: Long = TIMEOUT_MS,
) {

    private val running = HashMap<String, Deferred<List<String?>>>()

    suspend fun cached(trackId: String, lines: List<String>): List<String?>? =
        withContext(Dispatchers.IO) { cache.get(trackId, lines) }

    suspend fun translate(
        trackId: String,
        lines: List<String>,
        title: String?,
        artist: String?,
        endpoint: suspend () -> GeminiEndpoint,
    ): List<String?> {
        cached(trackId, lines)?.let { return it }
        val key = cache.key(trackId, lines)
        val job = synchronized(running) {
            running[key]?.takeIf { it.isActive } ?: scope.async {
                try {
                    val result = withTimeout(timeoutMs) { fetch(lines, title, artist, endpoint) }
                    withContext(Dispatchers.IO) { cache.put(trackId, lines, result) }
                    result
                } catch (e: TimeoutCancellationException) {
                    throw GeminiException(GeminiProblem.Busy)
                } finally {
                    synchronized(running) { running.remove(key) }
                }
            }.also { running[key] = it }
        }
        return job.await()
    }

    private suspend fun fetch(
        lines: List<String>,
        title: String?,
        artist: String?,
        endpoint: suspend () -> GeminiEndpoint,
    ): List<String?> {
        val plan = LyricLanguage.plan(lines)
        val ids = plan.sendIndices.take(LyricsTranslationPrompt.MAX_LINES)
        if (ids.isEmpty()) return List(lines.size) { null }
        val ep = withContext(Dispatchers.IO) { endpoint() }
        val body = LyricsTranslationPrompt.requestBody(lines, ids, title, artist)
        var attempt = 0
        while (true) {
            attempt++
            // In its own job so the timeout can stop waiting even while OkHttp blocks on the
            // network (the call itself ends at GeminiClient's call timeout and its answer is dropped)
            val turn = scope.async { client.stream(ep, body) {} }.await()
            if (turn.blocked && turn.text.isBlank()) throw GeminiException(GeminiProblem.Blocked)
            val items = LyricsTranslationParser.parseItems(turn.text)
            val aligned = items?.let { LyricsTranslationParser.align(ids, it) }
            if (aligned != null) return LyricsTranslationParser.finish(lines, plan.kinds, aligned)
            if (attempt >= MAX_ATTEMPTS) throw GeminiException(GeminiProblem.Other("lines didn't match"))
        }
    }

    companion object {
        const val TIMEOUT_MS = 45_000L
        const val MAX_ATTEMPTS = 2
    }
}

