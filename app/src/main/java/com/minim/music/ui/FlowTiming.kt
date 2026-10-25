package com.minim.music.ui

import com.minim.music.data.model.LyricsLine
import java.text.BreakIterator
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * v4.9.8 part 4: "Flow", the fake word by word lyrics style. LRC files only time whole lines,
 * so each line's time is spread over its words, weighted by word length, and the full screen
 * lyrics light the words up in turn. Pure (no Android), so all of it is unit tested.
 */
object FlowTiming {

    /** No word lights up faster than this (when the line is long enough for it). */
    const val MIN_WORD_MS = 90L
    /** Lines longer than this aren't singing: they don't count toward the typical line. */
    const val TYPICAL_MAX_GAP_MS = 15_000L
    const val TYPICAL_MIN_GAP_MS = 300L
    /** Used when a song has too few lines to tell its typical line length. */
    const val TYPICAL_DEFAULT_MS = 3_000L
    /** A line never takes longer than this many typical lines... */
    const val TYPICAL_CAP_FACTOR = 1.2
    /** ...unless its text is long: this much per letter, plus a little. */
    const val TEXT_MS_PER_UNIT = 110L
    const val TEXT_BASE_MS = 500L
    /** Unsung words of the current line: this much of the text colour. */
    const val DIM = 0.45f
    /** Each word fades in over its own time, but never quicker or slower than these. */
    const val FADE_MIN_MS = 160L
    const val FADE_MAX_MS = 700L
    /** The fade starts this share of itself before the word's slot, so words overlap a little. */
    const val FADE_LEAD = 0.25f

    /** One word: the characters [start]..[end) of the line's text, and its [weight] in letters. */
    data class Word(val start: Int, val end: Int, val weight: Int)

    /** The Lyrics timing rule (same as Now Playing): + offsets show lines (and words) earlier. */
    fun shiftedPosition(positionMs: Long, fileOffsetMs: Long, userOffsetMs: Int): Long =
        positionMs + fileOffsetMs + userOffsetMs

    /**
     * 5.1.0: where to seek so the line starting at [lineStartMs] becomes the active one: the
     * inverse of [shiftedPosition], never before the start of the song.
     */
    fun seekTargetForLine(lineStartMs: Long, fileOffsetMs: Long, userOffsetMs: Int): Long =
        (lineStartMs - fileOffsetMs - userOffsetMs).coerceAtLeast(0L)

    /** The active line at [shiftedMs]: the last one that has started, -1 before the first. */
    fun activeIndex(lines: List<LyricsLine>, shiftedMs: Long): Int {
        var index = -1
        for (i in lines.indices) {
            if (lines[i].startMs <= shiftedMs) index = i else break
        }
        return index
    }

    // ------------------------------------------------------------------------------------------
    // Words
    // ------------------------------------------------------------------------------------------

    /**
     * Splits a line into words. Spaced scripts (Latin, Hindi, Tamil, Bengali...) split on
     * whitespace. Runs in scripts written without spaces (Chinese, Japanese, Thai...) split into
     * single characters (grapheme clusters), with any Latin inside them kept as one word, and
     * punctuation sticks to the word before it.
     */
    fun splitWords(text: CharSequence): List<Word> {
        val s = text.toString()
        val out = ArrayList<Word>()
        var i = 0
        while (i < s.length) {
            while (i < s.length && isSpace(s[i])) i++
            if (i >= s.length) break
            var j = i
            while (j < s.length && !isSpace(s[j])) j++
            val token = s.substring(i, j)
            if (hasUnspacedScript(token)) splitUnspaced(token, i, out) else out.add(Word(i, j, letterCount(token)))
            i = j
        }
        return out
    }

    private fun isSpace(c: Char): Boolean = Character.isWhitespace(c) || Character.isSpaceChar(c)

