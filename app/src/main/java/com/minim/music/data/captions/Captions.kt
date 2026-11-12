package com.minim.music.data.captions

import com.minim.music.data.model.LyricsData
import com.minim.music.data.model.LyricsLine
import com.minim.music.util.SafeRegex

/**
 * 5.7.0 u46: YouTube captions for stories uploads, shown in Full Screen Lyrics in place of lyrics.
 * Plain logic (no Android): which caption track to use, reading YouTube's caption files, and
 * joining auto-caption chunks into sentence lines. Unit tested.
 */

/** One caption track of a video. [urls] maps a YouTube format ("srv1", "vtt", "srv3", "ttml") to its link. */
data class CaptionTrack(
    /** YouTube's language code, like "en", "en-GB", "hi", "te". */
    val languageTag: String,
    /** The language in words, like "English" (the UI adds "auto" for [auto]). */
    val languageName: String,
    /** True for YouTube's auto-generated (speech recognition) captions. */
    val auto: Boolean,
    val urls: Map<String, String>
) {
    val language: String get() = CaptionPicker.base(languageTag)
}

/** One timed caption: [startMs] to [endMs]. */
data class Cue(val startMs: Long, val endMs: Long, val text: String)

object CaptionPicker {
    /** At most this many tracks in the switch pill. */
    const val MAX_CHOICES = 4

    fun base(tag: String): String = tag.trim().lowercase().substringBefore('-').substringBefore('_')

    /**
     * The video's spoken language: YouTube's auto captions are always in it, so their language
     * wins; else what YouTube says about the video; else the first track the channel added.
     */
    fun originalLanguage(tracks: List<CaptionTrack>, videoLanguage: String?): String? =
        tracks.firstOrNull { it.auto }?.language
            ?: videoLanguage?.takeIf { it.isNotBlank() }?.let { base(it) }
            ?: tracks.firstOrNull { !it.auto }?.language

    /**
     * The tracks to offer, best first: the channel's own captions before auto-generated ones, and
     * within each, the original language before English. Other languages only when there's
     * nothing in either. One track per language and kind.
     */
    fun choices(tracks: List<CaptionTrack>, videoLanguage: String? = null): List<CaptionTrack> {
        val usable = tracks.filter { it.urls.isNotEmpty() && it.languageTag.isNotBlank() }
        if (usable.isEmpty()) return emptyList()
        val original = originalLanguage(usable, videoLanguage)
        fun langRank(t: CaptionTrack): Int = when (t.language) {
            original -> 0
            "en" -> 1
            else -> 2
        }
        val ranked = usable.withIndex()
            .sortedWith(compareBy<IndexedValue<CaptionTrack>>({ if (it.value.auto) 1 else 0 }, { langRank(it.value) }, { it.index }))
            .map { it.value }
            .distinctBy { it.language to it.auto }
        val preferred = ranked.filter { langRank(it) < 2 }
        return (preferred.ifEmpty { ranked }).take(MAX_CHOICES)
    }

    /** The format to download first, then the others. */
    val FORMATS = listOf("srv1", "vtt", "srv3", "ttml")

    fun urlsInOrder(track: CaptionTrack): List<String> =
        FORMATS.mapNotNull { track.urls[it] } + track.urls.filterKeys { it !in FORMATS }.values
}

