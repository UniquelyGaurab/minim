package com.minim.music.data.share

import com.minim.music.data.ai.JsonOut
import com.minim.music.data.greetings.MiniJson
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.Deflater
import java.util.zip.Inflater

/**
 * 6.6.0 (update 68): Minim share links.
 *
 *   song      https://uniquelygaurab.github.io/m/s#1.<data>
 *   playlist  https://uniquelygaurab.github.io/m/p#2.<data>   (ids only, see [PlaylistIds])
 *
 * Everything rides after the `#`, so it never reaches a server: GitHub Pages only ever sees /m/s or /m/p.
 * `1` is the payload version. `<data>` is base64url (no padding) of deflate-raw of UTF-8 JSON:
 *
 *   song      {"i":videoId,"t":title,"a":artist,"n":sharer}
 *   playlist  {"l":name,"n":sharer,"s":[[videoId,title,artist],...]}   (v1, still read)
 *
 * Playlists are written as v2: the name, the sharer and the video ids as raw bits, no titles. A 5-song
 * link is about a third as long as v1. The receiver looks the songs up ([Decoded.SharedPlaylist.lookup]).
 * "n" is left out when the Profile name is blank. A playlist link carries at most [MAX_SONGS].
 * The web page (uniquelygaurab.github.io/m/s, /m/p) decodes the same bytes in the browser.
 *
 * Pure Kotlin (java.util.zip + java.util.Base64), so it runs the same in unit tests and on the phone.
 * [decode] never throws: anything wrong is [Decoded.Broken], a newer version is [Decoded.TooNew].
 */
object ShareLinks {

    const val HOST = "uniquelygaurab.github.io"
    const val PATH_PREFIX = "/m/"
    const val SONG_PATH = "/m/s"
    const val PLAYLIST_PATH = "/m/p"
    const val BASE = "https://$HOST"

    /** The payload versions this Minim writes and reads. Links from a newer Minim say "Update Minim". */
    const val SONG_VERSION = 1
    const val PLAYLIST_VERSION = 2

    /** Songs per playlist link. Longer playlists are asked "Share the first 100?". */
    const val MAX_SONGS = 100

    const val MAX_TITLE = 200
    const val MAX_ARTIST = 200
    const val MAX_NAME = 60
    const val MAX_LIST_NAME = 80

    /** A link longer than this is refused before decoding (100 songs came to ~4-5K in v1, ~1.1K in v2). */
    const val MAX_PAYLOAD_CHARS = 32 * 1024
    /** Inflated JSON bigger than this is refused (a tiny link can't blow up into megabytes). */
    const val MAX_JSON_BYTES = 128 * 1024

    private val VIDEO_ID = com.minim.music.util.SafeRegex.of("^[A-Za-z0-9_-]{11}$")
    private val PAYLOAD_TAIL = com.minim.music.util.SafeRegex.of("[^A-Za-z0-9_-]+$")

    data class Song(val id: String, val title: String, val artist: String)

    sealed class Decoded {
        data class SharedSong(val song: Song, val sharer: String?) : Decoded()
        /**
         * [lookup] is true for a v2 link: [songs] carry only their ids (blank titles and artists) and
         * Minim looks them up before Play and Save.
         */
        data class SharedPlaylist(
            val name: String,
            val sharer: String?,
            val songs: List<Song>,
            val skipped: Int,
            val lookup: Boolean = false
        ) : Decoded()
        /** Made by a newer Minim (payload version [version]). */
        data class TooNew(val version: Int) : Decoded()
        /** Cut short, garbled, or not a Minim link at all. */
        data class Broken(val why: String) : Decoded()
    }

    fun isVideoId(id: String?): Boolean = id != null && VIDEO_ID.matches(id)

    // ---- links ------------------------------------------------------------------------------------

    fun songLink(song: Song, sharer: String?): String = "$BASE$SONG_PATH#" + encodeSong(song, sharer)

    /** Only the first [MAX_SONGS] go in; songs without a YouTube video id are left out. */
    fun playlistLink(name: String, sharer: String?, songs: List<Song>): String =
        "$BASE$PLAYLIST_PATH#" + encodePlaylist(name, sharer, songs)

    /** True when a playlist this long gets the "Share the first 100?" question. */
    fun needsCap(count: Int): Boolean = count > MAX_SONGS

    /** The songs a playlist link can carry: real video ids only, the first [MAX_SONGS]. */
    fun shareable(songs: List<Song>): List<Song> = songs.filter { isVideoId(it.id) }.take(MAX_SONGS)

    fun encodeSong(song: Song, sharer: String?): String {
        val m = LinkedHashMap<String, Any?>()
        m["i"] = song.id
        m["t"] = clip(song.title, MAX_TITLE)
        m["a"] = clip(song.artist, MAX_ARTIST)
        cleanName(sharer)?.let { m["n"] = it }
        return pack(JsonOut.write(m))
    }

