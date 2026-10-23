package com.minim.music.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import android.view.animation.DecelerateInterpolator
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.ColorUtils
import com.minim.music.R
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * Synced lyrics, seven lines at a time: three previous, the current one (centred, bold, full
 * opacity) and three upcoming. The further a line is from the current one, the fainter and
 * slightly smaller it is. When the song moves on, the lines glide up smoothly so the current
 * line stays centred. It can't be scrolled by hand.
 */
class LyricsView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val density = resources.displayMetrics.density
    private val sidePad = (28 * density).roundToInt()
    private val gap = 14 * density

    private val regularPaint = TextPaint(TextPaint.ANTI_ALIAS_FLAG).apply {
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 20f, resources.displayMetrics)
        typeface = font(R.font.poppins_regular)
    }
    private val boldPaint = TextPaint(TextPaint.ANTI_ALIAS_FLAG).apply {
        textSize = regularPaint.textSize
        typeface = font(R.font.poppins_bold)
    }

    private var lines: List<String> = emptyList()
    private var regular: List<StaticLayout> = emptyList()
    private var bold: List<StaticLayout> = emptyList()
    private var heights = FloatArray(0)
    private var centers = FloatArray(0)
    private var builtForWidth = -1

    private var activeIndex = -1
    /** Animated position measured in lines (e.g. 3.4 = 40% of the way from line 3 to line 4). */
    private var position = -1f
    private var animator: ValueAnimator? = null

    private var fg = Pal.DESIGN_DARK
    private var muted = Pal.DESIGN_MUTED

    private fun font(id: Int): Typeface =
        try { ResourcesCompat.getFont(context, id) ?: Typeface.DEFAULT } catch (_: Exception) { Typeface.DEFAULT }

    fun setColors(fg: Int, muted: Int) {
        if (this.fg == fg && this.muted == muted) return
        this.fg = fg
        this.muted = muted
        invalidate()
    }

    fun setLines(newLines: List<String>) {
        animator?.cancel()
        lines = newLines.map { if (it.isBlank()) "\u266A" else it.trim() }
        builtForWidth = -1
        activeIndex = -1
        position = -1f
        invalidate()
    }

    /** Moves the highlight to [index] (-1 = before the first line). */
    fun setActive(index: Int, animate: Boolean = true) {
        if (index == activeIndex) return
        val from = position
        activeIndex = index
        animator?.cancel()
        if (!animate || !isShown || !ValueAnimator.areAnimatorsEnabled() || abs(index - from) > 3f) {
            position = index.toFloat()
            invalidate()
            return
        }
        animator = ValueAnimator.ofFloat(from, index.toFloat()).apply {
            duration = 520
            interpolator = DecelerateInterpolator(1.6f)
            addUpdateListener {
                position = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    private fun ensureLayouts() {
        val w = width - sidePad * 2
        if (w <= 0 || builtForWidth == w) return
        builtForWidth = w
        regular = lines.map { build(it, regularPaint, w) }
        bold = lines.map { build(it, boldPaint, w) }
        heights = FloatArray(lines.size) { maxOf(regular[it].height, bold[it].height).toFloat() }
        centers = FloatArray(lines.size)
        var top = 0f
        for (i in lines.indices) {
            centers[i] = top + heights[i] / 2f
            top += heights[i] + gap
        }
    }

    private fun build(text: String, paint: TextPaint, width: Int): StaticLayout =
        StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setIncludePad(false)
            .setLineSpacing(0f, 1f)
            .build()

    /** Centre of line i, extended past both ends so "before the first line" has a place too. */
    private fun centerOf(i: Int): Float {
        val n = centers.size
        return when {
            n == 0 -> 0f
            i < 0 -> centers[0] + i * (heights[0] + gap)
            i >= n -> centers[n - 1] + (i - n + 1) * (heights[n - 1] + gap)
            else -> centers[i]
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        builtForWidth = -1
    }

    override fun onDraw(canvas: Canvas) {
        if (lines.isEmpty()) return
        ensureLayouts()
        if (centers.isEmpty()) return

        val lo = floor(position).toInt()
        val hi = ceil(position).toInt()
        val frac = position - lo
        val scrollCenter = centerOf(lo) + (centerOf(hi) - centerOf(lo)) * frac

        canvas.save()
        canvas.translate(sidePad.toFloat(), height / 2f - scrollCenter)
        val midX = (width - sidePad * 2) / 2f
        for (i in (lo - VISIBLE_EACH_SIDE)..(hi + VISIBLE_EACH_SIDE)) {
            if (i < 0 || i >= lines.size) continue
            val distance = abs(i - position)
            // 0..3 lines away stay on screen; the 4th fades out while the lines glide
            val visibility = (VISIBLE_EACH_SIDE + 1f - distance).coerceIn(0f, 1f)
            if (visibility <= 0f) continue
            val emphasis = (1f - distance).coerceIn(0f, 1f) // 1 = the current line
            val d = distance.coerceAtMost(VISIBLE_EACH_SIDE.toFloat())
            val fade = 1f - d * FADE_PER_LINE      // 1, 0.78, 0.56, 0.34 by distance
            val size = 1f - d * SHRINK_PER_LINE    // 1, 0.95, 0.90, 0.85 by distance
            val top = centers[i] - heights[i] / 2f

            canvas.save()
            canvas.scale(size, size, midX, centers[i])
            if (emphasis < 1f) {
                regularPaint.color = muted
                regularPaint.alpha = (255 * visibility * fade * (1f - emphasis)).roundToInt()
                canvas.save()
                canvas.translate(0f, top + (heights[i] - regular[i].height) / 2f)
                regular[i].draw(canvas)
                canvas.restore()
            }
            if (emphasis > 0f) {
                boldPaint.color = ColorUtils.blendARGB(muted, fg, emphasis)
                boldPaint.alpha = (255 * visibility * emphasis).roundToInt()
                canvas.save()
                canvas.translate(0f, top + (heights[i] - bold[i].height) / 2f)
                bold[i].draw(canvas)
                canvas.restore()
            }
            canvas.restore()
        }
        canvas.restore()
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        super.onDetachedFromWindow()
    }

    private companion object {
        const val VISIBLE_EACH_SIDE = 3
        const val FADE_PER_LINE = 0.22f
        const val SHRINK_PER_LINE = 0.05f
    }
}
