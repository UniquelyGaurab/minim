package com.minim.music.ui

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * v4.9.1: Share (Android share sheet with the card image) and Save (to the gallery, in
 * Pictures/Minim). Android 10+ saves with no permission at all; Android 9 needs the old storage
 * permission, which MainActivity asks for first (declared only up to Android 9 in the manifest).
 */
object RecapShare {

    private fun fileName(day: Int) = "minim-recap-$day.png"

    /** Writes the card to the app's cache and returns a content:// link other apps can read. */
    suspend fun shareUri(context: Context, bmp: Bitmap, day: Int): Uri = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "recap").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val file = File(dir, fileName(day))
        FileOutputStream(file).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)
    }

    fun shareIntent(uri: Uri): Intent {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = android.content.ClipData.newRawUri("", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Intent.createChooser(send, null)
    }

    /** True when saving needs the storage permission first (Android 9 only). */
    fun needsPermission(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q

    /** True when Save must ask for the storage permission before it can write (Android 9, not granted yet). */
    fun needsPermission(context: Context): Boolean =
        needsPermission() && ContextCompat.checkSelfPermission(
            context, Manifest.permission.WRITE_EXTERNAL_STORAGE
        ) != PackageManager.PERMISSION_GRANTED

    /** Saves into Pictures/Minim. Returns true when it worked. */
    suspend fun save(context: Context, bmp: Bitmap, day: Int): Boolean = withContext(Dispatchers.IO) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val resolver = context.contentResolver
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, "minim-recap-$day-${System.currentTimeMillis() / 1000}.png")
                    put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                    put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Minim")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
                val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                    ?: return@withContext false
                val ok = resolver.openOutputStream(uri)?.use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) } ?: false
                if (!ok) {
                    resolver.delete(uri, null, null)
                    return@withContext false
                }
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
                true
            } else {
                @Suppress("DEPRECATION")
                val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "Minim")
                if (!dir.exists() && !dir.mkdirs()) return@withContext false
                val file = File(dir, "minim-recap-$day-${System.currentTimeMillis() / 1000}.png")
                FileOutputStream(file).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                MediaScannerConnection.scanFile(context, arrayOf(file.absolutePath), arrayOf("image/png"), null)
                true
            }
        } catch (_: Exception) {
            false
        }
    }
}
