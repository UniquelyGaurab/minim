package com.minim.music.data.source

import com.minim.music.data.model.ArtistCountKind

/**
 * v4.6.1: NewPipe reads the number under a YouTube Music artist result ("647M monthly audience",
 * "155K subscribers") as a subscriber count and drops the words. These helpers read the words
 * from the raw search response so each number gets the right label.
 */
object ArtistAudience {

    /** What the subtitle text counts: "647M monthly audience" -> MONTHLY_LISTENERS. */
    fun kindOf(text: String?): ArtistCountKind {
        val t = text?.lowercase().orEmpty()
        return when {
            t.contains("monthly") -> ArtistCountKind.MONTHLY_LISTENERS
            t.contains("subscriber") -> ArtistCountKind.SUBSCRIBERS
            else -> ArtistCountKind.UNKNOWN
        }
    }

    /** "https://music.youtube.com/channel/UCabc?x=1" -> "UCabc"; null when there is none. */
    fun channelId(url: String?): String? {
        val u = url ?: return null
        val i = u.indexOf("/channel/")
        if (i < 0) return null
        val rest = u.substring(i + "/channel/".length)
        val id = rest.takeWhile { it != '/' && it != '?' && it != '#' }
        return id.ifBlank { null }
    }

    /**
     * Channel id -> the last piece of text of each artist row's last column (the piece NewPipe
     * reads its number from), found anywhere in a search response tree of maps and lists.
     */
    fun subtitlesById(tree: Any?): Map<String, String> {
        val out = HashMap<String, String>()
        walk(tree, out, 0)
        return out
    }

    private fun walk(node: Any?, out: MutableMap<String, String>, depth: Int) {
        if (depth > 40) return
        when (node) {
            is Map<*, *> -> {
                val row = node["musicResponsiveListItemRenderer"] as? Map<*, *>
                if (row != null) readRow(row, out)
                for (v in node.values) walk(v, out, depth + 1)
            }
            is List<*> -> for (v in node) walk(v, out, depth + 1)
        }
    }

    private fun readRow(row: Map<*, *>, out: MutableMap<String, String>) {
        val id = ((row["navigationEndpoint"] as? Map<*, *>)
            ?.get("browseEndpoint") as? Map<*, *>)
            ?.get("browseId") as? String ?: return
        val columns = row["flexColumns"] as? List<*> ?: return
        val last = columns.lastOrNull() as? Map<*, *> ?: return
        val runs = (((last["musicResponsiveListItemFlexColumnRenderer"] as? Map<*, *>)
            ?.get("text") as? Map<*, *>)
            ?.get("runs") as? List<*>) ?: return
        val text = (runs.lastOrNull() as? Map<*, *>)?.get("text") as? String ?: return
        out.putIfAbsent(id, text)
    }

    /** The raw response a NewPipe YouTube Music search extractor fetched, or null. */
    fun rawResponse(extractor: Any): Any? = try {
        var c: Class<*>? = extractor.javaClass
        var found: Any? = null
        while (c != null && found == null) {
            val f = c.declaredFields.firstOrNull { it.name == "initialData" }
            if (f != null) {
                f.isAccessible = true
                found = f.get(extractor)
            }
            c = c.superclass
        }
        found
    } catch (e: Throwable) {
        null
    }
}
