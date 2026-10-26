package com.minim.music.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/**
 * 5.3.0: Minim as a share target. Spotify / YouTube Music -> Share -> Minim lands here; this hands
 * the shared text to MainActivity (bringing Minim's own window forward instead of starting a second
 * one inside the sharing app) and closes straight away. MainActivity opens the Import Playlist
 * sheet with the link filled in.
 */
class ShareImportActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val text = shareText(intent)
        if (!text.isNullOrBlank()) {
            startActivity(
                Intent(this, MainActivity::class.java)
                    .setAction(ACTION_IMPORT)
                    .putExtra(EXTRA_IMPORT_TEXT, text)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            )
        }
        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }

    companion object {
        const val ACTION_IMPORT = "com.minim.music.IMPORT_PLAYLIST"
        const val EXTRA_IMPORT_TEXT = "com.minim.music.extra.IMPORT_TEXT"

        /** The shared text, plus the subject when the link came in that instead. */
        fun shareText(intent: Intent?): String? {
            if (intent?.action != Intent.ACTION_SEND) return null
            val text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString().orEmpty()
            val subject = intent.getStringExtra(Intent.EXTRA_SUBJECT).orEmpty()
            return listOf(text, subject).firstOrNull { it.contains("http") || it.contains("spotify:") }
                ?: text.ifBlank { null }
        }
    }
}
