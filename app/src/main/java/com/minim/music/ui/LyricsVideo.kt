package com.minim.music.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.opengl.GLUtils
import android.view.Surface
import com.minim.music.data.share.LyricsVideoPlan
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.coroutines.coroutineContext

/**
 * 6.7.0: makes the animated lyrics card on the phone. Frames are drawn with [LyricsCard.Scene]
 * on a 1080 x 1920 bitmap, uploaded to an OpenGL texture and drawn onto the H.264 encoder's
 * input surface (MediaCodec + MediaMuxer, no extra libraries). The song audio is cut from the
 * same stream Minim plays, faded in and out, and encoded to AAC; [combine] puts the two in one MP4.
 */
object LyricsVideo {
    private const val MIME = MediaFormat.MIMETYPE_VIDEO_AVC
    private const val AAC = MediaFormat.MIMETYPE_AUDIO_AAC
    /** Mostly still text: 1.6 Mbit/s keeps a 31-second card around 6 MB with sound. */
    const val VIDEO_BITRATE = 1_600_000
    const val AUDIO_BITRATE = 128_000

    /**
     * Renders the silent video for [plan] into [out].
     * @return the size it was encoded at (1080 x 1920, or 720 x 1280 on encoders that can't).
     */
    suspend fun renderVideo(
        scene: LyricsCard.Scene,
        plan: LyricsVideoPlan.Plan,
        timing: List<LyricsCard.Timing>,
        out: File,
        onProgress: (Float) -> Unit
    ): Pair<Int, Int> = withContext(Dispatchers.Default) {
        val sizes = listOf(1080 to 1920, 720 to 1280)
        var last: Throwable? = null
        for ((w, h) in sizes) {
            coroutineContext.ensureActive()
            try {
                encode(scene, plan, timing, out, w, h, onProgress)
                return@withContext w to h
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                last = e
                out.delete()
            }
        }
        throw last ?: IllegalStateException("no encoder")
    }

