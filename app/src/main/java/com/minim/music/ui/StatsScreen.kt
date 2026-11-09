package com.minim.music.ui

import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.minim.music.R
import com.minim.music.data.stats.ChannelItem
import com.minim.music.data.stats.DayStats
import com.minim.music.data.stats.Days
import com.minim.music.data.stats.StatsFormat
import com.minim.music.data.stats.TapeStats
import com.minim.music.data.stats.TopSong
import com.minim.music.util.ArtworkPipeline

/**
 * v4.9: the Stats tab (replaces Recents). Today's live numbers on top, then the recap of the most
 * recent past day with listening. Only fills in views; MainActivity loads the numbers and paints
 * the colours (tags in view_stats.xml).
 *
 * 5.7.0 u51: one page for songs and tapes. On a day with tapes the boxes pair up as songs | tapes,
 * artists | channels, listened | day streak, with a top channel card under the top artist. On a
 * music-only day they go back to songs | listened, artists | day streak, as before.
 */
class StatsScreen(root: View, private val artwork: ArtworkPipeline) {
    private val res = root.resources

    private val todayDate: TextView = root.findViewById(R.id.stats_today_date)
    private val todaySongs: TextView = root.findViewById(R.id.stats_today_songs)
    private val todaySongsLabel: TextView = root.findViewById(R.id.stats_today_songs_label)
    private val todayTime: TextView = root.findViewById(R.id.stats_today_time)
    private val todaySaved: TextView = root.findViewById(R.id.stats_today_saved)
    private val todayArtists: TextView = root.findViewById(R.id.stats_today_artists)
    private val todayArtistsLabel: TextView = root.findViewById(R.id.stats_today_artists_label)
    private val todayTapes: TextView = root.findViewById(R.id.stats_today_tapes)
    private val todayTapesLabel: TextView = root.findViewById(R.id.stats_today_tapes_label)
    private val todayChannels: TextView = root.findViewById(R.id.stats_today_channels)
    private val todayChannelsLabel: TextView = root.findViewById(R.id.stats_today_channels_label)
    private val todayTopArtist: TextView = root.findViewById(R.id.stats_today_top_artist)
    private val todayTopArtistSub: TextView = root.findViewById(R.id.stats_today_top_artist_sub)
    private val todayArtistPhoto: ImageView = root.findViewById(R.id.stats_today_artist_photo)
    private val todayStreak: TextView = root.findViewById(R.id.stats_today_streak)
    private val todayTopChannel: TextView = root.findViewById(R.id.stats_today_top_channel)
    private val todayTopChannelSub: TextView = root.findViewById(R.id.stats_today_top_channel_sub)
    private val todayChannelPhoto: ImageView = root.findViewById(R.id.stats_today_channel_photo)
    private val todayChannelInitial: TextView = root.findViewById(R.id.stats_today_channel_initial)

    private val todayRow1: LinearLayout = root.findViewById(R.id.stats_today_row1)
    private val todayRow2: LinearLayout = root.findViewById(R.id.stats_today_row2)
    private val todayRow3: LinearLayout = root.findViewById(R.id.stats_today_row3)

    /** v4.9.2: today's tappable boxes (songs and artists sheets) and the top artist card. */
    val todaySongsBox: View = root.findViewById(R.id.stats_today_songs_box)
    val todayArtistsBox: View = root.findViewById(R.id.stats_today_artists_box)
    val todayArtistCard: View = root.findViewById(R.id.stats_today_artist_card)

    /** 5.7.0 u51: the boxes and card tapes add, and "listened" (songs and tapes time apart). */
    val todayTimeBox: View = root.findViewById(R.id.stats_today_time_box)
    val todayTapesBox: View = root.findViewById(R.id.stats_today_tapes_box)
    val todayChannelsBox: View = root.findViewById(R.id.stats_today_channels_box)
    val todayChannelCard: View = root.findViewById(R.id.stats_today_channel_card)
    private val todayStreakBox: View = root.findViewById(R.id.stats_today_streak_box)

