package com.minim.music.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import com.minim.music.data.share.ShareLinks

/**
 * 6.6.0: a Minim link (https://uniquelygaurab.github.io/m/s#... or /m/p#...) tapped in WhatsApp or
 * anywhere else lands here through the App Link. Like ShareImportActivity, this screenless hop
 * brings Minim's own window forward (in Minim's own task, not the app the link was tapped in),
 * hands it the link, and closes. MainActivity opens the "Shared with you" pop-up: from onCreate on a
 * cold start, from onNewIntent when Minim is already running.
 */
class ShareLinkActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            val link = linkFrom(intent)
            if (link != null) {
                startActivity(
                    Intent(this, MainActivity::class.java)
                        .setAction(ACTION_OPEN_SHARED)
                        .putExtra(EXTRA_SHARED_LINK, link)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                )
            } else {
                // Not a share link (the bare site, say): just open Minim
                startActivity(
                    Intent(this, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                )
            }
        } catch (_: Exception) {
        }
        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }

    companion object {
        const val ACTION_OPEN_SHARED = "com.minim.music.OPEN_SHARED"
        const val EXTRA_SHARED_LINK = "com.minim.music.extra.SHARED_LINK"

        /**
         * The link to hand on, rebuilt from the intent's path and its raw fragment (the payload), or
         * null when the intent isn't a share link. Uri.getEncodedFragment keeps the payload exactly as
         * it was sent.
         */
        fun linkFrom(intent: Intent?): String? {
            if (intent?.action != Intent.ACTION_VIEW) return null
            val data: Uri = intent.data ?: return null
            return linkFrom(data.scheme, data.host, data.path, data.encodedFragment)
        }

        fun linkFrom(scheme: String?, host: String?, path: String?, fragment: String?): String? {
            if (scheme?.lowercase() != "https" && scheme?.lowercase() != "http") return null
            if (host?.lowercase() != ShareLinks.HOST) return null
            val parsed = ShareLinks.fromParts(path, fragment) ?: return null
            val p = if (parsed.kind == ShareLinks.Kind.SONG) ShareLinks.SONG_PATH else ShareLinks.PLAYLIST_PATH
            return ShareLinks.BASE + p + "#" + parsed.payload
        }
    }
}
