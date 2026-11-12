package com.minim.music.data.stories

import com.minim.music.R
import com.minim.music.data.model.Track

/**
 * 5.7.0 u44: the stories tab. YouTube channels the user adds; their uploads play as audio only
 * through the normal player. Plain logic here (no Android), so it is unit tested.
 */

/** One added channel. [id] is the UC... channel id when known, else the channel link. */
data class StoryChannel(
    val id: String,
    val name: String,
    val url: String,
    val avatarUrl: String = "",
    /** "1.2M subscribers" style line for the add sheet only (not saved). */
    val subtitle: String = ""
)

/** One upload from an added channel. [publishedAt] is epoch ms, 0 when YouTube gave no date. */
data class StoryUpload(
    val videoId: String,
    val title: String,
    val channelId: String,
    val channelName: String,
    val durationSec: Long,
    val publishedAt: Long,
    /** u47: views when YouTube gave them (-1 when not), for a channel page's Popular order. Not saved. */
    val viewCount: Long = -1L
) {
    fun toTrack(): Track = StoryTracks.track(videoId, title, channelName, durationSec, channelId)
}

/**
 * A stories upload is a normal [Track] with a marker in its source link, so it stays a story
 * through the queue, the saved queue, Liked Songs and offline saves without any new column.
 * Its cover is the video thumbnail (i.ytimg.com), which the artwork pipeline cuts to a centred
 * square everywhere a cover shows (and the colours are taken from that square).
 */
object StoryTracks {
    const val MARK = "minim=story"

    const val CHANNEL_PARAM = "mch="

    /** u47: the channel's UC id rides along after the marker, so Now Playing can open its Channel page. */
    fun sourceUrl(videoId: String, channelId: String? = null): String {
        val base = "https://www.youtube.com/watch?v=$videoId&$MARK"
        return if (channelId != null && channelId.startsWith("UC") && channelId.length >= 12) "$base&$CHANNEL_PARAM$channelId" else base
    }

    /** The tape's channel id, when it was saved with one (tapes from before u47 have none). */
    fun channelIdOf(track: Track?): String? {
        if (!isStory(track)) return null
        val url = track!!.sourceUrl ?: return null
        val i = url.indexOf("&$CHANNEL_PARAM")
        if (i < 0) return null
        val id = url.substring(i + CHANNEL_PARAM.length + 1).substringBefore('&')
        return id.takeIf { it.startsWith("UC") && it.length >= 12 }
    }

    fun thumbnailUrl(videoId: String) = "https://i.ytimg.com/vi/$videoId/hqdefault.jpg"

    fun track(videoId: String, title: String, channel: String, durationSec: Long, channelId: String? = null) = Track(
        id = videoId,
        title = title,
        artist = channel,
        durationSec = durationSec.coerceAtLeast(0L),
        thumbnailUrl = thumbnailUrl(videoId),
        sourceUrl = sourceUrl(videoId, channelId)
    )

    fun isStory(track: Track?): Boolean {
        val url = track?.sourceUrl ?: return false
        return url.contains("&$MARK") || url.contains("?$MARK")
    }
}

/** Now Playing's small line above the cover: songs are vibed to, stories are listened to. */
object NowPlayingLabel {
    fun forTrack(track: Track?): Int =
        if (StoryTracks.isStory(track)) R.string.youre_listening_to else R.string.youre_vibing_to
}

/** 5.7.0 u46: which tabs' top rows start with the "minim" logo (by Tab name). Stories has no music search. */
object TopRowBranding {
    fun showsLogo(tab: String): Boolean = tab == "HOME" || tab == "STORIES" || tab == "TAPES"
    fun showsSearch(tab: String): Boolean = tab == "STATS" || tab == "LIBRARY"
}

/** 5.7.0 u46: Now Playing's CC button (top right, next to the 3 dots) is for stories uploads only. */
object NowPlayingCaptions {
    fun showCcButton(track: Track?): Boolean = StoryTracks.isStory(track)

    /**
     * 6.1.1: captions on tapes start off. The CC button turns them on and off again. The choice
     * carries over to the next tape while Minim stays open, and starts off again after Minim is
     * closed (it isn't saved).
     */
    const val ON_BY_DEFAULT = false

    /** 6.1.1: the CC button is full strength while captions are on, faded while they're off. */
    fun ccAlpha(on: Boolean): Float = if (on) 1f else CC_OFF_ALPHA

    const val CC_OFF_ALPHA = 0.4f

    /** The caption pill's text: "English", or "English · auto" for YouTube's auto captions. */
    fun pillLabel(languageName: String, auto: Boolean): String =
        if (auto) "$languageName \u00B7 auto" else languageName

