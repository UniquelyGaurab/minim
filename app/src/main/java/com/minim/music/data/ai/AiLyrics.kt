package com.minim.music.data.ai

import com.minim.music.data.greetings.MiniJson
import com.minim.music.data.model.LyricsData
import com.minim.music.data.model.LyricsLine
import com.minim.music.util.SafeRegex
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import java.io.File

/**
 * 5.8.1 (update 53): Gemini lyrics. A song with no lyrics can get them from Gemini, which
 * listens to the song's audio and writes a synced LRC. Never automatic (full screen lyrics
 * offers it and nothing runs until you tap), always with your own key from Settings, and the
 * result is saved on this phone (no-backup folder, never synced), keyed by song, so a song
 * needs Gemini once.
 */

/** The name saved Gemini lyrics carry in [LyricsData.providerName]; the screen shows the AI line for it. */
const val AI_LYRICS_PROVIDER = "gemini"

fun LyricsData?.isAiLyrics(): Boolean = this?.providerName == AI_LYRICS_PROVIDER

/** The fixed request. You can't edit it (on purpose): it's what keeps the reply parseable. */
object AiLyricsPrompt {

    fun build(title: String?, artist: String?, durationMs: Long): String {
        val t = clean(title).ifEmpty { "Unknown title" }
        val a = clean(artist).ifEmpty { "Unknown artist" }
        val len = if (durationMs > 0) " Length: ${clock(durationMs)}." else ""
        return """
You are writing out the lyrics of a song from its audio.
Song: "$t" by $a.$len
Use the title and artist only as context. Write what is actually sung in the attached audio.

Reply with a synced LRC file and nothing else.
Rules:
- Each line is [mm:ss.xx] followed by the words, for example: [01:02.50] words of the line
- The timestamp is when that phrase starts being sung, counted from the start of this audio.
- One line per sung phrase, in the order they are sung. When a line is sung again (a chorus), write it again with its new time.
- Write every lyric in Latin script. If the song is in a language with another script (such as Hindi, Tamil, Bengali, Japanese or Korean), write the usual romanized form of the words. Don't translate.
- No lines for instrumental parts, intros, solos or silence. Never write things like [Music], (Instrumental) or ♪.
- If you can't make out a phrase clearly, leave that line out rather than guessing.
- Timestamps always go up and stay within the song's length.
- No title, no notes, no tags such as [ti:] or [ar:], no code fences.
- If nobody sings in this audio, reply with exactly: NO_LYRICS
""".trim()
    }

    private fun clean(s: String?): String =
        (s ?: "").replace('\n', ' ').replace('"', '\'').trim().take(200)

    fun clock(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        return "%d:%02d".format(s / 60, s % 60)
    }
}

/**
 * Turns Gemini's reply into lyrics, or says why it can't. Anything odd (prose around the lines,
 * code fences, word tags, lines past the end, out of order times, "[Music]" lines) is cleaned
 * or dropped, so a bad reply can never break the lyrics view.
 */
object AiLyricsParser {

    sealed class Outcome {
        data class Lines(val lines: List<LyricsLine>) : Outcome()
        /** Gemini said nobody sings in it. */
        object Instrumental : Outcome()
        /** Nothing usable came back. */
        object Unusable : Outcome()
    }

    /** At least this many lines, or it's not really lyrics. */
    const val MIN_LINES = 2
    const val MAX_LINES = 600
    const val MAX_LINE_CHARS = 200
    /** A line may start this far past the song's length (its fade out) before it's dropped. */
    const val END_SLACK_MS = 3_000L
    const val LAST_LINE_MS = 6_000L

    private val TIME_TAGS = SafeRegex.of("^((?:\\[\\s*\\d{1,3}\\s*:\\s*\\d{1,2}(?:[.:]\\d{1,3})?\\s*\\])+)(.*)$")
    private val ONE_TAG = SafeRegex.of("\\[\\s*(\\d{1,3})\\s*:\\s*(\\d{1,2})(?:[.:](\\d{1,3}))?\\s*\\]")
    private val SPACES = SafeRegex.of("\\s+")
    private val WORD_TAGS = SafeRegex.of("<\\d{1,3}:\\d{1,2}(?:[.:]\\d{1,3})?>")
    private val NOT_SUNG = SafeRegex.of(
        "^[\\[(（*]*\\s*(music|instrumental|instrumental break|intro|outro|solo|guitar solo|interlude|" +
            "chorus|verse|bridge|hook|pre-chorus|silence|no lyrics|inaudible|unintelligible|applause|" +
            "humming|beat|beat drop|drop)\\s*\\d*\\s*[\\])）*:]*$",
        RegexOption.IGNORE_CASE
    )
    private val ONLY_SYMBOLS = SafeRegex.of("^[\\s♪♫♬♩~*.\\-–—…·•]+$")