    /** v2: `2.` + the name, the sharer and the video ids ([PlaylistIds]). Titles stay on the sharer's phone. */
    fun encodePlaylist(name: String, sharer: String?, songs: List<Song>): String {
        val ids = shareable(songs).map { it.id }.distinct()
        require(ids.isNotEmpty()) { "no songs with a video id" }
        val listName = clip(name.trim(), MAX_LIST_NAME).ifBlank { "Playlist" }
        return "$PLAYLIST_VERSION." + PlaylistIds.encode(listName, cleanName(sharer), ids)
    }

    /** The 6.6.0 test builds' playlist payload (v1, with titles). Only the tests write it now; Minim still reads it. */
    internal fun encodePlaylistV1(name: String, sharer: String?, songs: List<Song>): String {
        val m = LinkedHashMap<String, Any?>()
        m["l"] = clip(name.trim(), MAX_LIST_NAME).ifBlank { "Playlist" }
        cleanName(sharer)?.let { m["n"] = it }
        m["s"] = shareable(songs).map { listOf(it.id, clip(it.title, MAX_TITLE), clip(it.artist, MAX_ARTIST)) }
        return pack(JsonOut.write(m))
    }

    /** The Profile name as it goes in a link: trimmed, at most [MAX_NAME], null when blank. */
    fun cleanName(raw: String?): String? = raw?.trim()?.let { clip(it, MAX_NAME) }?.takeIf { it.isNotBlank() }

    // ---- reading a link ------------------------------------------------------------------------

    enum class Kind { SONG, PLAYLIST }

    data class Parsed(val kind: Kind, val payload: String)

    /**
     * The kind and payload of a Minim link, from its path and fragment, or null when it isn't one.
     * Takes the parts apart by hand (no android.net.Uri), so it's the same in tests.
     */
    fun parse(url: String?): Parsed? {
        val raw = url?.trim() ?: return null
        val hash = raw.indexOf('#')
        val beforeHash = if (hash >= 0) raw.substring(0, hash) else raw
        val fragment = if (hash >= 0) raw.substring(hash + 1) else ""
        val schemeEnd = beforeHash.indexOf("://")
        if (schemeEnd <= 0) return null
        val scheme = beforeHash.substring(0, schemeEnd).lowercase()
        if (scheme != "https" && scheme != "http") return null
        val rest = beforeHash.substring(schemeEnd + 3)
        val slash = rest.indexOf('/')
        val host = (if (slash >= 0) rest.substring(0, slash) else rest).substringBefore(':').lowercase()
        if (host != HOST) return null
        val path = (if (slash >= 0) rest.substring(slash) else "/").substringBefore('?')
        return fromParts(path, fragment)
    }

    /** Same as [parse], from an intent's path and fragment (Uri.getPath / Uri.getEncodedFragment). */
    fun fromParts(path: String?, fragment: String?): Parsed? {
        val p = path?.trimEnd('/')?.removeSuffix(".html")?.lowercase() ?: return null
        val kind = when (p) {
            SONG_PATH -> Kind.SONG
            PLAYLIST_PATH -> Kind.PLAYLIST
            else -> return null
        }
        return Parsed(kind, fragment.orEmpty())
    }

    /** Decodes a link (or a bare [Parsed]) into a song, a playlist, [Decoded.TooNew] or [Decoded.Broken]. */
    fun decodeLink(url: String?): Decoded {
        val parsed = parse(url) ?: return Decoded.Broken("not a Minim link")
        return decode(parsed)
    }

    fun decode(parsed: Parsed): Decoded = try {
        decodeUnsafe(parsed)
    } catch (e: OutOfMemoryError) {
        Decoded.Broken("too big")
    } catch (e: Exception) {
        Decoded.Broken(e.message ?: e.javaClass.simpleName)
    }