    private fun splitUnspaced(token: String, base: Int, out: MutableList<Word>) {
        val it = BreakIterator.getCharacterInstance()
        it.setText(token)
        var unitStart = -1
        var unitEnd = -1
        var unitWeight = 0
        var unitUnspaced = false
        fun flush() {
            if (unitStart >= 0) out.add(Word(base + unitStart, base + unitEnd, max(1, unitWeight)))
            unitStart = -1
            unitWeight = 0
        }
        var start = it.first()
        var end = it.next()
        while (end != BreakIterator.DONE) {
            val g = token.substring(start, end)
            val cp = g.codePointAt(0)
            val unspaced = isUnspacedScript(cp)
            val letter = Character.isLetterOrDigit(cp)
            val shared = Character.UnicodeScript.of(cp).let {
                it == Character.UnicodeScript.COMMON || it == Character.UnicodeScript.INHERITED
            }
            when {
                // Punctuation and marks (and shared signs like the kana long mark) stay with the word before
                !unspaced && unitStart >= 0 && (!letter || (unitUnspaced && shared)) -> unitEnd = end
                // Each unspaced character is its own word
                unspaced -> {
                    flush()
                    unitStart = start; unitEnd = end; unitWeight = 1; unitUnspaced = true
                }
                // Latin (or other spaced letters) inside an unspaced run: one word
                else -> {
                    if (unitStart < 0 || unitUnspaced) {
                        flush()
                        unitStart = start; unitUnspaced = false
                    }
                    unitEnd = end
                    if (letter) unitWeight++
                }
            }
            start = end
            end = it.next()
        }
        flush()
    }

    private fun hasUnspacedScript(token: String): Boolean {
        var k = 0
        while (k < token.length) {
            val cp = token.codePointAt(k)
            if (isUnspacedScript(cp)) return true
            k += Character.charCount(cp)
        }
        return false
    }

    private fun isUnspacedScript(cp: Int): Boolean = when (Character.UnicodeScript.of(cp)) {
        Character.UnicodeScript.HAN, Character.UnicodeScript.HIRAGANA, Character.UnicodeScript.KATAKANA,
        Character.UnicodeScript.THAI, Character.UnicodeScript.LAO, Character.UnicodeScript.KHMER,
        Character.UnicodeScript.MYANMAR, Character.UnicodeScript.TIBETAN, Character.UnicodeScript.BOPOMOFO -> true
        else -> false
    }

    /** Length of a word in letters: grapheme clusters that hold a letter or digit (at least 1). */
    fun letterCount(token: String): Int {
        val it = BreakIterator.getCharacterInstance()
        it.setText(token)
        var n = 0
        var start = it.first()
        var end = it.next()
        while (end != BreakIterator.DONE) {
            var k = start
            while (k < end) {
                val cp = token.codePointAt(k)
                if (Character.isLetterOrDigit(cp)) {
                    n++
                    break
                }
                k += Character.charCount(cp)
            }
            start = end
            end = it.next()
        }
        return max(1, n)
    }

    // ------------------------------------------------------------------------------------------
    // Line and word times
    // ------------------------------------------------------------------------------------------

    /** The typical time between line starts: the median of the gaps that look like singing. */
    fun typicalLineMs(lines: List<LyricsLine>): Long {
        val gaps = ArrayList<Long>()
        for (i in 0 until lines.size - 1) {
            val g = lines[i + 1].startMs - lines[i].startMs
            if (g in TYPICAL_MIN_GAP_MS..TYPICAL_MAX_GAP_MS) gaps.add(g)
        }
        if (gaps.size < 2) return TYPICAL_DEFAULT_MS
        gaps.sort()
        val m = gaps.size / 2
        return if (gaps.size % 2 == 1) gaps[m] else (gaps[m - 1] + gaps[m]) / 2
    }

    /** The longest a line's words may take: about 1.2 typical lines, more for long text. */
    fun lineCapMs(units: Int, typicalMs: Long): Long =
        max((typicalMs * TYPICAL_CAP_FACTOR).toLong(), TEXT_BASE_MS + units * TEXT_MS_PER_UNIT)

    /**
     * How long each line's words take to light up: up to the next line's start, capped for long
     * gaps (an instrumental break after the line) and for the last line, which has no next one.
     */
    fun lineDurations(lines: List<LyricsLine>): LongArray {
        val typical = typicalLineMs(lines)
        return LongArray(lines.size) { i ->
            val units = splitWords(lines[i].text.trim()).sumOf { it.weight }
            val cap = lineCapMs(units, typical)
            val raw = if (i + 1 < lines.size) lines[i + 1].startMs - lines[i].startMs else -1L
            (if (raw > 0L) min(raw, cap) else cap).coerceAtLeast(1L)
        }
    }

