package com.minim.music.util

import java.util.regex.Pattern

/**
 * 5.3.1: every regex in Minim is built here, so one bad pattern can't take a whole class down.
 *
 * Android's regex engine (ICU) is stricter than desktop Java's, where the unit tests run. In 5.3.0
 * SongMatcher had `\{[^}]*}` (a bare `}`), which desktop Java accepts and ICU rejects. Because the
 * Regex was built while the class loaded, Android threw ExceptionInInitializerError and every
 * Spotify / text import crashed the app.
 *
 * Here a rejected pattern becomes one that never matches (and is logged): the feature that uses it
 * works a little worse instead of crashing. IcuRegexSafetyTest checks every pattern in the app, so
 * the fallback should never actually be needed.
 */
object SafeRegex {

    /** How many patterns were rejected since the app started (tests expect 0). */
    @Volatile
    var failures: Int = 0
        private set

    /** Text no title, URL or lyric line contains, so the fallback never matches. */
    private const val NEVER_TEXT = "\uFFFFminim-never\uFFFF"

    fun of(pattern: String, vararg options: RegexOption): Regex = try {
        Regex(pattern, options.toSet())
    } catch (t: Throwable) {
        failed(pattern, t)
        never()
    }

    /** Same for code that needs a java.util.regex.Pattern (Matcher groups). */
    fun pattern(pattern: String, flags: Int = 0): Pattern = try {
        Pattern.compile(pattern, flags)
    } catch (t: Throwable) {
        failed(pattern, t)
        Pattern.compile(Pattern.quote(NEVER_TEXT))
    }

    private fun never(): Regex = Regex(Pattern.quote(NEVER_TEXT))

    private fun failed(pattern: String, t: Throwable) {
        failures++
        try {
            android.util.Log.e("Minim", "Regex rejected, using a never-matching one: $pattern", t)
        } catch (_: Throwable) {
            // no Android Log in plain unit tests
        }
    }
}
