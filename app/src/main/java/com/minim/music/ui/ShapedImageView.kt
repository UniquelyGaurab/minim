package com.minim.music.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.view.View
import android.view.ViewOutlineProvider

/**
 * 6.9.0 b78: a Reprise picture with its shape: rounded corners ([radiusPx]) or a circle.
 *
 * On screen the shape comes from the outline (cheap, done by the GPU). The share card draws the
 * slide into a plain bitmap, where outlines are ignored, which is why its pictures came out as
 * sharp squares. Drawn there, this view masks itself to the same shape, smoothed, so the card
 * matches the slide. The picture is never cropped further than on screen.
 */
class ShapedImageView(context: Context) : androidx.appcompat.widget.AppCompatImageView(context) {
    var round = false
        private set
    var radiusPx = 0f
        private set

    // Clears everything outside the shape: an inverse path, since a Porter-Duff paint only
    // touches the pixels its own shape covers
    private val mask = Paint(Paint.ANTI_ALIAS_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT) }
    private val box = RectF()
    private val outside = android.graphics.Path()

    init {
        clipToOutline = true
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                if (round) outline.setOval(0, 0, view.width, view.height)
                else outline.setRoundRect(0, 0, view.width, view.height, radiusPx)
            }
        }
    }

    fun shape(round: Boolean, radiusPx: Float) {
        this.round = round
        this.radiusPx = radiusPx
        invalidateOutline()
        invalidate()
    }

    override fun draw(canvas: Canvas) {
        if (canvas.isHardwareAccelerated || width <= 0 || height <= 0 || (!round && radiusPx <= 0f)) {
            super.draw(canvas)
            return
        }
        box.set(0f, 0f, width.toFloat(), height.toFloat())
        val save = canvas.saveLayer(box, null)
        super.draw(canvas)
        outside.reset()
        if (round) outside.addOval(box, android.graphics.Path.Direction.CW)
        else outside.addRoundRect(box, radiusPx, radiusPx, android.graphics.Path.Direction.CW)
        outside.fillType = android.graphics.Path.FillType.INVERSE_WINDING
        canvas.drawPath(outside, mask)
        canvas.restoreToCount(save)
    }
}