    /**
     * Word boundaries in ms from the line's start: word i is sung from b[i] to b[i+1], and b.last()
     * is [durationMs]. Each word gets [MIN_WORD_MS], and the rest of the time goes by weight; a line
     * too short for that splits evenly.
     */
    fun wordBounds(weights: IntArray, durationMs: Long): LongArray {
        val n = weights.size
        val b = LongArray(n + 1)
        if (n == 0) return b
        val d = max(0L, durationMs)
        val total = weights.sumOf { max(1, it) }.toDouble()
        val floor = if (d >= n * MIN_WORD_MS) MIN_WORD_MS.toDouble() else d.toDouble() / n
        val spare = d - floor * n
        var acc = 0.0
        for (i in 0 until n) {
            acc += floor + spare * max(1, weights[i]) / total
            b[i + 1] = Math.round(acc).coerceIn(b[i], d)
        }
        b[n] = d
        return b
    }

    /**
     * Real word times from an Enhanced LRC line, when it has them and they match the words we
     * split; null otherwise (the usual case: LrcLib files time whole lines).
     */
    fun realWordBounds(line: LyricsLine, words: List<Word>, durationMs: Long): LongArray? {
        val w = line.words
        if (w.isEmpty() || w.size != words.size) return null
        val b = LongArray(w.size + 1)
        for (i in w.indices) b[i] = (w[i].startMs - line.startMs).coerceIn(if (i == 0) 0L else b[i - 1], durationMs)
        val lastEnd = if (w.last().endMs > w.last().startMs) w.last().endMs - line.startMs else durationMs
        b[w.size] = lastEnd.coerceIn(b[w.size - 1], max(durationMs, b[w.size - 1]))
        return b
    }

    /**
     * How lit word [i] is (0 = dim, 1 = full) [tMs] after its line started. The word fades in over
     * its own time (between [FADE_MIN_MS] and [FADE_MAX_MS]), starting a little before its slot so
     * neighbours overlap into one smooth wave; eased, so it never jumps.
     */
    fun wordFill(bounds: LongArray, i: Int, tMs: Long): Float {
        if (i < 0 || i >= bounds.size - 1) return 1f
        val start = bounds[i]
        val dur = bounds[i + 1] - start
        val fade = dur.coerceIn(FADE_MIN_MS, FADE_MAX_MS).toFloat()
        val from = start - fade * FADE_LEAD
        val x = ((tMs - from) / fade).coerceIn(0f, 1f)
        return x * x * (3f - 2f * x)
    }

    /**
     * The unsung level for the current line while its row is still fading up to full opacity, so
     * unsung words never dip below what the row showed a moment ago as a neighbour line.
     */
    fun dimFor(rowAlpha: Float): Float = (DIM / max(rowAlpha, DIM)).coerceIn(DIM, 1f)
}

/**
 * v4.9.8 part 4: the song position, per frame. The player is only asked about 4 times a second;
 * in between this runs on from the last answer while playing, so the word highlight moves every
 * frame. Small differences are eased in and never step backwards; a big one (a seek, a new song)
 * is taken at once. Paused, it stands still.
 */
class FlowClock(private val jumpMs: Long = JUMP_MS) {

    private var anchorPos = 0L
    private var anchorNs = 0L
    private var lastOut = Long.MIN_VALUE
    var playing: Boolean = false
        private set
    var hasSample: Boolean = false
        private set

    /** A new position from the player. Returns true when it was a jump (taken at once). */
    fun sample(positionMs: Long, isPlaying: Boolean, nowNs: Long): Boolean {
        val predicted = if (hasSample) raw(nowNs) else positionMs
        val err = positionMs - predicted
        val jump = !hasSample || abs(err) > jumpMs
        anchorPos = when {
            jump -> positionMs
            !isPlaying -> positionMs
            else -> predicted + err / 2
        }
        anchorNs = nowNs
        playing = isPlaying
        hasSample = true
        if (jump) lastOut = Long.MIN_VALUE
        return jump
    }

    /** Where the song is at [nowNs] (a frame time or System.nanoTime()). Never goes backwards. */
    fun positionAt(nowNs: Long): Long {
        val out = max(raw(nowNs), lastOut)
        lastOut = out
        return out
    }

    fun reset() {
        hasSample = false
        playing = false
        lastOut = Long.MIN_VALUE
    }

    private fun raw(nowNs: Long): Long {
        if (!playing) return anchorPos
        val elapsed = ((nowNs - anchorNs) / 1_000_000L).coerceIn(0L, MAX_EXTRAPOLATE_MS)
        return anchorPos + elapsed
    }

    companion object {
        /** Bigger differences than this are a seek. */
        const val JUMP_MS = 350L
        /** Without news from the player, don't run on further than this. */
        const val MAX_EXTRAPOLATE_MS = 1_000L
    }
}
