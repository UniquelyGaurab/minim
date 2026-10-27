package com.minim.music.playback

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import com.minim.music.data.source.ResolvedStream
import com.minim.music.data.source.YoutubeStreamRequest
import okhttp3.OkHttpClient
import java.io.IOException

/** Resolves a song id to a stream. Arguments: videoId, excluded itags, preferred itag, force fresh. */
fun interface StreamResolver {
    @Throws(IOException::class)
    fun resolve(videoId: String, excludedItags: Set<Int>, preferItag: Int?, forceFresh: Boolean): ResolvedStream
}

/**
 * YouTube refused a stream request (403 / 410 / 401) and a fresh link did not help.
 * PlaybackService catches it and restarts the song from the same spot with another format.
 */
@OptIn(UnstableApi::class)
class YoutubeStreamException(
    message: String,
    val videoId: String,
    val itag: Int,
    val responseCode: Int,
    val otherOptions: Int,
    cause: Throwable? = null
) : DataSourceException(message, cause, PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS)

/**
 * v4.9.5: plays `minim://track/{videoId}`. The stream is resolved here on ExoPlayer's loader
 * thread (as before), then read in NewPipe-style pieces by [ChunkedStreamReader].
 * Anything that is not a minim:// URI, or a stream that is not a YouTube videoplayback link
 * (HLS), goes through the plain OkHttp data source exactly like 4.9.4.
 */
@OptIn(UnstableApi::class)
class YoutubeChunkedDataSource(
    client: OkHttpClient,
    private val resolver: StreamResolver,
    private val plainFactory: DataSource.Factory,
    chunkBytes: Long = YoutubeStreamRequest.DEFAULT_CHUNK_BYTES,
    /** 5.4.0: the saved file of a song saved offline, or null. Played instead of streaming. */
    private val localFile: (String) -> java.io.File? = { null }
) : DataSource {

    class Factory(
        private val client: OkHttpClient,
        private val resolver: StreamResolver,
        private val plainFactory: DataSource.Factory,
        private val localFile: (String) -> java.io.File? = { null }
    ) : DataSource.Factory {
        override fun createDataSource(): DataSource =
            YoutubeChunkedDataSource(client, resolver, plainFactory, YoutubeStreamRequest.DEFAULT_CHUNK_BYTES, localFile)
    }

    private val reader = ChunkedStreamReader(client, resolver, chunkBytes)
    private val listeners = mutableListOf<TransferListener>()
    private var plain: DataSource? = null
    private var spec: DataSpec? = null
    private var opened = false

    override fun addTransferListener(transferListener: TransferListener) {
        if (transferListener !in listeners) listeners.add(transferListener)
    }

    @Throws(IOException::class)
    override fun open(dataSpec: DataSpec): Long {
        val uri = dataSpec.uri
        if (uri.scheme != "minim" || uri.host != "track") {
            return openPlain(dataSpec)
        }
        val videoId = uri.lastPathSegment?.takeIf { it.isNotBlank() }
            ?: throw IOException("Missing song id")

        // 5.4.0: saved offline: play the file on the phone (no signal or data needed)
        val saved = try { localFile(videoId) } catch (_: Exception) { null }
        if (saved != null) {
            val source = androidx.media3.datasource.FileDataSource()
            listeners.forEach { source.addTransferListener(it) }
            plain = source
            return source.open(dataSpec.buildUpon().setUri(Uri.fromFile(saved)).build())
        }

        val resolved: ResolvedStream =
            resolver.resolve(videoId, StreamRecovery.excludedItags(videoId), null, false)
        if (!resolved.chunked) {
            // HLS or another non-videoplayback link: one plain request, same as 4.9.4
            return openPlain(dataSpec.buildUpon().setUri(Uri.parse(resolved.url)).build())
        }

        spec = dataSpec
        listeners.forEach { it.onTransferInitializing(this, dataSpec, true) }
        val length = if (dataSpec.length == C.LENGTH_UNSET.toLong()) -1L else dataSpec.length
        val bytes = try {
            reader.open(resolved, dataSpec.position, length)
        } catch (e: ChunkedStreamReader.PositionOutOfRangeException) {
            throw DataSourceException(PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE)
        }
        opened = true
        listeners.forEach { it.onTransferStart(this, dataSpec, true) }
        return if (bytes >= 0) bytes else C.LENGTH_UNSET.toLong()
    }

    private fun openPlain(dataSpec: DataSpec): Long {
        val source = plainFactory.createDataSource()
        listeners.forEach { source.addTransferListener(it) }
        plain = source
        return source.open(dataSpec)
    }

    @Throws(IOException::class)
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        plain?.let { return it.read(buffer, offset, length) }
        val current = spec
        val n = reader.read(buffer, offset, length) { count ->
            if (current != null) listeners.forEach { it.onBytesTransferred(this, current, true, count) }
        }
        return if (n == -1) C.RESULT_END_OF_INPUT else n
    }

    override fun getUri(): Uri? = plain?.uri ?: spec?.uri

    override fun getResponseHeaders(): Map<String, List<String>> =
        plain?.responseHeaders ?: reader.headers

    @Throws(IOException::class)
    override fun close() {
        plain?.let {
            plain = null
            it.close()
            return
        }
        reader.close()
        val current = spec
        if (opened && current != null) {
            listeners.forEach { it.onTransferEnd(this, current, true) }
        }
        opened = false
        spec = null
    }
}
