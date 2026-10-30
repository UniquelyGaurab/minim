package com.minim.music.playback

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

class BassBoostTest {

    private val rate = 48000

    // ---------------------------------------------------------------- the curve

    @Test
    fun stagesAreOffWarmPunchyHeavy() {
        assertEquals(0f, BassBoost.stageDb(BassBoost.OFF))
        assertEquals(4f, BassBoost.stageDb(BassBoost.WARM))
        assertEquals(7f, BassBoost.stageDb(BassBoost.PUNCHY))
        assertEquals(10f, BassBoost.stageDb(BassBoost.HEAVY))
        // Anything out of range is clamped
        assertEquals(0f, BassBoost.stageDb(-3))
        assertEquals(10f, BassBoost.stageDb(9))
    }

    private fun peakDb(stage: Int): Pair<Double, Double> {
        var best = -99.0
        var at = 0.0
        var hz = 20.0
        while (hz < 400.0) {
            val g = BassBoost.curveDb(stage, rate, hz)
            if (g > best) { best = g; at = hz }
            hz += 1.0
        }
        return best to at
    }

    @Test
    fun eachStagePeaksAtItsGainInTheKickRange() {
        for (stage in BassBoost.WARM..BassBoost.HEAVY) {
            val (peak, at) = peakDb(stage)
            assertEquals("stage $stage peak", BassBoost.stageDb(stage).toDouble(), peak, 0.5)
            assertTrue("stage $stage peaks at $at Hz", at in 35.0..75.0)
        }
    }

    @Test
    fun stagesAreClearlyApartWhereTheKickIs() {
        val at60 = (0..3).map { BassBoost.curveDb(it, rate, 60.0) }
        assertEquals(0.0, at60[0], 1e-9)
        for (i in 1..3) assertTrue("step $i: ${at60[i] - at60[i - 1]} dB", at60[i] - at60[i - 1] >= 2.5)
        // The bassline (80-100 Hz) gets a real lift too on Heavy
        assertTrue(BassBoost.curveDb(BassBoost.HEAVY, rate, 80.0) > 6.5)
        assertTrue(BassBoost.curveDb(BassBoost.HEAVY, rate, 100.0) > 5.0)
    }

    @Test
    fun midsAndVocalsStayPutSoItIsNotMuddy() {
        for (stage in 0..3) {
            assertTrue(BassBoost.curveDb(stage, rate, 250.0) < 1.0)
            assertTrue(BassBoost.curveDb(stage, rate, 400.0) < 0.5)
            assertEquals(0.0, BassBoost.curveDb(stage, rate, 1000.0), 0.15)
            assertEquals(0.0, BassBoost.curveDb(stage, rate, 5000.0), 0.05)
        }
    }

    @Test
    fun offIsExactlyFlat() {
        val c = BassBoost.Coeffs()
        BassBoost.lowShelf(c, rate, BassBoost.SHELF_HZ, 0.0, BassBoost.SHELF_SLOPE)
        assertEquals(1.0, c.b0, 0.0)
        assertEquals(c.a1, c.b1, 0.0)
        assertEquals(c.a2, c.b2, 0.0)
    }

    @Test
    fun curveIsTheSameAt44k() {
        for (stage in 1..3) {
            assertEquals(BassBoost.curveDb(stage, 48000, 60.0), BassBoost.curveDb(stage, 44100, 60.0), 0.05)
        }
    }

    // ---------------------------------------------------------------- the DSP

    private fun sine(hz: Double, amp: Float, frames: Int, channels: Int = 2, start: Int = 0): FloatArray {
        val out = FloatArray(frames * channels)
        for (f in 0 until frames) {
            val v = (amp * sin(2 * PI * hz * (f + start) / rate)).toFloat()
            for (c in 0 until channels) out[f * channels + c] = v
        }
        return out
    }

    private fun rms(buf: FloatArray, from: Int, to: Int): Double {
        var s = 0.0
        for (i in from until to) s += buf[i].toDouble() * buf[i]
        return sqrt(s / (to - from))
    }

    private fun db(x: Double) = 20 * log10(x)

