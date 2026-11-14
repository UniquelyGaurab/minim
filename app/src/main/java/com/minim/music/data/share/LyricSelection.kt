package com.minim.music.data.share

/**
 * 6.6.0 lyrics card: which lines are picked in full-screen lyrics' select mode. Pure Kotlin, so the
 * rules are unit tested:
 *  - the picked lines are always next to each other ([first]..[last]);
 *  - tap the line just above or below to add it, tap an end line to drop it;
 *  - at most [MAX_LINES] lines and about [MAX_CHARS] characters (one long line on its own is fine);
 *  - tapping a line further away starts again from that line;
 *  - blank lines can't be picked.
 */
class LyricSelection(lines: List<String>) {

    enum class Result { STARTED, ADDED, REMOVED, NOTHING, LIMIT_LINES, LIMIT_CHARS }

    private val texts: List<String> = lines.map { it.trim() }

    var first: Int = -1
        private set
    var last: Int = -1
        private set

    val isActive: Boolean get() = first >= 0
    val count: Int get() = if (isActive) last - first + 1 else 0
    val chars: Int get() = if (isActive) (first..last).sumOf { len(texts[it]) } else 0
    val range: IntRange get() = if (isActive) first..last else IntRange.EMPTY
    val selected: List<String> get() = if (isActive) texts.subList(first, last + 1) else emptyList()

    fun isSelectable(i: Int): Boolean = i in texts.indices && texts[i].isNotEmpty()

    /** Starts over at line [i]; false (and nothing changes) for a blank or missing line. */
    fun start(i: Int): Boolean {
        if (!isSelectable(i)) return false
        first = i
        last = i
        return true
    }

    /** The first line that can be picked at or after [i], else at or before it; -1 when none can. */
    fun nearestSelectable(i: Int): Int {
        if (texts.isEmpty()) return -1
        val from = i.coerceIn(0, texts.lastIndex)
        for (j in from..texts.lastIndex) if (isSelectable(j)) return j
        for (j in from downTo 0) if (isSelectable(j)) return j
        return -1
    }

    fun clear() {
        first = -1
        last = -1
    }

    fun tap(i: Int): Result {
        if (!isSelectable(i)) return Result.NOTHING
        if (!isActive) return if (start(i)) Result.STARTED else Result.NOTHING
        return when {
            i == first - 1 || i == last + 1 -> {
                when {
                    count >= MAX_LINES -> Result.LIMIT_LINES
                    chars + len(texts[i]) > MAX_CHARS -> Result.LIMIT_CHARS
                    else -> {
                        if (i < first) first = i else last = i
                        Result.ADDED
                    }
                }
            }
            i == first && count > 1 -> { first++; Result.REMOVED }
            i == last && count > 1 -> { last--; Result.REMOVED }
            i in first..last -> Result.NOTHING
            else -> { start(i); Result.STARTED }
        }
    }

    companion object {
        const val MAX_LINES = 5
        const val MAX_CHARS = 180

        /** Characters as a person counts them (code points, so Hindi and emoji count once each). */
        fun len(s: String): Int = s.codePointCount(0, s.length)
    }
}
