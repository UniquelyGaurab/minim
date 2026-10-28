package com.minim.music.ui.adapter

import android.content.res.Resources
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import com.minim.music.R
import com.minim.music.data.model.ArtistCountKind
import com.minim.music.data.model.ArtistRef
import com.minim.music.data.model.Track
import com.minim.music.ui.GridTile
import com.minim.music.ui.Painter
import com.minim.music.ui.Pal
import com.minim.music.ui.QueueEdit
import com.minim.music.ui.RoundPhoto
import com.minim.music.util.ArtworkPipeline

/*
 * List code for the v4 screens. Every row is coloured by [Painter] from the android:tag roles in
 * its layout, using the current [Pal] (cover-art colours or the design greens), so nothing here
 * hardcodes a colour. The row of the song that is playing is drawn "filled" (dark card, light
 * text in Light mode; swapped in Dark mode), like the TOP RESULT card.
 */

private fun dp(v: Int): Int = (v * Resources.getSystem().displayMetrics.density).toInt()

private fun inflate(parent: ViewGroup, layout: Int): View =
    LayoutInflater.from(parent.context).inflate(layout, parent, false)

/** Default palette until MainActivity sends the real one (design greens, Light mode). */
private val DEFAULT_PAL = Pal.build(0, false)

/**
 * v4.9.6: song rows (search, Liked Songs, playlists, albums) look like the Stats "today's songs"
 * list: a soft-filled rounded row. The playing song is fully filled with the colours swapped,
 * like before, so it still stands out.
 */
private fun styleSongRow(card: View, art: ImageView, texts: List<TextView>, active: Boolean) {
    card.setBackgroundResource(R.drawable.shape_card)
    card.tag = if (active) "fill:fg" else "fill:soft"
    art.tag = if (active) "fill:bg" else "fill:fg"
    val on = if (active) "bg" else "fg"
    texts.forEach { it.tag = "text:$on" }
}

/** 5.4.0: songs saved offline, and songs saving now with how far along (0..1). */
data class OfflineMarks(val saved: Set<String>, val saving: Map<String, Float>) {
    companion object {
        val NONE = OfflineMarks(emptySet(), emptyMap())
    }
}

private const val PAYLOAD_OFFLINE = "offline"

// =============================================================================================
// Song cards (Liked Songs / a playlist)
// =============================================================================================

class TrackAdapter(
    private val artwork: ArtworkPipeline,
    private val onClick: (Track, List<Track>) -> Unit,
    private val onLongClick: (View, Track) -> Unit
) : RecyclerView.Adapter<TrackAdapter.Holder>() {

    private var tracks: List<Track> = emptyList()
    private var activeId: String? = null
    private var pal: Pal = DEFAULT_PAL
    private var offline: OfflineMarks = OfflineMarks.NONE

    fun submitList(tracks: List<Track>) {
        this.tracks = tracks.toList()
        notifyDataSetChanged()
    }

    /** 5.4.0: which songs are saved offline or saving; only the rows that changed redraw. */
    fun setOffline(marks: OfflineMarks) {
        val old = offline
        offline = marks
        tracks.forEachIndexed { i, t ->
            if (old.saved.contains(t.id) != marks.saved.contains(t.id) || old.saving[t.id] != marks.saving[t.id]) {
                notifyItemChanged(i, PAYLOAD_OFFLINE)
            }
        }
    }

    fun setActiveTrack(id: String?) {
        if (id == activeId) return
        activeId = id
        notifyDataSetChanged()
    }

    fun setPalette(p: Pal) {
        pal = p
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = tracks.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Holder(inflate(parent, R.layout.item_track))

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val track = tracks[position]
        holder.bind(track, track.id == activeId, artwork, pal)
        holder.bindOffline(track.id, offline)
        holder.card.setOnClickListener { onClick(track, tracks) }
        holder.card.setOnLongClickListener { onLongClick(it, track); true }
    }

    override fun onBindViewHolder(holder: Holder, position: Int, payloads: MutableList<Any>) {
        if (payloads.isNotEmpty() && payloads.all { it == PAYLOAD_OFFLINE }) {
            holder.bindOffline(tracks[position].id, offline)
            Painter.paint(holder.itemView, pal)
            return
        }
        onBindViewHolder(holder, position)
    }

    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        // clipToOutline keeps the press ripple inside the rounded row
        val card: View = view.findViewById<View>(R.id.track_card).apply { clipToOutline = true }
        private val art: ImageView = view.findViewById<ImageView>(R.id.track_art).apply { clipToOutline = true }
        private val title: TextView = view.findViewById(R.id.track_title)
        private val artist: TextView = view.findViewById(R.id.track_artist)
        private val offlineBox: View = view.findViewById(R.id.track_offline_box)
        private val offlineMark: ImageView = view.findViewById(R.id.track_offline)
        private val ring: com.minim.music.ui.ProgressRing = view.findViewById(R.id.track_ring)

        fun bindOffline(id: String, marks: OfflineMarks) {
            val saving = marks.saving[id]
            val saved = marks.saved.contains(id)
            offlineBox.visibility = if (saving != null || saved) View.VISIBLE else View.GONE
            ring.visibility = if (saving != null) View.VISIBLE else View.GONE
            offlineMark.visibility = if (saving == null && saved) View.VISIBLE else View.GONE
            if (saving != null) ring.setProgress(saving)
        }

        fun bind(track: Track, active: Boolean, artwork: ArtworkPipeline, pal: Pal) {
            title.text = track.title
            artist.text = track.artist
            styleSongRow(card, art, listOf(title, artist), active)
            val on = if (active) "bg" else "fg"
            offlineMark.tag = "tint:$on"
            ring.tag = "tint:$on"
            artwork.loadInto(art, track.thumbnailUrl, dp(44))
            Painter.paint(itemView, pal)
        }
    }
}

