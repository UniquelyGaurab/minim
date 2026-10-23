package com.minim.music.ui

import android.view.View
import android.widget.ImageView
import android.widget.TextView
import com.minim.music.R
import com.minim.music.data.stats.DayStats
import com.minim.music.data.stats.Days
import com.minim.music.data.stats.StatsFormat
import com.minim.music.util.ArtworkPipeline

/**
 * v4.9: the Stats tab (replaces Recents). Today's four live numbers on top (songs, time, artists,
 * top artist), then the recap of the most recent past day with listening. Only fills in views;
 * MainActivity loads the numbers and paints the colours (tags in view_stats.xml).
 */
class StatsScreen(root: View, private val artwork: ArtworkPipeline) {
    private val res = root.resources
    private val todayDate: TextView = root.findViewById(R.id.stats_today_date)
    private val todaySongs: TextView = root.findViewById(R.id.stats_today_songs)
    private val todaySongsLabel: TextView = root.findViewById(R.id.stats_today_songs_label)
    private val todayTime: TextView = root.findViewById(R.id.stats_today_time)
    private val todayArtists: TextView = root.findViewById(R.id.stats_today_artists)
    private val todayArtistsLabel: TextView = root.findViewById(R.id.stats_today_artists_label)
    private val todayTopArtist: TextView = root.findViewById(R.id.stats_today_top_artist)
    private val todayTopArtistSub: TextView = root.findViewById(R.id.stats_today_top_artist_sub)
    private val todayArtistPhoto: ImageView = root.findViewById(R.id.stats_today_artist_photo)
    private val todayStreak: TextView = root.findViewById(R.id.stats_today_streak)

    /** v4.9.2: today's tappable boxes (songs and artists sheets) and the top artist card. */
    val todaySongsBox: View = root.findViewById(R.id.stats_today_songs_box)
    val todayArtistsBox: View = root.findViewById(R.id.stats_today_artists_box)
    val todayArtistCard: View = root.findViewById(R.id.stats_today_artist_card)

    private val quietLine: TextView = root.findViewById(R.id.stats_quiet_line)
    private val recapDate: TextView = root.findViewById(R.id.stats_recap_date)
    private val recapEmpty: View = root.findViewById(R.id.stats_recap_empty)
    private val recapBox: View = root.findViewById(R.id.stats_recap_box)
    private val recapTime: TextView = root.findViewById(R.id.stats_recap_time)
    private val recapQuiet: TextView = root.findViewById(R.id.stats_recap_quiet)
    private val recapSongs: TextView = root.findViewById(R.id.stats_recap_songs)
    private val recapSongsLabel: TextView = root.findViewById(R.id.stats_recap_songs_label)
    private val recapArtists: TextView = root.findViewById(R.id.stats_recap_artists)
    private val recapArtistsLabel: TextView = root.findViewById(R.id.stats_recap_artists_label)
    private val topSongCard: View = root.findViewById(R.id.stats_top_song)
    private val topSongArt: ImageView = root.findViewById(R.id.stats_top_song_art)
    private val topSongTitle: TextView = root.findViewById(R.id.stats_top_song_title)
    private val topSongSub: TextView = root.findViewById(R.id.stats_top_song_sub)
    private val topArtistCard: View = root.findViewById(R.id.stats_top_artist)
    private val topArtistPhoto: ImageView = root.findViewById(R.id.stats_top_artist_photo)
    private val topArtistName: TextView = root.findViewById(R.id.stats_top_artist_name)
    private val topArtistSub: TextView = root.findViewById(R.id.stats_top_artist_sub)
    private val peakCard: View = root.findViewById(R.id.stats_peak)
    private val peakText: TextView = root.findViewById(R.id.stats_peak_text)
    private val streakCard: View = root.findViewById(R.id.stats_streak)
    private val streakText: TextView = root.findViewById(R.id.stats_streak_text)
    val diveIn: View = root.findViewById(R.id.stats_dive_in)
    val openCard: View = root.findViewById(R.id.stats_open_card)
    val hero: View = root.findViewById(R.id.stats_recap_hero)

    private var shownArtUrl: String? = null
    private var shownPhotoUrl: String? = null
    private var shownPhotoArtist: String? = null
    private var todayPhotoArtist: String? = null
    private var todayPhotoUrl: String? = null

    /** Today's top artist (null: nothing heard yet), for the card's tap. */
    var todayTopArtistName: String? = null
        private set

    init {
        RoundPhoto.apply(topArtistPhoto)
        topSongArt.clipToOutline = true
        RoundPhoto.apply(todayArtistPhoto)
    }

