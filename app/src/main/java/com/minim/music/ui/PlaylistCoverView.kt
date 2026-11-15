package com.minim.music.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import com.minim.music.R

/**
 * 6.8.0: a playlist's cover, drawn the same on the playlist page and on its Library tile: a
 * rounded square (16dp corners, like the tiles) that shows one cover, a 2x2 mosaic, a word or an
 * emoji on a fixed colour, a blurred cover with the playlist's name, a photo, or the filled
 * square with an icon (Liked Songs). Empty (no picture yet) it's the filled square in the app
 * colour, like the tiles were.
 */
class PlaylistCoverView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    sealed class Look {
        /** The filled square in the app colour, with an optional icon (Liked Songs). */
        data class Filled(val iconRes: Int = 0) : Look()
        data class Single(val bitmap: Bitmap?) : Look()
        data class Mosaic(val bitmaps: List<Bitmap?>) : Look()
        data class Type(val text: String, val color: Int) : Look()
        data class Blur(val bitmap: Bitmap?, val name: String) : Look()
    }

    private var look: Look = Look.Filled()
    private var fg = Color.WHITE
    private var bg = Color.BLACK
    private val radius = 16f * resources.displayMetrics.density
    private val clip = Path()
    private val box = RectF()
    private val bmpPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bold: Typeface by lazy { ShareCard.font(context, R.font.poppins_bold, Typeface.DEFAULT_BOLD) }
    private var icon: Drawable? = null
    private var blurred: Bitmap? = null
    private var blurredFrom: Bitmap? = null

    /** Which cover this view shows now, so a late load for another one is ignored. */
    var key: String? = null

    fun setColors(fg: Int, bg: Int) {
        if (this.fg == fg && this.bg == bg) return
        this.fg = fg
        this.bg = bg
        invalidate()
    }

    fun show(look: Look) {
        this.look = look
        icon = (look as? Look.Filled)?.iconRes?.takeIf { it != 0 }?.let { ContextCompat.getDrawable(context, it)?.mutate() }
        if (look !is Look.Blur) { blurred = null; blurredFrom = null }
        invalidate()
    }

    val current: Look get() = look

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // always a square, as wide as it's allowed to be
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val hMode = MeasureSpec.getMode(heightMeasureSpec)
        val side = if (MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.UNSPECIFIED && hMode != MeasureSpec.UNSPECIFIED)
            MeasureSpec.getSize(heightMeasureSpec) else w
        setMeasuredDimension(side, side)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        box.set(0f, 0f, w.toFloat(), h.toFloat())
        clip.reset()
        clip.addRoundRect(box, radius, radius, Path.Direction.CW)
    }

    override fun onDraw(canvas: Canvas) {
        if (width == 0 || height == 0) return
        val save = canvas.save()
        canvas.clipPath(clip)
        when (val l = look) {
            is Look.Filled -> { canvas.drawColor(fg); drawIcon(canvas) }
            is Look.Single -> if (l.bitmap != null && !l.bitmap.isRecycled) drawCrop(canvas, l.bitmap, box) else canvas.drawColor(fg)
            is Look.Mosaic -> drawMosaic(canvas, l.bitmaps)
            is Look.Type -> drawType(canvas, l)
            is Look.Blur -> drawBlur(canvas, l)
        }
        canvas.restoreToCount(save)
    }

    private fun drawIcon(canvas: Canvas) {
        val d = icon ?: return
        val s = (width * 0.3f).toInt().coerceAtMost((52 * resources.displayMetrics.density).toInt())
        val l = (width - s) / 2
        val t = (height - s) / 2
        d.setBounds(l, t, l + s, t + s)
        d.setTint(bg)
        d.draw(canvas)
    }

    private fun drawCrop(canvas: Canvas, b: Bitmap, dst: RectF) {
        val side = minOf(b.width, b.height)
        val src = Rect((b.width - side) / 2, (b.height - side) / 2, (b.width + side) / 2, (b.height + side) / 2)
        canvas.drawBitmap(b, src, dst, bmpPaint)
    }

    private fun drawMosaic(canvas: Canvas, bitmaps: List<Bitmap?>) {
        canvas.drawColor(fg)
        val half = width / 2f
        val cells = listOf(
            RectF(0f, 0f, half, half), RectF(half, 0f, width.toFloat(), half),
            RectF(0f, half, half, height.toFloat()), RectF(half, half, width.toFloat(), height.toFloat())
        )
        cells.forEachIndexed { i, r ->
            val b = bitmaps.getOrNull(i)
            if (b != null && !b.isRecycled) drawCrop(canvas, b, r)
            else { fill.color = ColorUtils.blendARGB(fg, bg, 0.15f * (i % 2 + 1)); canvas.drawRect(r, fill) }
        }
    }

    private fun drawType(canvas: Canvas, l: Look.Type) {
        canvas.drawColor(l.color or 0xFF000000.toInt())
        val text = l.text
        if (text.isEmpty()) return
        val emoji = PlaylistLook.isSingleEmoji(text)
        val p = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = bold
            color = PlaylistLook.textOn(l.color)
            textAlign = Paint.Align.CENTER
        }
        // the biggest size that fits 76% of the width (emoji a bit smaller, they're wide)
        val maxW = width * 0.76f
        var size = height * (if (emoji) 0.42f else 0.30f)
        p.textSize = size
        val w = p.measureText(text)
        if (w > maxW) { size *= maxW / w; p.textSize = size }
        val fm = p.fontMetrics
        val y = height / 2f - (fm.ascent + fm.descent) / 2f
        canvas.drawText(text, width / 2f, y, p)
    }

    private fun drawBlur(canvas: Canvas, l: Look.Blur) {
        val src = l.bitmap
        if (src == null || src.isRecycled) {
            canvas.drawColor(fg)
        } else {
            if (blurredFrom !== src || blurred == null) {
                blurred = tinyBlur(src)
                blurredFrom = src
            }
            blurred?.let { canvas.drawBitmap(it, null, RectF(-width * 0.04f, -height * 0.04f, width * 1.04f, height * 1.04f), bmpPaint) }
            // the same darkening as the Blurred lyrics card, a bit more at the bottom
            fill.shader = LinearGradient(
                0f, 0f, 0f, height.toFloat(),
                intArrayOf(ColorUtils.setAlphaComponent(Color.BLACK, 84), ColorUtils.setAlphaComponent(Color.BLACK, 150)),
                null, Shader.TileMode.CLAMP
            )
            canvas.drawRect(box, fill)
            fill.shader = null
        }
        val name = l.name.trim()
        if (name.isEmpty()) return
        val pad = width * 0.09f
        val p = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = bold
            color = Color.WHITE
            textSize = (width * 0.13f).coerceAtLeast(8f)
        }
        val avail = (width - pad * 2).toInt().coerceAtLeast(1)
        val layout = StaticLayout.Builder.obtain(name, 0, name.length, p, avail)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setMaxLines(3)
            .setEllipsize(TextUtils.TruncateAt.END)
            .setLineSpacing(0f, 0.92f)
            .setIncludePad(false)
            .build()
        canvas.save()
        canvas.translate(pad, height - pad - layout.height)
        layout.draw(canvas)
        canvas.restore()
    }

    companion object {
        private const val BLUR_PX = 40

        /** The same soft blur as the lyrics cards: shrink to 40 px, box blur, draw it back up. */
        fun tinyBlur(cover: Bitmap): Bitmap {
            val small = Bitmap.createScaledBitmap(cover, BLUR_PX, BLUR_PX, true)
            val px = IntArray(BLUR_PX * BLUR_PX)
            small.getPixels(px, 0, BLUR_PX, 0, 0, BLUR_PX, BLUR_PX)
            LyricsCardSpacing.boxBlur(px, BLUR_PX, BLUR_PX, 3)
            val out = Bitmap.createBitmap(BLUR_PX, BLUR_PX, Bitmap.Config.ARGB_8888)
            out.setPixels(px, 0, BLUR_PX, 0, 0, BLUR_PX, BLUR_PX)
            if (small !== cover) small.recycle()
            return out
        }
    }
}