    private val quietLine: TextView = root.findViewById(R.id.stats_quiet_line)
    private val recapDate: TextView = root.findViewById(R.id.stats_recap_date)
    private val recapEmpty: View = root.findViewById(R.id.stats_recap_empty)
    private val recapBox: View = root.findViewById(R.id.stats_recap_box)
    private val recapTime: TextView = root.findViewById(R.id.stats_recap_time)
    private val recapQuiet: TextView = root.findViewById(R.id.stats_recap_quiet)
    private val recapSplit: TextView = root.findViewById(R.id.stats_recap_split)
    private val recapSaved: TextView = root.findViewById(R.id.stats_recap_saved)
    private val recapSongs: TextView = root.findViewById(R.id.stats_recap_songs)
    private val recapSongsLabel: TextView = root.findViewById(R.id.stats_recap_songs_label)
    private val recapArtists: TextView = root.findViewById(R.id.stats_recap_artists)
    private val recapArtistsLabel: TextView = root.findViewById(R.id.stats_recap_artists_label)
    private val recapTapes: TextView = root.findViewById(R.id.stats_recap_tapes)
    private val recapTapesLabel: TextView = root.findViewById(R.id.stats_recap_tapes_label)
    private val recapChannels: TextView = root.findViewById(R.id.stats_recap_channels)
    private val recapChannelsLabel: TextView = root.findViewById(R.id.stats_recap_channels_label)
    private val recapRow2: View = root.findViewById(R.id.stats_recap_row2)

    /** 5.7.0 u51: the recap's boxes and cards are tappable too. */
    val recapSongsBox: View = root.findViewById(R.id.stats_recap_songs_box)
    val recapArtistsBox: View = root.findViewById(R.id.stats_recap_artists_box)
    val recapTapesBox: View = root.findViewById(R.id.stats_recap_tapes_box)
    val recapChannelsBox: View = root.findViewById(R.id.stats_recap_channels_box)

    // v4.9.9 part 3: the big numbers count up from zero when the tab opens
    private val todaySongsCount = CountingText(todaySongs, CountUpMath::countText)
    private val todayTimeCount = CountingText(todayTime, CountUpMath::durationText)
    private val todayArtistsCount = CountingText(todayArtists, CountUpMath::countText)
    private val todayStreakCount = CountingText(todayStreak, CountUpMath::countText)
    private val todayTapesCount = CountingText(todayTapes, CountUpMath::countText)
    private val todayChannelsCount = CountingText(todayChannels, CountUpMath::countText)
    private val recapTimeCount = CountingText(recapTime, CountUpMath::durationText)
    private val recapSongsCount = CountingText(recapSongs, CountUpMath::countText)
    private val recapArtistsCount = CountingText(recapArtists, CountUpMath::countText)
    private val recapTapesCount = CountingText(recapTapes, CountUpMath::countText)
    private val recapChannelsCount = CountingText(recapChannels, CountUpMath::countText)
    private val counters = listOf(
        todaySongsCount, todayTimeCount, todayArtistsCount, todayStreakCount,
        todayTapesCount, todayChannelsCount,
        recapTimeCount, recapSongsCount, recapArtistsCount, recapTapesCount, recapChannelsCount
    )

    /**
     * v4.9.9: the Stats tab just opened. Every big number counts up from zero to what it shows
     * (or to its first value, when the stats haven't loaded yet), once. The refresh that follows
     * and live updates after it change the numbers directly, or glide from a count still running.
     */
    fun countUp() = counters.forEach { it.countUp() }
    private val topSongCard: View = root.findViewById(R.id.stats_top_song)
    private val topSongArt: ImageView = root.findViewById(R.id.stats_top_song_art)
    private val topSongTitle: TextView = root.findViewById(R.id.stats_top_song_title)
    private val topSongSub: TextView = root.findViewById(R.id.stats_top_song_sub)
    private val topArtistCard: View = root.findViewById(R.id.stats_top_artist)
    private val topArtistPhoto: ImageView = root.findViewById(R.id.stats_top_artist_photo)
    private val topArtistName: TextView = root.findViewById(R.id.stats_top_artist_name)
    private val topArtistSub: TextView = root.findViewById(R.id.stats_top_artist_sub)
    private val topChannelPhoto: ImageView = root.findViewById(R.id.stats_top_channel_photo)
    private val topChannelInitial: TextView = root.findViewById(R.id.stats_top_channel_initial)
    private val topChannelName: TextView = root.findViewById(R.id.stats_top_channel_name)
    private val topChannelSub: TextView = root.findViewById(R.id.stats_top_channel_sub)
    private val peakCard: View = root.findViewById(R.id.stats_peak)
    private val peakText: TextView = root.findViewById(R.id.stats_peak_text)
    private val streakCard: View = root.findViewById(R.id.stats_streak)
    private val streakText: TextView = root.findViewById(R.id.stats_streak_text)
    val diveIn: View = root.findViewById(R.id.stats_dive_in)
    val openCard: View = root.findViewById(R.id.stats_open_card)
    val hero: View = root.findViewById(R.id.stats_recap_hero)