    fun renderToday(s: DayStats) {
        todayDate.text = Days.label(s.day)
        todaySongs.text = s.songs.toString()
        todaySongsLabel.setText(if (s.songs == 1) R.string.stats_song_one else R.string.stats_songs)
        todayTime.text = StatsFormat.duration(s.timeMs)
        todayArtists.text = s.artists.toString()
        todayArtistsLabel.setText(if (s.artists == 1) R.string.stats_artist_one else R.string.stats_artists)
        // v4.9.2: top artist moved to its own full-width card (photo, name on one line)
        val artist = s.topArtist
        todayTopArtistName = artist?.name
        todayArtistCard.visibility = if (artist != null) View.VISIBLE else View.GONE
        if (artist != null) {
            todayTopArtist.text = artist.name
            todayTopArtistSub.text = res.getString(R.string.stats_artist_time, StatsFormat.duration(artist.ms))
            todayArtistCard.contentDescription = "${res.getString(R.string.stats_top_artist)}, ${artist.name}"
            if (todayPhotoArtist != artist.name) {
                todayPhotoArtist = artist.name
                todayPhotoUrl = null
                todayArtistPhoto.setImageDrawable(null)
            }
        }
        todaySongsBox.isClickable = s.songs > 0
        todayArtistsBox.isClickable = s.artists > 0
    }

    /**
     * The most recent day when it was quiet and a better recap is kept below it (null hides it):
     * "Friday, 23 Oct was a quiet one: 3m, 1 song."
     */
    fun renderQuietDay(s: DayStats?) {
        if (s == null) {
            quietLine.visibility = View.GONE
            return
        }
        val songs = if (s.songs == 1) "1 ${res.getString(R.string.stats_song_one)}"
        else "${s.songs} ${res.getString(R.string.stats_songs)}"
        quietLine.text = res.getString(
            R.string.stats_quiet_day_line, Days.label(s.day), StatsFormat.duration(s.timeMs), songs
        )
        quietLine.visibility = View.VISIBLE
    }

    /** [s] = the recap (null: no past day with listening yet). */
    fun renderRecap(s: DayStats?) {
        if (s == null) {
            recapDate.visibility = View.GONE
            recapEmpty.visibility = View.VISIBLE
            recapBox.visibility = View.GONE
            return
        }
        recapDate.visibility = View.VISIBLE
        recapDate.text = Days.label(s.day)
        recapEmpty.visibility = View.GONE
        recapBox.visibility = View.VISIBLE
        recapTime.text = StatsFormat.duration(s.timeMs)
        recapQuiet.visibility = if (s.isQuiet) View.VISIBLE else View.GONE
        recapSongs.text = s.songs.toString()
        recapSongsLabel.setText(if (s.songs == 1) R.string.stats_song_one else R.string.stats_songs)
        recapArtists.text = s.artists.toString()
        recapArtistsLabel.setText(if (s.artists == 1) R.string.stats_artist_one else R.string.stats_artists)

        val song = s.topSong
        topSongCard.visibility = if (song != null) View.VISIBLE else View.GONE
        if (song != null) {
            topSongTitle.text = song.title
            topSongSub.text = res.getString(R.string.stats_plays_by, playsText(song.plays), song.artist)
            if (shownArtUrl != song.thumbnailUrl) {
                shownArtUrl = song.thumbnailUrl
                artwork.loadInto(topSongArt, song.thumbnailUrl, dp(56))
            }
        }

        val artist = s.topArtist
        topArtistCard.visibility = if (artist != null) View.VISIBLE else View.GONE
        if (artist != null) {
            topArtistName.text = artist.name
            topArtistSub.text = res.getString(R.string.stats_artist_time, StatsFormat.duration(artist.ms))
            if (shownPhotoArtist != artist.name) {
                shownPhotoArtist = artist.name
                shownPhotoUrl = null
                topArtistPhoto.setImageDrawable(null)
            }
        }

        peakCard.visibility = if (s.peakHour >= 0) View.VISIBLE else View.GONE
        if (s.peakHour >= 0) peakText.text = res.getString(R.string.stats_peak_line, StatsFormat.hour(s.peakHour))

        val streak = s.streak
        streakCard.visibility = if (streak != null) View.VISIBLE else View.GONE
        if (streak != null) streakText.text = res.getString(R.string.stats_streak_line, streak.count, streak.artist)
    }

    /** v4.9.2: days in a row with 10+ minutes ("0", "1", "4" over "day streak"). */
    fun renderStreak(days: Int) {
        todayStreak.text = days.coerceAtLeast(0).toString()
    }

    /** Today's top artist photo, once it's found. */
    fun showTodayArtistPhoto(artistName: String, url: String) {
        if (artistName != todayPhotoArtist || url == todayPhotoUrl || url.isBlank()) return
        todayPhotoUrl = url
        artwork.loadInto(todayArtistPhoto, url, dp(48))
    }

    /** The top artist's photo, once it's found (like the Artist page). */
    fun showArtistPhoto(artistName: String, url: String) {
        if (artistName != shownPhotoArtist || url == shownPhotoUrl || url.isBlank()) return
        shownPhotoUrl = url
        artwork.loadInto(topArtistPhoto, url, dp(56))
    }

    fun playsText(n: Int): String =
        if (n == 1) res.getString(R.string.stats_play_one) else res.getString(R.string.stats_plays, n)

    private fun dp(v: Int): Int = (v * res.displayMetrics.density).toInt()
}