// =============================================================================================
// Search: TOP RESULT card, "Other results" label, outlined cards
// =============================================================================================

class SearchResultsAdapter(
    private val artwork: ArtworkPipeline,
    private val onClick: (Track, List<Track>) -> Unit,
    private val onLongClick: (View, Track) -> Unit,
    /** v4.6: an artist pill was tapped. */
    private val onArtistClick: (ArtistRef) -> Unit = {}
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private sealed class Row {
        object Top : Row()
        object Label : Row()
        data class Song(val index: Int) : Row()
        data class Artist(val artist: ArtistRef) : Row()
    }

    private var tracks: List<Track> = emptyList()
    private var artists: List<ArtistRef> = emptyList()
    private var artistsFirst = false
    private var rows: List<Row> = emptyList()
    private var activeId: String? = null
    private var pal: Pal = DEFAULT_PAL

    fun submit(tracks: List<Track>) {
        this.tracks = tracks.toList()
        rebuild()
    }

    /** v4.7: the artist pills on screen now (live search keeps the ones that still match). */
    val shownArtists: List<ArtistRef> get() = artists

    /** v4.9.9: the songs showing, to tell a new set of results from the same one again. */
    val shownTrackIds: List<String> get() = tracks.map { it.id }

    /**
     * v4.6: artist pills. [first] puts them above the TOP RESULT card (the search looks like an
     * artist's name); otherwise they sit right under it.
     */
    fun submitArtists(list: List<ArtistRef>, first: Boolean) {
        artists = list.toList()
        artistsFirst = first
        rebuild()
    }

    private fun rebuild() {
        val r = ArrayList<Row>()
        if (artistsFirst) artists.forEach { r.add(Row.Artist(it)) }
        if (tracks.isNotEmpty()) r.add(Row.Top)
        if (!artistsFirst) artists.forEach { r.add(Row.Artist(it)) }
        if (tracks.size > 1) {
            r.add(Row.Label)
            for (i in 1 until tracks.size) r.add(Row.Song(i))
        }
        rows = r
        notifyDataSetChanged()
    }

    fun setActiveTrack(id: String?) {
        if (id == activeId) return
        activeId = id
        notifyDataSetChanged()
    }

    fun setPalette(p: Pal) {
        pal = p
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = rows.size

    override fun getItemViewType(position: Int): Int = when (rows[position]) {
        Row.Top -> TYPE_TOP
        Row.Label -> TYPE_LABEL
        is Row.Song -> TYPE_TRACK
        is Row.Artist -> TYPE_ARTIST
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder = when (viewType) {
        TYPE_TOP -> TopHolder(inflate(parent, R.layout.item_top_result))
        TYPE_LABEL -> LabelHolder(inflate(parent, R.layout.item_section_label))
        TYPE_ARTIST -> ArtistHolder(inflate(parent, R.layout.item_artist_result)).also {
            // v4.9.9: the artist pill shrinks a little when pressed, like every other pill
            com.minim.music.ui.PressScale.apply(it.pill)
        }
        else -> TrackAdapter.Holder(inflate(parent, R.layout.item_track))
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val row = rows[position]
        when (holder) {
            is TopHolder -> {
                val track = tracks[0]
                holder.bind(track, artwork, pal)
                holder.card.setOnClickListener { onClick(track, tracks) }
                holder.card.setOnLongClickListener { onLongClick(it, track); true }
            }
            is LabelHolder -> holder.bind(pal)
            is ArtistHolder -> {
                val artist = (row as Row.Artist).artist
                holder.bind(artist, artwork, pal)
                holder.pill.setOnClickListener { onArtistClick(artist) }
            }
            is TrackAdapter.Holder -> {
                val track = tracks[(row as Row.Song).index]
                holder.bind(track, track.id == activeId, artwork, pal)
                holder.card.setOnClickListener { onClick(track, tracks) }
                holder.card.setOnLongClickListener { onLongClick(it, track); true }
            }
        }
    }

    private class TopHolder(view: View) : RecyclerView.ViewHolder(view) {
        val card: View = view.findViewById(R.id.top_card)
        private val art: ImageView = view.findViewById<ImageView>(R.id.top_art).apply { clipToOutline = true }
        private val title: TextView = view.findViewById(R.id.top_title)
        private val artist: TextView = view.findViewById(R.id.top_artist)

        fun bind(track: Track, artwork: ArtworkPipeline, pal: Pal) {
            title.text = track.title
            artist.text = track.artist
            artwork.loadInto(art, track.thumbnailUrl, dp(66))
            Painter.paint(itemView, pal)
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

    private class LabelHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val label: TextView = view.findViewById(R.id.section_label)

        fun bind(pal: Pal) {
            label.setText(R.string.other_results)
            Painter.paint(itemView, pal)
        }
    }

    private companion object {
        const val TYPE_TOP = 0
        const val TYPE_LABEL = 1
        const val TYPE_TRACK = 2
        const val TYPE_ARTIST = 3
    }
}

/**
 * v4.6.1: the line under an artist's name, labelled by what the number really is:
 * "625M monthly listeners" or "155K subscribers". Just "Artist" when YouTube gives no number
 * or the label couldn't be read.
 */
fun formatArtistCount(res: Resources, count: Long, kind: ArtistCountKind): String {
    if (count < 0) return res.getString(R.string.artist_label)
    return when (kind) {
        ArtistCountKind.MONTHLY_LISTENERS ->
            if (count == 1L) res.getString(R.string.artist_listener_one)
            else res.getString(R.string.artist_listeners, compactCount(count))
        ArtistCountKind.SUBSCRIBERS ->
            if (count == 1L) res.getString(R.string.artist_subscriber_one)
            else res.getString(R.string.artist_subscribers, compactCount(count))
        ArtistCountKind.UNKNOWN -> res.getString(R.string.artist_label)
    }
}

fun compactCount(n: Long): String {
    fun fmt(v: Double, suffix: String): String {
        val s = if (v < 10.0) String.format(java.util.Locale.US, "%.1f", Math.floor(v * 10) / 10)
        else Math.floor(v).toLong().toString()
        return s.removeSuffix(".0") + suffix
    }
    return when {
        n >= 1_000_000_000L -> fmt(n / 1_000_000_000.0, "B")
        n >= 1_000_000L -> fmt(n / 1_000_000.0, "M")
        n >= 1_000L -> fmt(n / 1_000.0, "K")
        else -> n.toString()
    }
}

// =============================================================================================
// Recents / Library grid tiles
// =============================================================================================

class GridTileAdapter(
    private val artwork: ArtworkPipeline,
    private val onClick: (GridTile) -> Unit,
    private val onLongClick: (View, GridTile) -> Unit
) : RecyclerView.Adapter<GridTileAdapter.Holder>() {

    private var tiles: List<GridTile> = emptyList()
    private var activeId: String? = null
    private var pal: Pal = DEFAULT_PAL
    private var saving: Map<String, Float> = emptyMap()

    fun submit(tiles: List<GridTile>) {
        this.tiles = tiles.toList()
        notifyDataSetChanged()
    }

    /**
     * 5.4.0: playlists saving offline, by tile key ("liked", "pl:<id>"), 0..1 done. Only the
     * tiles whose ring changed redraw.
     */
    fun setSaving(map: Map<String, Float>) {
        val old = saving
        saving = map
        tiles.forEachIndexed { i, t ->
            val k = savingKey(t) ?: return@forEachIndexed
            if (old[k] != map[k]) notifyItemChanged(i, PAYLOAD_OFFLINE)
        }
    }

    fun setActiveTrack(id: String?) {
        if (id == activeId) return
        activeId = id
        notifyDataSetChanged()
    }

    fun setPalette(p: Pal) {
        pal = p
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = tiles.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Holder(inflate(parent, R.layout.item_grid_tile))

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val tile = tiles[position]
        holder.bind(tile, activeId, artwork, pal)
        holder.bindRing(savingKey(tile)?.let { saving[it] }, pal)
        holder.box.setOnClickListener { onClick(tile) }
        holder.box.setOnLongClickListener { onLongClick(it, tile); true }
    }

    override fun onBindViewHolder(holder: Holder, position: Int, payloads: MutableList<Any>) {
        if (payloads.isNotEmpty() && payloads.all { it == PAYLOAD_OFFLINE }) {
            holder.bindRing(savingKey(tiles[position])?.let { saving[it] }, pal)
            return
        }
        onBindViewHolder(holder, position)
    }

    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        private val ringBox: View = view.findViewById(R.id.tile_ring_box)
        private val ring: com.minim.music.ui.ProgressRing = view.findViewById(R.id.tile_ring)
        private val fromRow: View = view.findViewById(R.id.tile_from_row)
        private val logo: ImageView = view.findViewById(R.id.tile_logo)
        private val from: TextView = view.findViewById(R.id.tile_from)

        /** 5.4.0: the corner ring while this playlist saves offline (null = not saving). */
        fun bindRing(f: Float?, pal: Pal) {
            ringBox.visibility = if (f != null) View.VISIBLE else View.GONE
            if (f != null) {
                ring.setProgress(f)
                Painter.paint(ringBox, pal)
            }
        }

        val box: View = view.findViewById(R.id.tile_box)
        private val art: ImageView = view.findViewById<ImageView>(R.id.tile_art).apply { clipToOutline = true }
        private val outline: View = view.findViewById(R.id.tile_outline)
        private val icon: ImageView = view.findViewById(R.id.tile_plus)
        private val title: TextView = view.findViewById(R.id.tile_title)
        private val subtitle: TextView = view.findViewById(R.id.tile_subtitle)

        fun bind(tile: GridTile, activeId: String?, artwork: ArtworkPipeline, pal: Pal) {
            val res = itemView.resources
            // Half the screen is plenty for a 2-column grid; the pipeline downsamples to this.
            val artPx = (res.displayMetrics.widthPixels / 2).coerceAtMost(dp(220))
            subtitle.visibility = View.GONE
            fromRow.visibility = View.GONE
            outline.visibility = View.GONE
            icon.visibility = View.GONE
            art.visibility = View.VISIBLE
            art.tag = "fill:fg"
            icon.tag = "tint:fg"

            when (tile) {
                is GridTile.Song -> {
                    title.text = tile.track.title
                    subtitle.text = tile.track.artist
                    subtitle.visibility = View.VISIBLE
                    // The song that is playing gets a ring around its cover.
                    if (tile.track.id == activeId) outline.visibility = View.VISIBLE
                    artwork.loadInto(art, tile.track.thumbnailUrl, artPx)
                    box.contentDescription = "${tile.track.title}, ${tile.track.artist}"
                }
                is GridTile.Liked -> {
                    // Design: a plain filled square with a heart, no cover.
                    title.setText(R.string.liked_songs)
                    artwork.loadInto(art, null, artPx)
                    icon.setImageResource(R.drawable.ic_heart_filled)
                    icon.tag = "tint:bg"
                    icon.visibility = View.VISIBLE
                    box.contentDescription = res.getString(R.string.liked_songs)
                }
                is GridTile.RecentsTile -> {
                    // v4.9: same filled square as Liked Songs, with a clock
                    title.setText(R.string.recents)
                    artwork.loadInto(art, null, artPx)
                    icon.setImageResource(R.drawable.ic_history)
                    icon.tag = "tint:bg"
                    icon.visibility = View.VISIBLE
                    box.contentDescription = res.getString(R.string.recents)
                }
                is GridTile.OfflineTile -> {
                    // 5.4.0: same filled square, with the offline arrow
                    title.setText(R.string.offline_title)
                    artwork.loadInto(art, null, artPx)
                    icon.setImageResource(R.drawable.ic_offline)
                    icon.tag = "tint:bg"
                    icon.visibility = View.VISIBLE
                    box.contentDescription = res.getString(R.string.offline_title)
                }
                is GridTile.PlaylistTile -> {
                    title.text = tile.playlist.name
                    box.contentDescription = tile.playlist.name
                    // 5.3.0: an imported playlist says where it came from under its name
                    // 5.4.0: "from" + the full logo (Spotify, YouTube Music, YouTube)
                    com.minim.music.data.importer.ImportOrigin.fromCode(tile.playlist.source)?.let {
                        val mark = com.minim.music.ui.SourceLogos.wordmark(it)
                        if (mark != null) {
                            com.minim.music.ui.SourceLogos.show(logo, mark, from)
                            fromRow.visibility = View.VISIBLE
                            box.contentDescription = res.getString(
                                R.string.tile_from_desc, tile.playlist.name, com.minim.music.ui.SourceLogos.name(it)
                            )
                        } else {
                            subtitle.text = it.label
                            subtitle.visibility = View.VISIBLE
                        }
                    }
                    // Filled square; shows the first song's cover when the playlist has songs.
                    artwork.loadInto(art, tile.coverUrl, artPx)
                }
                is GridTile.AlbumTile -> {
                    title.text = tile.album.title
                    subtitle.text = tile.album.subtitle
                    subtitle.visibility = View.VISIBLE
                    artwork.loadInto(art, tile.album.thumbnailUrl, artPx)
                    box.contentDescription = "${tile.album.title}, ${tile.album.subtitle}"
                }
                is GridTile.NewPlaylist -> {
                    // 5.3.0: the "+" now offers New Playlist and Import Playlist
                    title.setText(R.string.library_add_tile)
                    artwork.loadInto(art, null, artPx)
                    art.visibility = View.INVISIBLE // keeps the square size for the outline
                    outline.visibility = View.VISIBLE
                    icon.setImageResource(R.drawable.ic_add)
                    icon.visibility = View.VISIBLE
                    box.contentDescription = res.getString(R.string.library_add_tile)
                }
            }
            Painter.paint(itemView, pal)
        }
    }
}

/** 5.4.0: which tiles can show the saving ring. */
private fun savingKey(t: GridTile): String? = when (t) {
    is GridTile.Liked -> "liked"
    is GridTile.PlaylistTile -> "pl:${t.playlist.id}"
    // 5.4.0: everything saving right now, on the Offline tile
    is GridTile.OfflineTile -> "offline"
    else -> null
}

// =============================================================================================
// Queue
// =============================================================================================

class QueueAdapter(
    private val artwork: ArtworkPipeline,
    /** Tapped a row: its queue index. */
    private val onItemClick: (Int) -> Unit,
    /** 5.5.1: a row with song id was dragged from queue index (hint) to shown position. */
    private val onMove: (id: String, fromQueueIndex: Int, toShown: Int) -> Unit,
    /** 5.5.1: tapped a row's x: song id, queue index (hint). */
    private val onRemove: (id: String, queueIndex: Int) -> Unit,
    /** v4.8: long-press a queue row for the song menu (queue index). */
    private val onLongClick: (Int) -> Unit = {},
    private val haptic: (View, Int) -> Unit = { _, _ -> }
) : RecyclerView.Adapter<QueueAdapter.Holder>() {

    /** 5.5.1: a song row is being dragged by its handle. */
    var isDragging = false
        private set

    /** The whole player queue and the playing song's index in it. */
    private var queue: List<Track> = emptyList()
    private var activeIndex: Int = -1
    /** 5.5.1: what's on screen: the playing song (pinned first), then the songs after it. */
    private var shown: List<Track> = emptyList()
    private var pal: Pal = DEFAULT_PAL
    private var list: RecyclerView? = null
    private var paletteRefreshPending = false
    /** A queue that arrived mid-drag; it's applied once the row is dropped. */
    private var pending: Pair<List<Track>, Int>? = null
    private var dragFromShown = -1
    private var dragId: String? = null
    private var dragOffset = 0

    private val touchHelper = ItemTouchHelper(DragCallback())

    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        list = recyclerView
        touchHelper.attachToRecyclerView(recyclerView)
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        if (list === recyclerView) {
            list = null
            touchHelper.attachToRecyclerView(null)
        }
    }

    /**
     * v4.9.9: small changes (a song moved up or down, removed, a few added, the next song
     * starting) go through DiffUtil, so moved rows glide, removed rows fade out and the rest close
     * the gap. A basically new queue, or one that isn't on screen, just redraws.
     * 5.5.1: only the playing song and the songs after it are listed; mid-drag it waits.
     */
    fun submitQueue(queue: List<Track>, activeIndex: Int) {
        if (isDragging) {
            pending = queue.toList() to activeIndex
            return
        }
        if (queue == this.queue && activeIndex == this.activeIndex) return
        val old = shown
        val oldActive = if (this.activeIndex >= 0) 0 else -1
        val newShown = queue.drop(QueueEdit.offset(activeIndex))
        val newActive = if (activeIndex >= 0) 0 else -1
        this.queue = queue.toList()
        this.activeIndex = activeIndex
        this.shown = newShown
        val isShown = list?.isShown == true
        if (!isShown || com.minim.music.ui.QueueDiffRules.isWholesale(old.map { it.id }, newShown.map { it.id })) {
            redrawAll()
            return
        }
        DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize() = old.size
            override fun getNewListSize() = newShown.size
            override fun areItemsTheSame(o: Int, n: Int) = old[o].id == newShown[n].id
            override fun areContentsTheSame(o: Int, n: Int) = old[o] == newShown[n] &&
                com.minim.music.ui.QueueDiffRules.sameContents(
                    old[o].id, o, oldActive, old.size,
                    newShown[n].id, n, newActive, newShown.size
                )
        }, true).dispatchUpdatesTo(this)
    }

    fun setPalette(p: Pal) {
        pal = p
        // v4.9.9: a full redraw ends a move or removal mid glide, so it waits for them to finish.
        // (The rows on screen already have the new colours from the palette fade.)
        val animator = list?.itemAnimator
        if (!isDragging && (animator == null || !animator.isRunning)) {
            redrawAll()
            return
        }
        if (paletteRefreshPending) return
        paletteRefreshPending = true
        val again = {
            paletteRefreshPending = false
            list?.post { if (isDragging) setPalette(pal) else redrawAll() }
        }
        if (animator == null) again() else animator.isRunning { again() }
    }

    private fun redrawAll() = notifyDataSetChanged()

    /** 5.5.1: every row drawn again (a remove that didn't go through puts its row back). */
    fun redraw() = redrawAll()

    override fun getItemCount(): Int = shown.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Holder(inflate(parent, R.layout.item_queue_track))

    @android.annotation.SuppressLint("ClickableViewAccessibility")
    override fun onBindViewHolder(holder: Holder, position: Int) {
        val track = shown[position]
        val pinned = QueueEdit.isPinned(position, activeIndex)
        holder.bind(track, pinned, artwork, pal)
        holder.row.setOnClickListener { pos(holder)?.let { onItemClick(QueueEdit.toQueueIndex(it, activeIndex)) } }
        holder.row.setOnLongClickListener { pos(holder)?.let { onLongClick(QueueEdit.toQueueIndex(it, activeIndex)) }; true }
        holder.remove.setOnClickListener { v -> removeRow(holder, v) }
        // Touching the handle picks the row up straight away (no long-press wait)
        holder.handle.setOnTouchListener { _, ev ->
            if (ev.actionMasked == android.view.MotionEvent.ACTION_DOWN) {
                val p = pos(holder)
                if (p != null && QueueEdit.isMovable(p, activeIndex, shown.size)) touchHelper.startDrag(holder)
            }
            true
        }
        // Without the handle (TalkBack, switch access) a row can still move one step at a time
        ViewCompat.removeAccessibilityAction(holder.row, holder.a11yUp)
        ViewCompat.removeAccessibilityAction(holder.row, holder.a11yDown)
        holder.a11yUp = View.NO_ID
        holder.a11yDown = View.NO_ID
        if (!pinned) {
            val res = holder.row.resources
            holder.a11yUp = ViewCompat.addAccessibilityAction(holder.row, res.getString(R.string.move_up)) { _, _ ->
                stepRow(holder, -1)
            }
            holder.a11yDown = ViewCompat.addAccessibilityAction(holder.row, res.getString(R.string.move_down)) { _, _ ->
                stepRow(holder, 1)
            }
        }
    }

    override fun onViewRecycled(holder: Holder) {
        holder.itemView.animate().cancel()
        holder.itemView.alpha = 1f
        holder.itemView.translationX = 0f
        holder.itemView.translationY = 0f
        holder.itemView.scaleX = 1f
        holder.itemView.scaleY = 1f
        holder.row.translationZ = 0f
    }

    private fun stepRow(holder: Holder, by: Int): Boolean {
        val p = pos(holder) ?: return false
        val to = p + by
        if (!QueueEdit.isMovable(p, activeIndex, shown.size) || !QueueEdit.isMovable(to, activeIndex, shown.size)) return false
        onMove(shown[p].id, QueueEdit.toQueueIndex(p, activeIndex), to)
        return true
    }

    /** The row slides out a little as it fades, then the song leaves the queue and the gap closes. */
    private fun removeRow(holder: Holder, v: View) {
        val p = pos(holder) ?: return
        if (holder.removing || isDragging || !QueueEdit.isMovable(p, activeIndex, shown.size)) return
        holder.removing = true
        haptic(v, android.view.HapticFeedbackConstants.CONTEXT_CLICK)
        val id = shown[p].id
        val q = QueueEdit.toQueueIndex(p, activeIndex)
        val item = holder.itemView
        val commit = {
            holder.removing = false
            onRemove(id, q)
        }
        if (!android.animation.ValueAnimator.areAnimatorsEnabled()) {
            commit()
            return
        }
        item.animate().cancel()
        item.animate().alpha(0f).translationX(item.width * 0.12f)
            .setDuration(REMOVE_SLIDE_MS)
            .setInterpolator(android.view.animation.AccelerateInterpolator(1.4f))
            .withEndAction { commit() }
            .start()
    }

    private fun pos(holder: Holder): Int? =
        holder.bindingAdapterPosition.takeIf { it != RecyclerView.NO_POSITION }

    /**
     * 5.5.1: drag by the handle. The row lifts with a shadow and a light tick, the others slide
     * out of its way, and on drop it settles with a small bounce; the player's queue changes then.
     */
    private inner class DragCallback : ItemTouchHelper.Callback() {
        override fun isLongPressDragEnabled() = false
        override fun isItemViewSwipeEnabled() = false

        override fun getMovementFlags(rv: RecyclerView, vh: RecyclerView.ViewHolder): Int {
            val p = vh.bindingAdapterPosition
            return if (p != RecyclerView.NO_POSITION && QueueEdit.isMovable(p, activeIndex, shown.size))
                makeMovementFlags(ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0) else 0
        }

        override fun canDropOver(rv: RecyclerView, current: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder): Boolean {
            val p = target.bindingAdapterPosition
            return p != RecyclerView.NO_POSITION && QueueEdit.isMovable(p, activeIndex, shown.size)
        }

        override fun onMove(rv: RecyclerView, vh: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder): Boolean {
            val from = vh.bindingAdapterPosition
            val to = target.bindingAdapterPosition
            if (from == RecyclerView.NO_POSITION || to == RecyclerView.NO_POSITION) return false
            if (!QueueEdit.isMovable(from, activeIndex, shown.size) || !QueueEdit.isMovable(to, activeIndex, shown.size)) return false
            shown = QueueEdit.move(shown, from, to)
            notifyItemMoved(from, to)
            return true
        }

        override fun onSwiped(vh: RecyclerView.ViewHolder, direction: Int) = Unit

        override fun onSelectedChanged(vh: RecyclerView.ViewHolder?, actionState: Int) {
            super.onSelectedChanged(vh, actionState)
            if (actionState != ItemTouchHelper.ACTION_STATE_DRAG || vh !is Holder) return
            val p = vh.bindingAdapterPosition
            if (p == RecyclerView.NO_POSITION) return
            isDragging = true
            dragFromShown = p
            dragId = shown[p].id
            dragOffset = QueueEdit.offset(activeIndex)
            val d = vh.itemView.resources.displayMetrics.density
            vh.itemView.animate().cancel()
            vh.itemView.animate().scaleX(LIFT_SCALE).scaleY(LIFT_SCALE)
                .setDuration(LIFT_MS).setInterpolator(android.view.animation.DecelerateInterpolator()).start()
            vh.row.animate().translationZ(LIFT_DP * d).setDuration(LIFT_MS).start()
            haptic(vh.handle, android.view.HapticFeedbackConstants.CLOCK_TICK)
        }

        override fun clearView(rv: RecyclerView, vh: RecyclerView.ViewHolder) {
            super.clearView(rv, vh)
            val holder = vh as? Holder
            if (holder != null) {
                holder.itemView.animate().scaleX(1f).scaleY(1f)
                    .setDuration(SETTLE_MS).setInterpolator(android.view.animation.OvershootInterpolator(3f)).start()
                holder.row.animate().translationZ(0f).setDuration(SETTLE_MS).start()
            }
            if (!isDragging) return
            isDragging = false
            val id = dragId
            val from = dragFromShown
            val to = vh.bindingAdapterPosition
            dragId = null
            dragFromShown = -1
            // What's on screen is now the truth; the player follows right away
            queue = queue.take(dragOffset) + shown
            val waiting = pending
            pending = null
            if (id != null && to != RecyclerView.NO_POSITION && to != from) {
                holder?.let { haptic(it.handle, android.view.HapticFeedbackConstants.CLOCK_TICK) }
                onMove(id, dragOffset + from, to)
            } else if (waiting != null) {
                submitQueue(waiting.first, waiting.second)
            }
        }
    }

    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val row: View = view.findViewById(R.id.queue_row)
        private val art: ImageView = view.findViewById<ImageView>(R.id.queue_art).apply { clipToOutline = true }
        private val title: TextView = view.findViewById(R.id.queue_title)
        private val artist: TextView = view.findViewById(R.id.queue_artist)
        val remove: ImageView = view.findViewById(R.id.queue_remove)
        val handle: ImageView = view.findViewById(R.id.queue_handle)
        var removing = false
        var a11yUp = View.NO_ID
        var a11yDown = View.NO_ID

        fun bind(track: Track, pinned: Boolean, artwork: ArtworkPipeline, pal: Pal) {
            if (removing || itemView.translationX != 0f) {
                // A remove that didn't go through (the song was already gone): the row comes back
                itemView.animate().cancel()
                itemView.alpha = 1f
                itemView.translationX = 0f
            }
            removing = false
            title.text = track.title
            artist.text = track.artist
            // v4.9.7: the same filled row as search and playlists; the buttons follow the text colour
            styleSongRow(row, art, listOf(title, artist), pinned)
            // 5.5.1: the playing song is pinned: no x, no handle
            val tools = if (pinned) View.GONE else View.VISIBLE
            remove.visibility = tools
            handle.visibility = tools
            val on = if (pinned) "bg" else "fg"
            listOf(remove, handle).forEach { it.tag = "tint:$on" }
            artwork.loadInto(art, track.thumbnailUrl, dp(44))
            Painter.paint(itemView, pal)
        }
    }

    private companion object {
        const val LIFT_SCALE = 1.03f
        const val LIFT_DP = 8f
        const val LIFT_MS = 150L
        const val SETTLE_MS = 280L
        const val REMOVE_SLIDE_MS = 160L
    }
}

