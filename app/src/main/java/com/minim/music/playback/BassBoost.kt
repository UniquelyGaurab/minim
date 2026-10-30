package com.minim.music.playback

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tanh

/**
 * 5.5.2 (update 40): Minim's own bass boost. Not Android's BassBoost/Equalizer effects: the
 * sound is shaped inside the app, in ExoPlayer's audio chain ([BassBoostAudioProcessor]), before
 * the volume boost (LoudnessEnhancer on the output) and the fade (player volume).
 *
 * The curve: a low shelf (RBJ biquad, 105 Hz) lifts the kick and the bassline, and a second,
 * gentle negative shelf at 30 Hz takes some of the lift back from the deep sub rumble that small
 * speakers and earbuds can't play anyway (it only eats headroom). Together they peak around
 * 45-60 Hz at exactly the stage's gain and stay out of the 200-400 Hz "mud" (Heavy adds about
 * 1.5 dB at 200 Hz and under 1 dB from 250 Hz up).
 *
 * Stages: Off 0 dB, Warm +4 dB, Punchy +7 dB, Heavy +10 dB. Plain maths so it runs in unit tests.
 */
object BassBoost {

    const val OFF = 0
    const val WARM = 1
    const val PUNCHY = 2
    const val HEAVY = 3

    /** Peak lift of the whole curve per stage, in dB. */
    private val STAGE_DB = floatArrayOf(0f, 4f, 7f, 10f)

    /** Main shelf: corner frequency, shelf slope (S), and how much its own gain exceeds the peak. */
    const val SHELF_HZ = 105.0
    const val SHELF_SLOPE = 0.85
    const val SHELF_SCALE = 1.17

    /** Sub trim: a negative shelf that gives back part of the lift below about 30 Hz. */
    const val SUB_HZ = 30.0
    const val SUB_SLOPE = 0.9
    const val SUB_RATIO = 0.35

    /** Pre-gain taken off before the lift, as a share of the stage's dB (Heavy: -3.5 dB). */
    const val HEADROOM_RATIO = 0.35f

    /** Limiter ceiling at full Heavy (linear, about -0.7 dBFS); Off is 1.0 so Off stays untouched. */
    const val CEILING_AT_HEAVY = 0.92f

    /** Stage changes glide over this long. */
    const val RAMP_SECONDS = 0.5

    /** How far ahead the limiter looks, and how fast it lets go. */
    const val LOOKAHEAD_MS = 3.0
    const val RELEASE_MS = 120.0

    fun clampStage(stage: Int): Int = stage.coerceIn(OFF, HEAVY)

    fun stageDb(stage: Int): Float = STAGE_DB[clampStage(stage)]

    fun headroomDb(boostDb: Float): Float = -HEADROOM_RATIO * boostDb

    /** The limiter ceiling for a boost of [boostDb] (moves with the glide, 1.0 at 0 dB). */
    fun ceiling(boostDb: Float): Float {
        val amount = (boostDb / STAGE_DB[HEAVY]).coerceIn(0f, 1f)
        return 1f - (1f - CEILING_AT_HEAVY) * amount
    }

    /** Normalised biquad coefficients b0, b1, b2, a1, a2 (a0 = 1). */
    class Coeffs(
        @JvmField var b0: Double = 1.0,
        @JvmField var b1: Double = 0.0,
        @JvmField var b2: Double = 0.0,
        @JvmField var a1: Double = 0.0,
        @JvmField var a2: Double = 0.0
    )

    /** RBJ cookbook low shelf. 0 dB gives exactly b = a (the filter passes the signal through). */
    fun lowShelf(out: Coeffs, sampleRate: Int, f0: Double, gainDb: Double, slope: Double) {
        val a = 10.0.pow(gainDb / 40.0)
        val w = 2.0 * PI * f0 / sampleRate
        val c = cos(w)
        val s = sin(w)
        val alpha = s / 2.0 * sqrt((a + 1.0 / a) * (1.0 / slope - 1.0) + 2.0)
        val sa = 2.0 * sqrt(a) * alpha
        val b0 = a * ((a + 1) - (a - 1) * c + sa)
        val b1 = 2 * a * ((a - 1) - (a + 1) * c)
        val b2 = a * ((a + 1) - (a - 1) * c - sa)
        val a0 = (a + 1) + (a - 1) * c + sa
        val a1 = -2 * ((a - 1) + (a + 1) * c)
        val a2 = (a + 1) + (a - 1) * c - sa
        out.b0 = b0 / a0
        out.b1 = b1 / a0
        out.b2 = b2 / a0
        out.a1 = a1 / a0
        out.a2 = a2 / a0
    }