    /** 5.7.0 u51: the recap's top song, artist and channel cards (tap: play, Artist page, Channel page). */
    val recapTopSongCard: View get() = topSongCard
    val recapTopArtistCard: View get() = topArtistCard
    val recapTopChannelCard: View = root.findViewById(R.id.stats_top_channel)

    private var shownArtUrl: String? = null
    private var shownPhotoUrl: String? = null
    private var shownPhotoArtist: String? = null
    private var todayPhotoArtist: String? = null
    private var todayPhotoUrl: String? = null
    private var todayChannelKey: String? = null
    private var todayChannelUrl: String? = null
    private var recapChannelKey: String? = null
    private var recapChannelUrl: String? = null

    /** Today's top artist (null: nothing heard yet), for the card's tap. */
    var todayTopArtistName: String? = null
        private set

    /** 5.7.0 u51: today's and the recap's top channel, and the recap's top song and artist. */
    var todayTopChannelItem: ChannelItem? = null
        private set
    var recapTopChannelItem: ChannelItem? = null
        private set
    var recapTopSong: TopSong? = null
        private set
    var recapTopArtistName: String? = null
        private set

    /** Whether today's boxes are laid out with tapes (null: not laid out yet). */
    private var todayWithTapes: Boolean? = null

    init {
        RoundPhoto.apply(topArtistPhoto)
        topSongArt.clipToOutline = true
        RoundPhoto.apply(todayArtistPhoto)
        RoundPhoto.apply(todayChannelPhoto)
        RoundPhoto.apply(topChannelPhoto)
        todayStreakBox.isClickable = false
        todayStreakBox.isFocusable = false
        arrangeToday(false)
    }

    /**
     * Puts today's six boxes in their rows: with tapes, songs | tapes, artists | channels,
     * listened | day streak; without, songs | listened, artists | day streak (the third row hides).
     */
    private fun arrangeToday(withTapes: Boolean) {
        if (todayWithTapes == withTapes) return
        todayWithTapes = withTapes
        if (withTapes) {
            place(todayRow1, todaySongsBox, todayTapesBox)
            place(todayRow2, todayArtistsBox, todayChannelsBox)
            place(todayRow3, todayTimeBox, todayStreakBox)
        } else {
            place(todayRow3, todayTapesBox, todayChannelsBox)
            place(todayRow1, todaySongsBox, todayTimeBox)
            place(todayRow2, todayArtistsBox, todayStreakBox)
        }
        todayRow3.visibility = if (withTapes) View.VISIBLE else View.GONE
    }

    private fun place(row: LinearLayout, left: View, right: View) {
        if (row.childCount == 2 && row.getChildAt(0) === left && row.getChildAt(1) === right) return
        for (v in listOf(left, right)) (v.parent as? ViewGroup)?.removeView(v)
        row.removeAllViews()
        row.addView(left, boxParams(start = true))
        row.addView(right, boxParams(start = false))
    }

    private fun boxParams(start: Boolean) =
        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply {
            if (start) marginEnd = dp(5) else marginStart = dp(5)
        }

