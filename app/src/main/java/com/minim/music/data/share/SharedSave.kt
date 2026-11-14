package com.minim.music.data.share

import com.minim.music.data.importer.ImportNames
import com.minim.music.data.local.LibraryDatabase
import com.minim.music.data.model.Track

/**
 * 6.6.0: what Play and Save do in the "Shared with you" pop-up. Only the normal like / playlist
 * events reach Fermata (through the library), nothing about who shared it.
 */
object SharedSave {

    /** A shared song as a playable track. The cover is the video's thumbnail; the loader finds the best size. */
    fun toTrack(song: ShareLinks.Song): Track = Track(
        id = song.id,
        title = song.title,
        artist = song.artist,
        durationSec = 0L,
        thumbnailUrl = coverUrl(song.id),
        sourceUrl = "https://www.youtube.com/watch?v=${song.id}"
    )

    fun coverUrl(id: String): String = "https://i.ytimg.com/vi/$id/hqdefault.jpg"

    /** A library track as it goes in a link. */
    fun toSong(track: Track): ShareLinks.Song = ShareLinks.Song(track.id, track.title, track.artist)

    /**
     * The shared songs as tracks, in order. A song already in your library keeps its own copy (its
     * real cover and length), so saving or playing a shared link never makes it look worse.
     */
    suspend fun resolve(db: LibraryDatabase, songs: List<ShareLinks.Song>): List<Track> {
        val known = try {
            db.getTracksByIds(songs.map { it.id }).associateBy { it.id }
        } catch (c: kotlinx.coroutines.CancellationException) {
            throw c
        } catch (_: Exception) {
            emptyMap()
        }
        return songs.map { known[it.id]?.takeIf { t -> t.thumbnailUrl.isNotBlank() } ?: toTrack(it) }
    }

    /** Save on a song: into Liked Songs. False when it was already there (nothing changes). */
    suspend fun saveSong(db: LibraryDatabase, song: ShareLinks.Song): Boolean {
        db.refreshLikedCache()
        if (db.likedIds.value.contains(song.id)) return false
        return db.toggleLike(resolve(db, listOf(song)).first())
    }

    /**
     * Save on a playlist: a new playlist in the library with the shared songs, in order. A name you
     * already have becomes "Name (2)", "Name (3)"... Returns the name it got, or null if it failed.
     */
    suspend fun savePlaylist(db: LibraryDatabase, name: String, songs: List<ShareLinks.Song>): Pair<Long, String>? {
        if (songs.isEmpty()) return null
        // "road trip" and "Road Trip" read as the same name, so they clash too
        val taken = try { db.getPlaylists().map { it.name.trim().lowercase() }.toHashSet() } catch (c: kotlinx.coroutines.CancellationException) { throw c } catch (_: Exception) { hashSetOf() }
        for (n in 1..200) {
            val candidate = ImportNames.candidate(name, n)
            if (candidate.lowercase() in taken) continue
            val id = db.createPlaylist(candidate)
            if (id != -1L) {
                db.addTracksToPlaylist(id, resolve(db, songs))
                return id to candidate
            }
        }
        return null
    }
}
