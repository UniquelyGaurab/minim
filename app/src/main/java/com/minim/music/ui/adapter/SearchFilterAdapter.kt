package com.minim.music.ui.adapter

import android.annotation.SuppressLint
import android.content.res.Resources
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.minim.music.R
import com.minim.music.data.model.Album
import com.minim.music.data.model.ArtistRef
import com.minim.music.data.model.Track
import com.minim.music.ui.Painter
import com.minim.music.ui.Pal
import com.minim.music.ui.PressScale
import com.minim.music.ui.RoundPhoto
import com.minim.music.ui.SearchPill
import com.minim.music.util.ArtworkPipeline

/**
 * 6.8.0 b72: one search filter pill's results (Songs, Artists, Albums or Tapes; All keeps
 * [SearchResultsAdapter]). Songs are the usual song rows, artists the round-photo pills,
 * albums square covers, tapes wide 16:9 thumbnails.
 */
class SearchFilterAdapter(
    private val artwork: ArtworkPipeline,
    private val onSong: (Track, List<Track>) -> Unit,
    private val onSongLong: (View, Track) -> Unit,
    private val onArtist: (ArtistRef) -> Unit,
    private val onAlbum: (Album) -> Unit,
    private val onTape: (Track, List<Track>) -> Unit,
    private val onTapeLong: (View, Track) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private var pal: Pal = Pal.build(0, false)
    private var activeId: String? = null
    var pill: SearchPill = SearchPill.SONGS
        private set
    private var items: List<Any> = emptyList()

    /** The ids (or urls) on screen, so the same results again don't replay the entrance. */
    val shownKeys: List<String> get() = items.map { keyOf(it) }

    @SuppressLint("NotifyDataSetChanged")
    fun submit(pill: SearchPill, items: List<Any>) {
        this.pill = pill
        this.items = items.filter { accepts(pill, it) }
        notifyDataSetChanged()
    }

    @SuppressLint("NotifyDataSetChanged")
    fun setActiveTrack(id: String?) {
        if (id == activeId) return
        activeId = id
        if (pill == SearchPill.SONGS || pill == SearchPill.TAPES) notifyDataSetChanged()
    }

    @SuppressLint("NotifyDataSetChanged")
    fun setPalette(p: Pal) {
        pal = p
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = items.size

    override fun getItemViewType(position: Int): Int = when (pill) {
        SearchPill.ARTISTS -> TYPE_ARTIST
        SearchPill.ALBUMS -> TYPE_ALBUM
        SearchPill.TAPES -> TYPE_TAPE
        else -> TYPE_SONG
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inf = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_ARTIST -> ArtistHolder(inf.inflate(R.layout.item_artist_result, parent, false)).also { PressScale.apply(it.pill) }
            TYPE_ALBUM -> AlbumHolder(inf.inflate(R.layout.item_track, parent, false))
            TYPE_TAPE -> TapeHolder(inf.inflate(R.layout.item_search_tape, parent, false))
            else -> TrackAdapter.Holder(inf.inflate(R.layout.item_track, parent, false))
        }
    }

    @Suppress("UNCHECKED_CAST")
    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val item = items[position]
        when (holder) {
            is TrackAdapter.Holder -> {
                val t = item as Track
                val all = items as List<Track>
                holder.bindOffline(t.id, OfflineMarks.NONE)
                holder.bind(t, t.id == activeId, artwork, pal)
                holder.card.setOnClickListener { onSong(t, all) }
                holder.card.setOnLongClickListener { onSongLong(it, t); true }
            }
            is ArtistHolder -> {
                val a = item as ArtistRef
                holder.bind(a, artwork, pal)
                holder.pill.setOnClickListener { onArtist(a) }
            }
            is AlbumHolder -> {
                val a = item as Album
                holder.bind(a, artwork, pal)
                holder.card.setOnClickListener { onAlbum(a) }
            }
            is TapeHolder -> {
                val t = item as Track
                val all = items as List<Track>
                holder.bind(t, t.id == activeId, artwork, pal)
                holder.card.setOnClickListener { onTape(t, all) }
                holder.card.setOnLongClickListener { onTapeLong(it, t); true }
            }
        }
    }

    private class ArtistHolder(view: View) : RecyclerView.ViewHolder(view) {
        val pill: View = view.findViewById<View>(R.id.artist_pill).apply { clipToOutline = true }
        private val photo: ImageView = view.findViewById<ImageView>(R.id.artist_photo_small).also { RoundPhoto.apply(it) }
        private val name: TextView = view.findViewById(R.id.artist_result_name)
        private val subs: TextView = view.findViewById(R.id.artist_result_subs)

        fun bind(artist: ArtistRef, artwork: ArtworkPipeline, pal: Pal) {
            name.text = artist.name
            subs.text = formatArtistCount(itemView.resources, artist.count, artist.countKind)
            pill.contentDescription = "${artist.name}, ${subs.text}"
            artwork.loadInto(photo, artist.thumbnailUrl, dp(44))
            Painter.paint(itemView, pal)
        }
    }

    /** An album: the song row's shape with its square cover; opens in the playlist screen. */
    private class AlbumHolder(view: View) : RecyclerView.ViewHolder(view) {
        val card: View = view.findViewById<View>(R.id.track_card).apply { clipToOutline = true }
        private val art: ImageView = view.findViewById<ImageView>(R.id.track_art).apply { clipToOutline = true }
        private val title: TextView = view.findViewById(R.id.track_title)
        private val sub: TextView = view.findViewById(R.id.track_artist)

        init {
            view.findViewById<View>(R.id.track_offline_box).visibility = View.GONE
        }

        fun bind(album: Album, artwork: ArtworkPipeline, pal: Pal) {
            title.text = album.title
            val label = itemView.resources.getString(R.string.album_label_short)
            sub.text = listOf(label, album.subtitle).filter { it.isNotBlank() }.joinToString(" \u00B7 ")
            card.contentDescription = "${album.title}, ${sub.text}"
            artwork.loadInto(art, album.thumbnailUrl, dp(44))
            Painter.paint(itemView, pal)
        }
    }

    /** A tape: YouTube's own 16:9 thumbnail (only its black bars cut), title and channel. */
    private class TapeHolder(view: View) : RecyclerView.ViewHolder(view) {
        val card: View = view.findViewById<View>(R.id.tape_card).apply { clipToOutline = true }
        private val art: ImageView = view.findViewById<ImageView>(R.id.tape_art).apply { clipToOutline = true }
        private val title: TextView = view.findViewById(R.id.tape_title)
        private val meta: TextView = view.findViewById(R.id.tape_meta)

        fun bind(track: Track, active: Boolean, artwork: ArtworkPipeline, pal: Pal) {
            title.text = track.title
            meta.text = listOf(track.artist, tapeLength(track.durationSec)).filter { it.isNotBlank() }.joinToString(" \u00B7 ")
            card.tag = if (active) "fill:fg" else "fill:soft"
            art.tag = if (active) "fill:bg" else "fill:fg"
            val on = if (active) "text:bg" else "text:fg"
            title.tag = on
            meta.tag = on
            artwork.loadInto(art, track.thumbnailUrl, dp(112), wide = true)
            Painter.paint(itemView, pal)
        }
    }

    companion object {
        const val TYPE_SONG = 0
        const val TYPE_ARTIST = 1
        const val TYPE_ALBUM = 2
        const val TYPE_TAPE = 3

        fun accepts(pill: SearchPill, item: Any): Boolean = when (pill) {
            SearchPill.ARTISTS -> item is ArtistRef
            SearchPill.ALBUMS -> item is Album
            SearchPill.SONGS, SearchPill.TAPES -> item is Track
            SearchPill.ALL -> false
        }

        fun keyOf(item: Any): String = when (item) {
            is Track -> item.id
            is ArtistRef -> item.url
            is Album -> item.url
            else -> item.toString()
        }

        /** "4:05", or "1:02:30" for a long video; blank when YouTube gave no length. */
        fun tapeLength(sec: Long): String {
            if (sec <= 0L) return ""
            val h = sec / 3600
            val m = (sec % 3600) / 60
            val s = sec % 60
            return if (h > 0) String.format(java.util.Locale.US, "%d:%02d:%02d", h, m, s)
            else String.format(java.util.Locale.US, "%d:%02d", m, s)
        }

        private fun dp(v: Int): Int = (v * Resources.getSystem().displayMetrics.density).toInt()
    }
}
