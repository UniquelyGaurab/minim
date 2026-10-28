package com.minim.music.ui

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.os.SystemClock
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import androidx.core.graphics.ColorUtils

/**
 * Thin rounded progress bar from the designs: played part in the dark colour, the rest muted.
 * Drag or tap anywhere on it to seek.
 *
 * 5.5.0: with [expressive] on (Now Playing's bar) it also: springs to 3x thickness when the finger
 * lands and grows a round thumb under it; reports the drag to [onScrub] (for the floating time
 * pill) and the ends to [onEdge] (a light tick); slides the fill to a tapped spot instead of
 * teleporting; glides every frame while playing (see [setPlayback]); and shows the buffered part
 * as a faint lighter section ahead of the fill. Other bars keep the plain behaviour.
 */
class ProgressLine @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val density = resources.displayMetrics.density
    private val thickness = 5f * density
    private val thumbMax = 10f * density
    private val slop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    private val playedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = thickness
        strokeCap = Paint.Cap.ROUND
    }
    private val restPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = thickness
        strokeCap = Paint.Cap.ROUND
    }
    private val bufferPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = thickness
        strokeCap = Paint.Cap.ROUND
    }
    private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    /** 0..1 */
    private var fraction = 0f
    private var dragging = false

    /** Called with the chosen fraction while dragging (fromUser=true until release). */
    var onSeek: ((fraction: Float, finished: Boolean) -> Unit)? = null

    /** 5.5.0: Now Playing's animated bar. */
    var expressive = false

    /** 5.5.0: the finger's fraction while dragging (shown = true), and shown = false when let go. */
    var onScrub: ((fraction: Float, shown: Boolean) -> Unit)? = null

    /** 5.5.0: the drag reached 0:00 or the very end. */
    var onEdge: (() -> Unit)? = null

    private var grow = 0f
    private var growAnim: ValueAnimator? = null
    private var slideAnim: ValueAnimator? = null
    private var buffered = 0f
    private var basePos = 0L
    private var baseDur = 0L
    private var baseAt = 0L
    private var playing = false
    private var downX = 0f
    private var isDragMove = false
    private var lastEdge = 0

    fun setColors(played: Int, rest: Int) {
        playedPaint.color = played
        restPaint.color = rest
        thumbPaint.color = played
        // The buffered part: the rest colour a step towards the played colour
        bufferPaint.color = ColorUtils.blendARGB(rest, played, 0.3f)
        invalidate()
    }

    fun setFraction(f: Float) {
        if (dragging || slideAnim != null) return
        val v = f.coerceIn(0f, 1f)
        if (expressive) {
            // Keep gliding from the new spot
            basePos = SeekMotion.targetMs(v, baseDur)
            baseAt = SystemClock.uptimeMillis()
        } else {
            playing = false
        }
        if (v != fraction) {
            fraction = v
            invalidate()
        }
    }

    /**
     * 5.5.0: the latest playback position. While playing, the fill is worked out again every
     * frame from this, so it moves smoothly. [bufferedMs] is how far the song has loaded.
     */
    fun setPlayback(positionMs: Long, durationMs: Long, isPlaying: Boolean, bufferedMs: Long) {
        buffered = if (durationMs > 0L) (bufferedMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
        if (dragging || slideAnim != null) { invalidate(); return }
        basePos = positionMs
        baseDur = durationMs
        baseAt = SystemClock.uptimeMillis()
        playing = isPlaying && expressive && durationMs > 0L
        fraction = SeekMotion.liveFraction(basePos, baseDur, 0L, false)
        if (playing) postInvalidateOnAnimation() else invalidate()
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        if (isVisible && playing) postInvalidateOnAnimation()
    }

    private fun barLeft(): Float = paddingLeft + thickness / 2f
    private fun barRight(): Float = width - paddingRight - thickness / 2f

    override fun onDraw(canvas: Canvas) {
        val left = barLeft()
        val right = barRight()
        val y = height / 2f
        if (!expressive) {
            val x = left + (right - left) * fraction
            canvas.drawLine(left, y, right, y, restPaint)
            if (fraction > 0f) canvas.drawLine(left, y, x, y, playedPaint)
            return
        }
        if (playing && !dragging && slideAnim == null) {
            fraction = SeekMotion.liveFraction(basePos, baseDur, SystemClock.uptimeMillis() - baseAt, true)
            if (isShown) postInvalidateOnAnimation()
        }
        val t = SeekMotion.thickness(thickness, grow)
        restPaint.strokeWidth = t
        playedPaint.strokeWidth = t
        bufferPaint.strokeWidth = t
        val x = left + (right - left) * fraction
        canvas.drawLine(left, y, right, y, restPaint)
        if (buffered > fraction) canvas.drawLine(x, y, left + (right - left) * buffered, y, bufferPaint)
        if (fraction > 0f || grow > 0f) canvas.drawLine(left, y, x, y, playedPaint)
        if (grow > 0.01f) canvas.drawCircle(x, y, thumbMax * grow, thumbPaint)
    }

    private fun animateGrow(to: Float) {
        growAnim?.cancel()
        growAnim = ValueAnimator.ofFloat(grow, to).apply {
            duration = if (to > grow) SeekMotion.GROW_MS else SeekMotion.SHRINK_MS
            interpolator = if (to > grow) OvershootInterpolator(2.2f) else DecelerateInterpolator()
            addUpdateListener { grow = (it.animatedValue as Float).coerceAtLeast(0f); invalidate() }
            start()
        }
    }

    private fun slideTo(target: Float) {
        slideAnim?.cancel()
        val from = fraction
        val a = ValueAnimator.ofFloat(from, target).apply {
            duration = SeekMotion.TAP_SLIDE_MS
            interpolator = DecelerateInterpolator(1.6f)
            addUpdateListener { fraction = it.animatedValue as Float; invalidate() }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    if (slideAnim === animation) slideAnim = null
                    // Carry on gliding from the new spot
                    basePos = SeekMotion.targetMs(target, baseDur)
                    baseAt = SystemClock.uptimeMillis()
                    invalidate()
                }
            })
        }
        slideAnim = a
        a.start()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled) return false
        if (expressive) return expressiveTouch(event)
        val half = thickness / 2f
        val left = paddingLeft + half
        val right = width - paddingRight - half
        val f = ((event.x - left) / (right - left)).coerceIn(0f, 1f)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                dragging = true
                fraction = f
                invalidate()
                onSeek?.invoke(f, false)
            }
            MotionEvent.ACTION_MOVE -> {
                fraction = f
                invalidate()
                onSeek?.invoke(f, false)
            }
            MotionEvent.ACTION_UP -> {
                fraction = f
                dragging = false
                invalidate()
                onSeek?.invoke(f, true)
            }
            MotionEvent.ACTION_CANCEL -> {
                dragging = false
                onSeek?.invoke(fraction, true)
            }
        }
        return true
    }

    private fun expressiveTouch(event: MotionEvent): Boolean {
        val f = SeekMotion.fractionAt(event.x, barLeft(), barRight())
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                slideAnim?.cancel(); slideAnim = null
                dragging = true
                isDragMove = false
                downX = event.x
                lastEdge = SeekMotion.edge(fraction)
                animateGrow(1f)
            }
            MotionEvent.ACTION_MOVE -> {
                if (!isDragMove && SeekMotion.isDrag(downX, event.x, slop)) isDragMove = true
                if (isDragMove) {
                    fraction = f
                    val e = SeekMotion.edge(f)
                    if (SeekMotion.edgeTicks(lastEdge, e)) onEdge?.invoke()
                    lastEdge = e
                    invalidate()
                    onSeek?.invoke(f, false)
                    onScrub?.invoke(f, true)
                }
            }
            MotionEvent.ACTION_UP -> {
                dragging = false
                animateGrow(0f)
                onScrub?.invoke(f, false)
                if (isDragMove) {
                    fraction = f
                    basePos = SeekMotion.targetMs(f, baseDur)
                    baseAt = SystemClock.uptimeMillis()
                    invalidate()
                } else {
                    // A tap: the fill slides to the spot
                    slideTo(f)
                }
                onSeek?.invoke(f, true)
            }
            MotionEvent.ACTION_CANCEL -> {
                dragging = false
                animateGrow(0f)
                onScrub?.invoke(fraction, false)
                // Only a drag in progress seeks; a cancelled tap leaves the song alone
                if (isDragMove) onSeek?.invoke(fraction, true)
            }
        }
        return true
    }
}
