package com.minim.music.ui

import com.minim.music.data.model.Track
import org.json.JSONObject
import java.text.Normalizer
import java.util.Locale

/**
 * 6.8.0: the plain rules behind the new playlist screen (sorting, the custom order, the total
 * time, the search in the header and the custom covers). No Android views here, so the tests can
 * run every rule.
 */
object PlaylistLook {

    // ---- Sort ------------------------------------------------------------------------------

    enum class Sort(val code: String) {
        CUSTOM("custom"), RECENT("recent"), TITLE("title"), ARTIST("artist");

        companion object {
            fun fromCode(code: String?): Sort = values().firstOrNull { it.code == code } ?: CUSTOM
        }
    }

    /** A song in a playlist, with its place in your own order and when it was added. */
    data class Entry(val track: Track, val position: Long, val addedAt: Long)

    /** When a song was added; rows synced from an older build have no time, so their place stands in. */
    fun addedTime(e: Entry): Long = if (e.addedAt > 0L) e.addedAt else e.position

    /**
     * The songs in [sort] order. Custom is your own order (hold and drag). Recently added puts the
     * newest first. Title and Artist go A to Z, ignoring case and accents; songs that tie keep
     * your own order, so switching back and forth never shuffles them.
     */
    fun sort(entries: List<Entry>, sort: Sort): List<Track> {
        val custom = entries.sortedBy { it.position }
        return when (sort) {
            Sort.CUSTOM -> custom
            // songs added in the same moment: the one further down your list counts as newer
            Sort.RECENT -> custom.withIndex()
                .sortedWith(compareByDescending<IndexedValue<Entry>> { addedTime(it.value) }.thenByDescending { it.index })
                .map { it.value }
            Sort.TITLE -> custom.sortedWith(compareBy({ fold(it.track.title) }, { fold(it.track.artist) }))
            Sort.ARTIST -> custom.sortedWith(compareBy({ fold(it.track.artist) }, { fold(it.track.title) }))
        }.map { it.track }
    }

    /**
     * The new places after a drag. [current] is every song with its place now; [newOrder] is the
     * song IDs in the order on screen. The playlist keeps the same set of place numbers (handed
     * out again in the new order), so songs added later still land at the end and the other
     * phones get the order as it is. Only the songs whose place changed are returned.
     */
    fun reorderedPositions(current: List<Pair<String, Long>>, newOrder: List<String>): Map<String, Long> {
        val now = current.toMap()
        if (newOrder.size != now.size || newOrder.toSet() != now.keys) return emptyMap()
        val slots = current.map { it.second }.sorted().toMutableList()
        // two songs may share a place (an old import); make every place unique and rising
        for (i in 1 until slots.size) if (slots[i] <= slots[i - 1]) slots[i] = slots[i - 1] + 1
        val out = LinkedHashMap<String, Long>()
        newOrder.forEachIndexed { i, id -> if (now[id] != slots[i]) out[id] = slots[i] }
        return out
    }

    /** Moves one item, like a drag from [from] to [to]. */
    fun <T> move(list: List<T>, from: Int, to: Int): List<T> {
        if (from !in list.indices || to !in list.indices || from == to) return list
        val m = list.toMutableList()
        m.add(to, m.removeAt(from))
        return m
    }

    // ---- Total time --------------------------------------------------------------------------

    /** "1 hr 32 min", "2 hr", "45 min"; null when no song has a known length. */
    fun totalTime(tracks: List<Track>): String? {
        val sec = tracks.sumOf { it.durationSec.coerceAtLeast(0L) }
        if (sec <= 0L) return null
        val mins = ((sec + 30L) / 60L).coerceAtLeast(1L)
        val h = mins / 60L
        val m = mins % 60L
        return when {
            h == 0L -> "$m min"
            m == 0L -> "$h hr"
            else -> "$h hr $m min"
        }
    }

    // ---- Search in the header ----------------------------------------------------------------

    fun isActive(query: String?): Boolean = !query.isNullOrBlank()

    /** Songs whose title or artist has every word typed (case and accents don't matter). */
    fun filter(tracks: List<Track>, query: String?): List<Track> {
        if (!isActive(query)) return tracks
        val words = fold(query!!).split(' ').filter { it.isNotBlank() }
        return tracks.filter { t ->
            val hay = fold(t.title + " " + t.artist)
            words.all { hay.contains(it) }
        }
    }

