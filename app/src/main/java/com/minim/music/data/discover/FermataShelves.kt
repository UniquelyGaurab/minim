package com.minim.music.data.discover

import com.minim.fermata.FermataRecs
import com.minim.music.data.fermata.Recommendations.Companion.seedTracks
import com.minim.music.data.fermata.Recommendations.Companion.toTrack
import com.minim.music.data.model.Track

/**
 * 6.1: the Discover shelves that come from Fermata's taste model, once there is enough history.
 * Pure: the same model and time give the same shelves. [DiscoverEngine] uses these in place of
 * its own "On repeat" and "Haven't played in a while", adds "Because you like X", and picks
 * them before the shuffled ones. In cold start (or before the model is built) none of this runs
 * and Discover works as it did in 6.0.
 */
object FermataShelves {
    /** What Fermata gives Discover for one build. */
    data class Picks(
        /** "On repeat": songs played 3+ times this week, most first. */
        val onRepeat: List<Track>,
        /** "Forgotten favourites": liked or much-played songs not heard for a month. */
        val forgotten: List<Track>,
        /** "Because you like X": the top artist by taste, and their own best songs as mix seeds. */
        val becauseArtist: String?,
        val becauseSeeds: List<Track>,
        /** Further top artists by taste, best first (for "More like" / "If you like"), each with seeds. */
        val moreArtists: List<Pair<String, List<Track>>>,
    )

    /**
     * Reads the shelves from [recs]. [known] maps ids to the library's copies (artwork, source);
     * [blocked] are "not interested" ids (Fermata leaves those out too, this covers a mark not
     * yet in its log). Songs over [maxSongSec] are left out of the song shelves, as in 6.0.
     */
    fun read(
        recs: FermataRecs,
        known: Map<String, Track>,
        blocked: Set<String>,
        maxSongSec: Long,
        shelfSize: Int,
        now: Long,
    ): Picks {
        fun ok(t: Track) = t.id !in blocked && (t.durationSec <= maxSongSec || t.durationSec == 0L)
        val onRepeat = recs.onRepeat(limit = shelfSize * 2, now = now).orEmpty().map { it.toTrack(known) }.filter(::ok)
        val forgotten = recs.forgottenFavourites(limit = shelfSize * 2, now = now).orEmpty().map { it.toTrack(known) }.filter(::ok)
        val artists = recs.becauseYouLike(artists = 4, seedsPerArtist = 3, now = now).orEmpty()
            .filter { !it.artist.equals(UNKNOWN_ARTIST, ignoreCase = true) }
            .map { it.artist to it.seedTracks(known).filter { t -> t.id !in blocked } }
            .filter { it.second.isNotEmpty() }
        return Picks(
            onRepeat = onRepeat,
            forgotten = forgotten,
            becauseArtist = artists.firstOrNull()?.first,
            becauseSeeds = artists.firstOrNull()?.second.orEmpty(),
            moreArtists = artists.drop(1),
        )
    }

    /** Minim's placeholder for a song with no artist; never a "Because you like" artist. */
    private const val UNKNOWN_ARTIST = "Unknown Artist"
}
