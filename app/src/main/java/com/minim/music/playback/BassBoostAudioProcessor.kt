package com.minim.music.playback

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 5.5.2 (update 40): runs [BassBoostDsp] inside ExoPlayer's audio chain. It stays in the chain
 * for every 16-bit and float PCM format, whatever the stage, so changing the stage never makes
 * ExoPlayer reconfigure, flush or restart the song: the stage just glides.
 */
@OptIn(UnstableApi::class)
class BassBoostAudioProcessor(initialStage: Int) : BaseAudioProcessor() {

    @Volatile
    private var stage = BassBoost.clampStage(initialStage)

    private var dsp: BassBoostDsp? = null
    private var work = FloatArray(0)
    private var tailPending = false

    /** Any thread (Settings changes it on the main thread). */
    fun setStage(stage: Int) {
        this.stage = BassBoost.clampStage(stage)
        dsp?.setStage(this.stage)
    }

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        val ok = (inputAudioFormat.encoding == C.ENCODING_PCM_16BIT || inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT) &&
            inputAudioFormat.channelCount in 1..8 && inputAudioFormat.sampleRate > 0
        // Anything else (passthrough, 24/32-bit int): not active, the audio goes by untouched
        return if (ok) inputAudioFormat else AudioProcessor.AudioFormat.NOT_SET
    }

    private fun dspFor(): BassBoostDsp {
        val f = inputAudioFormat
        val d = dsp
        if (d != null && d.sampleRate == f.sampleRate && d.channels == f.channelCount) {
            d.setStage(stage)
            return d
        }
        return BassBoostDsp(f.sampleRate, f.channelCount, stage).also { dsp = it }
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return
        val f = inputAudioFormat
        val d = dspFor()
        val isFloat = f.encoding == C.ENCODING_PCM_FLOAT
        val bytesPerSample = if (isFloat) 4 else 2
        val frames = remaining / (bytesPerSample * f.channelCount)
        val samples = frames * f.channelCount
        if (work.size < samples) work = FloatArray(samples)
        val src = inputBuffer.order(ByteOrder.nativeOrder())
        if (isFloat) {
            for (i in 0 until samples) work[i] = src.getFloat()
        } else {
            for (i in 0 until samples) work[i] = src.getShort() / 32768f
        }
        // Skip any partial frame left at the end (never happens with whole frames from ExoPlayer)
        inputBuffer.position(inputBuffer.limit())
        d.process(work, 0, frames)
        val out = replaceOutputBuffer(samples * bytesPerSample)
        write(out, samples, isFloat)
        out.flip()
    }

    private fun write(out: ByteBuffer, samples: Int, isFloat: Boolean) {
        if (isFloat) {
            for (i in 0 until samples) out.putFloat(work[i])
        } else {
            for (i in 0 until samples) out.putShort(toPcm16(work[i]))
        }
    }

    override fun onQueueEndOfStream() {
        tailPending = dsp != null
    }

    /** After the last input, the few milliseconds still in the limiter's delay come out too. */
    override fun getOutput(): ByteBuffer {
        val out = super.getOutput()
        if (out.hasRemaining() || !tailPending) return out
        tailPending = false
        val d = dsp ?: return out
        val samples = d.lookahead * d.channels
        if (work.size < samples) work = FloatArray(samples)
        d.drain(work, 0)
        val isFloat = inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT
        val buf = replaceOutputBuffer(samples * (if (isFloat) 4 else 2))
        write(buf, samples, isFloat)
        buf.flip()
        return super.getOutput()
    }

    override fun isEnded(): Boolean = super.isEnded() && !tailPending

    override fun onFlush() {
        tailPending = false
        val f = inputAudioFormat
        val d = dsp
        if (d != null && d.sampleRate == f.sampleRate && d.channels == f.channelCount) {
            d.setStage(stage)
            d.reset()
        } else if (f != AudioProcessor.AudioFormat.NOT_SET && f.sampleRate > 0 && f.channelCount > 0) {
            dsp = BassBoostDsp(f.sampleRate, f.channelCount, stage)
        } else {
            dsp = null
        }
    }

    override fun onReset() {
        dsp = null
        work = FloatArray(0)
        tailPending = false
    }

    companion object {
        fun toPcm16(x: Float): Short {
            val v = Math.round(x * 32768f)
            return v.coerceIn(-32768, 32767).toShort()
        }
    }
}