    private val MARKS = com.minim.music.util.SafeRegex.of("\\p{Mn}+")
    private val SPACES = com.minim.music.util.SafeRegex.of("\\s+")

    internal fun fold(s: String?): String {
        if (s.isNullOrEmpty()) return ""
        val n = Normalizer.normalize(s, Normalizer.Form.NFD).replace(MARKS, "")
        return n.lowercase(Locale.ROOT).replace(SPACES, " ").trim()
    }

    // ---- Custom covers -----------------------------------------------------------------------

    enum class Mode(val code: String) {
        AUTO("auto"), MOSAIC("mosaic"), TYPE("type"), BLUR("blur"), PHOTO("photo");

        companion object {
            fun fromCode(code: String?): Mode = values().firstOrNull { it.code == code } ?: AUTO
        }
    }

    /**
     * How a playlist's cover is drawn. Type keeps its word and colour (fixed: it doesn't follow
     * the playing song); Blur keeps the song whose cover it blurs; Photo keeps the file name of
     * the cropped picture in the app's own storage.
     */
    data class Cover(
        val mode: Mode = Mode.AUTO,
        val text: String = "",
        val color: Int = 0,
        val trackId: String? = null,
        val url: String? = null,
        val file: String? = null
    ) {
        fun encode(): String = JSONObject().apply {
            put("m", mode.code)
            if (text.isNotEmpty()) put("t", text)
            if (mode == Mode.TYPE) put("c", color)
            trackId?.let { put("s", it) }
            url?.let { put("u", it) }
            file?.let { put("f", it) }
        }.toString()

        companion object {
            val AUTO = Cover()

            fun decode(raw: String?): Cover {
                if (raw.isNullOrBlank()) return AUTO
                return try {
                    val o = JSONObject(raw)
                    val mode = Mode.fromCode(o.optString("m"))
                    val c = Cover(
                        mode = mode,
                        text = o.optString("t", ""),
                        color = o.optInt("c", 0),
                        trackId = o.optString("s", "").ifEmpty { null },
                        url = o.optString("u", "").ifEmpty { null },
                        file = o.optString("f", "").ifEmpty { null }
                    )
                    if (isUsable(c)) c else AUTO
                } catch (_: Exception) {
                    AUTO
                }
            }

            /** A cover that lost what it needs (no word, no picture) falls back to Auto. */
            fun isUsable(c: Cover): Boolean = when (c.mode) {
                Mode.AUTO, Mode.MOSAIC -> true
                Mode.TYPE -> c.text.isNotBlank()
                Mode.BLUR -> !c.url.isNullOrBlank()
                Mode.PHOTO -> !c.file.isNullOrBlank()
            }
        }
    }

    /** The first four different covers, for the 2x2 mosaic; null when there aren't four. */
    fun mosaicUrls(urls: List<String?>): List<String>? {
        val distinct = urls.mapNotNull { it?.trim()?.takeIf { u -> u.isNotEmpty() } }.distinct()
        return if (distinct.size >= 4) distinct.take(4) else null
    }

    /** Songs with a cover, one per cover, for the Blur picker (in playlist order). */
    fun <T> distinctCovers(items: List<T>, url: (T) -> String?, max: Int = 12): List<T> {
        val seen = HashSet<String>()
        val out = ArrayList<T>()
        for (it in items) {
            val u = url(it)?.trim().orEmpty()
            if (u.isEmpty() || !seen.add(u)) continue
            out.add(it)
            if (out.size >= max) break
        }
        return out
    }

    const val TYPE_MAX_CHARS = 12

    /**
     * What a Type cover shows: one word (the first, up to 12 letters) or one emoji. Spaces at the
     * ends go; anything after the first word is dropped.
     */
    fun typeText(raw: String?): String {
        val s = raw?.trim().orEmpty()
        if (s.isEmpty()) return ""
        if (isSingleEmoji(s)) return s
        val word = s.split(SPACES).first()
        if (word.codePointCount(0, word.length) <= TYPE_MAX_CHARS) return word
        return word.substring(0, word.offsetByCodePoints(0, TYPE_MAX_CHARS))
    }