    @Test
    fun settledStagesLiftTheBassAndOnlyTakeHeadroomOffTheRest() {
        for (stage in 1..3) {
            for ((hz, expected) in listOf(60.0 to BassBoost.curveDb(stage, rate, 60.0), 1000.0 to 0.0)) {
                val dsp = BassBoostDsp(rate, 2, stage)
                val x = sine(hz, 0.1f, rate)
                val y = x.copyOf()
                dsp.process(y, 0, rate)
                val gain = db(rms(y, rate, 2 * rate) / rms(x, rate, 2 * rate))
                val want = expected + BassBoost.headroomDb(BassBoost.stageDb(stage))
                assertEquals("stage $stage at $hz Hz", want, gain, 0.3)
            }
        }
    }

    @Test
    fun offPassesTheSamplesThroughUnchangedJustDelayed() {
        val dsp = BassBoostDsp(rate, 2, BassBoost.OFF)
        val rnd = java.util.Random(7)
        val x = FloatArray(4000 * 2) { (rnd.nextInt(65536) - 32768) / 32768f }
        val y = x.copyOf()
        dsp.process(y, 0, 4000)
        val d = dsp.lookahead * 2
        for (i in 0 until d) assertEquals(0f, y[i], 0f)
        for (i in d until y.size) assertEquals(x[i - d], y[i], 0f)
    }

    @Test
    fun limiterKeepsEverySampleInRangeOnHeavy() {
        val dsp = BassBoostDsp(rate, 2, BassBoost.HEAVY)
        val frames = rate * 2
        // A full-scale kick-like 50 Hz tone plus loud noise on top: the worst case for clipping
        val rnd = java.util.Random(3)
        val x = sine(50.0, 0.95f, frames)
        for (i in x.indices) x[i] = (x[i] + (rnd.nextFloat() - 0.5f) * 0.1f).coerceIn(-1f, 1f)
        dsp.process(x, 0, frames)
        var peak = 0f
        var over = 0
        val ceiling = BassBoost.ceiling(10f)
        for (v in x) {
            peak = maxOf(peak, abs(v))
            if (abs(v) > ceiling + 0.02f) over++
        }
        assertTrue("peak $peak", peak <= 1f)
        // The look-ahead does almost all the work; the soft knee only catches stragglers
        assertTrue("$over samples over the ceiling", over < x.size / 1000)
    }

    @Test
    fun limiterHoldsWithFloatInputAboveFullScale() {
        val dsp = BassBoostDsp(rate, 1, BassBoost.PUNCHY)
        val x = sine(70.0, 1.6f, rate, channels = 1)
        dsp.process(x, 0, rate)
        for (v in x) assertTrue(abs(v) <= 1f)
    }

    @Test
    fun switchingStagesGlidesOverHalfASecondWithoutAPop() {
        val dsp = BassBoostDsp(rate, 1, BassBoost.OFF)
        val total = rate * 2
        val x = sine(60.0, 0.2f, total, channels = 1)
        val y = x.copyOf()
        val switchAt = rate / 2
        dsp.process(y, 0, switchAt)
        dsp.setStage(BassBoost.HEAVY)
        var reached = -1
        var f = switchAt
        while (f < total) {
            val n = minOf(256, total - f)
            dsp.process(y, f, n)
            f += n
            if (reached < 0 && dsp.currentDb == 10f) reached = f
        }
        val glideMs = (reached - switchAt) * 1000.0 / rate
        assertTrue("glide took $glideMs ms", glideMs in 450.0..560.0)
        // No pop: no sample-to-sample jump bigger than the boosted sine itself can make
        val boosted = 0.2 * Math.pow(10.0, (BassBoost.curveDb(3, rate, 60.0) - 3.5) / 20)
        val maxStep = boosted * 2 * PI * 60 / rate
        for (i in 1 until y.size) {
            assertTrue("jump at $i", abs(y[i] - y[i - 1]) <= maxStep * 1.3)
        }
        // Back to Off glides too, and settles bit-exact again
        dsp.setStage(BassBoost.OFF)
        val z = sine(60.0, 0.2f, rate * 2, channels = 1, start = total)
        dsp.process(z, 0, rate * 2)
        assertTrue(dsp.isBypassed)
    }