    fun renderToday(s: DayStats) {
        val t = s.tapes
        arrangeToday(t.shows)
        todayDate.text = Days.label(s.day)
        todaySongsCount.set(s.songs.toLong())
        todaySongsLabel.setText(if (s.songs == 1) R.string.stats_song_one else R.string.stats_songs)
        // 5.7.0 u51: listened is songs and tapes together
        todayTimeCount.set(s.totalMs)
        val saved = TapeStats.savedLine(t)
        todaySaved.text = saved.orEmpty()
        todaySaved.visibility = if (saved != null) View.VISIBLE else View.GONE
        todayArtistsCount.set(s.artists.toLong())
        todayArtistsLabel.setText(if (s.artists == 1) R.string.stats_artist_one else R.string.stats_artists)
        todayTapesCount.set(t.tapes.toLong())
        todayTapesLabel.setText(if (t.tapes == 1) R.string.stats_tape_one else R.string.stats_tapes)
        todayChannelsCount.set(t.channels.toLong())
        todayChannelsLabel.setText(if (t.channels == 1) R.string.stats_channel_one else R.string.stats_channels)
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
        // 5.7.0 u51: top channel, under the top artist
        val channel = t.topChannel
        todayTopChannelItem = channel
        todayChannelCard.visibility = if (channel != null) View.VISIBLE else View.GONE
        if (channel != null) {
            todayTopChannel.text = channel.name
            todayTopChannelSub.text = channelSub(channel)
            todayChannelCard.contentDescription = "${res.getString(R.string.stats_top_channel)}, ${channel.name}"
            if (todayChannelKey != channel.key) {
                todayChannelKey = channel.key
                todayChannelUrl = null
                todayChannelPhoto.setImageDrawable(null)
                todayChannelInitial.text = initialOf(channel.name)
                todayChannelInitial.visibility = View.VISIBLE
            }
        }
        todaySongsBox.isClickable = s.songs > 0
        todayArtistsBox.isClickable = s.artists > 0
        todayTapesBox.isClickable = t.tapes > 0
        todayChannelsBox.isClickable = t.channels > 0
        todayTimeBox.isClickable = s.totalMs > 0
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
        val parts = ArrayList<String>()
        if (s.songs > 0 || s.tapes.tapes == 0) {
            parts += if (s.songs == 1) "1 ${res.getString(R.string.stats_song_one)}"
            else "${s.songs} ${res.getString(R.string.stats_songs)}"
        }
        if (s.tapes.tapes > 0) {
            parts += if (s.tapes.tapes == 1) "1 ${res.getString(R.string.stats_tape_one)}"
            else "${s.tapes.tapes} ${res.getString(R.string.stats_tapes)}"
        }
        quietLine.text = res.getString(
            R.string.stats_quiet_day_line, Days.label(s.day), StatsFormat.duration(s.totalMs), parts.joinToString(", ")
        )
        quietLine.visibility = View.VISIBLE
    }

