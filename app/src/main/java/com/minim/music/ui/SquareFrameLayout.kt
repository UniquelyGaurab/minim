package com.minim.music.ui

import android.content.Context
import android.graphics.Outline
import android.util.AttributeSet
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout

/**
 * v4.6 Artist page header: always as tall as it is wide (1:1), with only the bottom corners
 * rounded. The photo and the fade inside are clipped to that shape.
 * v4.6.1: [extraTop] makes it that much taller, so it can reach up behind the status bar and
 * still look square below it.
 */
class SquareFrameLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    private val radius = 32f * resources.displayMetrics.density

    var extraTop: Int = 0
        set(value) {
            if (field == value) return
            field = value
            requestLayout()
        }

    init {
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                // Rect pushed up past the top edge, so only the bottom corners show as round
                outline.setRoundRect(0, -radius.toInt(), view.width, view.height, radius)
            }
        }
        clipToOutline = true
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val square = MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY)
        val tall = MeasureSpec.makeMeasureSpec(w + extraTop.coerceAtLeast(0), MeasureSpec.EXACTLY)
        super.onMeasure(square, tall)
    }
}
