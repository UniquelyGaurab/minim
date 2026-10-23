package com.minim.music.ui

import com.minim.music.data.model.Playlist
import com.minim.music.data.model.Track

/**
 * One tile in the Recents or Library grid (layout: item_grid_tile).
 *  - Song: Recents tile, cover + title + artist
 *  - Liked: "Liked Songs" tile in Library (cover of the newest liked song, if any)
 *  - PlaylistTile: one of your playlists (cover of its first song, if any)
 *  - NewPlaylist: outlined tile with a big "+" and "New Playlist"
 */
sealed class GridTile {
    data class Song(val track: Track) : GridTile()
    data class Liked(val count: Int, val coverUrl: String?) : GridTile()
    /** v4.9: "Recents" tile in Library, next to Liked Songs (the last 50 songs played). */
    data class RecentsTile(val count: Int) : GridTile()
    data class PlaylistTile(val playlist: Playlist, val coverUrl: String?) : GridTile()
    object NewPlaylist : GridTile()
    /** v4.6: an album on the Artist page's "Albums" grid. */
    data class AlbumTile(val album: com.minim.music.data.model.Album) : GridTile()
}
