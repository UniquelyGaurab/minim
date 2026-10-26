package com.minim.music.data.importer

import com.minim.music.util.SafeRegex
import java.util.Locale

/**
 * 5.3.0: what was pasted (or shared) into the Import Playlist box. Minim works it out by itself,
 * there is no source picker.
 */
sealed class ImportInput {
    /** A YouTube or YouTube Music playlist (also an album, OLAK5uy_..., or a mix, RD...). */
    data class YouTube(val listId: String) : ImportInput() {
        val url: String get() = "https://www.youtube.com/playlist?list=$listId"
    }

    /** A Spotify playlist or album. [kind] is "playlist" or "album". */
    data class Spotify(val kind: String, val id: String) : ImportInput()

    /** A spotify.link / spoti.fi short link: followed once to find the real one. */
    data class SpotifyShort(val url: String) : ImportInput()

    /** Plain lines, one song each ("song – artist"). Already without repeats. */
    data class Lines(val songs: List<SourceSong>) : ImportInput()

    /** A link Minim can't import, with why. */
    data class Unsupported(val why: Why) : ImportInput()

    object Empty : ImportInput()

    enum class Why { SPOTIFY_NOT_PLAYLIST, YOUTUBE_NOT_PLAYLIST, YOUTUBE_PRIVATE_LIST, UNKNOWN_LINK }
}

/** One song as the source lists it, before it's found on YouTube. */
data class SourceSong(
    val title: String,
    /** Artists as the source names them (Spotify), or the part after the dash (plain text). May be empty. */
    val artists: List<String>,
    /** 0 when unknown. */
    val durationMs: Long = 0L,
    /** Plain text only: the whole line, kept for searching when it couldn't be split. */
    val line: String = ""
) {
    /** What to search YouTube for. */
    val query: String
        get() = if (artists.isEmpty()) (line.ifBlank { title }) else "$title ${artists.first()}"

    /** Two rows with the same key are the same song (repeats inside one import). */
    val key: String
        get() = SongMatcher.core(title) + "|" + artists.joinToString(",") { SongMatcher.core(it) }
}

object ImportDetect {

    const val MAX_LINES = 1000

    private val URL = SafeRegex.of("""(?i)\b(?:https?://|www\.|open\.spotify\.com/|music\.youtube\.com/|youtube\.com/|m\.youtube\.com/|youtu\.be/|spotify\.link/|spoti\.fi/)\S+""")
    private val SPOTIFY_URI = SafeRegex.of("""(?i)\bspotify:(playlist|album|track|artist|episode|show|user):([A-Za-z0-9]+)""")
    private val SPOTIFY_PATH = SafeRegex.of("""(?i)^/(?:intl-[a-z_-]+/|embed/)?(playlist|album|track|artist|episode|show|user)/([A-Za-z0-9]+)""")
    private val LIST_PARAM = SafeRegex.of("""[?&]list=([A-Za-z0-9_-]+)""")

    fun detect(raw: String): ImportInput {
        val text = raw.trim()
        if (text.isEmpty()) return ImportInput.Empty

        SPOTIFY_URI.find(text)?.let { m -> return spotify(m.groupValues[1].lowercase(Locale.ROOT), m.groupValues[2]) }

        val url = URL.find(text)?.value?.trimEnd('.', ',', ')', ']', '"', '\'', '>')
        if (url != null) return fromUrl(url)

        return lines(text)
    }

    private fun spotify(kind: String, id: String): ImportInput =
        if (kind == "playlist" || kind == "album") ImportInput.Spotify(kind, id)
        else ImportInput.Unsupported(ImportInput.Why.SPOTIFY_NOT_PLAYLIST)

