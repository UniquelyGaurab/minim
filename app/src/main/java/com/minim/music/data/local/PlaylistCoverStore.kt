package com.minim.music.data.local

import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import com.minim.music.ui.PlaylistLook
import java.io.File
import java.io.FileOutputStream

/**
 * 6.8.0: each playlist's chosen cover and sort, kept on this phone only (share links and
 * backups carry songs, not covers). Gallery photos are saved as a small square JPEG in the app's
 * own storage, so the picture still shows if it's deleted from the gallery.
 */
class PlaylistCoverStore(filesDir: File, private val prefs: SharedPreferences) {

    private val dir = File(filesDir, DIR)

    /** Bumped on every change, so the Library tiles know to redraw. */
    @Volatile var version: Long = 0L
        private set

    fun cover(playlistId: Long): PlaylistLook.Cover {
        val c = PlaylistLook.Cover.decode(prefs.getString(coverKey(playlistId), null))
        if (c.mode == PlaylistLook.Mode.PHOTO && !photoFile(c).isFile) return PlaylistLook.Cover.AUTO
        return c
    }

    fun setCover(playlistId: Long, cover: PlaylistLook.Cover) {
        val old = cover(playlistId)
        if (cover.mode == PlaylistLook.Mode.AUTO) prefs.edit().remove(coverKey(playlistId)).apply()
        else prefs.edit().putString(coverKey(playlistId), cover.encode()).apply()
        // a photo that's no longer used is deleted
        if (old.mode == PlaylistLook.Mode.PHOTO && old.file != cover.file) photoFile(old).delete()
        version++
    }

    fun sort(playlistId: Long): PlaylistLook.Sort = PlaylistLook.Sort.fromCode(prefs.getString(sortKey(playlistId), null))

    fun setSort(playlistId: Long, sort: PlaylistLook.Sort) {
        if (sort == PlaylistLook.Sort.CUSTOM) prefs.edit().remove(sortKey(playlistId)).apply()
        else prefs.edit().putString(sortKey(playlistId), sort.code).apply()
    }

    /** Forgets everything about a deleted playlist. */
    fun forget(playlistId: Long) {
        val old = cover(playlistId)
        if (old.mode == PlaylistLook.Mode.PHOTO) photoFile(old).delete()
        prefs.edit().remove(coverKey(playlistId)).remove(sortKey(playlistId)).apply()
        version++
    }

    fun photoFile(c: PlaylistLook.Cover): File = File(dir, c.file ?: "none")

    fun loadPhoto(c: PlaylistLook.Cover): Bitmap? {
        val f = photoFile(c)
        if (!f.isFile) return null
        return try { BitmapFactory.decodeFile(f.path) } catch (_: Throwable) { null }
    }

    /**
     * Saves the [crop] square of [source] as this playlist's photo ([SIZE] px JPEG, written to a
     * temporary file and renamed, so a failure never leaves half a picture). Returns the cover to
     * store, or null when it couldn't be saved.
     */
    fun savePhoto(playlistId: Long, source: Bitmap, crop: Rect): PlaylistLook.Cover? {
        return try {
            val out = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
            Canvas(out).drawBitmap(
                source, crop, RectF(0f, 0f, SIZE.toFloat(), SIZE.toFloat()),
                Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
            )
            dir.mkdirs()
            // a new name each time, so a tile that has the old picture in memory reloads
            val name = "p${playlistId}_${System.currentTimeMillis()}.jpg"
            val tmp = File(dir, "$name.tmp")
            FileOutputStream(tmp).use { o ->
                if (!out.compress(Bitmap.CompressFormat.JPEG, 90, o)) throw IllegalStateException("compress")
                o.fd.sync()
            }
            out.recycle()
            val dest = File(dir, name)
            if (!tmp.renameTo(dest)) { tmp.delete(); return null }
            PlaylistLook.Cover(mode = PlaylistLook.Mode.PHOTO, file = name)
        } catch (_: Throwable) {
            null
        }
    }

    companion object {
        const val DIR = "playlist_covers"
        const val PREFS = "minim_playlist_covers"
        /** Side of a saved photo: sharp on the 140dp header at high-density screens. */
        const val SIZE = 640

        private fun coverKey(id: Long) = "cover_$id"
        private fun sortKey(id: Long) = "sort_$id"
    }
}
