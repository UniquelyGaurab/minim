package com.minim.music.data.ai

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Build
import com.minim.music.data.greetings.MiniJson
import com.minim.music.data.offline.FetchSource
import com.minim.music.data.offline.OfflineStore
import com.minim.music.data.offline.ParallelFetcher
import com.minim.music.data.source.StreamOption
import com.minim.music.data.source.StreamSelection
import com.minim.music.data.source.YoutubeStreamRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.UnknownHostException
import java.nio.ByteBuffer
import java.util.Base64
import java.util.concurrent.TimeUnit

/** 5.8.1: the request ran out of time (no reply from Gemini in [GeminiAudioRequester.CALL_TIMEOUT_S]). */
class AiTimeoutException : IOException("Gemini took too long")

/** 5.8.1: Gemini refused to write it out (copyright "recitation" or safety). */
class AiRefusedException(val reason: String) : IOException("Gemini refused: $reason")

/**
 * AAC in an MP4 (.m4a) isn't one of the audio types Gemini lists, but raw AAC (ADTS, audio/aac)
 * is. Remuxing just puts a 7-byte header in front of each AAC frame: no re-encoding, no quality
 * change, and fast.
 */
object AacAdts {

    data class Config(val objectType: Int, val freqIndex: Int, val channels: Int)

    /**
     * Reads the AudioSpecificConfig (MediaExtractor's csd-0). For HE-AAC (SBR / PS signalled
     * explicitly) the ADTS header carries the core AAC-LC settings, which every decoder reads.
     */
    fun parseAsc(csd: ByteArray): Config? {
        if (csd.size < 2) return null
        val bits = BitReader(csd)
        var type = bits.read(5)
        if (type == 31) type = 32 + bits.read(6)
        var freq = bits.read(4)
        if (freq == 15) { bits.read(24); freq = -1 }
        val ch = bits.read(4)
        if (type == 5 || type == 29) {
            // explicit SBR: extension rate, then the core object type
            var ext = bits.read(4)
            if (ext == 15) { bits.read(24); ext = -1 }
            type = bits.read(5)
            if (type == 31) type = 32 + bits.read(6)
        }
        if (type !in 1..4 || freq !in 0..12 || ch !in 1..7) return null
        return Config(type, freq, ch)
    }

    fun freqIndexFor(sampleRate: Int): Int =
        intArrayOf(96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000, 7350)
            .indexOf(sampleRate)

    /** The 7-byte ADTS header (no CRC) for one frame of [payloadBytes]. */
    fun header(c: Config, payloadBytes: Int): ByteArray {
        val len = payloadBytes + 7
        val profile = c.objectType - 1
        return byteArrayOf(
            0xFF.toByte(),
            0xF1.toByte(), // MPEG-4, layer 0, no CRC
            ((profile shl 6) or (c.freqIndex shl 2) or (c.channels shr 2)).toByte(),
            (((c.channels and 3) shl 6) or (len shr 11)).toByte(),
            ((len shr 3) and 0xFF).toByte(),
            (((len and 7) shl 5) or 0x1F).toByte(),
            0xFC.toByte(),
        )
    }

    private class BitReader(private val b: ByteArray) {
        private var pos = 0
        fun read(n: Int): Int {
            var v = 0
            repeat(n) {
                val byte = if (pos / 8 < b.size) b[pos / 8].toInt() and 0xFF else 0
                v = (v shl 1) or ((byte shr (7 - pos % 8)) and 1)
                pos++
            }
            return v
        }
    }
}

/** Turns a downloaded / saved song file into a file Gemini reads. Android only (MediaExtractor). */
object AudioRemux {