    private fun decodeUnsafe(parsed: Parsed): Decoded {
        // WhatsApp & co. can glue a full stop or a bracket onto the end of a link
        var payload = parsed.payload.trim()
        if (payload.contains('%')) payload = percentDecode(payload)
        payload = payload.replace(PAYLOAD_TAIL, "")
        if (payload.isEmpty()) return Decoded.Broken("empty")
        if (payload.length > MAX_PAYLOAD_CHARS) return Decoded.Broken("too long")
        val dot = payload.indexOf('.')
        if (dot <= 0 || dot > 4) return Decoded.Broken("no version")
        val version = payload.substring(0, dot).toIntOrNull() ?: return Decoded.Broken("bad version")
        val newest = if (parsed.kind == Kind.SONG) SONG_VERSION else PLAYLIST_VERSION
        if (version > newest) return Decoded.TooNew(version)
        if (version < 1) return Decoded.Broken("bad version")
        if (version == 2) return decodeV2(payload.substring(dot + 1))
        val json = unpack(payload.substring(dot + 1)) ?: return Decoded.Broken("garbled")
        val obj = MiniJson.parse(json, MAX_JSON_BYTES) as? Map<*, *> ?: return Decoded.Broken("not an object")
        val sharer = cleanName(obj["n"] as? String)
        return when (parsed.kind) {
            Kind.SONG -> {
                val id = obj["i"] as? String
                if (!isVideoId(id)) return Decoded.Broken("no song")
                Decoded.SharedSong(song(id!!, obj["t"], obj["a"]), sharer)
            }
            Kind.PLAYLIST -> {
                val list = obj["s"] as? List<*> ?: return Decoded.Broken("no songs")
                val songs = ArrayList<Song>()
                var skipped = 0
                val seen = HashSet<String>()
                for (entry in list) {
                    val row = entry as? List<*>
                    val id = row?.getOrNull(0) as? String
                    if (row == null || !isVideoId(id) || songs.size >= MAX_SONGS || !seen.add(id!!)) {
                        skipped++
                        continue
                    }
                    songs.add(song(id, row.getOrNull(1), row.getOrNull(2)))
                }
                if (songs.isEmpty()) return Decoded.Broken("no songs")
                val name = clip((obj["l"] as? String).orEmpty().trim(), MAX_LIST_NAME).ifBlank { "Shared playlist" }
                Decoded.SharedPlaylist(name, sharer, songs, skipped)
            }
        }
    }

    private fun decodeV2(data: String): Decoded {
        val p = PlaylistIds.decode(data) ?: return Decoded.Broken("garbled")
        val songs = ArrayList<Song>()
        val seen = HashSet<String>()
        var skipped = 0
        for (id in p.ids) {
            if (!isVideoId(id) || songs.size >= MAX_SONGS || !seen.add(id)) { skipped++; continue }
            songs.add(Song(id, "", ""))
        }
        if (songs.isEmpty()) return Decoded.Broken("no songs")
        val name = clip(p.name.trim(), MAX_LIST_NAME).ifBlank { "Shared playlist" }
        return Decoded.SharedPlaylist(name, cleanName(p.sharer), songs, skipped, lookup = true)
    }

    private fun song(id: String, title: Any?, artist: Any?) = Song(
        id,
        clip((title as? String).orEmpty().trim(), MAX_TITLE).ifBlank { "Untitled" },
        clip((artist as? String).orEmpty().trim(), MAX_ARTIST).ifBlank { "Unknown Artist" }
    )

    // ---- the bytes -------------------------------------------------------------------------------

    private fun pack(json: String): String {
        val input = json.toByteArray(Charsets.UTF_8)
        val deflater = Deflater(Deflater.BEST_COMPRESSION, true)
        try {
            deflater.setInput(input)
            deflater.finish()
            val out = ByteArrayOutputStream(input.size / 2 + 64)
            val buf = ByteArray(4096)
            while (!deflater.finished()) {
                val n = deflater.deflate(buf)
                out.write(buf, 0, n)
            }
            return "$SONG_VERSION." + Base64.getUrlEncoder().withoutPadding().encodeToString(out.toByteArray())
        } finally {
            deflater.end()
        }
    }

    /** The JSON inside, or null when the data is cut short, garbled or too big. */
    private fun unpack(data: String): String? {
        val bytes = try {
            Base64.getUrlDecoder().decode(data)
        } catch (_: IllegalArgumentException) {
            return null
        }
        if (bytes.isEmpty()) return null
        val inflater = Inflater(true)
        try {
            // nowrap wants one spare byte after the stream
            inflater.setInput(bytes + 0)
            val out = ByteArrayOutputStream(bytes.size * 3)
            val buf = ByteArray(4096)
            while (!inflater.finished()) {
                val n = inflater.inflate(buf)
                // nothing came out and it isn't done: cut short (or stuck), never loop on it
                if (n == 0 && !inflater.finished()) return null
                out.write(buf, 0, n)
                if (out.size() > MAX_JSON_BYTES) return null
            }
            return String(out.toByteArray(), Charsets.UTF_8)
        } catch (_: java.util.zip.DataFormatException) {
            return null
        } finally {
            inflater.end()
        }
    }

    private fun percentDecode(s: String): String = try {
        java.net.URLDecoder.decode(s.replace("+", "%2B"), "UTF-8")
    } catch (_: Exception) {
        s
    }

    /** At most [max] chars, never cutting an emoji (a surrogate pair) in half. */
    fun clip(s: String, max: Int): String {
        if (s.length <= max) return s
        var end = max
        if (end > 0 && Character.isHighSurrogate(s[end - 1])) end--
        return s.substring(0, end)
    }

    // ---- share text ------------------------------------------------------------------------------

    /** "Gaurab shared Suroor on Minim", or "Listen to Suroor on Minim" with no Profile name. */
    fun shareLine(sharer: String?, title: String): String {
        val n = cleanName(sharer)
        return if (n != null) "$n shared $title on Minim" else "Listen to $title on Minim"
    }

    fun shareText(sharer: String?, title: String, link: String): String = shareLine(sharer, title) + "\n" + link
}