    // ---------------------------------------------------------------- inside ExoPlayer's chain

    private fun pcm16(samples: ShortArray): ByteBuffer {
        val b = ByteBuffer.allocateDirect(samples.size * 2).order(ByteOrder.nativeOrder())
        for (s in samples) b.putShort(s)
        b.flip()
        return b
    }

    private fun drainShorts(p: BassBoostAudioProcessor, into: MutableList<Short>) {
        while (true) {
            val out = p.getOutput()
            if (!out.hasRemaining()) return
            while (out.hasRemaining()) into.add(out.getShort())
        }
    }

    @Test
    fun processorStaysInTheChainAndChangingStageNeverReconfigures() {
        val p = BassBoostAudioProcessor(BassBoost.OFF)
        val fmt = AudioProcessor.AudioFormat(44100, 2, C.ENCODING_PCM_16BIT)
        assertEquals(fmt, p.configure(fmt))
        p.flush()
        assertTrue(p.isActive)
        p.setStage(BassBoost.HEAVY)
        assertTrue(p.isActive)
        p.setStage(BassBoost.OFF)
        assertTrue(p.isActive)
        // Not PCM it can shape: left out of the chain, untouched
        val other = BassBoostAudioProcessor(BassBoost.HEAVY)
        other.configure(AudioProcessor.AudioFormat(44100, 2, C.ENCODING_PCM_24BIT))
        assertTrue(!other.isActive)
    }

    @Test
    fun processorOffIsBitExactAndTheTailComesOutAtTheEnd() {
        val p = BassBoostAudioProcessor(BassBoost.OFF)
        p.configure(AudioProcessor.AudioFormat(48000, 2, C.ENCODING_PCM_16BIT))
        p.flush()
        val rnd = java.util.Random(11)
        val input = ShortArray(2000 * 2) { (rnd.nextInt(65536) - 32768).toShort() }
        val got = ArrayList<Short>()
        p.queueInput(pcm16(input))
        drainShorts(p, got)
        p.queueEndOfStream()
        drainShorts(p, got)
        assertTrue(p.isEnded)
        val delay = BassBoostDsp(48000, 2).lookahead * 2
        assertEquals(input.size + delay, got.size)
        for (i in 0 until delay) assertEquals(0.toShort(), got[i])
        for (i in input.indices) assertEquals(input[i], got[i + delay])
    }

    @Test
    fun processorHeavyOnLoudPcm16NeverWraps() {
        val p = BassBoostAudioProcessor(BassBoost.HEAVY)
        p.configure(AudioProcessor.AudioFormat(48000, 2, C.ENCODING_PCM_16BIT))
        p.flush()
        val frames = 48000
        val input = ShortArray(frames * 2) { i ->
            val f = i / 2
            (32767 * sin(2 * PI * 55.0 * f / 48000)).toInt().toShort()
        }
        val got = ArrayList<Short>()
        p.queueInput(pcm16(input))
        drainShorts(p, got)
        // A wrapped sample would flip sign between neighbours at full scale
        for (i in 2 until got.size step 2) {
            assertTrue("jump at $i", abs(got[i] - got[i - 2]) < 3000)
        }
    }

    @Test
    fun pcm16ConversionClamps() {
        assertEquals(32767.toShort(), BassBoostAudioProcessor.toPcm16(1.5f))
        assertEquals((-32768).toShort(), BassBoostAudioProcessor.toPcm16(-1.5f))
        assertEquals(0.toShort(), BassBoostAudioProcessor.toPcm16(0f))
    }

    @Test
    fun softClipNeverPassesFullScale() {
        for (x in listOf(0.5f, 0.92f, 0.95f, 1f, 2f, 50f)) {
            val y = BassBoostDsp.softClip(x, 0.92f)
            assertTrue(y <= 1f)
            assertEquals(-y, BassBoostDsp.softClip(-x, 0.92f), 0f)
        }
        assertEquals(0.5f, BassBoostDsp.softClip(0.5f, 0.92f), 0f)
    }
}
