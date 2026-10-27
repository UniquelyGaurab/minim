package com.minim.music.playback

import android.net.Uri
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import com.minim.music.data.model.Track
import com.minim.music.data.source.LrcLibLyricsAdapter
import com.minim.music.data.source.RawLyrics
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

/** 5.4.0: a song saved offline plays from the phone, and its lyrics show with no signal. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
class OfflinePlaybackTest {

    /** No signal: any network call fails, and we count them. */
    private val netCalls = AtomicInteger(0)
    private val noSignal = OkHttpClient.Builder().addInterceptor(Interceptor {
        netCalls.incrementAndGet()
        throw IOException("no signal")
    }).build()

    @Test
    fun savedSongPlaysFromTheFileWithoutAskingYouTube() {
        val bytes = ByteArray(5000) { (it % 251).toByte() }
        val file = File.createTempFile("saved", ".webm").apply { writeBytes(bytes) }
        val resolverCalls = AtomicInteger(0)
        val source = YoutubeChunkedDataSource(
            noSignal,
            { _, _, _, _ -> resolverCalls.incrementAndGet(); throw IOException("no signal") },
            DefaultHttpDataSource.Factory(),
            localFile = { id -> if (id == "abc123") file else null }
        )
        val length = source.open(DataSpec(Uri.parse("minim://track/abc123")))
        assertEquals(5000L, length)
        val out = ByteArrayOutputStream()
        val buf = ByteArray(1024)
        while (true) {
            val n = source.read(buf, 0, buf.size)
            if (n < 0) break
            out.write(buf, 0, n)
        }
        source.close()
        assertArrayEquals(bytes, out.toByteArray())
        assertEquals(0, resolverCalls.get())
        assertEquals(0, netCalls.get())
    }

    @Test
    fun seekingInASavedSongWorks() {
        val bytes = ByteArray(5000) { (it % 97).toByte() }
        val file = File.createTempFile("saved", ".webm").apply { writeBytes(bytes) }
        val source = YoutubeChunkedDataSource(
            noSignal, { _, _, _, _ -> throw IOException("no signal") }, DefaultHttpDataSource.Factory(),
            localFile = { file }
        )
        val length = source.open(DataSpec.Builder().setUri(Uri.parse("minim://track/abc123")).setPosition(4000).build())
        assertEquals(1000L, length)
        val buf = ByteArray(10)
        source.read(buf, 0, 10)
        assertArrayEquals(bytes.copyOfRange(4000, 4010), buf)
        source.close()
    }

    @Test
    fun songNotSavedStillStreams() {
        val resolverCalls = AtomicInteger(0)
        val source = YoutubeChunkedDataSource(
            noSignal,
            { _, _, _, _ -> resolverCalls.incrementAndGet(); throw IOException("no signal") },
            DefaultHttpDataSource.Factory(),
            localFile = { null }
        )
        try {
            source.open(DataSpec(Uri.parse("minim://track/other")))
        } catch (_: IOException) {
            // expected: no signal here
        }
        assertEquals(1, resolverCalls.get())
    }

    @Test
    fun savedLyricsShowWithNoSignal() = runBlocking {
        val adapter = LrcLibLyricsAdapter(noSignal)
        adapter.offlineLyrics = { id ->
            if (id == "abc123") RawLyrics(true, "[00:01.00]First line\n[00:04.50]Second line") else null
        }
        val track = Track("abc123", "Song", "Artist", 200, "", "")
        val lyrics = adapter.getLyrics(track)!!
        assertTrue(lyrics.isSynced)
        assertEquals(listOf("First line", "Second line"), lyrics.lines.map { it.text })
        assertEquals(1000L, lyrics.lines.first().startMs)
        assertEquals(0, netCalls.get())
    }
}