    fun parse(reply: String?, durationMs: Long): Outcome {
        val text = (reply ?: "").take(MiniJson.MAX_CHARS)
        val out = ArrayList<Pair<Long, String>>()
        for (raw in text.lineSequence()) {
            val line = raw.trim().removePrefix("-").trim()
            if (line.isEmpty() || line.startsWith("```")) continue
            val m = TIME_TAGS.find(line) ?: continue
            val tags = m.groupValues[1]
            var words = m.groupValues[2]
            words = WORD_TAGS.replace(words, "").replace(SPACES, " ").trim()
                .removePrefix("-").trim()
            if (words.isEmpty() || NOT_SUNG.matches(words) || ONLY_SYMBOLS.matches(words)) continue
            if (words.length > MAX_LINE_CHARS) words = words.take(MAX_LINE_CHARS).trimEnd() + "…"
            for (t in ONE_TAG.findAll(tags)) {
                val ms = toMs(t.groupValues[1], t.groupValues[2], t.groupValues[3]) ?: continue
                if (durationMs > 0 && ms > durationMs + END_SLACK_MS) continue
                out += ms to words
            }
        }
        if (out.isEmpty()) {
            return if (text.contains("NO_LYRICS")) Outcome.Instrumental else Outcome.Unusable
        }
        // In time order (stable, so same-time lines keep their order), without exact repeats
        val sorted = out.sortedBy { it.first }
        val unique = ArrayList<Pair<Long, String>>(sorted.size)
        for (p in sorted) {
            val last = unique.lastOrNull()
            if (last != null && last.first == p.first && last.second.equals(p.second, ignoreCase = true)) continue
            unique += p
        }
        if (unique.size < MIN_LINES) return Outcome.Unusable
        val capped = unique.take(MAX_LINES)
        val lines = capped.mapIndexed { i, (start, words) ->
            val next = capped.getOrNull(i + 1)?.first
            val end = when {
                next != null -> next
                durationMs > start -> minOf(durationMs, start + LAST_LINE_MS)
                else -> start + LAST_LINE_MS
            }
            LyricsLine(startMs = start, endMs = end.coerceAtLeast(start + 500L), text = words)
        }
        return Outcome.Lines(lines)
    }

    private fun toMs(min: String, sec: String, frac: String): Long? {
        val m = min.toLongOrNull() ?: return null
        val s = sec.toLongOrNull() ?: return null
        if (s >= 60) return null
        val f = when (frac.length) {
            0 -> 0L
            1 -> (frac.toLongOrNull() ?: 0L) * 100L
            2 -> (frac.toLongOrNull() ?: 0L) * 10L
            else -> frac.take(3).toLongOrNull() ?: 0L
        }
        return m * 60_000L + s * 1_000L + f
    }

    /** The lines back as LRC text (what the store saves). */
    fun toLrc(lines: List<LyricsLine>): String = lines.joinToString("\n") { l ->
        val cs = (l.startMs / 10).coerceAtLeast(0)
        "[%02d:%02d.%02d]%s".format(cs / 6000, (cs / 100) % 60, cs % 100, l.text)
    }

    fun toLyrics(trackId: String, lines: List<LyricsLine>) = LyricsData(
        trackId = trackId,
        lines = lines,
        isSynced = true,
        hasWordTimestamps = false,
        providerName = AI_LYRICS_PROVIDER,
    )
}

/**
 * Saved Gemini lyrics, one small file per song in the no-backup folder (never in Android's
 * backup, never synced). The file keeps the song ID, so two IDs that clean to the
 * same file name can't show each other's lyrics.
 */
class AiLyricsStore(private val dir: File) {

    private val lock = Any()

    private fun fileFor(trackId: String): File = File(dir, safe(trackId) + ".json")

    fun load(trackId: String): LyricsData? = synchronized(lock) {
        val f = fileFor(trackId)
        if (!f.isFile) return null
        runCatching {
            val o = MiniJson.parse(f.readText()).asObj() ?: return null
            if (o["trackId"] != trackId) return null
            val lrc = o["lrc"] as? String ?: return null
            val parsed = AiLyricsParser.parse(lrc, durationMs = 0L) as? AiLyricsParser.Outcome.Lines ?: return null
            AiLyricsParser.toLyrics(trackId, parsed.lines)
        }.getOrNull()
    }

    fun has(trackId: String): Boolean = synchronized(lock) { fileFor(trackId).isFile }

    fun save(trackId: String, lines: List<LyricsLine>, model: String) = synchronized(lock) {
        dir.mkdirs()
        val json = JsonOut.write(
            linkedMapOf(
                "v" to 1,
                "trackId" to trackId,
                "model" to model,
                "savedAt" to System.currentTimeMillis(),
                "lrc" to AiLyricsParser.toLrc(lines),
            )
        )
        val f = fileFor(trackId)
        val tmp = File(dir, f.name + ".tmp")
        tmp.writeText(json)
        if (!tmp.renameTo(f)) {
            f.delete()
            tmp.renameTo(f)
        }
    }

    fun remove(trackId: String) = synchronized(lock) { fileFor(trackId).delete() }

    companion object {
        private val UNSAFE = SafeRegex.of("[^A-Za-z0-9._-]")
        fun safe(id: String): String = UNSAFE.replace(id, "_").take(80).ifBlank { "song" }
    }
}