    /** .m4a (AAC) -> .aac (ADTS). Returns false when the file has no AAC track. */
    fun m4aToAdts(input: File, out: File): Boolean {
        val ex = MediaExtractor()
        try {
            ex.setDataSource(input.absolutePath)
            val track = (0 until ex.trackCount).firstOrNull {
                ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME) == MediaFormat.MIMETYPE_AUDIO_AAC
            } ?: return false
            val fmt = ex.getTrackFormat(track)
            val cfg = fmt.getByteBuffer("csd-0")?.let { bb ->
                val a = ByteArray(bb.remaining()); bb.duplicate().get(a); AacAdts.parseAsc(a)
            } ?: AacAdts.Config(
                objectType = 2,
                freqIndex = AacAdts.freqIndexFor(fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)).takeIf { it >= 0 } ?: return false,
                channels = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceIn(1, 7),
            )
            ex.selectTrack(track)
            val buf = ByteBuffer.allocate(1 shl 16)
            val frame = ByteArray(1 shl 16)
            var frames = 0
            FileOutputStream(out).buffered(1 shl 16).use { os ->
                while (true) {
                    buf.clear()
                    val n = ex.readSampleData(buf, 0)
                    if (n < 0) break
                    if (n in 1..(0x1FFF - 7)) {
                        buf.get(frame, 0, n)
                        os.write(AacAdts.header(cfg, n))
                        os.write(frame, 0, n)
                        frames++
                    }
                    ex.advance()
                }
            }
            return frames > 0
        } finally {
            ex.release()
        }
    }

    /** .webm (Opus) -> .ogg (Opus), Android 10+ only (MediaMuxer can't write Ogg before that). */
    fun webmToOgg(input: File, out: File): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        val ex = MediaExtractor()
        var muxer: MediaMuxer? = null
        try {
            ex.setDataSource(input.absolutePath)
            val track = (0 until ex.trackCount).firstOrNull {
                ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME) == MediaFormat.MIMETYPE_AUDIO_OPUS
            } ?: return false
            ex.selectTrack(track)
            val m = MediaMuxer(out.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_OGG)
            muxer = m
            val dst = m.addTrack(ex.getTrackFormat(track))
            m.start()
            val buf = ByteBuffer.allocate(1 shl 16)
            val info = MediaCodec.BufferInfo()
            var frames = 0
            while (true) {
                buf.clear()
                val n = ex.readSampleData(buf, 0)
                if (n < 0) break
                info.set(0, n, ex.sampleTime, 0)
                m.writeSampleData(dst, buf, info)
                frames++
                ex.advance()
            }
            m.stop()
            return frames > 0
        } catch (_: Exception) {
            return false
        } finally {
            runCatching { muxer?.release() }
            ex.release()
        }
    }
}

/**
 * Where the audio comes from: the song saved offline when it's there (no download at all),
 * otherwise a fresh AAC link from YouTube, the smallest one that's still clear (Gemini only
 * needs to hear the words, and a smaller file is a faster upload).
 */
class PhoneSongAudio(
    private val workDir: File,
    private val offline: () -> OfflineStore?,
    private val options: (videoId: String, fresh: Boolean) -> List<StreamOption>,
    private val fetcher: ParallelFetcher,
) : SongAudioSource {

    override suspend fun prepare(song: AiLyricsSong): PreparedAudio = withContext(Dispatchers.IO) {
        workDir.mkdirs()
        val out = File(workDir, AiLyricsStore.safe(song.id) + ".aac")
        val saved = offline()?.let { s -> s.audioFor(song.id)?.let { it to (s.song(song.id)?.format ?: "") } }
        if (saved != null) {
            val (file, format) = saved
            if (format == "m4a" && AudioRemux.m4aToAdts(file, out)) return@withContext PreparedAudio(out, "audio/aac", true)
            val ogg = File(workDir, AiLyricsStore.safe(song.id) + ".ogg")
            if (format == "webm" && AudioRemux.webmToOgg(file, ogg)) return@withContext PreparedAudio(ogg, "audio/ogg", true)
            ogg.delete()
        }
        val part = File(workDir, AiLyricsStore.safe(song.id) + ".m4a.part")
        try {
            var pick = pickForGemini(options(song.id, false)) ?: pickForGemini(options(song.id, true))
                ?: throw IOException("no AAC stream")
            fetcher.fetch(FetchSource(pick.url, pick.contentLength), part, refresh = {
                val fresh = options(song.id, true)
                StreamSelection.originalOnly(fresh, pick.trackId).firstOrNull {
                    it.itag == pick.itag && it.trackId == pick.trackId &&
                        it.contentLength == pick.contentLength && it.url.isNotBlank()
                }
                    ?.let { pick = it; FetchSource(it.url, it.contentLength) }
            })
            currentCoroutineContext().ensureActive()
            if (!AudioRemux.m4aToAdts(part, out)) throw IOException("couldn't read the AAC track")
            PreparedAudio(out, "audio/aac", true)
        } catch (e: Exception) {
            out.delete()
            throw e
        } finally {
            part.delete()
        }
    }

    companion object {
        /**
         * AAC-LC 128 kbps (itag 140) first: the clearest one Gemini can take after the remux.
         * Then any other AAC-LC, then HE-AAC (itag 139). Never Opus (no remux before Android 10).
         */
        fun pickForGemini(options: List<StreamOption>): StreamOption? =
            // 5.8.5: the original audio track only, so the lyrics are the song's own words
            StreamSelection.originalOnly(options).filter {
                it.url.isNotBlank() && it.progressive && it.contentLength > 0 &&
                    (it.formatName.contains("M4A", true) || it.itag in setOf(139, 140, 141)) &&
                    YoutubeStreamRequest.isVideoPlaybackUrl(it.url)
            }.sortedWith(
                compareBy<StreamOption> { when (it.itag) { 140 -> 0; 139 -> 2; else -> 1 } }
                    .thenBy { it.contentLength }
            ).firstOrNull()
    }
}

