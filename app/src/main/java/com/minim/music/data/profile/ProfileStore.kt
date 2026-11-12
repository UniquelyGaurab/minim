package com.minim.music.data.profile

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import com.minim.music.data.local.AppPreferences
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream

/**
 * 5.10.1 (V6): the profile at the top of Settings, in place of the Google account. A name (the
 * same setting the greetings use, [AppPreferences.userName]) and an optional round photo, saved
 * in app-private storage as a small PNG. Neither ever leaves the phone: they are not Fermata
 * events, so folder backups and phone-to-phone merges never carry them.
 *
 * Everything here is plain file and bitmap work, so it runs off the main thread and in unit tests.
 */
class ProfileStore(filesDir: File, private val preferences: AppPreferences) {

    private val dir = File(filesDir, DIR)
    val photoFile: File = File(dir, PHOTO)

    /** The greeting name, trimmed (empty when not set). */
    var name: String
        get() = preferences.userName.trim()
        set(value) { preferences.userName = value }

    val hasPhoto: Boolean get() = photoFile.isFile && photoFile.length() > 0L

    /** Bumped on every save or remove, so screens know to reload the photo. */
    @Volatile var photoVersion: Long = 0L
        private set

    /** The saved round photo, or null when there is none (or it can't be read). */
    fun loadPhoto(): Bitmap? {
        if (!hasPhoto) return null
        return try { BitmapFactory.decodeFile(photoFile.path) } catch (_: Throwable) { null }
    }

    /**
     * Saves a photo: center-cropped to a square, scaled to [SIZE] px and cut to a circle, written
     * to a temporary file first and renamed over the old one, so a failure never leaves half a
     * photo. Returns false when it couldn't be saved (the old photo stays).
     */
    fun savePhoto(source: Bitmap): Boolean {
        return try {
            val round = roundCrop(source, SIZE)
            dir.mkdirs()
            val tmp = File(dir, "$PHOTO.tmp")
            FileOutputStream(tmp).use { out ->
                if (!round.compress(Bitmap.CompressFormat.PNG, 100, out)) throw IllegalStateException("compress")
                out.fd.sync()
            }
            if (round !== source) round.recycle()
            if (!tmp.renameTo(photoFile)) {
                tmp.delete()
                return false
            }
            photoVersion++
            true
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * Decodes an image stream (gallery or camera) at a size just above [SIZE], turned upright by
     * its EXIF [orientation], then saves it like [savePhoto]. [open] is called twice (bounds, then
     * pixels), so pass something that can be opened again (a content Uri, a file).
     */
    fun savePhoto(open: () -> InputStream?, orientation: Int = 1): Boolean {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        try { open()?.use { BitmapFactory.decodeStream(it, null, bounds) } } catch (_: Throwable) { return false }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return false
        val opts = BitmapFactory.Options().apply { inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, SIZE) }
        val decoded = try { open()?.use { BitmapFactory.decodeStream(it, null, opts) } } catch (_: Throwable) { null } ?: return false
        val upright = rotate(decoded, orientation)
        val ok = savePhoto(upright)
        if (upright !== decoded) upright.recycle()
        decoded.recycle()
        return ok
    }

    fun removePhoto() {
        photoFile.delete()
        File(dir, "$PHOTO.tmp").delete()
        photoVersion++
    }

    companion object {
        const val DIR = "profile"
        const val PHOTO = "photo.png"
        /** Side of the saved photo in px: sharp on the 72dp Settings avatar at xxxhdpi-ish sizes. */
        const val SIZE = 256

        /** The centred square of a [w] x [h] image. */
        fun centerSquare(w: Int, h: Int): Rect {
            val side = minOf(w, h)
            val left = (w - side) / 2
            val top = (h - side) / 2
            return Rect(left, top, left + side, top + side)
        }

        /** The largest power of two that keeps the short side at least [target] px. */
        fun sampleSize(w: Int, h: Int, target: Int): Int {
            var sample = 1
            val short = minOf(w, h)
            while (short / (sample * 2) >= target) sample *= 2
            return sample
        }

        /** The initial for the avatar when there's no photo: the name's first letter, or null. */
        fun initialOf(name: String?): String? {
            val t = name?.trim().orEmpty()
            if (t.isEmpty()) return null
            val cp = t.codePointAt(0)
            if (!Character.isLetterOrDigit(cp)) return null
            return String(Character.toChars(cp)).uppercase()
        }

        /** [src] center-cropped, scaled to [size] x [size] and cut to a circle (transparent corners). */
        fun roundCrop(src: Bitmap, size: Int): Bitmap {
            val sq = centerSquare(src.width, src.height)
            val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val c = Canvas(out)
            val shader = BitmapShader(src, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            val m = Matrix()
            m.setRectToRect(RectF(sq), RectF(0f, 0f, size.toFloat(), size.toFloat()), Matrix.ScaleToFit.FILL)
            shader.setLocalMatrix(m)
            val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply { this.shader = shader }
            c.drawCircle(size / 2f, size / 2f, size / 2f, p)
            return out
        }

        /** Turns a decoded photo upright by its EXIF orientation (1 = already upright). */
        fun rotate(src: Bitmap, orientation: Int): Bitmap {
            val m = Matrix()
            when (orientation) {
                2 -> m.setScale(-1f, 1f)
                3 -> m.setRotate(180f)
                4 -> m.setScale(1f, -1f)
                5 -> { m.setRotate(90f); m.postScale(-1f, 1f) }
                6 -> m.setRotate(90f)
                7 -> { m.setRotate(-90f); m.postScale(-1f, 1f) }
                8 -> m.setRotate(-90f)
                else -> return src
            }
            return Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
        }
    }
}