    private suspend fun encode(
        scene: LyricsCard.Scene,
        plan: LyricsVideoPlan.Plan,
        timing: List<LyricsCard.Timing>,
        out: File,
        w: Int,
        h: Int,
        onProgress: (Float) -> Unit
    ) {
        val codec = MediaCodec.createEncoderByType(MIME)
        val caps = codec.codecInfo.getCapabilitiesForType(MIME).videoCapabilities
        if (caps != null && !caps.isSizeSupported(w, h)) {
            codec.release()
            throw IllegalStateException("size $w x $h not supported")
        }
        val bitrate = (VIDEO_BITRATE.toLong() * (w * h) / (1080 * 1920)).toInt()
        val format = MediaFormat.createVideoFormat(MIME, w, h).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, LyricsVideoPlan.FPS)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
        }
        var muxer: MediaMuxer? = null
        var egl: Egl? = null
        var quad: Quad? = null
        val frame = Bitmap.createBitmap(LyricsCard.W, LyricsCard.H, Bitmap.Config.ARGB_8888)
        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val input = codec.createInputSurface()
            codec.start()
            egl = Egl(input)
            quad = Quad()
            muxer = MediaMuxer(out.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val drain = Drainer(codec, muxer)
            val canvas = Canvas(frame)
            val frames = plan.frames
            var lastPct = -1
            for (i in 0 until frames) {
                coroutineContext.ensureActive()
                scene.drawFrame(canvas, plan, timing, plan.frameMs(i))
                quad.upload(frame)
                GLES20.glViewport(0, 0, w, h)
                quad.draw()
                egl.present(i * 1_000_000_000L / LyricsVideoPlan.FPS)
                drain.drain(false)
                val pct = (i + 1) * 100 / frames
                if (pct != lastPct) { lastPct = pct; onProgress(pct / 100f) }
            }
            codec.signalEndOfInputStream()
            drain.drain(true)
            if (!drain.started) throw IllegalStateException("encoder gave no output")
        } finally {
            runCatching { quad?.release() }
            runCatching { egl?.release() }
            runCatching { codec.stop() }
            runCatching { codec.release() }
            runCatching { muxer?.stop() }
            runCatching { muxer?.release() }
            frame.recycle()
        }
    }

    /** Moves encoded output into the muxer; starts the muxer on the first format. */
    private class Drainer(private val codec: MediaCodec, private val muxer: MediaMuxer) {
        private val info = MediaCodec.BufferInfo()
        private var track = -1
        var started = false
            private set

        fun drain(end: Boolean) {
            var idle = 0
            while (true) {
                val idx = codec.dequeueOutputBuffer(info, if (end) 10_000L else 0L)
                when {
                    idx == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                        if (!end) return
                        if (++idle > 300) return // ~3 s with no output at the end: give up waiting
                    }
                    idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        if (!started) {
                            track = muxer.addTrack(codec.outputFormat)
                            muxer.start()
                            started = true
                        }
                    }
                    idx >= 0 -> {
                        val buf = codec.getOutputBuffer(idx)
                        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                        if (buf != null && info.size > 0 && started) {
                            buf.position(info.offset)
                            buf.limit(info.offset + info.size)
                            muxer.writeSampleData(track, buf, info)
                        }
                        codec.releaseOutputBuffer(idx, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                    }
                }
            }
        }
    }

    /** An EGL context drawing into the encoder's surface. */
    private class Egl(private val surface: Surface) {
        private val display: EGLDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        private val context: EGLContext
        private val eglSurface: EGLSurface

        init {
            val v = IntArray(2)
            check(EGL14.eglInitialize(display, v, 0, v, 1)) { "eglInitialize" }
            val attribs = intArrayOf(
                EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL_RECORDABLE_ANDROID, 1,
                EGL14.EGL_NONE
            )
            val configs = arrayOfNulls<EGLConfig>(1)
            val num = IntArray(1)
            check(EGL14.eglChooseConfig(display, attribs, 0, configs, 0, 1, num, 0) && num[0] > 0) { "eglChooseConfig" }
            val config = configs[0]!!
            context = EGL14.eglCreateContext(
                display, config, EGL14.EGL_NO_CONTEXT,
                intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0
            )
            check(context != EGL14.EGL_NO_CONTEXT) { "eglCreateContext" }
            eglSurface = EGL14.eglCreateWindowSurface(display, config, surface, intArrayOf(EGL14.EGL_NONE), 0)
            check(eglSurface != EGL14.EGL_NO_SURFACE) { "eglCreateWindowSurface" }
            check(EGL14.eglMakeCurrent(display, eglSurface, eglSurface, context)) { "eglMakeCurrent" }
        }

        fun present(ptsNs: Long) {
            EGLExt.eglPresentationTimeANDROID(display, eglSurface, ptsNs)
            EGL14.eglSwapBuffers(display, eglSurface)
        }

        fun release() {
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            EGL14.eglDestroySurface(display, eglSurface)
            EGL14.eglDestroyContext(display, context)
            EGL14.eglReleaseThread()
            EGL14.eglTerminate(display)
            surface.release()
        }

        companion object {
            private const val EGL_RECORDABLE_ANDROID = 0x3142
        }
    }

    /** A full-screen textured quad that shows the frame bitmap. */
    private class Quad {
        private val program: Int
        private val tex: Int
        private val aPos: Int
        private val aTex: Int
        private var uploaded = false
        // x, y, u, v as a triangle strip; bitmap row 0 is the top of the picture
        private val verts: FloatBuffer = ByteBuffer.allocateDirect(16 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
            put(floatArrayOf(-1f, -1f, 0f, 1f, 1f, -1f, 1f, 1f, -1f, 1f, 0f, 0f, 1f, 1f, 1f, 0f)); position(0)
        }

        init {
            program = link(
                "attribute vec4 aPos; attribute vec2 aTex; varying vec2 vTex; void main(){ gl_Position = aPos; vTex = aTex; }",
                "precision mediump float; varying vec2 vTex; uniform sampler2D uTex; void main(){ gl_FragColor = texture2D(uTex, vTex); }"
            )
            aPos = GLES20.glGetAttribLocation(program, "aPos")
            aTex = GLES20.glGetAttribLocation(program, "aTex")
            val t = IntArray(1)
            GLES20.glGenTextures(1, t, 0)
            tex = t[0]
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        }

        fun upload(bmp: Bitmap) {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex)
            if (!uploaded) { GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0); uploaded = true }
            else GLUtils.texSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, bmp)
        }

        fun draw() {
            GLES20.glUseProgram(program)
            verts.position(0)
            GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 16, verts)
            GLES20.glEnableVertexAttribArray(aPos)
            verts.position(2)
            GLES20.glVertexAttribPointer(aTex, 2, GLES20.GL_FLOAT, false, 16, verts)
            GLES20.glEnableVertexAttribArray(aTex)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        }

        fun release() {
            GLES20.glDeleteTextures(1, intArrayOf(tex), 0)
            GLES20.glDeleteProgram(program)
        }

        private fun link(vs: String, fs: String): Int {
            fun shader(type: Int, src: String): Int {
                val s = GLES20.glCreateShader(type)
                GLES20.glShaderSource(s, src)
                GLES20.glCompileShader(s)
                val ok = IntArray(1)
                GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0)
                check(ok[0] != 0) { "shader: " + GLES20.glGetShaderInfoLog(s) }
                return s
            }
            val p = GLES20.glCreateProgram()
            GLES20.glAttachShader(p, shader(GLES20.GL_VERTEX_SHADER, vs))
            GLES20.glAttachShader(p, shader(GLES20.GL_FRAGMENT_SHADER, fs))
            GLES20.glLinkProgram(p)
            val ok = IntArray(1)
            GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0)
            check(ok[0] != 0) { "link: " + GLES20.glGetProgramInfoLog(p) }
            return p
        }
    }

    // ---------------------------------------------------------------- audio

    /**
     * Cuts [clipMs] of [src] from [startMs], fades it in and out ([LyricsVideoPlan.AUDIO_FADE_MS]),
     * pads silence to [totalMs] (the ending) and writes AAC in an MP4 to [out]. Throws on failure.
     */
    suspend fun makeAudioClip(src: File, startMs: Long, clipMs: Long, totalMs: Long, out: File) =
        withContext(Dispatchers.Default) {
            val pcm = decode(src, startMs * 1000, (startMs + clipMs) * 1000)
            coroutineContext.ensureActive()
            val clipFrames = clipMs * pcm.rate / 1000
            val totalFrames = totalMs * pcm.rate / 1000
            val fade = LyricsVideoPlan.AUDIO_FADE_MS * pcm.rate / 1000
            val samples = ShortArray((totalFrames * pcm.channels).toInt())
            val have = minOf(clipFrames, pcm.frames.toLong())
            for (f in 0 until have) {
                val g = LyricsVideoPlan.gain(f, clipFrames, fade)
                for (ch in 0 until pcm.channels) {
                    val i = (f * pcm.channels + ch).toInt()
                    samples[i] = (pcm.data[i] * g).toInt().coerceIn(-32768, 32767).toShort()
                }
            }
            encodeAac(samples, pcm.rate, pcm.channels, out)
        }

    private class Pcm(val data: ShortArray, val frames: Int, val rate: Int, val channels: Int)

    private suspend fun decode(src: File, startUs: Long, endUs: Long): Pcm {
        val ex = MediaExtractor()
        var dec: MediaCodec? = null
        try {
            ex.setDataSource(src.path)
            val track = (0 until ex.trackCount).firstOrNull {
                ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: throw IllegalStateException("no audio track")
            val fmt = ex.getTrackFormat(track)
            ex.selectTrack(track)
            ex.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            val codec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!)
            dec = codec
            codec.configure(fmt, null, null, 0)
            codec.start()
            var rate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var srcCh = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var float = false
            var outCh = minOf(2, srcCh)
            var buf = ShortArray(((endUs - startUs) / 1000 + 500).toInt() * rate / 1000 * outCh + 4096)
            var frames = 0
            val info = MediaCodec.BufferInfo()
            var inDone = false
            var outDone = false
            var idle = 0
            while (!outDone) {
                coroutineContext.ensureActive()
                if (!inDone) {
                    val ii = codec.dequeueInputBuffer(10_000)
                    if (ii >= 0) {
                        val ib = codec.getInputBuffer(ii)!!
                        val n = ex.readSampleData(ib, 0)
                        if (n < 0 || ex.sampleTime > endUs + 200_000) {
                            codec.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inDone = true
                        } else {
                            codec.queueInputBuffer(ii, 0, n, ex.sampleTime, 0)
                            ex.advance()
                        }
                    }
                }
                val oi = codec.dequeueOutputBuffer(info, 10_000)
                when {
                    oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val of = codec.outputFormat
                        rate = of.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        srcCh = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        outCh = minOf(2, srcCh)
                        float = of.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                            of.getInteger(MediaFormat.KEY_PCM_ENCODING) == android.media.AudioFormat.ENCODING_PCM_FLOAT
                    }
                    oi == MediaCodec.INFO_TRY_AGAIN_LATER -> if (inDone && ++idle > 300) outDone = true
                    oi >= 0 -> {
                        idle = 0
                        val ob = codec.getOutputBuffer(oi)
                        if (ob != null && info.size > 0) {
                            ob.position(info.offset); ob.limit(info.offset + info.size)
                            ob.order(ByteOrder.nativeOrder())
                            val bytesPer = if (float) 4 else 2
                            val n = info.size / (bytesPer * srcCh)
                            val keep = LyricsVideoPlan.keep(info.presentationTimeUs, n, rate, startUs, endUs)
                            if (keep != null) {
                                val (skip, count) = keep
                                val need = (frames + count) * outCh
                                if (need > buf.size) buf = buf.copyOf(need + rate * outCh)
                                if (float) {
                                    val fb = ob.asFloatBuffer()
                                    for (f in 0 until count) for (ch in 0 until outCh)
                                        buf[(frames + f) * outCh + ch] = (fb.get((skip + f) * srcCh + ch) * 32767f)
                                            .toInt().coerceIn(-32768, 32767).toShort()
                                } else {
                                    val sb = ob.asShortBuffer()
                                    for (f in 0 until count) for (ch in 0 until outCh)
                                        buf[(frames + f) * outCh + ch] = sb.get((skip + f) * srcCh + ch)
                                }
                                frames += count
                            }
                        }
                        codec.releaseOutputBuffer(oi, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outDone = true
                    }
                }
            }
            if (frames == 0) throw IllegalStateException("no audio decoded")
            return Pcm(buf, frames, rate, outCh)
        } finally {
            runCatching { dec?.stop() }
            runCatching { dec?.release() }
            ex.release()
        }
    }

    private suspend fun encodeAac(samples: ShortArray, rate: Int, channels: Int, out: File) {
        val fmt = MediaFormat.createAudioFormat(AAC, rate, channels).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, AUDIO_BITRATE)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
        }
        val codec = MediaCodec.createEncoderByType(AAC)
        val muxer = MediaMuxer(out.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var track = -1
        var started = false
        try {
            codec.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            val info = MediaCodec.BufferInfo()
            var pos = 0 // in samples
            var inDone = false
            var outDone = false
            var idle = 0
            while (!outDone) {
                coroutineContext.ensureActive()
                if (!inDone) {
                    val ii = codec.dequeueInputBuffer(10_000)
                    if (ii >= 0) {
                        val ib = codec.getInputBuffer(ii)!!
                        ib.clear(); ib.order(ByteOrder.nativeOrder())
                        val room = (ib.capacity() / 2 / channels) * channels
                        val n = minOf(room, samples.size - pos)
                        val pts = (pos / channels).toLong() * 1_000_000L / rate
                        if (n <= 0) {
                            codec.queueInputBuffer(ii, 0, 0, pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inDone = true
                        } else {
                            ib.asShortBuffer().put(samples, pos, n)
                            codec.queueInputBuffer(ii, 0, n * 2, pts, 0)
                            pos += n
                        }
                    }
                }
                val oi = codec.dequeueOutputBuffer(info, 10_000)
                when {
                    oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> if (!started) {
                        track = muxer.addTrack(codec.outputFormat); muxer.start(); started = true
                    }
                    oi == MediaCodec.INFO_TRY_AGAIN_LATER -> if (inDone && ++idle > 300) outDone = true
                    oi >= 0 -> {
                        idle = 0
                        val ob = codec.getOutputBuffer(oi)
                        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                        if (ob != null && info.size > 0 && started) {
                            ob.position(info.offset); ob.limit(info.offset + info.size)
                            muxer.writeSampleData(track, ob, info)
                        }
                        codec.releaseOutputBuffer(oi, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outDone = true
                    }
                }
            }
            if (!started) throw IllegalStateException("audio encoder gave no output")
        } finally {
            runCatching { codec.stop() }
            runCatching { codec.release() }
            runCatching { if (started) muxer.stop() }
            runCatching { muxer.release() }
        }
    }

    // ---------------------------------------------------------------- mux

    /** Copies the video track of [video] and the audio track of [audio] (when given) into [out]. */
    suspend fun combine(video: File, audio: File?, out: File) = withContext(Dispatchers.IO) {
        val muxer = MediaMuxer(out.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        val sources = listOfNotNull(video to "video/", audio?.let { it to "audio/" })
        val extractors = ArrayList<Pair<MediaExtractor, Int>>()
        var started = false
        try {
            for ((file, kind) in sources) {
                val ex = MediaExtractor()
                ex.setDataSource(file.path)
                val t = (0 until ex.trackCount).firstOrNull {
                    ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith(kind) == true
                } ?: run { ex.release(); throw IllegalStateException("no $kind track") }
                ex.selectTrack(t)
                extractors += ex to muxer.addTrack(ex.getTrackFormat(t))
            }
            muxer.start()
            started = true
            val buf = ByteBuffer.allocate(1 shl 20)
            val info = MediaCodec.BufferInfo()
            for ((ex, track) in extractors) {
                while (true) {
                    coroutineContext.ensureActive()
                    val n = ex.readSampleData(buf, 0)
                    if (n < 0) break
                    info.set(0, n, ex.sampleTime,
                        if (ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
                    muxer.writeSampleData(track, buf, info)
                    ex.advance()
                }
            }
        } finally {
            extractors.forEach { runCatching { it.first.release() } }
            runCatching { if (started) muxer.stop() }
            runCatching { muxer.release() }
        }
    }
}