    /** One emoji, including skin tones, flags and joined emoji like a family. */
    fun isSingleEmoji(s: String): Boolean {
        if (s.isEmpty()) return false
        val it = java.text.BreakIterator.getCharacterInstance(Locale.ROOT)
        it.setText(s)
        var count = 0
        while (it.next() != java.text.BreakIterator.DONE) count++
        // BreakIterator on older JDKs splits joined emoji, so also accept a run that's all emoji parts
        val allEmoji = s.codePoints().allMatch { isEmojiPart(it) }
        val hasPictograph = s.codePoints().anyMatch { isPictograph(it) }
        return hasPictograph && allEmoji && (count == 1 || s.codePoints().anyMatch { it == 0x200D || it in 0x1F1E6..0x1F1FF })
    }

    private fun isPictograph(cp: Int): Boolean =
        cp in 0x1F300..0x1FAFF || cp in 0x2600..0x27BF || cp in 0x1F1E6..0x1F1FF ||
            cp in 0x2B00..0x2BFF || cp in 0x1F000..0x1F2FF || cp == 0x2764 || cp in 0x2190..0x21FF

    private fun isEmojiPart(cp: Int): Boolean =
        isPictograph(cp) || cp == 0x200D || cp == 0xFE0F || cp == 0xFE0E || cp in 0x1F3FB..0x1F3FF ||
            cp in 0xE0020..0xE007F || cp == 0x20E3

    /**
     * The colours offered for a Type cover, from the playlist's own covers: clear, different
     * colours first (near-greys and near-duplicates are left out), at most [max].
     */
    fun typeColors(candidates: List<Int>, max: Int = 6): List<Int> {
        val out = ArrayList<Int>()
        val sorted = candidates.filter { it != 0 }.sortedByDescending { saturation(it) }
        for (c in sorted) {
            val opaque = c or 0xFF000000.toInt()
            if (out.none { distance(it, opaque) < 48.0 }) out.add(opaque)
            if (out.size >= max) break
        }
        if (out.isEmpty()) out.add(DEFAULT_TYPE_COLOR)
        return out
    }

    const val DEFAULT_TYPE_COLOR = 0xFF2E7D5B.toInt()

    /** White or near-black, whichever reads better on [bg]. */
    fun textOn(bg: Int): Int = if (luminance(bg) > 0.55) 0xFF111111.toInt() else 0xFFFFFFFF.toInt()

    internal fun luminance(c: Int): Double {
        fun ch(v: Int): Double {
            val s = v / 255.0
            return if (s <= 0.03928) s / 12.92 else Math.pow((s + 0.055) / 1.055, 2.4)
        }
        return 0.2126 * ch((c shr 16) and 0xFF) + 0.7152 * ch((c shr 8) and 0xFF) + 0.0722 * ch(c and 0xFF)
    }

    private fun saturation(c: Int): Double {
        val r = (c shr 16) and 0xFF; val g = (c shr 8) and 0xFF; val b = c and 0xFF
        val mx = maxOf(r, g, b); val mn = minOf(r, g, b)
        return if (mx == 0) 0.0 else (mx - mn).toDouble() / mx
    }

    private fun distance(a: Int, b: Int): Double {
        val dr = ((a shr 16) and 0xFF) - ((b shr 16) and 0xFF)
        val dg = ((a shr 8) and 0xFF) - ((b shr 8) and 0xFF)
        val db = (a and 0xFF) - (b and 0xFF)
        return Math.sqrt((dr * dr + dg * dg + db * db).toDouble())
    }

    // ---- which controls a list shows -----------------------------------------------------------

    /** What's open in the playlist screen. */
    enum class ListType { OWN, ALBUM, LIKED, LIKED_ARTISTS, RECENTS, OFFLINE }

    /**
     * The header's controls. Only your own playlists can be renamed, re-covered, sorted, dragged,
     * shared, added to or deleted (the ⋯ holds Sort, Edit cover, Download all and Delete).
     * Albums and Liked Songs get Play, Shuffle and search; Liked > Artists is a list of artists.
     */
    data class Controls(
        val play: Boolean,
        val shuffle: Boolean,
        val search: Boolean,
        val more: Boolean,
        val rename: Boolean,
        val share: Boolean,
        val addSongs: Boolean,
        val reorder: Boolean
    )

    fun controls(type: ListType): Controls {
        val own = type == ListType.OWN
        val songs = type != ListType.LIKED_ARTISTS
        return Controls(
            play = songs, shuffle = songs, search = songs,
            more = own, rename = own, share = own, addSongs = own, reorder = own
        )
    }

    /** Drag to reorder only in your own order with nothing typed in the search. */
    fun canDrag(type: ListType, sort: Sort, query: String?): Boolean =
        controls(type).reorder && sort == Sort.CUSTOM && !isActive(query)
}