/** What happened when you asked for Gemini lyrics. */
sealed class AiLyricsResult {
    data class Done(val lyrics: LyricsData) : AiLyricsResult()
    /** No key in Settings. */
    object NoKey : AiLyricsResult()
    /** Gemini said there's no singing. */
    object Instrumental : AiLyricsResult()
    /** The reply had nothing usable in it. */
    object Unusable : AiLyricsResult()
    /** The song's audio couldn't be had (YouTube refused, or no connection). */
    object NoAudio : AiLyricsResult()
    /** Longer than [AiLyricsMaker.MAX_DURATION_MS]. */
    object TooLong : AiLyricsResult()
    /** No reply in time. */
    object TimedOut : AiLyricsResult()
    /** Gemini wouldn't write them out (copyright or safety). */
    object Refused : AiLyricsResult()
    data class Failed(val problem: GeminiProblem) : AiLyricsResult()
}

/** The song as the request needs it. */
data class AiLyricsSong(val id: String, val title: String?, val artist: String?, val durationMs: Long)

/** Gets a song's audio as one file in a format Gemini reads. */
fun interface SongAudioSource {
    /** Throws on failure. The caller deletes [PreparedAudio.file] when [PreparedAudio.temporary]. */
    suspend fun prepare(song: AiLyricsSong): PreparedAudio
}

data class PreparedAudio(val file: File, val mimeType: String, val temporary: Boolean)

/** One request: audio + the fixed prompt, returns Gemini's text. */
fun interface AiLyricsRequester {
    suspend fun transcribe(endpoint: GeminiEndpoint, audio: PreparedAudio, prompt: String): String
}

/**
 * Runs the requests. They live in the app (not the screen), so closing full screen lyrics or
 * changing song doesn't lose a request that's on its way: it still gets saved for that song.
 * Asking again for a song already in progress joins the same request. Cancel stops it.
 */
class AiLyricsMaker(
    private val store: AiLyricsStore,
    private val audio: SongAudioSource,
    private val requester: AiLyricsRequester,
    private val scope: CoroutineScope,
) {
    private val lock = Any()
    private val running = HashMap<String, Deferred<AiLyricsResult>>()

    /** Requests made (tests). */
    @Volatile var requestsMade = 0
        private set

    suspend fun saved(trackId: String): LyricsData? = withContext(Dispatchers.IO) { store.load(trackId) }

    fun isWorking(trackId: String): Boolean = synchronized(lock) { running[trackId]?.isActive == true }

    /**
     * The lyrics for [song]. A saved copy comes straight back (no Gemini call) unless
     * [regenerate]; then the old copy stays saved until a new one works. [endpoint] null = no key.
     */
    suspend fun generate(song: AiLyricsSong, endpoint: GeminiEndpoint?, regenerate: Boolean): AiLyricsResult {
        if (!regenerate) saved(song.id)?.let { return AiLyricsResult.Done(it) }
        val job = synchronized(lock) {
            running[song.id]?.takeIf { it.isActive } ?: run {
                if (endpoint == null) return AiLyricsResult.NoKey
                if (song.durationMs > MAX_DURATION_MS) return AiLyricsResult.TooLong
                scope.async { work(song, endpoint) }.also { d ->
                    running[song.id] = d
                    d.invokeOnCompletion { synchronized(lock) { if (running[song.id] === d) running.remove(song.id) } }
                }
            }
        }
        return job.await()
    }

    fun cancel(trackId: String) {
        val d = synchronized(lock) { running.remove(trackId) }
        (d as? Job)?.cancel()
    }

    private suspend fun work(song: AiLyricsSong, endpoint: GeminiEndpoint): AiLyricsResult {
        val prepared = try {
            audio.prepare(song)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: GeminiException) {
            return AiLyricsResult.Failed(e.problem)
        } catch (e: Exception) {
            return AiLyricsResult.NoAudio
        }
        try {
            requestsMade++
            val reply = try {
                requester.transcribe(endpoint, prepared, AiLyricsPrompt.build(song.title, song.artist, song.durationMs))
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: GeminiException) {
                return AiLyricsResult.Failed(e.problem)
            } catch (e: AiTimeoutException) {
                return AiLyricsResult.TimedOut
            } catch (e: AiRefusedException) {
                return AiLyricsResult.Refused
            } catch (e: java.io.IOException) {
                return AiLyricsResult.Failed(GeminiProblem.Offline)
            } catch (e: Exception) {
                return AiLyricsResult.Unusable
            }
            return when (val o = AiLyricsParser.parse(reply, song.durationMs)) {
                is AiLyricsParser.Outcome.Lines -> {
                    withContext(Dispatchers.IO) { runCatching { store.save(song.id, o.lines, endpoint.model) } }
                    AiLyricsResult.Done(AiLyricsParser.toLyrics(song.id, o.lines))
                }
                AiLyricsParser.Outcome.Instrumental -> AiLyricsResult.Instrumental
                AiLyricsParser.Outcome.Unusable -> AiLyricsResult.Unusable
            }
        } finally {
            if (prepared.temporary) prepared.file.delete()
        }
    }

    companion object {
        /** Longer than this (a mix, a podcast) isn't a song to transcribe. */
        const val MAX_DURATION_MS = 20L * 60_000L
    }
}
