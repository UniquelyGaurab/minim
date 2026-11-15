package com.minim.music.ui

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream

/**
 * 6.7.0: Save to gallery for lyrics cards. Android 10+ writes through MediaStore (Pictures/Minim
 * for images, Movies/Minim for videos) and needs no permission; Android 9 uses the storage
 * permission Minim already asks for when saving a recap card.
 */
object LyricsCardGallery {

    suspend fun saveImage(context: Context, bmp: Bitmap): Boolean =
        save(context, "minim-lyrics-${stamp()}.png", "image/png", video = false) { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }

    suspend fun saveVideo(context: Context, file: File): Boolean =
        save(context, "minim-lyrics-${stamp()}.mp4", "video/mp4", video = true) { out ->
            file.inputStream().use { it.copyTo(out) }; true
        }

    private fun stamp() = System.currentTimeMillis() / 1000

    private suspend fun save(
        context: Context,
        name: String,
        mime: String,
        video: Boolean,
        write: (OutputStream) -> Boolean
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val resolver = context.contentResolver
                val collection: Uri = if (video) MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                    put(MediaStore.MediaColumns.MIME_TYPE, mime)
                    put(
                        MediaStore.MediaColumns.RELATIVE_PATH,
                        (if (video) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_PICTURES) + "/Minim"
                    )
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                val uri = resolver.insert(collection, values) ?: return@withContext false
                val ok = try {
                    resolver.openOutputStream(uri)?.use(write) ?: false
                } catch (_: Exception) {
                    false
                }
                if (!ok) {
                    resolver.delete(uri, null, null)
                    return@withContext false
                }
                values.clear()
                values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
                true
            } else {
                @Suppress("DEPRECATION")
                val dir = File(
                    Environment.getExternalStoragePublicDirectory(
                        if (video) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_PICTURES
                    ),
                    "Minim"
                )
                if (!dir.exists() && !dir.mkdirs()) return@withContext false
                val file = File(dir, name)
                val ok = FileOutputStream(file).use(write)
                if (ok) MediaScannerConnection.scanFile(context, arrayOf(file.absolutePath), arrayOf(mime), null)
                ok
            }
        } catch (_: Exception) {
            false
        }
    }
}
