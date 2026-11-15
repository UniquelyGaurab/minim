package com.minim.music.data.share

/**
 * The choices on the lyrics-card share sheet (6.7.0). Kept as plain data so the sheet,
 * the saved preference and the tests all agree on one set of rules.
 */
data class LyricsCardOptions(
    val animated: Boolean = false,
    val sound: Boolean = true,
    val lyrics: Lyrics = Lyrics.ORIGINAL,
    val background: Background = Background.COLOURS
) {
    enum class Lyrics { ORIGINAL, TRANSLATION, BOTH }
    enum class Background { COLOURS, BLUR }

    /** Stored in one preference string, e.g. `style=animated;sound=on;lyrics=both;bg=blur`. */
    fun encode(): String =
        "style=${if (animated) "animated" else "static"};sound=${if (sound) "on" else "off"};" +
            "lyrics=${lyrics.name.lowercase()};bg=${if (background == Background.BLUR) "blur" else "colours"}"

    companion object {
        val DEFAULT = LyricsCardOptions()

        /** Anything missing or garbled falls back to the default for that one choice. */
        fun decode(raw: String?): LyricsCardOptions {
            if (raw.isNullOrBlank()) return DEFAULT
            val map = raw.split(';').mapNotNull { part ->
                val i = part.indexOf('=')
                if (i <= 0) null else part.substring(0, i).trim() to part.substring(i + 1).trim().lowercase()
            }.toMap()
            return LyricsCardOptions(
                animated = map["style"] == "animated",
                sound = map["sound"] != "off",
                lyrics = when (map["lyrics"]) {
                    "translation" -> Lyrics.TRANSLATION
                    "both" -> Lyrics.BOTH
                    else -> Lyrics.ORIGINAL
                },
                background = if (map["bg"] == "blur") Background.BLUR else Background.COLOURS
            )
        }
    }
}

/** Which rows and pills the sheet shows, and what a card is really made with. */
object LyricsCardRules {

    data class Shown(
        val animatedEnabled: Boolean,
        val animatedNote: Boolean,
        val soundRow: Boolean,
        val lyricsRow: Boolean
    )

    fun shown(o: LyricsCardOptions, synced: Boolean, hasTranslation: Boolean): Shown {
        val e = effective(o, synced, hasTranslation)
        return Shown(
            animatedEnabled = synced,
            animatedNote = !synced,
            soundRow = e.animated,
            lyricsRow = hasTranslation
        )
    }

    /**
     * The options a card is made with. The remembered choices are never rewritten by this:
     * a song without timing or without a translation just ignores the choice it cannot use.
     */
    fun effective(o: LyricsCardOptions, synced: Boolean, hasTranslation: Boolean): LyricsCardOptions =
        o.copy(
            animated = o.animated && synced,
            sound = o.animated && synced && o.sound,
            lyrics = if (hasTranslation) o.lyrics else LyricsCardOptions.Lyrics.ORIGINAL
        )
}

/** What the preview and the Share button do for an animated card while video and audio arrive. */
object LyricsCardAudio {
    enum class Status { IDLE, LOADING, READY, FAILED }
    enum class Preview { IMAGE, RENDERING, VIDEO_SILENT, VIDEO_SOUND, AUDIO_FAILED }

    /**
     * @param skipSound the user tapped "Share without sound" after the audio failed.
     */
    fun preview(
        e: LyricsCardOptions,
        videoReady: Boolean,
        audio: Status,
        skipSound: Boolean
    ): Preview = when {
        !e.animated -> Preview.IMAGE
        !e.sound || skipSound -> if (videoReady) Preview.VIDEO_SILENT else Preview.RENDERING
        audio == Status.FAILED -> Preview.AUDIO_FAILED
        videoReady && audio == Status.READY -> Preview.VIDEO_SOUND
        else -> Preview.RENDERING
    }

    /** True when Share card / Save can go ahead right now with what the preview shows. */
    fun ready(p: Preview): Boolean =
        p == Preview.IMAGE || p == Preview.VIDEO_SILENT || p == Preview.VIDEO_SOUND
}
