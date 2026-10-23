package com.minim.music.ui

/**
 * v4.9.4: the Settings "Colours" choice.
 *  - Dynamic: the app takes its colours from the playing song's cover (as before).
 *  - Default: the black-and-white look (seed 0) all the time, whatever is playing.
 * The recap story and card keep the top song's colours either way.
 */
object ColourMode {
    /** The seed the app palette is built from: the cover colour in Dynamic, none in Default. */
    fun seedFor(coverSeed: Int, dynamic: Boolean): Int = if (dynamic) coverSeed else 0
}
