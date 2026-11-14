package com.minim.music.ui

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * 6.6.0: the share card as a file for the share sheet, like RecapShare: one PNG in cache/share/
 * (the last card replaces the one before), handed out through Minim's FileProvider.
 */
object ShareCardFiles {
    private const val FILE = "minim-share.png"

    suspend fun write(context: Context, bmp: Bitmap): Uri = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "share").apply { mkdirs() }
        val file = File(dir, FILE)
        FileOutputStream(file).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)
    }

    /** The share sheet with the card, the line and the link (apps that take one or the other pick theirs). */
    fun intent(uri: Uri, text: String, subject: String, chooser: String): Intent {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_TEXT, text)
            putExtra(Intent.EXTRA_SUBJECT, subject)
            clipData = android.content.ClipData.newRawUri("", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Intent.createChooser(send, chooser).apply { addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) }
    }
}
