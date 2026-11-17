package com.minim.music.ui

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.ColorUtils
import com.minim.music.R

/**
 * 6.9.0 b76: shown while a Weekly or Monthly Reprise is still being put together. A soft, slowly
 * breathing blurred cover under the theme's colour, with one line in Poppins ("Spinning up your
 * week…"). No spinner. It fades in only after a short wait, so a quick open never flashes it.
 */
@SuppressLint("ViewConstructor")
class RepriseLoadingView(
    context: Context,
    isWeek: Boolean,
    /** A small copy of a cover (the song playing, else none); blurred here. */
    cover: Bitmap?,
    /** The theme's background and ink (Light, Dark, or Haze's scrim and ink). */
    base: Int,
    ink: Int
) : FrameLayout(context) {

    private val backdrop = ImageView(context)
    private var pulse: ValueAnimator? = null
    val line: TextView

    init {
        isClickable = true // swallows taps while it's up
        setBackgroundColor(base)
        alpha = 0f
        backdrop.scaleType = ImageView.ScaleType.CENTER_CROP
        val blurred = cover?.let { blurSmall(it) }
        if (blurred != null) backdrop.setImageBitmap(blurred)
        else backdrop.setImageDrawable(android.graphics.drawable.GradientDrawable(
            android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
            intArrayOf(ColorUtils.blendARGB(base, ink, 0.16f), base, ColorUtils.blendARGB(base, ink, 0.08f))
        ))
        addView(backdrop, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        // The theme's colour over the cover keeps the line readable in Light, Dark and Haze
        addView(View(context).apply { setBackgroundColor(ColorUtils.setAlphaComponent(base, 150)) },
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        val col = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
        line = TextView(context).apply {
            text = title(isWeek)
            setTextColor(ink)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            typeface = font(R.font.poppins_semibold, Typeface.DEFAULT_BOLD)
            gravity = Gravity.CENTER
        }
        col.addView(line)
        col.addView(TextView(context).apply {
            text = if (isWeek) "Weekly Reprise" else "Monthly Reprise"
            setTextColor(ColorUtils.setAlphaComponent(ink, 170))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = font(R.font.poppins_regular, Typeface.DEFAULT)
            gravity = Gravity.CENTER
            setPadding(0, (6 * resources.displayMetrics.density).toInt(), 0, 0)
        })
        val pad = (36 * resources.displayMetrics.density).toInt()
        addView(col, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER).apply {
            leftMargin = pad; rightMargin = pad
        })
    }

    /** Fades in after [delayMs] and starts breathing. */
    fun appear(delayMs: Long = 180L) {
        animate().alpha(1f).setStartDelay(delayMs).setDuration(260L).start()
        pulse = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1600L
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener {
                val f = it.animatedValue as Float
                backdrop.alpha = 0.55f + 0.45f * f
                backdrop.scaleX = 1.04f + 0.05f * f
                backdrop.scaleY = backdrop.scaleX
            }
            start()
        }
    }

    /** Fades out and removes itself. */
    fun dismiss() {
        animate().cancel()
        animate().alpha(0f).setStartDelay(0L).setDuration(180L).withEndAction { stop(); (parent as? android.view.ViewGroup)?.removeView(this) }.start()
    }

    val breathing: Boolean get() = pulse?.isRunning == true

    private fun stop() {
        pulse?.cancel()
        pulse = null
    }

    override fun onDetachedFromWindow() {
        stop()
        animate().cancel()
        super.onDetachedFromWindow()
    }

    private fun font(id: Int, fallback: Typeface): Typeface = try {
        ResourcesCompat.getFont(context, id) ?: fallback
    } catch (_: Exception) {
        fallback
    }

    companion object {
        fun title(isWeek: Boolean) = if (isWeek) "Spinning up your week\u2026" else "Gathering your month\u2026"

        /** A heavy blur on a tiny copy (Android 9 has no RenderEffect); the ImageView scales it up softly. */
        fun blurSmall(src: Bitmap): Bitmap? = try {
            val w = 48
            val h = (48f * src.height / src.width.coerceAtLeast(1)).toInt().coerceIn(24, 96)
            val small = Bitmap.createScaledBitmap(src, w, h, true)
            val px = IntArray(w * h)
            small.getPixels(px, 0, w, 0, 0, w, h)
            val out = SoftwareBlur.blur(px, w, h, 4)
            Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888)
        } catch (_: Exception) {
            null
        }

        /** Base and ink for the theme. */
        fun colors(dark: Boolean, haze: HazeLook.Look?): Pair<Int, Int> = when {
            haze != null -> haze.average to haze.ink
            dark -> Color.rgb(18, 18, 18) to Color.WHITE
            else -> Color.rgb(246, 246, 246) to Color.rgb(20, 20, 20)
        }
    }
}