    /** [s] = the recap (null: no past day with listening yet). */
    fun renderRecap(s: DayStats?) {
        if (s == null) {
            recapDate.visibility = View.GONE
            recapEmpty.visibility = View.VISIBLE
            recapBox.visibility = View.GONE
            recapTopChannelItem = null
            recapTopSong = null
            recapTopArtistName = null
            return
        }
        val t = s.tapes
        recapDate.visibility = View.VISIBLE
        recapDate.text = Days.label(s.day)
        recapEmpty.visibility = View.GONE
        recapBox.visibility = View.VISIBLE
        recapTimeCount.set(s.totalMs)
        recapQuiet.visibility = if (s.isQuiet) View.VISIBLE else View.GONE
        // 5.7.0 u51: songs and tapes time apart, and the time speed saved
        if (t.shows && t.ms > 0L) {
            recapSplit.text = res.getString(
                R.string.stats_split_line, StatsFormat.duration(s.timeMs), StatsFormat.duration(t.ms)
            )
            recapSplit.visibility = View.VISIBLE
        } else {
            recapSplit.visibility = View.GONE
        }
        val saved = TapeStats.savedLine(t)
        recapSaved.text = saved.orEmpty()
        recapSaved.visibility = if (saved != null) View.VISIBLE else View.GONE
        recapSongsCount.set(s.songs.toLong())
        recapSongsLabel.setText(if (s.songs == 1) R.string.stats_song_one else R.string.stats_songs)
        recapArtistsCount.set(s.artists.toLong())
        recapArtistsLabel.setText(if (s.artists == 1) R.string.stats_artist_one else R.string.stats_artists)
        recapRow2.visibility = if (t.shows) View.VISIBLE else View.GONE
        recapTapesCount.set(t.tapes.toLong())
        recapTapesLabel.setText(if (t.tapes == 1) R.string.stats_tape_one else R.string.stats_tapes)
        recapChannelsCount.set(t.channels.toLong())
        recapChannelsLabel.setText(if (t.channels == 1) R.string.stats_channel_one else R.string.stats_channels)
        recapSongsBox.isClickable = s.songs > 0
        recapArtistsBox.isClickable = s.artists > 0
        recapTapesBox.isClickable = t.tapes > 0
        recapChannelsBox.isClickable = t.channels > 0

        val song = s.topSong
        recapTopSong = song
        topSongCard.visibility = if (song != null) View.VISIBLE else View.GONE
        if (song != null) {
            topSongTitle.text = song.title
            topSongSub.text = res.getString(R.string.stats_plays_by, playsText(song.plays), song.artist)
            topSongCard.contentDescription = "${res.getString(R.string.stats_top_song)}, ${song.title}, ${song.artist}"
            if (shownArtUrl != song.thumbnailUrl) {
                shownArtUrl = song.thumbnailUrl
                artwork.loadInto(topSongArt, song.thumbnailUrl, dp(56))
            }
        }

        val artist = s.topArtist
        recapTopArtistName = artist?.name
        topArtistCard.visibility = if (artist != null) View.VISIBLE else View.GONE
        if (artist != null) {
            topArtistName.text = artist.name
            topArtistSub.text = res.getString(R.string.stats_artist_time, StatsFormat.duration(artist.ms))
            topArtistCard.contentDescription = "${res.getString(R.string.stats_top_artist)}, ${artist.name}"
            if (shownPhotoArtist != artist.name) {
                shownPhotoArtist = artist.name
                shownPhotoUrl = null
                topArtistPhoto.setImageDrawable(null)
            }
        }

        val channel = t.topChannel
        recapTopChannelItem = channel
        recapTopChannelCard.visibility = if (channel != null) View.VISIBLE else View.GONE
        if (channel != null) {
            topChannelName.text = channel.name
            topChannelSub.text = channelSub(channel)
            recapTopChannelCard.contentDescription = "${res.getString(R.string.stats_top_channel)}, ${channel.name}"
            if (recapChannelKey != channel.key) {
                recapChannelKey = channel.key
                recapChannelUrl = null
                topChannelPhoto.setImageDrawable(null)
                topChannelInitial.text = initialOf(channel.name)
                topChannelInitial.visibility = View.VISIBLE
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
        todayStreakCount.set(days.coerceAtLeast(0).toLong())
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

    /** 5.7.0 u51: a top channel's photo (a followed channel's), over its first letter. */
    fun showChannelPhoto(channelKey: String, url: String) {
        if (url.isBlank()) return
        if (channelKey == todayChannelKey && url != todayChannelUrl) {
            todayChannelUrl = url
            todayChannelInitial.visibility = View.GONE
            artwork.loadInto(todayChannelPhoto, url, dp(48))
        }
        if (channelKey == recapChannelKey && url != recapChannelUrl) {
            recapChannelUrl = url
            topChannelInitial.visibility = View.GONE
            artwork.loadInto(topChannelPhoto, url, dp(56))
        }
    }

    /** "1h 10m listened · 3 tapes" (or just the time with one tape). */
    fun channelSub(c: ChannelItem): String {
        val time = StatsFormat.duration(c.ms)
        return if (c.tapes > 1) res.getString(R.string.stats_channel_time_tapes, time, tapesText(c.tapes))
        else res.getString(R.string.stats_channel_time, time)
    }

    fun tapesText(n: Int): String =
        if (n == 1) "1 ${res.getString(R.string.stats_tape_one)}" else "$n ${res.getString(R.string.stats_tapes)}"

    fun playsText(n: Int): String =
        if (n == 1) res.getString(R.string.stats_play_one) else res.getString(R.string.stats_plays, n)

    private fun initialOf(name: String): String =
        name.trim().firstOrNull { it.isLetterOrDigit() }?.uppercaseChar()?.toString() ?: ""

    private fun dp(v: Int): Int = (v * res.displayMetrics.density).toInt()
}