/** Reads YouTube caption files: srv1 (transcript), srv3 (timedtext), TTML and WebVTT. */
object CaptionParser {
    private val SRV1 = SafeRegex.of("""<text\b([^>]*)>(.*?)</text>""", RegexOption.DOT_MATCHES_ALL)
    private val SRV3_P = SafeRegex.of("""<p\b([^>]*?)(?:/>|>(.*?)</p>)""", RegexOption.DOT_MATCHES_ALL)
    private val ATTR = SafeRegex.of("""([\w:]+)\s*=\s*"([^"]*)"""")
    private val TAG = SafeRegex.of("""<[^>]*>""")
    private val ENTITY = SafeRegex.of("""&(#x[0-9a-fA-F]+|#\d+|amp|lt|gt|quot|apos|nbsp);""")
    private val BLANK_LINE = SafeRegex.of("\n\\s*\n")
    private val BR = SafeRegex.of("<br\\s*/?>", RegexOption.IGNORE_CASE)
    private val SPACES = SafeRegex.of("\\s+")
    private val VTT_TIME = SafeRegex.of("""((?:\d+:)?\d{1,2}:\d{2}[.,]\d{1,3})\s+-->\s+((?:\d+:)?\d{1,2}:\d{2}[.,]\d{1,3})""")

    fun parse(body: String?): List<Cue> {
        if (body.isNullOrBlank()) return emptyList()
        val s = body.trimStart('\uFEFF', ' ', '\n', '\r', '\t')
        val cues = when {
            s.startsWith("WEBVTT") -> parseVtt(s)
            s.contains("<transcript") || SRV1.containsMatchIn(s) && !s.contains("<timedtext") -> parseSrv1(s)
            s.contains("<timedtext") || s.contains("<tt") -> parsePara(s)
            else -> emptyList()
        }
        return cues.filter { it.text.isNotBlank() }.sortedBy { it.startMs }
    }

    private fun parseSrv1(s: String): List<Cue> = SRV1.findAll(s).mapNotNull { m ->
        val a = attrs(m.groupValues[1])
        val start = secondsToMs(a["start"]) ?: return@mapNotNull null
        val dur = secondsToMs(a["dur"]) ?: 0L
        Cue(start, start + dur, clean(m.groupValues[2]))
    }.toList()

    /** srv3 (`<p t="ms" d="ms">`) and TTML (`<p begin="00:00:01.000" end=...>`). */
    private fun parsePara(s: String): List<Cue> = SRV3_P.findAll(s).mapNotNull { m ->
        val a = attrs(m.groupValues[1])
        val text = clean(m.groupValues[2])
        if (text.isBlank()) return@mapNotNull null
        val t = a["t"]?.toLongOrNull()
        if (t != null) {
            Cue(t, t + (a["d"]?.toLongOrNull() ?: 0L), text)
        } else {
            val begin = clockToMs(a["begin"]) ?: return@mapNotNull null
            val end = clockToMs(a["end"]) ?: (begin + (clockToMs(a["dur"]) ?: 0L))
            Cue(begin, end, text)
        }
    }.toList()

    /**
     * WebVTT. YouTube's auto captions there roll: each cue repeats the line before it, so a line
     * that was just shown is not shown again.
     */
    private fun parseVtt(s: String): List<Cue> {
        val out = ArrayList<Cue>()
        val blocks = s.replace("\r\n", "\n").split(BLANK_LINE)
        var last = ""
        for (b in blocks) {
            val lines = b.split('\n')
            val ti = lines.indexOfFirst { VTT_TIME.containsMatchIn(it) }
            if (ti < 0) continue
            val m = VTT_TIME.find(lines[ti]) ?: continue
            val start = clockToMs(m.groupValues[1]) ?: continue
            val end = clockToMs(m.groupValues[2]) ?: start
            val texts = lines.drop(ti + 1).map { clean(it) }.filter { it.isNotBlank() }
            val fresh = texts.filter { it != last }
            if (fresh.isEmpty()) continue
            // A blink cue (10 ms) only holds the rolled line; skip it
            if (end - start < 50 && texts.size == 1 && texts[0] == last) continue
            val text = fresh.joinToString(" ")
            last = texts.last()
            out.add(Cue(start, end, text))
        }
        return out
    }

    private fun attrs(raw: String): Map<String, String> =
        ATTR.findAll(raw).associate { it.groupValues[1] to it.groupValues[2] }

    private fun secondsToMs(v: String?): Long? = v?.toDoubleOrNull()?.let { Math.round(it * 1000) }

    /** "01:02:03.450", "02:03.450", "12.5s" or "1234ms" to ms. */
    fun clockToMs(v: String?): Long? {
        val s = v?.trim() ?: return null
        if (s.isEmpty()) return null
        if (s.endsWith("ms")) return s.dropLast(2).toDoubleOrNull()?.toLong()
        if (s.endsWith("s")) return s.dropLast(1).toDoubleOrNull()?.let { Math.round(it * 1000) }
        val parts = s.replace(',', '.').split(':')
        if (parts.size !in 2..3) return s.toDoubleOrNull()?.let { (it * 1000).toLong() }
        val sec = parts.last().toDoubleOrNull() ?: return null
        val min = parts[parts.size - 2].toLongOrNull() ?: return null
        val hr = if (parts.size == 3) parts[0].toLongOrNull() ?: return null else 0L
        return hr * 3_600_000L + min * 60_000L + Math.round(sec * 1000)
    }

    /** Tags out, entities decoded (YouTube sometimes encodes twice), one line, single spaces. */
    fun clean(raw: String): String {
        var t = raw.replace(BR, " ")
        t = TAG.replace(t, "")
        repeat(2) { t = decode(t) }
        t = TAG.replace(t, "")
        return t.replace('\u00A0', ' ').replace(SPACES, " ").trim()
    }

    private fun decode(s: String): String = ENTITY.replace(s) { m ->
        when (val e = m.groupValues[1]) {
            "amp" -> "&"
            "lt" -> "<"
            "gt" -> ">"
            "quot" -> "\""
            "apos" -> "'"
            "nbsp" -> " "
            else -> {
                val code = if (e.startsWith("#x")) e.drop(2).toIntOrNull(16) else e.drop(1).toIntOrNull()
                if (code != null && code in 1..0x10FFFF) String(Character.toChars(code)) else m.value
            }
        }
    }
}

/**
 * Auto captions arrive in tiny 2 or 3 word chunks. These are joined into lines you can read: a
 * line ends at the end of a sentence, at a pause, or when it gets long. The channel's own captions
 * are kept as they are (only cleaned).
 */
object CaptionJoiner {
    /** A pause this long between chunks ends the line. */
    const val PAUSE_MS = 1_200L
    /** A sentence end only ends the line once it has this many characters. */
    const val MIN_CHARS = 18
    /** Lines never grow past this many characters... */
    const val MAX_CHARS = 64
    /** ...or last longer than this. */
    const val MAX_LINE_MS = 7_000L