/**
 * Sends the audio + the fixed prompt to Gemini with your own key. Small files go inline (the
 * base64 is streamed straight from the file, never held in memory as one string); bigger ones
 * go through the Files API and are deleted from your Google storage right after.
 */
class GeminiAudioRequester(
    baseClient: OkHttpClient,
    private val base: String = GeminiEndpoint.DIRECT_BASE,
    private val uploadBase: String = UPLOAD_BASE,
    private val pollWaitMs: Long = 2_000L,
) : AiLyricsRequester {

    private val http = baseClient.newBuilder()
        .cache(null)
        .connectTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .readTimeout(READ_TIMEOUT_S, TimeUnit.SECONDS)
        .callTimeout(CALL_TIMEOUT_S, TimeUnit.SECONDS)
        .build()

    override suspend fun transcribe(endpoint: GeminiEndpoint, audio: PreparedAudio, prompt: String): String =
        withContext(Dispatchers.IO) {
            val key = endpoint.headers["x-goog-api-key"] ?: throw GeminiException(GeminiProblem.BadKey)
            val model = endpoint.model
            val url = "$base/models/$model:generateContent"
            if (audio.file.length() <= INLINE_MAX_BYTES) {
                val body = InlineAudioBody(audio.file, audio.mimeType, prompt)
                readText(post(url, key, body, model))
            } else {
                val file = upload(key, audio, model)
                try {
                    val json = JsonOut.write(requestMap(mapOf("file_data" to mapOf("mime_type" to audio.mimeType, "file_uri" to file.uri)), prompt))
                    readText(post(url, key, json.toRequestBody(JSON), model))
                } finally {
                    runCatching { send(Request.Builder().url("$base/${file.name}").delete().header("x-goog-api-key", key).build(), model).close() }
                }
            }
        }

    private data class Uploaded(val name: String, val uri: String)

    private suspend fun upload(key: String, audio: PreparedAudio, model: String): Uploaded {
        val start = Request.Builder().url("$uploadBase/files")
            .header("x-goog-api-key", key)
            .header("X-Goog-Upload-Protocol", "resumable")
            .header("X-Goog-Upload-Command", "start")
            .header("X-Goog-Upload-Header-Content-Length", audio.file.length().toString())
            .header("X-Goog-Upload-Header-Content-Type", audio.mimeType)
            .post("""{"file":{"display_name":"minim-lyrics"}}""".toRequestBody(JSON))
            .build()
        val uploadUrl = send(start, model).use { r -> r.header("x-goog-upload-url") }
            ?: throw GeminiException(GeminiProblem.Busy)
        val put = Request.Builder().url(uploadUrl)
            .header("X-Goog-Upload-Offset", "0")
            .header("X-Goog-Upload-Command", "upload, finalize")
            .post(audio.file.asRequestBody(audio.mimeType.toMediaType()))
            .build()
        var info = send(put, model).use { r -> fileInfo(r.body?.string()) } ?: throw GeminiException(GeminiProblem.Busy)
        var waited = 0L
        while (info.third == "PROCESSING" && waited < 60_000L) {
            delay(pollWaitMs); waited += pollWaitMs
            info = send(Request.Builder().url("$base/${info.first}").header("x-goog-api-key", key).get().build(), model)
                .use { r -> fileInfo(r.body?.string()) } ?: throw GeminiException(GeminiProblem.Busy)
        }
        if (info.third != "ACTIVE" && info.third.isNotEmpty()) throw GeminiException(GeminiProblem.Busy)
        return Uploaded(info.first, info.second)
    }

    /** (name, uri, state) from a Files API reply (with or without the outer "file"). */
    private fun fileInfo(body: String?): Triple<String, String, String>? {
        val root = runCatching { MiniJson.parse(body ?: "") }.getOrNull().asObj() ?: return null
        val f = root["file"].asObj() ?: root
        val name = f["name"] as? String ?: return null
        val uri = f["uri"] as? String ?: return null
        return Triple(name, uri, (f["state"] as? String).orEmpty())
    }

    private suspend fun post(url: String, key: String, body: RequestBody, model: String): String =
        send(Request.Builder().url(url).header("x-goog-api-key", key).post(body).build(), model)
            .use { r -> r.body?.string() ?: "" }

    /** Runs one call; throws a sorted [GeminiException] / [AiTimeoutException] on failure. */
    private suspend fun send(req: Request, model: String): okhttp3.Response {
        val call: Call = http.newCall(req)
        val job = currentCoroutineContext()[kotlinx.coroutines.Job]
        val handle = job?.invokeOnCompletion { if (job.isCancelled) call.cancel() }
        try {
            val resp = try {
                call.execute()
            } catch (e: UnknownHostException) {
                throw GeminiException(GeminiProblem.Offline)
            } catch (e: ConnectException) {
                throw GeminiException(GeminiProblem.Offline)
            } catch (e: InterruptedIOException) {
                currentCoroutineContext().ensureActive()
                throw AiTimeoutException()
            } catch (e: IOException) {
                currentCoroutineContext().ensureActive()
                throw GeminiException(GeminiProblem.Offline)
            }
            if (!resp.isSuccessful) {
                val err = runCatching { resp.body?.string() }.getOrNull()
                resp.close()
                throw GeminiException(GeminiErrors.classify(resp.code, err, ownKey = true, model = model))
            }
            return resp
        } finally {
            handle?.dispose()
        }
    }

    companion object {
        const val UPLOAD_BASE = "https://generativelanguage.googleapis.com/upload/v1beta"
        /** Inline requests are capped at 20 MB in total; base64 grows the audio by a third. */
        const val INLINE_MAX_BYTES = 12L * 1024L * 1024L
        const val READ_TIMEOUT_S = 150L
        const val CALL_TIMEOUT_S = 240L
        private val JSON: MediaType = "application/json; charset=utf-8".toMediaType()

        fun requestMap(audioPart: Map<String, Any?>, prompt: String): Map<String, Any?> = linkedMapOf(
            "contents" to listOf(mapOf("role" to "user", "parts" to listOf(audioPart, mapOf("text" to prompt)))),
            "generationConfig" to mapOf("temperature" to 0.2, "responseMimeType" to "text/plain"),
        )

        /**
         * Gemini's text out of a generateContent reply. Thoughts are skipped. A refusal (copyright
         * recitation, safety) throws [AiRefusedException]; anything unreadable is an empty string,
         * which the parser then calls unusable.
         */
        fun readText(body: String): String {
            val root = runCatching { MiniJson.parse(body) }.getOrNull().asObj() ?: return ""
            root["promptFeedback"].asObj()?.get("blockReason")?.let { throw AiRefusedException(it.toString()) }
            val cand = root["candidates"].asList()?.firstOrNull().asObj() ?: return ""
            val text = StringBuilder()
            for (p in cand["content"].asObj()?.get("parts").asList().orEmpty()) {
                val part = p.asObj() ?: continue
                if (part["thought"] == true) continue
                (part["text"] as? String)?.let { text.append(it) }
            }
            val finish = cand["finishReason"] as? String
            if (text.isBlank() && finish in REFUSALS) throw AiRefusedException(finish!!)
            return text.toString()
        }

        private val REFUSALS = setOf("RECITATION", "SAFETY", "PROHIBITED_CONTENT", "BLOCKLIST", "SPII", "OTHER")
    }

    /** The inline request, with the audio base64'd straight from the file as it's sent. */
    class InlineAudioBody(private val file: File, private val mime: String, private val prompt: String) : RequestBody() {
        override fun contentType(): MediaType = JSON
        override fun writeTo(sink: BufferedSink) {
            val marker = "@@MINIM_AUDIO_DATA@@"
            val json = JsonOut.write(requestMap(mapOf("inline_data" to mapOf("mime_type" to mime, "data" to marker)), prompt))
            val at = json.indexOf(marker)
            sink.writeUtf8(json.substring(0, at))
            val enc = Base64.getEncoder().wrap(object : java.io.OutputStream() {
                override fun write(b: Int) { sink.writeByte(b) }
                override fun write(b: ByteArray, off: Int, len: Int) { sink.write(b, off, len) }
                override fun close() {} // the sink stays open for the rest of the JSON
            })
            file.inputStream().use { it.copyTo(enc, 1 shl 16) }
            enc.close()
            sink.writeUtf8(json.substring(at + marker.length))
        }
    }
}
