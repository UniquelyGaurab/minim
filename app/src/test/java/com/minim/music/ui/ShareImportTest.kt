package com.minim.music.ui

import android.content.Intent
import com.minim.music.data.importer.ImportDetect
import com.minim.music.data.importer.ImportInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 5.3.0: Share -> Minim hands the link on to the Import sheet. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class ShareImportTest {

    private fun send(text: String?, subject: String? = null) = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        if (text != null) putExtra(Intent.EXTRA_TEXT, text)
        if (subject != null) putExtra(Intent.EXTRA_SUBJECT, subject)
    }

    @Test fun spotifyShareTextBecomesAPlaylist() {
        val text = ShareImportActivity.shareText(
            send("Check out this playlist on Spotify https://open.spotify.com/playlist/37i9dQZF1DXcBWIGoYBM5M?si=abc")
        )!!
        val found = ImportDetect.detect(text)
        assertEquals(ImportInput.Spotify("playlist", "37i9dQZF1DXcBWIGoYBM5M"), found)
    }

    @Test fun linkInTheSubjectIsUsed() {
        val text = ShareImportActivity.shareText(send("My mix", "https://music.youtube.com/playlist?list=PLabc123"))!!
        assertTrue(ImportDetect.detect(text) is ImportInput.YouTube)
    }

    @Test fun notASendIsIgnored() {
        assertNull(ShareImportActivity.shareText(Intent(Intent.ACTION_VIEW)))
        assertNull(ShareImportActivity.shareText(send("")))
    }
}