    /** Both shelves for a peak lift of [boostDb]. */
    fun curve(shelf: Coeffs, sub: Coeffs, sampleRate: Int, boostDb: Double) {
        val g = boostDb * SHELF_SCALE
        lowShelf(shelf, sampleRate, SHELF_HZ, g, SHELF_SLOPE)
        lowShelf(sub, sampleRate, SUB_HZ, -SUB_RATIO * g, SUB_SLOPE)
    }

    /** Gain in dB of one biquad at [hz]. */
    fun magnitudeDb(c: Coeffs, sampleRate: Int, hz: Double): Double {
        val w = 2.0 * PI * hz / sampleRate
        val c1 = cos(w); val s1 = -sin(w)
        val c2 = cos(2 * w); val s2 = -sin(2 * w)
        val nr = c.b0 + c.b1 * c1 + c.b2 * c2
        val ni = c.b1 * s1 + c.b2 * s2
        val dr = 1.0 + c.a1 * c1 + c.a2 * c2
        val di = c.a1 * s1 + c.a2 * s2
        return 10.0 * kotlin.math.log10((nr * nr + ni * ni) / (dr * dr + di * di))
    }

    /** The lift of the whole curve (both shelves, before the headroom) at [hz], in dB. */
    fun curveDb(stage: Int, sampleRate: Int, hz: Double): Double {
        val shelf = Coeffs()
        val sub = Coeffs()
        curve(shelf, sub, sampleRate, stageDb(stage).toDouble())
        return magnitudeDb(shelf, sampleRate, hz) + magnitudeDb(sub, sampleRate, hz)
    }
}

/**
 * The bass boost itself, on interleaved float samples (-1..1). Shelves, then a look-ahead peak
 * limiter with a soft knee, so a boosted bassline never clips or crackles. Stage changes glide
 * over half a second (coefficients, pre-gain and ceiling together, every [BLOCK] frames), so
 * there is no pop, gap or restart. The limiter's short delay is always in the path, also when
 * Off, so turning the boost on or off never makes the audio jump.
 *
 * Off (once settled) is bit-exact: the samples only come out [lookahead] frames later.
 */
class BassBoostDsp(val sampleRate: Int, val channels: Int, initialStage: Int = BassBoost.OFF) {

    /** Delay of the limiter, in frames. */
    val lookahead: Int = maxOf(1, (sampleRate * BassBoost.LOOKAHEAD_MS / 1000.0).toInt())

    private val rampFrames = maxOf(1.0, sampleRate * BassBoost.RAMP_SECONDS)

    @Volatile
    private var targetStage = BassBoost.clampStage(initialStage)

    /** The lift right now (glides toward the target stage's dB). */
    var currentDb: Float = BassBoost.stageDb(targetStage)
        private set
    private var rampFromDb = currentDb
    private var rampToDb = currentDb
    private var rampStep = 0f

    private val shelf = BassBoost.Coeffs()
    private val sub = BassBoost.Coeffs()
    private var preGain = 1f
    private var ceiling = 1f
    private var knee = 1f

    // Biquad state, transposed direct form II, per channel
    private val s1a = DoubleArray(channels)
    private val s2a = DoubleArray(channels)
    private val s1b = DoubleArray(channels)
    private val s2b = DoubleArray(channels)

    // Delay line (lookahead frames x channels) and the sliding minimum of the needed gain
    private val delay = FloatArray(lookahead * channels)
    private var delayPos = 0
    private val minVal = FloatArray(lookahead + 1)
    private val minIdx = LongArray(lookahead + 1)
    private var minHead = 0
    private var minCount = 0
    private var frameIndex = 0L
    private var env = 1f
    private val attackCoef = (1.0 - exp(-1.0 / maxOf(1.0, lookahead / 4.0))).toFloat()
    private val releaseCoef = (1.0 - exp(-1.0 / (sampleRate * BassBoost.RELEASE_MS / 1000.0))).toFloat()
    private var blockLeft = 0

    init {
        updateCurve()
    }

    val stage: Int get() = targetStage

    /** Sets the stage; any thread. The change glides in over [BassBoost.RAMP_SECONDS]. */
    fun setStage(stage: Int) {
        targetStage = BassBoost.clampStage(stage)
    }

    /** Fully settled at 0 dB with the limiter at rest: the samples just pass through the delay. */
    val isBypassed: Boolean
        get() = currentDb == 0f && BassBoost.stageDb(targetStage) == 0f && env >= 1f

    /** Forget the old audio (a seek): filter memory, delay and limiter. The stage stays. */
    fun reset() {
        s1a.fill(0.0); s2a.fill(0.0); s1b.fill(0.0); s2b.fill(0.0)
        delay.fill(0f)
        delayPos = 0
        minHead = 0
        minCount = 0
        frameIndex = 0L
        env = 1f
        blockLeft = 0
        val to = BassBoost.stageDb(targetStage)
        currentDb = to
        rampFromDb = to
        rampToDb = to
        rampStep = 0f
        updateCurve()
    }

