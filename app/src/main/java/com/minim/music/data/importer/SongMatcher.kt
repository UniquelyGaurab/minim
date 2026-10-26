package com.minim.music.data.importer

import com.minim.music.data.model.Track
import java.text.Normalizer
import java.util.Locale
import kotlin.math.abs

/** 5.3.0: why a found song might not be the one in the imported playlist. */
enum class MatchFlag { REMIX, COVER, LIVE, VERSION, OTHER_ARTIST, LENGTH, WEAK }

enum class Confidence { SURE, DOUBTFUL, NONE }

data class Scored(val track: Track, val score: Double, val flags: Set<MatchFlag>, val sure: Boolean)

data class MatchResult(val best: Scored?, val options: List<Scored>, val confidence: Confidence)

/**
 * 5.3.0: picks the YouTube result that is the song a Spotify playlist or a pasted line names.
 *
 * Pure Kotlin so it is unit tested. A match is sure only when the title is all there, the artist
 * matches (or the line had no artist and every word of it is found), the length is close (when
 * both are known) and nothing marks it as another version: remix, cover, live, slowed, karaoke and
 * the like count only when the source title doesn't say the same.
 */
object SongMatcher {

    const val MAX_OPTIONS = 5
    private const val NONE_BELOW = 0.3

    /** Lower case, accents dropped, letters and digits (any script) separated by single spaces. */
    fun norm(s: String): String {
        val d = Normalizer.normalize(s, Normalizer.Form.NFKD).replace(Regex("\\p{Mn}+"), "")
        val sb = StringBuilder(d.length)
        var space = true
        for (c in d.lowercase(Locale.ROOT)) {
            if (c.isLetterOrDigit()) {
                sb.append(c)
                space = false
            } else if (!space) {
                sb.append(' ')
                space = true
            }
        }
        return sb.toString().trim()
    }

    private val BRACKETS = Regex("""\([^)]*\)|\[[^]]*]|\{[^}]*}|（[^）]*）|【[^】]*】""")
    private val FEAT = Regex("""(?i)\s(?:feat\.?|ft\.?|featuring)\s.*$""")
    private val SUFFIX_WORDS = Regex("""(?i)remaster|version|edit|mix|live|from|mono|stereo|radio|bonus|demo|acoustic|session|recorded|soundtrack|ost""")
    private val VIDEO_WORDS = Regex(
        """(?i)\b(?:official\s+(?:music\s+)?(?:video|audio|lyric\s+video|lyrics?\s+video|visuali[sz]er)|official|lyrics?\s+video|lyrical(?:\s+video)?|lyrics?|full\s+(?:video|song|audio)|video\s+song|audio\s+song|hd|4k|hq|mv|m/v)\b"""
    )

    /** The title without brackets, "feat." parts, " - Remastered 2011" style endings and video words. */
    fun core(title: String): String {
        var t = BRACKETS.replace(title, " ")
        val dash = Regex("""\s[-–—]\s""").find(t)
        if (dash != null) {
            val after = t.substring(dash.range.last + 1)
            if (SUFFIX_WORDS.containsMatchIn(after)) t = t.substring(0, dash.range.first)
        }
        t = FEAT.replace(t, "")
        t = VIDEO_WORDS.replace(t, " ")
        val n = norm(t)
        return n.ifEmpty { norm(title) }
    }

    private fun tokens(s: String): List<String> = s.split(' ').filter { it.isNotEmpty() }

    // Words that make a result another version of the song. Checked as words of the normalised
    // title (and two-word phrases), only when the source title doesn't have them.
    private val VARIANTS: Map<String, MatchFlag> = mapOf(
        "remix" to MatchFlag.REMIX, "rmx" to MatchFlag.REMIX, "mashup" to MatchFlag.REMIX,
        "bootleg" to MatchFlag.REMIX, "remixed" to MatchFlag.REMIX,
        "cover" to MatchFlag.COVER, "covered" to MatchFlag.COVER, "tribute" to MatchFlag.COVER,
        "reaction" to MatchFlag.COVER, "tutorial" to MatchFlag.COVER, "lesson" to MatchFlag.COVER,
        "parody" to MatchFlag.COVER,
        "live" to MatchFlag.LIVE, "concert" to MatchFlag.LIVE, "unplugged" to MatchFlag.LIVE,
        "acoustic" to MatchFlag.VERSION, "instrumental" to MatchFlag.VERSION, "karaoke" to MatchFlag.VERSION,
        "slowed" to MatchFlag.VERSION, "reverb" to MatchFlag.VERSION, "sped" to MatchFlag.VERSION,
        "spedup" to MatchFlag.VERSION, "nightcore" to MatchFlag.VERSION, "8d" to MatchFlag.VERSION,
        "lofi" to MatchFlag.VERSION, "lo fi" to MatchFlag.VERSION, "reprise" to MatchFlag.VERSION,
        "extended" to MatchFlag.VERSION, "bass boosted" to MatchFlag.VERSION, "version" to MatchFlag.VERSION,
        "piano" to MatchFlag.VERSION, "female" to MatchFlag.VERSION, "male" to MatchFlag.VERSION
    )