    /** The pill only shows when there's something to switch to. */
    fun showPill(optionCount: Int): Boolean = optionCount > 1
}

/**
 * Shorts never reach stories. Whatever YouTube tells us counts: the item's Shorts flag, a
 * /shorts/ link, or (when neither says so) a length of 60 seconds or less. Live streams and
 * premieres that haven't happened yet (no length) can't play as an upload, so they stay out too.
 */
object ShortsFilter {
    const val MAX_SHORT_SEC = 60L

    fun isShort(url: String?, durationSec: Long, shortFlag: Boolean): Boolean {
        if (shortFlag) return true
        if (url != null && url.contains("/shorts/", ignoreCase = true)) return true
        return durationSec in 1..MAX_SHORT_SEC
    }

    /** True when the upload can show in stories: not a Short, not live, has a length. */
    fun keep(url: String?, durationSec: Long, shortFlag: Boolean, isLive: Boolean): Boolean =
        !isLive && durationSec > 0 && !isShort(url, durationSec, shortFlag)
}

/** Channel links the add sheet opens directly (everything else is a name search). */
object ChannelLinks {
    private val PATH = com.minim.music.util.SafeRegex.of(
        """^(?:https?://)?(?:www\.|m\.|music\.)?youtube\.com/(@[^/?#\s]+|channel/UC[\w-]{10,}|c/[^/?#\s]+|user/[^/?#\s]+)""",
        RegexOption.IGNORE_CASE
    )
    private val CHANNEL_ID = com.minim.music.util.SafeRegex.of("""/channel/(UC[\w-]{10,})""")

    /** The link to open for [input], or null when it is a name to search. */
    fun normalise(input: String): String? {
        val s = input.trim()
        if (s.isEmpty() || s.contains(' ')) {
            return null
        }
        // A bare handle like "@mkbhd"
        if (s.startsWith("@") && s.length > 1 && !s.contains('/')) return "https://www.youtube.com/$s"
        val m = PATH.find(s) ?: return null
        return "https://www.youtube.com/${m.groupValues[1]}"
    }

    /** The UC... id inside a /channel/ link, or null. */
    fun channelId(url: String): String? =
        CHANNEL_ID.find(url)?.groupValues?.get(1)
}

/** The saved channel list: add (no doubles, newest first), remove. */
object StoryChannelList {
    fun add(list: List<StoryChannel>, channel: StoryChannel): List<StoryChannel> {
        if (list.any { same(it, channel) }) return list
        return listOf(channel.copy(subtitle = "")) + list
    }

    fun remove(list: List<StoryChannel>, id: String): List<StoryChannel> = list.filterNot { it.id == id }

    fun contains(list: List<StoryChannel>, channel: StoryChannel) = list.any { same(it, channel) }

    private fun same(a: StoryChannel, b: StoryChannel): Boolean =
        a.id == b.id || (a.url.isNotBlank() && a.url.trimEnd('/').equals(b.url.trimEnd('/'), ignoreCase = true))
}

/** The feed: every channel's uploads mixed, newest first, no doubles, only channels still added. */
object StoryFeed {
    const val MAX_ITEMS = 300

    fun merge(uploads: List<StoryUpload>, channels: List<StoryChannel>): List<StoryUpload> {
        val ids = channels.map { it.id }.toSet()
        // Same date (or none): keep the order YouTube gave within the channel
        return uploads.withIndex()
            .filter { it.value.channelId in ids }
            .distinctBy { it.value.videoId }
            .sortedWith(compareByDescending<IndexedValue<StoryUpload>> { it.value.publishedAt }.thenBy { it.index })
            .map { it.value }
            .take(MAX_ITEMS)
    }

    /** How old an upload is, as a bucket and a count (the UI picks the words). */
    enum class Age { TODAY, YESTERDAY, DAYS, WEEKS, MONTHS, YEARS, UNKNOWN }

    fun age(publishedAt: Long, now: Long): Pair<Age, Int> {
        if (publishedAt <= 0L) return Age.UNKNOWN to 0
        val days = ((now - publishedAt).coerceAtLeast(0L) / 86_400_000L).toInt()
        return when {
            days < 1 -> Age.TODAY to 0
            days < 2 -> Age.YESTERDAY to 1
            days < 7 -> Age.DAYS to days
            days < 30 -> Age.WEEKS to days / 7
            days < 365 -> Age.MONTHS to (days / 30).coerceAtLeast(1)
            else -> Age.YEARS to days / 365
        }
    }
}