    private fun fromUrl(found: String): ImportInput {
        val withScheme = if (found.contains("://")) found else "https://$found"
        val afterScheme = withScheme.substringAfter("://")
        val host = afterScheme.substringBefore('/').substringBefore('?').substringBefore('#')
            .substringAfter('@').substringBefore(':').lowercase(Locale.ROOT)
        val rest = afterScheme.removePrefix(afterScheme.substringBefore('/').substringBefore('?'))
        val path = rest.substringBefore('?').substringBefore('#')
        return when {
            host == "open.spotify.com" || host == "play.spotify.com" -> {
                val m = SPOTIFY_PATH.find(path) ?: return ImportInput.Unsupported(ImportInput.Why.SPOTIFY_NOT_PLAYLIST)
                spotify(m.groupValues[1].lowercase(Locale.ROOT), m.groupValues[2])
            }
            host == "spotify.link" || host == "spoti.fi" || host.endsWith(".app.link") && withScheme.contains("spotify") ->
                ImportInput.SpotifyShort(withScheme)
            isYouTubeHost(host) -> {
                val list = LIST_PARAM.find(rest)?.groupValues?.get(1)
                when {
                    list == null -> ImportInput.Unsupported(ImportInput.Why.YOUTUBE_NOT_PLAYLIST)
                    // Liked music, Watch later: only the signed-in owner can read them
                    list == "LM" || list == "LL" || list == "WL" -> ImportInput.Unsupported(ImportInput.Why.YOUTUBE_PRIVATE_LIST)
                    else -> ImportInput.YouTube(list)
                }
            }
            else -> ImportInput.Unsupported(ImportInput.Why.UNKNOWN_LINK)
        }
    }

    private fun isYouTubeHost(host: String): Boolean =
        host == "youtube.com" || host.endsWith(".youtube.com") || host == "youtu.be" ||
            host == "youtube-nocookie.com" || host.endsWith(".youtube-nocookie.com")

    // ---- plain text ------------------------------------------------------------------------------

    private val NUMBERING = SafeRegex.of("""^\s*(?:\d{1,4}\s*[.):\]-]\s+|[-*•·▪►]\s*)""")
    private val TRAILING_TIME = SafeRegex.of("""\s*[\[(]?\b\d{1,2}:\d{2}(?::\d{2})?\b[\])]?\s*$""")
    private val DASH = SafeRegex.of("""\s+[-–—]\s+|\s*[–—]\s*""")
    private val BY = SafeRegex.of("""\s+by\s+""", RegexOption.IGNORE_CASE)

    /** One song per line. Numbering, bullets and times are dropped; repeats are removed. */
    fun lines(text: String): ImportInput {
        val seen = HashSet<String>()
        val songs = ArrayList<SourceSong>()
        for (rawLine in text.lineSequence()) {
            val song = parseLine(rawLine) ?: continue
            if (seen.add(song.key)) songs.add(song)
            if (songs.size >= MAX_LINES) break
        }
        return if (songs.isEmpty()) ImportInput.Empty else ImportInput.Lines(songs)
    }

    /** "Kesariya – Arijit Singh" -> title Kesariya, artist Arijit Singh. Null for an empty line. */
    fun parseLine(rawLine: String): SourceSong? {
        var line = rawLine.replace('\t', ' ').trim()
        line = NUMBERING.replace(line, "")
        line = TRAILING_TIME.replace(line, "").trim().trim('"', '“', '”')
        if (line.isEmpty() || line.none { it.isLetterOrDigit() }) return null
        val parts = DASH.split(line, limit = 2).map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.size == 2) return SourceSong(parts[0], splitArtists(parts[1]), line = line)
        val by = BY.split(line, limit = 2).map { it.trim() }.filter { it.isNotEmpty() }
        if (by.size == 2) return SourceSong(by[0], splitArtists(by[1]), line = line)
        return SourceSong(line, emptyList(), line = line)
    }

    private val ARTIST_SPLIT = SafeRegex.of("""\s*(?:,|&|\bfeat\.?|\bft\.?|\bfeaturing\b)\s*""", RegexOption.IGNORE_CASE)

    fun splitArtists(s: String): List<String> =
        s.split(ARTIST_SPLIT).map { it.trim() }.filter { it.isNotEmpty() }
}