    private fun updateCurve() {
        BassBoost.curve(shelf, sub, sampleRate, currentDb.toDouble())
        preGain = 10f.pow(BassBoost.headroomDb(currentDb) / 20f)
        ceiling = BassBoost.ceiling(currentDb)
        knee = ceiling
    }

    /** Moves the glide on by one block when it's time, and starts a new glide on a new stage. */
    private fun tickBlock() {
        val to = BassBoost.stageDb(targetStage)
        if (to != rampToDb) {
            rampFromDb = currentDb
            rampToDb = to
            rampStep = ((to - currentDb) / rampFrames).toFloat()
        }
        if (currentDb != rampToDb) {
            val next = currentDb + rampStep * BLOCK
            currentDb = if ((rampStep > 0f && next >= rampToDb) || (rampStep < 0f && next <= rampToDb) || rampStep == 0f) {
                rampToDb
            } else next
            if (currentDb == 0f && rampToDb == 0f) {
                // Back at 0 dB: the shelves are exactly flat again, start them clean
                s1a.fill(0.0); s2a.fill(0.0); s1b.fill(0.0); s2b.fill(0.0)
            }
            updateCurve()
        }
        blockLeft = BLOCK
    }

    /** Processes [frames] interleaved frames of [buf] in place, from [offset] (in samples). */
    fun process(buf: FloatArray, offset: Int, frames: Int) {
        var p = offset
        for (f in 0 until frames) {
            if (blockLeft == 0) tickBlock()
            blockLeft--
            if (isBypassed) {
                // Off: through the delay unchanged, so switching on later doesn't jump
                for (ch in 0 until channels) {
                    val d = delayPos + ch
                    val out = delay[d]
                    delay[d] = buf[p + ch]
                    buf[p + ch] = out
                }
                pushNeed(1f)
                advance()
                p += channels
                continue
            }
            // 1) headroom + shelves, and the loudest channel of this frame
            var peak = 0f
            for (ch in 0 until channels) {
                val x = (buf[p + ch] * preGain).toDouble()
                val ya = shelf.b0 * x + s1a[ch]
                s1a[ch] = shelf.b1 * x - shelf.a1 * ya + s2a[ch]
                s2a[ch] = shelf.b2 * x - shelf.a2 * ya
                val yb = sub.b0 * ya + s1b[ch]
                s1b[ch] = sub.b1 * ya - sub.a1 * yb + s2b[ch]
                s2b[ch] = sub.b2 * ya - sub.a2 * yb
                val y = yb.toFloat()
                val a = abs(y)
                if (a > peak) peak = a
                // 2) into the delay; the sample coming out is [lookahead] frames old
                val d = delayPos + ch
                val out = delay[d]
                delay[d] = y
                buf[p + ch] = out
            }
            // 3) the gain the limiter needs over the next [lookahead] frames
            pushNeed(if (peak > ceiling) ceiling / peak else 1f)
            val need = minVal[minHead]
            env += (need - env) * (if (need < env) attackCoef else releaseCoef)
            if (env > 0.99999f) env = 1f
            // 4) gain, soft knee above the ceiling, and a hard stop at full scale
            for (ch in 0 until channels) {
                buf[p + ch] = softClip(buf[p + ch] * env, knee)
            }
            advance()
            p += channels
        }
    }

    /** End of the stream: the frames still in the delay, then silence. */
    fun drain(out: FloatArray, offset: Int) {
        java.util.Arrays.fill(out, offset, offset + lookahead * channels, 0f)
        process(out, offset, lookahead)
    }

    private fun advance() {
        delayPos += channels
        if (delayPos >= delay.size) delayPos = 0
        frameIndex++
    }

    /** Sliding minimum over the last lookahead + 1 frames (monotonic queue in a ring). */
    private fun pushNeed(v: Float) {
        val cap = minVal.size
        // Drop from the back everything not smaller than the new value
        while (minCount > 0) {
            val back = (minHead + minCount - 1) % cap
            if (minVal[back] >= v) minCount-- else break
        }
        val at = (minHead + minCount) % cap
        minVal[at] = v
        minIdx[at] = frameIndex
        minCount++
        // Drop from the front what's older than the window
        while (minCount > 0 && minIdx[minHead] <= frameIndex - cap) {
            minHead = (minHead + 1) % cap
            minCount--
        }
    }

    companion object {
        /** Frames per glide step (coefficients are recomputed this often while gliding). */
        const val BLOCK = 32

        /** Below [knee] unchanged; above it bends smoothly toward 1.0 and never passes it. */
        fun softClip(x: Float, knee: Float): Float {
            val a = abs(x)
            if (a <= knee) return x
            val room = 1f - knee
            val y = if (room < 1e-4f) 1f else knee + room * tanh(((a - knee) / room).toDouble()).toFloat()
            val c = if (y > 1f) 1f else y
            return if (x < 0f) -c else c
        }
    }
}