    /** Version words in [candidateTitle] that [sourceTitle] doesn't have. */
    fun variantFlags(sourceTitle: String, candidateTitle: String): Set<MatchFlag> {
        val src = phrases(norm(sourceTitle))
        val out = HashSet<MatchFlag>()
        for (p in phrases(norm(candidateTitle))) {
            val f = VARIANTS[p] ?: continue
            if (p !in src) out.add(f)
        }
        return out
    }

    private fun phrases(n: String): Set<String> {
        val t = tokens(n)
        val out = HashSet<String>(t)
        for (i in 0 until t.size - 1) out.add(t[i] + " " + t[i + 1])
        return out
    }

    private fun squash(s: String): String = norm(s).replace(" ", "").removeSuffix("topic").removeSuffix("vevo")

    /** True when one of [artists] is the result's artist or is named in its title. */
    fun artistMatches(artists: List<String>, t: Track): Boolean {
        val candArtist = squash(t.artist)
        val candTitle = squash(t.title)
        return artists.any { a ->
            val n = squash(a)
            n.length >= 2 && (candArtist.contains(n) || candTitle.contains(n) ||
                (candArtist.length >= 3 && n.contains(candArtist)))
        }
    }

    /** Share of [want] words found in [have] (letters-only fallback for scripts without spaces). */
    private fun coverage(want: String, have: String): Double {
        val w = tokens(want)
        if (w.isEmpty()) return 0.0
        val h = tokens(have).toHashSet()
        val byWord = w.count { it in h }.toDouble() / w.size
        if (byWord >= 1.0) return 1.0
        val ws = want.replace(" ", "")
        if (ws.length >= 2 && have.replace(" ", "").contains(ws)) return 1.0
        return byWord
    }

    fun score(song: SourceSong, t: Track): Scored {
        val best = orientations(song).map { scoreOne(it, t) }.maxByOrNull { it.score }!!
        return best
    }

    /** Plain text "artist - song" is tried both ways round; Spotify songs only one way. */
    private fun orientations(song: SourceSong): List<SourceSong> {
        if (song.line.isEmpty() || song.artists.size != 1) return listOf(song)
        return listOf(song, SourceSong(song.artists[0], listOf(song.title), song.durationMs, song.line))
    }

    private fun scoreOne(song: SourceSong, t: Track): Scored {
        val want = core(song.title)
        val candTitle = norm(BRACKETS.replace(t.title, " "))
        val candAll = norm(t.title + " " + t.artist)
        val titleCov = maxOf(coverage(want, candTitle), coverage(want, core(t.title)))
        // how much of the result's own title is the song (and its artist): low for medleys, jukeboxes
        val candCore = tokens(core(t.title))
        val known = (tokens(want) + song.artists.flatMap { tokens(norm(it)) }).toHashSet()
        val precision = if (candCore.isEmpty()) 0.0 else candCore.count { it in known }.toDouble() / candCore.size

        val flags = HashSet<MatchFlag>()
        flags.addAll(variantFlags(song.title, t.title))

        val hasArtist = song.artists.isNotEmpty()
        val artistOk = hasArtist && artistMatches(song.artists, t)
        if (hasArtist && !artistOk) flags.add(MatchFlag.OTHER_ARTIST)
        // a line with no artist: every word of it has to be in the result's title or artist
        val lineCov = if (!hasArtist) coverage(norm(song.line.ifBlank { song.title }), candAll) else 0.0

        var durationScore = 0.05
        val srcSec = song.durationMs / 1000L
        if (srcSec > 0 && t.durationSec > 0) {
            val diff = abs(srcSec - t.durationSec)
            durationScore = when {
                diff <= 7 -> 0.1
                diff <= 20 -> 0.05
                else -> 0.0
            }
            if (diff > 30 && diff > srcSec * 0.15) flags.add(MatchFlag.LENGTH)
        }

        var score = titleCov * 0.5 + precision * 0.1 + durationScore +
            when {
                artistOk -> 0.3
                !hasArtist -> 0.3 * lineCov
                else -> 0.0
            }
        if (flags.any { it == MatchFlag.REMIX || it == MatchFlag.COVER || it == MatchFlag.LIVE || it == MatchFlag.VERSION }) score -= 0.25
        if (MatchFlag.LENGTH in flags) score -= 0.15

        val sure = titleCov >= 0.8 && (artistOk || (!hasArtist && lineCov >= 0.9)) &&
            flags.isEmpty() && precision >= 0.25
        if (!sure && flags.isEmpty()) flags.add(MatchFlag.WEAK)
        return Scored(t, score, flags, sure)
    }

    /** Scores every result; the best one decides how sure the match is. Search order breaks ties. */
    fun match(song: SourceSong, candidates: List<Track>): MatchResult {
        val scored = candidates.distinctBy { it.id }.map { score(song, it) }
        val ranked = scored.withIndex()
            .sortedWith(compareByDescending<IndexedValue<Scored>> { it.value.sure }
                .thenByDescending { it.value.score }.thenBy { it.index })
            .map { it.value }
        val best = ranked.firstOrNull()
        val confidence = when {
            best == null -> Confidence.NONE
            best.sure -> Confidence.SURE
            best.score < NONE_BELOW -> Confidence.NONE
            else -> Confidence.DOUBTFUL
        }
        return MatchResult(best, ranked.take(MAX_OPTIONS), confidence)
    }
}