// =============================================================================================
// 5.3.0 import review: playlist song rows with a tick (saved) or a plus (left out). A doubtful
// match says why under its name ("Check: remix?"); a song that wasn't found says so.
// Tapping a doubtful or missing row opens its other options; tapping the mark ticks / unticks.
// =============================================================================================

class ImportReviewAdapter(
    private val artwork: ArtworkPipeline,
    private val onRow: (Int) -> Unit,
    private val onMark: (Int) -> Unit
) : RecyclerView.Adapter<ImportReviewAdapter.Holder>() {

    private var items: List<com.minim.music.data.importer.ReviewItem> = emptyList()
    private var pal: Pal = DEFAULT_PAL

    fun submit(list: List<com.minim.music.data.importer.ReviewItem>) {
        val old = items
        items = list
        if (old.size == list.size) {
            for (i in list.indices) if (old[i] != list[i]) notifyItemChanged(i)
        } else {
            notifyDataSetChanged()
        }
    }

    fun setPalette(p: Pal) {
        pal = p
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Holder(inflate(parent, R.layout.item_import_row))

    override fun onBindViewHolder(holder: Holder, position: Int) {
        holder.bind(items[position], artwork, pal)
        holder.card.setOnClickListener { holder.bindingAdapterPosition.takeIf { it >= 0 }?.let(onRow) }
        holder.mark.setOnClickListener { holder.bindingAdapterPosition.takeIf { it >= 0 }?.let(onMark) }
    }

    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val card: View = view.findViewById<View>(R.id.track_card).apply { clipToOutline = true }
        val mark: ImageView = view.findViewById(R.id.import_mark)
        private val art: ImageView = view.findViewById<ImageView>(R.id.track_art).apply { clipToOutline = true }
        private val title: TextView = view.findViewById(R.id.track_title)
        private val artist: TextView = view.findViewById(R.id.track_artist)

        fun bind(item: com.minim.music.data.importer.ReviewItem, artwork: ArtworkPipeline, pal: Pal) {
            val res = itemView.resources
            val chosen = item.chosen
            title.text = chosen?.title ?: item.source?.title.orEmpty()
            artist.text = when {
                chosen == null -> res.getString(R.string.import_not_found)
                item.confidence == com.minim.music.data.importer.Confidence.DOUBTFUL ->
                    res.getString(R.string.import_check, flagWords(res, item.flags)) + " · " + chosen.artist
                else -> chosen.artist
            }
            styleSongRow(card, art, listOf(title, artist), false)
            artwork.loadInto(art, chosen?.thumbnailUrl, dp(44))
            val on = item.included && chosen != null
            mark.setImageResource(if (on) R.drawable.ic_check else R.drawable.ic_add)
            mark.contentDescription = res.getString(if (on) R.string.import_mark_tick else R.string.import_mark_add)
            mark.visibility = if (chosen == null) View.INVISIBLE else View.VISIBLE
            Painter.paint(itemView, pal)
            // left-out songs fade back so the ticked ones read as the playlist
            card.alpha = if (on) 1f else 0.5f
        }

        private fun flagWords(res: android.content.res.Resources, flags: Set<com.minim.music.data.importer.MatchFlag>): String {
            val order = listOf(
                com.minim.music.data.importer.MatchFlag.REMIX to R.string.import_flag_remix,
                com.minim.music.data.importer.MatchFlag.COVER to R.string.import_flag_cover,
                com.minim.music.data.importer.MatchFlag.LIVE to R.string.import_flag_live,
                com.minim.music.data.importer.MatchFlag.VERSION to R.string.import_flag_version,
                com.minim.music.data.importer.MatchFlag.OTHER_ARTIST to R.string.import_flag_artist,
                com.minim.music.data.importer.MatchFlag.LENGTH to R.string.import_flag_length,
                com.minim.music.data.importer.MatchFlag.WEAK to R.string.import_flag_weak
            )
            return order.firstOrNull { it.first in flags }?.let { res.getString(it.second) }
                ?: res.getString(R.string.import_flag_weak)
        }
    }
}