    private val SENTENCE_END = SafeRegex.of("""[.?!।॥。！？]['"”’)]*$""")
    private val SPACES = SafeRegex.of("\\s+")
    private val SOUND_TAG = SafeRegex.of("""^[\[(][^\])]*[\])]$""")

    fun join(cues: List<Cue>, auto: Boolean): List<Cue> {
        val clean = cues.asSequence()
            .map { it.copy(text = tidy(it.text, auto)) }
            .filter { it.text.isNotBlank() }
            .filter { !auto || !SOUND_TAG.matches(it.text) }
            .toList()
        if (clean.isEmpty()) return emptyList()
        if (!auto) return dedupe(clean)

        val lines = ArrayList<Cue>()
        var start = -1L
        var end = 0L
        val text = StringBuilder()
        fun flush() {
            if (text.isNotEmpty()) lines.add(Cue(start, end, capitalise(text.toString().trim())))
            text.setLength(0)
            start = -1L
            end = 0L
        }
        for ((i, c) in clean.withIndex()) {
            if (text.isNotEmpty()) {
                val prev = clean[i - 1]
                val gap = c.startMs - prev.endMs
                val tooLong = text.length + 1 + c.text.length > MAX_CHARS
                val tooSlow = c.startMs - start >= MAX_LINE_MS
                val sentence = text.length >= MIN_CHARS && SENTENCE_END.containsMatchIn(text)
                if (gap >= PAUSE_MS || tooLong || tooSlow || sentence) flush()
            }
            if (text.isEmpty()) start = c.startMs else text.append(' ')
            text.append(c.text)
            end = maxOf(end, c.endMs, c.startMs)
        }
        flush()
        return fixEnds(lines)
    }

    /** Each line ends where the next one starts (or its own end, when that comes first). */
    private fun fixEnds(lines: List<Cue>): List<Cue> = lines.mapIndexed { i, l ->
        val next = lines.getOrNull(i + 1)?.startMs
        val e = if (next != null) minOf(maxOf(l.endMs, l.startMs + 1), next) else maxOf(l.endMs, l.startMs + 1)
        l.copy(endMs = maxOf(e, l.startMs))
    }

    private fun dedupe(cues: List<Cue>): List<Cue> {
        val out = ArrayList<Cue>(cues.size)
        for (c in cues) {
            val last = out.lastOrNull()
            if (last != null && last.text == c.text && c.startMs - last.endMs < PAUSE_MS) {
                out[out.size - 1] = last.copy(endMs = maxOf(last.endMs, c.endMs))
            } else out.add(c)
        }
        return fixEnds(out)
    }

    private fun tidy(t: String, auto: Boolean): String {
        var s = t.replace(SPACES, " ").trim()
        if (auto) s = s.replace(">>", "").replace(SPACES, " ").trim()
        return s
    }

    private fun capitalise(s: String): String =
        if (s.isNotEmpty() && s[0].isLowerCase()) s[0].uppercaseChar() + s.substring(1) else s
}

/** Captions as the lyrics Full Screen Lyrics already knows how to show (synced, line by line). */
object CaptionLyrics {
    const val PROVIDER = "captions"
    /** The upload has no captions (or they couldn't load): the view says so quietly. */
    const val PROVIDER_NONE = "captions-none"

    /** 6.1.1: captions are switched off (the default until the CC button turns them on). */
    const val PROVIDER_OFF = "captions-off"

    fun isCaptions(data: LyricsData?): Boolean =
        data?.providerName == PROVIDER || data?.providerName == PROVIDER_NONE || data?.providerName == PROVIDER_OFF

    /** 6.1.1: true while a tape's captions are switched off. */
    fun isOff(data: LyricsData?): Boolean = data?.providerName == PROVIDER_OFF

    fun off(trackId: String): LyricsData = LyricsData(
        trackId = trackId,
        lines = emptyList(),
        isSynced = false,
        hasWordTimestamps = false,
        providerName = PROVIDER_OFF
    )

    fun of(trackId: String, lines: List<Cue>): LyricsData = LyricsData(
        trackId = trackId,
        lines = lines.map { LyricsLine(it.startMs, it.endMs, it.text) },
        isSynced = true,
        hasWordTimestamps = false,
        providerName = if (lines.isEmpty()) PROVIDER_NONE else PROVIDER
    )

    fun none(trackId: String): LyricsData = of(trackId, emptyList())
}
